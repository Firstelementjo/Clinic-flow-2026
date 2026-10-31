// One-off maintenance script: renames the `state.state.*` translation keys to
// `state.*`, so the namespace name and the key prefix stop doubling up in every
// template (`'state.state.loading'` reads as a mistake, and it was one).
//
// Kept in the repository rather than run and deleted, because the rename is a
// reviewable diff and a future key addition should not repeat it.
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

function walk(directory) {
  const out = [];
  for (const entry of readdirSync(directory)) {
    const full = join(directory, entry);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

const root = process.argv[2] ?? 'src';
const before = "'state.state.";
const after = "'state.";

let changed = 0;
for (const file of walk(root).filter((name) => /\.(ts|html)$/.test(name))) {
  const source = readFileSync(file, 'utf8');
  const rewritten = source.split(before).join(after);
  if (rewritten !== source) {
    writeFileSync(file, rewritten);
    changed += 1;
  }
}
console.log(`rewrote ${changed} file(s)`);
