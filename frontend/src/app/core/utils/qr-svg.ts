/**
 * Self-contained pure-TypeScript ISO/IEC 18004 QR Code SVG generator (Byte mode, ECC Level M, Versions 1–10).
 * Generates scannable QR codes locally with zero external network dependencies.
 */

const GF_EXP = new Uint8Array(512);
const GF_LOG = new Uint8Array(256);

(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) {
      x ^= 0x11d;
    }
  }
  for (let i = 255; i < 512; i++) {
    GF_EXP[i] = GF_EXP[i - 255];
  }
})();

function gfMul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

function rsGeneratorPoly(degree: number): number[] {
  let poly = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array<number>(poly.length + 1).fill(0);
    const factor = GF_EXP[i];
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMul(poly[j], factor);
    }
    poly = next;
  }
  return poly;
}

function rsRemainder(data: number[], ecLen: number): number[] {
  const gen = rsGeneratorPoly(ecLen);
  const res = new Array<number>(data.length + ecLen).fill(0);
  for (let i = 0; i < data.length; i++) {
    res[i] = data[i];
  }
  for (let i = 0; i < data.length; i++) {
    const coef = res[i];
    if (coef !== 0) {
      for (let j = 0; j < gen.length; j++) {
        res[i + j] ^= gfMul(gen[j], coef);
      }
    }
  }
  return res.slice(data.length);
}

interface VersionSpec {
  version: number;
  size: number;
  totalDataCodewords: number;
  ecPerBlock: number;
  groups: Array<{ blocks: number; dataCodewords: number }>;
  alignmentCenters: number[];
}

// ISO/IEC 18004 Versions 1..10 at Error Correction Level M (15% recovery)
const VERSION_SPECS_M: VersionSpec[] = [
  { version: 1, size: 21, totalDataCodewords: 16, ecPerBlock: 10, groups: [{ blocks: 1, dataCodewords: 16 }], alignmentCenters: [] },
  { version: 2, size: 25, totalDataCodewords: 28, ecPerBlock: 16, groups: [{ blocks: 1, dataCodewords: 28 }], alignmentCenters: [6, 18] },
  { version: 3, size: 29, totalDataCodewords: 44, ecPerBlock: 26, groups: [{ blocks: 1, dataCodewords: 44 }], alignmentCenters: [6, 22] },
  { version: 4, size: 33, totalDataCodewords: 64, ecPerBlock: 18, groups: [{ blocks: 2, dataCodewords: 32 }], alignmentCenters: [6, 26] },
  { version: 5, size: 37, totalDataCodewords: 86, ecPerBlock: 24, groups: [{ blocks: 2, dataCodewords: 43 }], alignmentCenters: [6, 30] },
  { version: 6, size: 41, totalDataCodewords: 108, ecPerBlock: 16, groups: [{ blocks: 4, dataCodewords: 27 }], alignmentCenters: [6, 34] },
  { version: 7, size: 45, totalDataCodewords: 124, ecPerBlock: 18, groups: [{ blocks: 4, dataCodewords: 31 }], alignmentCenters: [6, 22, 38] },
  { version: 8, size: 49, totalDataCodewords: 154, ecPerBlock: 22, groups: [{ blocks: 2, dataCodewords: 38 }, { blocks: 2, dataCodewords: 39 }], alignmentCenters: [6, 24, 42] },
  { version: 9, size: 53, totalDataCodewords: 182, ecPerBlock: 22, groups: [{ blocks: 3, dataCodewords: 36 }, { blocks: 2, dataCodewords: 37 }], alignmentCenters: [6, 26, 46] },
  { version: 10, size: 57, totalDataCodewords: 216, ecPerBlock: 26, groups: [{ blocks: 4, dataCodewords: 43 }, { blocks: 1, dataCodewords: 44 }], alignmentCenters: [6, 28, 50] },
];

