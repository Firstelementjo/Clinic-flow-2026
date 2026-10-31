// One-off: restores LF line endings in files a PowerShell line-based rewrite flipped to
// CRLF.
//
// `WriteAllLines` writes `Environment.NewLine`, which on Windows is CRLF. The repository is
// LF, so a file edited that way turns a small change into a whole-file diff. This has
// happened repeatedly in this project, and neither typecheck, `npm test` nor
// `generate:api:check` can see it.
//
// Byte-level on purpose: re-encoding through a shell is how the Arabic and the em dashes
// were lost the first time, and a tool that fixes line endings must not be the thing that
// breaks encoding.
//
// Run with `node tools/restore-lf.mjs <file> [file…]`.

import { readFileSync, writeFileSync } from 'node:fs';

for (const path of process.argv.slice(2)) {
  const text = readFileSync(path, 'utf8');
  const crlf = (text.match(/\r\n/g) ?? []).length;
  if (crlf === 0) {
    console.log(`  ${path}: already LF`);
    continue;
  }
  const fixed = text.replace(/\r\n/g, '\n');
  writeFileSync(path, fixed, 'utf8');
  console.log(`  ${path}: ${crlf} CRLF -> ${(fixed.match(/\n/g) ?? []).length} LF`);
}