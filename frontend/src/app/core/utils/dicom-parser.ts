/**
 * Zero-dependency In-Browser DICOM (.dcm) Parser & Canvas Renderer.
 * Supports:
 * - Little Endian Explicit VR & Implicit VR
 * - 8-bit & 16-bit Grayscale (MONOCHROME1 / MONOCHROME2)
 * - Window Center / Window Width contrast leveling
 * - Encapsulated JPEG streams (SOF markers: 0xFFD8 ... 0xFFD9)
 */

export interface DicomParseResult {
  width: number;
  height: number;
  dataUrl: string;
  photometric: string;
  bitsAllocated: number;
  windowCenter?: number;
  windowWidth?: number;
  modality?: string;
  studyDescription?: string;
  seriesDescription?: string;
}

export function detectDentalFileType(
  fileName: string,
  mimeType?: string,
  dicomMeta?: Partial<DicomParseResult>
): { type: string; reason: string } {
  const lowerName = (fileName || '').toLowerCase();
  const ext = lowerName.split('.').pop() || '';

  // 1. 3D Digital Models
  if (['stl', 'obj', 'ply', 'gltf', 'glb'].includes(ext)) {
    return {
      type: '3D Dental Scan (STL)',
      reason: `3D Digital Model (.${ext.toUpperCase()})`,
    };
  }

  // 2. PDF Reports & Documents
  if (ext === 'pdf' || mimeType === 'application/pdf') {
    return {
      type: 'Report / Document (PDF)',
      reason: 'PDF Document / Lab Report',
    };
  }

  // 3. DICOM Medical Imaging Header Analysis
  const metaText = `${dicomMeta?.studyDescription || ''} ${dicomMeta?.seriesDescription || ''} ${lowerName}`.toLowerCase();
  const modality = (dicomMeta?.modality || '').toUpperCase();

  if (modality === 'CT' || metaText.includes('cbct') || metaText.includes('tomograph') || metaText.includes('cone beam')) {
    return {
      type: 'CBCT',
      reason: modality === 'CT' ? 'DICOM CT Modality (3D Volumetric Scan)' : 'CBCT Computed Tomography',
    };
  }

  if (modality === 'PX' || metaText.includes('panoramic') || metaText.includes('opg') || metaText.includes('orthopantom')) {
    return {
      type: 'OPG',
      reason: modality === 'PX' ? 'DICOM PX Modality (Panoramic X-Ray)' : 'Panoramic / OPG Scan',
    };
  }

  if (metaText.includes('bitewing') || metaText.includes('bite-wing') || metaText.includes('bw')) {
    return {
      type: 'Bitewings',
      reason: 'Bitewing Interproximal Radiograph',
    };
  }

  if (
    metaText.includes('periapical') ||
    metaText.includes('pa') ||
    metaText.includes('molar') ||
    metaText.includes('premolar') ||
    metaText.includes('incisor') ||
    metaText.includes('canine') ||
    modality === 'IO'
  ) {
    return {
      type: 'Periapical',
      reason: modality === 'IO' ? 'DICOM IO Modality (Intraoral Radiograph)' : 'Periapical Dental Scan',
    };
  }

  if (metaText.includes('ceph') || metaText.includes('cephalometric') || metaText.includes('lateral')) {
    return {
      type: 'Lateral Cephalogram',
      reason: 'Lateral Cephalometric Radiograph',
    };
  }

  if (ext === 'dcm' || ext === 'dicom' || mimeType === 'application/dicom') {
    if (dicomMeta?.width && dicomMeta?.height && dicomMeta.width >= dicomMeta.height * 1.5) {
      return {
        type: 'OPG',
        reason: 'Wide Aspect Ratio (OPG Panoramic View)',
      };
    }
    return {
      type: 'OPG',
      reason: 'DICOM Radiograph Scan',
    };
  }

  // 4. Standard Images (PNG, JPG, WEBP)
  if (['png', 'jpg', 'jpeg', 'webp'].includes(ext)) {
    if (lowerName.includes('panoram') || lowerName.includes('opg')) {
      return { type: 'OPG', reason: 'Panoramic X-Ray Image' };
    }
    if (lowerName.includes('bitewing') || lowerName.includes('bw')) {
      return { type: 'Bitewings', reason: 'Bitewing X-Ray Image' };
    }
    if (lowerName.includes('periapical') || lowerName.includes('pa')) {
      return { type: 'Periapical', reason: 'Periapical X-Ray Image' };
    }
    if (lowerName.includes('ceph')) {
      return { type: 'Lateral Cephalogram', reason: 'Cephalometric X-Ray Image' };
    }
    return {
      type: 'Periapical',
      reason: 'Dental Scan / Photo',
    };
  }

  return {
    type: 'OPG',
    reason: 'Standard Radiograph',
  };
}