function selectVersion(byteLen: number): VersionSpec {
  for (const spec of VERSION_SPECS_M) {
    const ccBits = spec.version <= 9 ? 8 : 16;
    const headerBits = 4 + ccBits;
    const maxBytes = Math.floor((spec.totalDataCodewords * 8 - headerBits) / 8);
    if (byteLen <= maxBytes) {
      return spec;
    }
  }
  throw new Error(`QR payload too long (${byteLen} bytes); maximum supported is 213 bytes.`);
}

function encodeCodewords(bytes: Uint8Array, spec: VersionSpec): number[] {
  const bits: number[] = [];
  const pushBits = (val: number, count: number) => {
    for (let i = count - 1; i >= 0; i--) {
      bits.push((val >> i) & 1);
    }
  };

  // Mode: Byte (0100)
  pushBits(0b0100, 4);
  const ccBits = spec.version <= 9 ? 8 : 16;
  pushBits(bytes.length, ccBits);

  for (let i = 0; i < bytes.length; i++) {
    pushBits(bytes[i], 8);
  }

  const totalDataBits = spec.totalDataCodewords * 8;
  const terminatorLen = Math.min(4, totalDataBits - bits.length);
  for (let i = 0; i < terminatorLen; i++) bits.push(0);

  while (bits.length % 8 !== 0) bits.push(0);

  const dataCodewords: number[] = [];
  for (let i = 0; i < bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) {
      b = (b << 1) | bits[i + j];
    }
    dataCodewords.push(b);
  }

  const padBytes = [0xec, 0x11];
  let padIdx = 0;
  while (dataCodewords.length < spec.totalDataCodewords) {
    dataCodewords.push(padBytes[padIdx % 2]);
    padIdx++;
  }

  // Split into RS blocks and interleave
  const dataBlocks: number[][] = [];
  const ecBlocks: number[][] = [];
  let offset = 0;
  for (const g of spec.groups) {
    for (let b = 0; b < g.blocks; b++) {
      const block = dataCodewords.slice(offset, offset + g.dataCodewords);
      offset += g.dataCodewords;
      dataBlocks.push(block);
      ecBlocks.push(rsRemainder(block, spec.ecPerBlock));
    }
  }

  const interleaved: number[] = [];
  const maxDataLen = Math.max(...dataBlocks.map((b) => b.length));
  for (let i = 0; i < maxDataLen; i++) {
    for (const block of dataBlocks) {
      if (i < block.length) interleaved.push(block[i]);
    }
  }
  for (let i = 0; i < spec.ecPerBlock; i++) {
    for (const ec of ecBlocks) {
      if (i < ec.length) interleaved.push(ec[i]);
    }
  }

  return interleaved;
}

function formatInfoBits(maskPattern: number): number {
  // EC Level M = 00
  const data = (0b00 << 3) | (maskPattern & 0x7);
  let rem = data << 10;
  const poly = 0b10100110111;
  for (let i = 14; i >= 10; i--) {
    if ((rem >> i) & 1) {
      rem ^= poly << (i - 10);
    }
  }
  return ((data << 10) | rem) ^ 0b101010000010010;
}

function versionInfoBits(version: number): number {
  let rem = version << 12;
  const poly = 0b1111100100101;
  for (let i = 17; i >= 12; i--) {
    if ((rem >> i) & 1) {
      rem ^= poly << (i - 12);
    }
  }
  return (version << 12) | rem;
}

