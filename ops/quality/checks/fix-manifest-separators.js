const fs = require('fs');
const p = 'tests/contracts/expected-suites.json';
const raw = fs.readFileSync(p, 'utf8');

const key = '"suites": [';
const at = raw.indexOf(key);
if (at < 0) throw new Error('no suites array found');
const header = raw.slice(0, at + key.length);

// Collect balanced {...} objects from the array body. This tolerates the missing
// separators and stray brackets that hand-editing this file has produced, instead of
// failing on the first one -- a repair tool that stops at the first error is useless
// when the file is already broken.
const body = raw.slice(at + key.length);
const objects = [];
let depth = 0;
let start = -1;
let inStr = false;
let esc = false;
for (let i = 0; i < body.length; i++) {
  const c = body[i];
  if (inStr) {
    if (esc) { esc = false; continue; }
    if (c === '\\') { esc = true; continue; }
    if (c === '"') inStr = false;
    continue;
  }
  if (c === '"') { inStr = true; continue; }
  if (c === '{') { if (depth === 0) start = i; depth++; continue; }
  if (c === '}') {
    depth--;
    if (depth === 0 && start >= 0) {
      const chunk = body.slice(start, i + 1);
      try { objects.push(JSON.parse(chunk)); } catch (e) { throw new Error('unparseable suite object: ' + e.message + '\n' + chunk.slice(0, 160)); }
      start = -1;
    }
  }
}

const seen = new Set();
const suites = objects.filter((s) => {
  const k = s.suite + '|' + (s.reportName || '');
  if (seen.has(k)) return false;
  seen.add(k);
  return true;
});

const out = header + '\n' + suites.map((s) => '    ' + JSON.stringify(s)).join(',\n') + '\n  ]\n}\n';
fs.writeFileSync(p, out);
const j = JSON.parse(fs.readFileSync(p, 'utf8'));
console.log('rebuilt OK: suites=' + j.suites.length);