export function isDicomFile(fileName: string, mimeType?: string): boolean {
  const lowerName = (fileName || '').toLowerCase();
  if (lowerName.endsWith('.dcm') || lowerName.endsWith('.dicom')) {
    return true;
  }
  return mimeType === 'application/dicom' || mimeType === 'image/dicom-rle';
}

export async function parseAndRenderDicom(buffer: ArrayBuffer): Promise<DicomParseResult> {
  const bytes = new Uint8Array(buffer);
  const view = new DataView(buffer);

  // 1. Check for encapsulated JPEG stream first (common in dental DICOMs)
  const jpegResult = extractEncapsulatedJpeg(bytes);
  if (jpegResult) {
    return jpegResult;
  }

  // 2. Locate starting offset: Check for 'DICM' at byte 128
  let offset = 0;
  let isExplicit = true;

  if (
    bytes.length > 132 &&
    bytes[128] === 0x44 &&
    bytes[129] === 0x49 &&
    bytes[130] === 0x43 &&
    bytes[131] === 0x4d
  ) {
    offset = 132;
  } else {
    // Preamble-less DICOM
    offset = 0;
  }

  let rows = 0;
  let cols = 0;
  let bitsAllocated = 16;
  let bitsStored = 16;
  let pixelRepresentation = 0; // 0 = unsigned, 1 = 2's complement
  let photometric = 'MONOCHROME2';
  let windowCenter: number | undefined;
  let windowWidth: number | undefined;
  let rescaleIntercept = 0;
  let rescaleSlope = 1;
  let pixelDataOffset = -1;
  let modality = '';
  let studyDescription = '';
  let seriesDescription = '';

  // VRs that have a 32-bit length preceded by 2 reserved bytes in Explicit VR
  const longVRs = new Set(['OB', 'OW', 'OF', 'OD', 'SQ', 'UC', 'UN', 'UR', 'UT']);

  while (offset + 4 <= buffer.byteLength) {
    const group = view.getUint16(offset, true);
    const element = view.getUint16(offset + 2, true);
    offset += 4;

    // Check if Explicit VR or Implicit VR
    let vr = '';
    let length = 0;

    if (offset + 2 <= buffer.byteLength) {
      const b0 = bytes[offset];
      const b1 = bytes[offset + 1];
      const isAsciiVR = b0 >= 65 && b0 <= 90 && b1 >= 65 && b1 <= 90;

      if (isAsciiVR && isExplicit) {
        vr = String.fromCharCode(b0, b1);
        offset += 2;
        if (longVRs.has(vr)) {
          offset += 2; // 2 reserved bytes
          length = view.getUint32(offset, true);
          offset += 4;
        } else {
          length = view.getUint16(offset, true);
          offset += 2;
        }
      } else {
        // Implicit VR Little Endian
        isExplicit = false;
        length = view.getUint32(offset, true);
        offset += 4;
      }
    }

    // Undefined length (0xFFFFFFFF) - encapsulated sequences
    if (length === 0xffffffff) {
      if (group === 0x7fe0 && element === 0x0010) {
        const embedded = extractEncapsulatedJpeg(bytes.subarray(offset));
        if (embedded) {
          return embedded;
        }
      }
      break;
    }

    // Process known tags
    if (group === 0x0008) {
      if (element === 0x0060) {
        modality = readString(bytes, offset, length).trim();
      } else if (element === 0x1030) {
        studyDescription = readString(bytes, offset, length).trim();
      } else if (element === 0x103e) {
        seriesDescription = readString(bytes, offset, length).trim();
      }
    } else if (group === 0x0028) {
      if (element === 0x0010) {
        rows = readUint(view, offset, length);
      } else if (element === 0x0011) {
        cols = readUint(view, offset, length);
      } else if (element === 0x0100) {
        bitsAllocated = readUint(view, offset, length);
      } else if (element === 0x0101) {
        bitsStored = readUint(view, offset, length);
      } else if (element === 0x0103) {
        pixelRepresentation = readUint(view, offset, length);
      } else if (element === 0x0004) {
        photometric = readString(bytes, offset, length).trim();
      } else if (element === 0x1050) {
        const s = readString(bytes, offset, length).trim();
        const parsed = parseFloat(s.split('\\')[0]);
        if (!isNaN(parsed)) windowCenter = parsed;
      } else if (element === 0x1051) {
        const s = readString(bytes, offset, length).trim();
        const parsed = parseFloat(s.split('\\')[0]);
        if (!isNaN(parsed)) windowWidth = parsed;
      } else if (element === 0x1052) {
        const s = readString(bytes, offset, length).trim();
        const parsed = parseFloat(s);
        if (!isNaN(parsed)) rescaleIntercept = parsed;
      } else if (element === 0x1053) {
        const s = readString(bytes, offset, length).trim();
        const parsed = parseFloat(s);
        if (!isNaN(parsed)) rescaleSlope = parsed;
      }
    } else if (group === 0x7fe0 && element === 0x0010) {
      pixelDataOffset = offset;
      break;
    }

    offset += length;
  }

  if (pixelDataOffset < 0 || rows <= 0 || cols <= 0) {
    const fallbackJpeg = extractEncapsulatedJpeg(bytes);
    if (fallbackJpeg) return fallbackJpeg;
    throw new Error('Could not find decodable pixel data in DICOM file');
  }

  // 3. Render raw uncompressed pixels to an offscreen Canvas
  const canvas = document.createElement('canvas');
  canvas.width = cols;
  canvas.height = rows;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Could not create 2D canvas context');

  const imgData = ctx.createImageData(cols, rows);
  const data = imgData.data;
  const totalPixels = rows * cols;
  const is16Bit = bitsAllocated > 8;
  const isSigned = pixelRepresentation === 1;

  let minVal = Infinity;
  let maxVal = -Infinity;

  const rawValues = is16Bit ? new Float32Array(totalPixels) : null;
  const pixelByteStride = is16Bit ? 2 : 1;

  for (let i = 0; i < totalPixels; i++) {
    const bytePos = pixelDataOffset + i * pixelByteStride;
    if (bytePos + pixelByteStride > buffer.byteLength) break;

    let val = 0;
    if (is16Bit) {
      val = isSigned ? view.getInt16(bytePos, true) : view.getUint16(bytePos, true);
      if (bitsStored < 16) {
        const mask = (1 << bitsStored) - 1;
        val = val & mask;
      }
      val = val * rescaleSlope + rescaleIntercept;
      rawValues![i] = val;
    } else {
      val = bytes[bytePos];
    }

    if (val < minVal) minVal = val;
    if (val > maxVal) maxVal = val;
  }

  // Windowing calculation
  let wCenter = windowCenter;
  let wWidth = windowWidth;

  if (wCenter === undefined || wWidth === undefined || wWidth <= 0) {
    wCenter = (minVal + maxVal) / 2;
    wWidth = Math.max(1, maxVal - minVal);
  }

  const windowMin = wCenter - wWidth / 2;
  const windowMax = wCenter + wWidth / 2;
  const isMonochrome1 = photometric.toUpperCase().includes('MONOCHROME1');

  for (let i = 0; i < totalPixels; i++) {
    const rawVal = is16Bit ? rawValues![i] : bytes[pixelDataOffset + i];
    let normalized = 0;

    if (rawVal <= windowMin) {
      normalized = 0;
    } else if (rawVal >= windowMax) {
      normalized = 255;
    } else {
      normalized = ((rawVal - windowMin) / (windowMax - windowMin)) * 255;
    }

    let gray = Math.round(normalized);
    if (isMonochrome1) {
      gray = 255 - gray;
    }

    const idx = i * 4;
    data[idx] = gray;
    data[idx + 1] = gray;
    data[idx + 2] = gray;
    data[idx + 3] = 255;
  }

  ctx.putImageData(imgData, 0, 0);

  return {
    width: cols,
    height: rows,
    dataUrl: canvas.toDataURL('image/png'),
    photometric,
    bitsAllocated,
    windowCenter: wCenter,
    windowWidth: wWidth,
    modality,
    studyDescription,
    seriesDescription,
  };
}