function buildMatrix(spec: VersionSpec, codewords: number[]): boolean[][] {
  const n = spec.size;
  const modules: Array<Array<boolean | null>> = Array.from({ length: n }, () => new Array(n).fill(null));
  const isFunction: boolean[][] = Array.from({ length: n }, () => new Array(n).fill(false));

  const setFn = (r: number, c: number, dark: boolean) => {
    modules[r][c] = dark;
    isFunction[r][c] = true;
  };

  // Finder patterns + separators
  const placeFinder = (top: number, left: number) => {
    for (let dr = -1; dr <= 7; dr++) {
      for (let dc = -1; dc <= 7; dc++) {
        const r = top + dr;
        const c = left + dc;
        if (r < 0 || r >= n || c < 0 || c >= n) continue;
        const inOuter = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6;
        const onBorder = dr === 0 || dr === 6 || dc === 0 || dc === 6;
        const inCore = dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4;
        setFn(r, c, inOuter && (onBorder || inCore));
      }
    }
  };
  placeFinder(0, 0);
  placeFinder(0, n - 7);
  placeFinder(n - 7, 0);

  // Alignment patterns
  const centers = spec.alignmentCenters;
  for (const cr of centers) {
    for (const cc of centers) {
      if (isFunction[cr][cc]) continue;
      for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          const dark = Math.max(Math.abs(dr), Math.abs(dc)) !== 1;
          setFn(cr + dr, cc + dc, dark);
        }
      }
    }
  }

  // Timing patterns
  for (let i = 8; i < n - 8; i++) {
    const dark = i % 2 === 0;
    if (!isFunction[6][i]) setFn(6, i, dark);
    if (!isFunction[i][6]) setFn(i, 6, dark);
  }

  // Dark module
  setFn(n - 8, 8, true);

  // Reserve format info modules
  for (let i = 0; i <= 8; i++) {
    if (!isFunction[8][i]) setFn(8, i, false);
    if (!isFunction[i][8]) setFn(i, 8, false);
  }
  for (let i = 0; i < 8; i++) {
    if (!isFunction[8][n - 1 - i]) setFn(8, n - 1 - i, false);
    if (!isFunction[n - 1 - i][8]) setFn(n - 1 - i, 8, false);
  }

  // Version info for >= 7
  if (spec.version >= 7) {
    const vBits = versionInfoBits(spec.version);
    for (let i = 0; i < 18; i++) {
      const bit = ((vBits >> i) & 1) === 1;
      const a = Math.floor(i / 3);
      const b = i % 3;
      setFn(n - 11 + b, a, bit);
      setFn(a, n - 11 + b, bit);
    }
  }

  // Data placement (zig-zag)
  let bitIdx = 0;
  const totalBits = codewords.length * 8;
  let upward = true;
  for (let right = n - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let step = 0; step < n; step++) {
      const r = upward ? n - 1 - step : step;
      for (let dx = 0; dx < 2; dx++) {
        const c = right - dx;
        if (!isFunction[r][c]) {
          let dark = false;
          if (bitIdx < totalBits) {
            const cw = codewords[bitIdx >> 3];
            dark = ((cw >> (7 - (bitIdx & 7))) & 1) === 1;
            bitIdx++;
          }
          modules[r][c] = dark;
        }
      }
    }
    upward = !upward;
  }

  // Try all 8 mask patterns and pick lowest penalty
  let bestScore = Infinity;
  let bestMatrix: boolean[][] = [];

  for (let mask = 0; mask < 8; mask++) {
    const candidate: boolean[][] = Array.from({ length: n }, (_, r) =>
      Array.from({ length: n }, (__, c) => {
        const base = Boolean(modules[r][c]);
        if (isFunction[r][c]) return base;
        return shouldInvert(mask, r, c) ? !base : base;
      }),
    );

    const fmt = formatInfoBits(mask);
    for (let i = 0; i <= 5; i++) {
      const bit = ((fmt >> i) & 1) === 1;
      candidate[8][i] = bit;
      candidate[n - 1 - i][8] = bit;
    }
    candidate[8][7] = ((fmt >> 6) & 1) === 1;
    candidate[n - 7][8] = ((fmt >> 6) & 1) === 1;
    candidate[8][8] = ((fmt >> 7) & 1) === 1;
    candidate[8][n - 8] = ((fmt >> 7) & 1) === 1;
    candidate[7][8] = ((fmt >> 8) & 1) === 1;
    candidate[8][n - 7] = ((fmt >> 8) & 1) === 1;
    for (let i = 9; i < 15; i++) {
      const bit = ((fmt >> i) & 1) === 1;
      candidate[14 - i][8] = bit;
      candidate[8][n - 15 + i] = bit;
    }

    const score = penaltyScore(candidate);
    if (score < bestScore) {
      bestScore = score;
      bestMatrix = candidate;
    }
  }

  return bestMatrix;
}

