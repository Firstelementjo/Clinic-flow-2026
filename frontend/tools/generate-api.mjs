/**
 * Regenerates the typed client from the committed Spring contract.
 *
 * The contract is `tests/contracts/openapi.json` — the document the running
 * application serves, promoted by
 * `.\ops\quality\checks\export-openapi-document.ps1` and kept honest by
 * `check-openapi-document.ps1`. It is a build input, not a vendored artefact:
 * regenerating is cheap, so a committed client cannot drift silently.
 *
 * The copy into `.openapi/` exists because the generator resolves `$ref`
 * against the file it is pointed at, and it must not write anything into
 * `tests/contracts/` (not ours to modify).
 *
 * ## The drift check
 *
 * `--check` compares a **freshly generated** tree against the committed one
 * byte for byte, and writes `.openapi/generated.sha256` when they agree.
 *
 * The obvious implementation — `git status --porcelain -- generated/` — is
 * wrong, and quietly so. `git status` also reports a path as modified because
 * of line-ending normalisation, a `.gitattributes` rule, a mode bit, or an
 * editor that rewrote the file identically; and it reports nothing at all for a
 * file that is untracked and unignored, so in a fresh workspace the check
 * silently passes against a stale client. Both failures look like a green run.
 *
 * Comparing content is the only version of this gate that means what it says.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const toolsDir = dirname(fileURLToPath(import.meta.url));
const workspaceDir = resolve(toolsDir, '..');
const repoRoot = resolve(workspaceDir, '..');

/** The committed contract. Throws with a fix if it is missing. */
const contractPath = join(repoRoot, 'tests', 'contracts', 'openapi.json');
const stagingDir = join(workspaceDir, '.openapi');
const stagedContract = join(stagingDir, 'source.json');
const fingerprintPath = join(stagingDir, 'generated.sha256');
const generatedDir = join(workspaceDir, 'src', 'app', 'core', 'api', 'generated');

/**
 * Where the committed tree is compared from, copied aside before regeneration.
 */
const snapshotDir = join(stagingDir, 'committed');

/**
 * Where a new tree is built before it replaces the committed one.
 *
 * Generation must not happen **in place**. `@hey-api/openapi-ts` writes into the
 * output directory, so an in-place run is a window in which `src/app/core/api/
 * generated/` does not exist — and if a dev server or a watch task is serving
 * during that window, it reports `TS2307: Cannot find module './generated'` to the
 * browser and the failure lands on a journey that did nothing wrong. That is not
 * hypothetical: it is how the first full-matrix run of the Phase 11 gate failed.
 *
 * Building into a scratch directory and swapping afterwards makes the whole
 * regeneration atomic from a reader's point of view.
 */
const buildDir = join(stagingDir, 'generated-build');

function readContract() {
  try {
    return JSON.parse(readFileSync(contractPath, 'utf8'));
  } catch {
    throw new Error(
      `Cannot read the committed contract at ${contractPath}. ` +
        'Phase 11 generates its client from tests/contracts/openapi.json and from nothing else.',
    );
  }
}

/** `relative path -> sha256`, over every file, order-independent. */
function fingerprint(root) {
  const files = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
      a.name < b.name ? -1 : 1,
    )) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile()) files.push(full);
    }
  };
  walk(root);
  const hash = createHash('sha256');
  for (const file of files) {
    // The path is hashed as well as the content: a rename is a change even when
    // the bytes are identical, and a client whose types moved files is a change
    // a reviewer needs to see.
    hash.update(relative(root, file).replace(/\\/g, '/'));
    hash.update('\0');
    hash.update(readFileSync(file));
    hash.update('\0');
  }
  return { digest: hash.digest('hex'), count: files.length };
}

const contract = readContract();
mkdirSync(stagingDir, { recursive: true });
writeFileSync(stagedContract, `${JSON.stringify(contract)}\n`, 'utf8');

const checking = process.argv.includes('--check');

// The committed tree is copied aside first so it survives the swap below; a
// `--check` run compares the two rather than comparing something with itself.
rmSync(snapshotDir, { recursive: true, force: true });
if (checking) {
  try {
    execFileSync(
      process.execPath,
      [
        '-e',
        `require('fs').cpSync(${JSON.stringify(generatedDir)}, ${JSON.stringify(snapshotDir)}, { recursive: true })`,
      ],
      { stdio: 'inherit' },
    );
  } catch {
    console.error(
      `No committed generated client at ${relative(repoRoot, generatedDir)}. ` +
        'Run `npm run generate:api` and commit the result.',
    );
    process.exit(1);
  }
}

// Build beside the committed tree, never over it, then swap. See `buildDir`.
rmSync(buildDir, { recursive: true, force: true });
execFileSync(
  process.execPath,
  [
    join(workspaceDir, 'node_modules', '@hey-api', 'openapi-ts', 'bin', 'run.js'),
    '--output',
    buildDir,
  ],
  { cwd: workspaceDir, stdio: 'inherit' },
);

/** Puts the freshly built tree where the committed one was, atomically enough. */
function swapInFreshTree() {
  rmSync(generatedDir, { recursive: true, force: true });
  mkdirSync(dirname(generatedDir), { recursive: true });
  execFileSync(
    process.execPath,
    [
      '-e',
      `require('fs').cpSync(${JSON.stringify(buildDir)}, ${JSON.stringify(generatedDir)}, { recursive: true })`,
    ],
    { stdio: 'inherit' },
  );
}

const paths = Object.keys(contract.paths ?? {});
const schemas = Object.keys(contract.components?.schemas ?? {});
const fresh = fingerprint(buildDir);

if (!checking) {
  swapInFreshTree();
  rmSync(buildDir, { recursive: true, force: true });
  writeFileSync(fingerprintPath, `${fresh.digest} ${fresh.count}\n`, 'utf8');
  console.log(
    `Generated from ${paths.length} paths and ${schemas.length} schemas ` +
      `(${fresh.count} files, sha256 ${fresh.digest.slice(0, 12)}).`,
  );
  process.exit(0);
}

const committed = fingerprint(snapshotDir);
rmSync(snapshotDir, { recursive: true, force: true });
// Leave the working tree exactly as it was found: a check that rewrites the
// client it is checking is a check that cannot be trusted to have checked.
swapInFreshTree();
rmSync(buildDir, { recursive: true, force: true });

if (committed.digest !== fresh.digest) {
  console.error(
    'The generated client is stale against tests/contracts/openapi.json.\n' +
      `  committed: ${committed.count} files, sha256 ${committed.digest.slice(0, 12)}\n` +
      `  generated: ${fresh.count} files, sha256 ${fresh.digest.slice(0, 12)}\n` +
      'Run `npm run generate:api` and commit the result.',
  );
  process.exit(1);
}

console.log(
  `The generated client matches the contract ` +
    `(${paths.length} paths, ${schemas.length} schemas, ${fresh.count} files).`,
);