function readUint(view: DataView, offset: number, length: number): number {
  if (length === 2) return view.getUint16(offset, true);
  if (length === 4) return view.getUint32(offset, true);
  if (length === 1) return view.getUint8(offset);
  return 0;
}

function readString(bytes: Uint8Array, offset: number, length: number): string {
  let str = '';
  for (let i = 0; i < length; i++) {
    const code = bytes[offset + i];
    if (code === 0) break;
    str += String.fromCharCode(code);
  }
  return str;
}

function extractEncapsulatedJpeg(bytes: Uint8Array): DicomParseResult | null {
  for (let i = 0; i < bytes.length - 4; i++) {
    if (bytes[i] === 0xff && bytes[i + 1] === 0xd8 && bytes[i + 2] === 0xff) {
      for (let j = i + 2; j < bytes.length - 1; j++) {
        if (bytes[j] === 0xff && bytes[j + 1] === 0xd9) {
          const jpegSlice = bytes.buffer.slice(
            bytes.byteOffset + i,
            bytes.byteOffset + j + 2
          ) as ArrayBuffer;
          const blob = new Blob([jpegSlice], { type: 'image/jpeg' });
          const objectUrl = URL.createObjectURL(blob);
          return {
            width: 1000,
            height: 600,
            dataUrl: objectUrl,
            photometric: 'MONOCHROME2',
            bitsAllocated: 8,
          };
        }
      }
    }
  }
  return null;
}