function shouldInvert(mask: number, r: number, c: number): boolean {
  switch (mask) {
    case 0: return (r + c) % 2 === 0;
    case 1: return r % 2 === 0;
    case 2: return c % 3 === 0;
    case 3: return (r + c) % 3 === 0;
    case 4: return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
    case 5: return ((r * c) % 2) + ((r * c) % 3) === 0;
    case 6: return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0;
    case 7: return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0;
    default: return false;
  }
}

function penaltyScore(m: boolean[][]): number {
  const n = m.length;
  let score = 0;

  // Rule 1: 5+ consecutive same color in row/col
  for (let r = 0; r < n; r++) {
    let runLen = 1;
    for (let c = 1; c < n; c++) {
      if (m[r][c] === m[r][c - 1]) {
        runLen++;
      } else {
        if (runLen >= 5) score += 3 + (runLen - 5);
        runLen = 1;
      }
    }
    if (runLen >= 5) score += 3 + (runLen - 5);
  }
  for (let c = 0; c < n; c++) {
    let runLen = 1;
    for (let r = 1; r < n; r++) {
      if (m[r][c] === m[r - 1][c]) {
        runLen++;
      } else {
        if (runLen >= 5) score += 3 + (runLen - 5);
        runLen = 1;
      }
    }
    if (runLen >= 5) score += 3 + (runLen - 5);
  }

  // Rule 2: 2x2 blocks
  for (let r = 0; r < n - 1; r++) {
    for (let c = 0; c < n - 1; c++) {
      const v = m[r][c];
      if (v === m[r][c + 1] && v === m[r + 1][c] && v === m[r + 1][c + 1]) {
        score += 3;
      }
    }
  }

  // Rule 4: dark ratio
  let darkCount = 0;
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (m[r][c]) darkCount++;
    }
  }
  const pct = (darkCount * 100) / (n * n);
  const k = Math.floor(Math.abs(pct - 50) / 5);
  score += k * 10;

  return score;
}

export function generateQrMatrix(text: string): boolean[][] {
  const encoder = new TextEncoder();
  const bytes = encoder.encode(text);
  const spec = selectVersion(bytes.length);
  const codewords = encodeCodewords(bytes, spec);
  return buildMatrix(spec, codewords);
}

export function generateQrSvgParts(text: string): { viewBoxSize: number; path: string } {
  const matrix = generateQrMatrix(text);
  const modules = matrix.length;
  const quiet = 4;
  const total = modules + quiet * 2;
  const paths: string[] = [];

  for (let r = 0; r < modules; r++) {
    for (let c = 0; c < modules; c++) {
      if (matrix[r][c]) {
        paths.push(`M${c + quiet},${r + quiet}h1v1h-1z`);
      }
    }
  }

  return { viewBoxSize: total, path: paths.join('') };
}

export function generateQrSvgString(text: string, sizePx = 220): string {
  const { viewBoxSize, path } = generateQrSvgParts(text);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${viewBoxSize} ${viewBoxSize}" width="${sizePx}" height="${sizePx}" shape-rendering="crispEdges"><rect width="${viewBoxSize}" height="${viewBoxSize}" fill="#ffffff"/><path d="${path}" fill="#0f172a"/></svg>`;
}

export function generateQrSvgDataUrl(text: string, sizePx = 240): string {
  const svg = generateQrSvgString(text, sizePx);
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

const PORTAL_BASE_STORAGE_KEY = 'cf_intake_portal_base_url';

export function getSavedIntakePortalBaseUrl(): string {
  try {
    const saved = localStorage.getItem(PORTAL_BASE_STORAGE_KEY);
    if (saved && saved.trim()) {
      return saved.trim().replace(/\/+$/, '');
    }
  } catch {
    // ignore storage errors
  }
  if (typeof window !== 'undefined' && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
    return `${window.location.protocol}//${window.location.hostname}:4300`;
  }
  return 'http://localhost:4300';
}

export function saveIntakePortalBaseUrl(url: string): string {
  const normalized = url.trim().replace(/\/+$/, '') || 'http://localhost:4300';
  try {
    localStorage.setItem(PORTAL_BASE_STORAGE_KEY, normalized);
  } catch {
    // ignore storage errors
  }
  return normalized;
}

export function buildIntakePortalUrl(clinicKey: string, baseUrl?: string): string {
  const base = (baseUrl && baseUrl.trim() ? baseUrl.trim() : getSavedIntakePortalBaseUrl()).replace(/\/+$/, '');
  return `${base}/c/${encodeURIComponent(clinicKey)}`;
}

export function printIntakeQrPoster(clinicName: string, portalUrl: string): void {
  const svg = generateQrSvgString(portalUrl, 320);
  const w = window.open('', '_blank', 'width=540,height=720');
  if (!w) return;
  const escapedName = clinicName.replace(/[<>&"]/g, (ch) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[ch] || ch));
  const escapedUrl = portalUrl.replace(/[<>&"]/g, (ch) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[ch] || ch));

  w.document.open();
  w.document.write(`<!doctype html>
<html dir="rtl" lang="ar">
<head>
<meta charset="utf-8" />
<title>QR بوابة المرضى - ${escapedName}</title>
<style>
  body { font-family: system-ui, -apple-system, 'Segoe UI', Tahoma, sans-serif; margin: 0; padding: 36px; text-align: center; color: #0f172a; background: #ffffff; }
  .poster { border: 3px solid #0d9488; border-radius: 20px; padding: 32px 24px; max-width: 440px; margin: 0 auto; }
  .brand { font-size: 13px; letter-spacing: 1px; color: #0d9488; font-weight: 700; text-transform: uppercase; margin-bottom: 6px; }
  h1 { font-size: 26px; margin: 0 0 8px; }
  .sub { color: #475569; font-size: 15px; margin: 0 0 22px; line-height: 1.5; }
  .qr-wrap { display: inline-block; padding: 14px; border: 1px solid #cbd5e1; border-radius: 16px; background: #fff; }
  .steps { margin-top: 22px; text-align: right; background: #f8fafc; border-radius: 12px; padding: 14px 18px; font-size: 14px; line-height: 1.8; }
  .url { margin-top: 14px; font-family: monospace; font-size: 11px; color: #64748b; word-break: break-all; direction: ltr; }
  @media print { body { padding: 12px; } }
</style>
</head>
<body>
  <div class="poster">
    <div class="brand">ClinicFlow Patient Intake</div>
    <h1>${escapedName}</h1>
    <p class="sub">امسح الرمز بكاميرا هاتفك لتعبئة بياناتك والتاريخ المرضي<br/>Scan with your phone camera to fill out your intake form</p>
    <div class="qr-wrap">${svg}</div>
    <div class="steps">
      <strong>خطوات التعبئة السريعة:</strong><br/>
      1. امسح رمز الـ QR بكاميرا جوالك.<br/>
      2. أدخل كود الدخول المؤقت الذي زوّدك به الاستقبال.<br/>
      3. أكمل بياناتك وأرسلها لتصل مباشرة لملفك لدى الطبيب.
    </div>
    <div class="url">${escapedUrl}</div>
  </div>
  <script>setTimeout(() => window.print(), 200);</script>
</body>
</html>`);
  w.document.close();
}
