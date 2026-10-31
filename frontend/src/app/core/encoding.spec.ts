/**
 * A text-encoding guard over the whole source tree.
 *
 * ## Why this file exists
 *
 * During Phase 11 group 5 a bulk edit of `core/api/endpoints.ts` was performed with a
 * PowerShell `Get-Content -Raw | … | Set-Content -Encoding utf8` pipeline. Windows
 * PowerShell 5.1 decodes a BOM-less file with the **system ANSI code page**, so every
 * non-ASCII character in the file — em dashes, `⇄`, `§` — was decoded as cp1252
 * bytes, edited as mojibake, and written back as valid UTF-8 mojibake. Then a
 * "repair" round-trip through Node's `latin1` turned each of those into U+FFFD.
 *
 * The file still **compiled and all 245 unit tests still passed**, because the damage
 * was confined to comments. It would have shipped 20 replacement characters into a
 * file whose entire purpose is to be the readable map of the backend surface.
 *
 * Nothing about `tsc` or Vitest can catch that: U+FFFD is a valid character. So this
 * test does.
 *
 * ## What it checks
 *
 * Every `.ts`, `.html`, `.css`, `.json`, `.md`, `.mjs` and `.js` file under the
 * project root, excluding build output:
 *
 * - **U+FFFD** — the replacement character. Its presence means a decode failed
 *   somewhere, and the text is already lost.
 * - **stray C0 control characters** other than tab, LF and CR — the other half of the
 *   same damage. When cp1252 decodes UTF-8 bytes it produces these, and they are what
 *   let the U+FFFD be located and reversed.
 * - **a byte-order mark** on a source file. A BOM in a `.ts` file is legal to the
 *   compiler and a reliable predictor of the exact toolchain that caused this.
 *
 * ## The rule it encodes
 *
 * **Never edit a source file through a shell pipeline.** Use the editor's read and
 * write tools. `frontend/README.md` says so, and this test is what makes the saying
 * enforceable rather than advisory.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The project root.
 *
 * `process.cwd()`, which is the `frontend/` directory for every script in
 * `package.json` — not `import.meta.url`, which the Angular test builder rewrites to
 * a bundle location under `.angular/` and which therefore points at build output.
 *
 * Verified below rather than assumed: a wrong root would make the walk find nothing
 * and every assertion would pass vacuously.
 */
const ROOT = process.cwd();

/**
 * PHASE 49. `test-results` and `playwright-report` joined the skip list, and this is a
 * real correction rather than a convenience.
 *
 * After a Playwright matrix the `test-results/` directory held 2,698 generated files,
 * among them Playwright's `error-context.md` snapshots -- which capture page and console
 * text verbatim, including the mojibake this repository's own logs produced. Those are
 * `.md`, which IS a scanned extension, so the guard read a GENERATED failure artefact as
 * if it were source and reported the source tree as corrupted.
 *
 * The causation was established rather than assumed: with the directory populated the
 * spec failed, and with it emptied it passed in 550 ms instead of 12,917 ms. The 23x
 * slowdown was the same 2,698 files.
 *
 * None of that is a reason to weaken the check. It still walks every real source file,
 * and `the walk covers src/app` still asserts it. Generated output is simply not source,
 * and a guard that reads a test runner's debris is measuring the runner, not the tree.
 */
const SKIP_DIRECTORIES = new Set([
  'node_modules',
  'dist',
  '.angular',
  '.git',
  'coverage',
  'test-results',
  'playwright-report',
  '.matrix-stream',
]);
const SOURCE_EXTENSIONS = ['.ts', '.html', '.css', '.json', '.md', '.mjs', '.js'];

function sourceFiles(directory: string, found: string[] = []): string[] {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (SKIP_DIRECTORIES.has(entry.name)) continue;
      sourceFiles(path, found);
      continue;
    }
    if (SOURCE_EXTENSIONS.some((extension) => entry.name.endsWith(extension))) {
      found.push(path);
    }
  }
  return found;
}

describe('the source tree is UTF-8 and was not round-tripped through a shell', () => {
  const files = sourceFiles(ROOT).filter((path) => {
    // Only files this project owns; the repository above `frontend` is not ours to
    // police, and phase-11 ownership is `frontend/**`.
    return relative(ROOT, path).split(sep).length > 0 && statSync(path).isFile();
  });

  it('is looking at the frontend workspace, so an empty scan is not mistaken for a pass', () => {
    // A wrong root would make the walk find nothing and every assertion below would
    // pass vacuously. This is the check that stops "0 files scanned" from reading as
    // "0 files damaged" — the failure mode that makes an encoding guard worthless.
    expect(ROOT.split(sep).pop(), 'the working directory is frontend/').toBe('frontend');
    expect(files.length, `source files discovered under ${ROOT}`).toBeGreaterThan(40);
    // Named files, not just a count: an accidentally-skipped `src` would still leave
    // `e2e` and `tools` non-empty.
    expect(
      files.some((path) => path.includes(`${sep}src${sep}app${sep}`)),
      'the walk covers src/app',
    ).toBe(true);
  });

  it('contains no replacement characters', () => {
    // Built from the code point rather than written as a literal: this file asserts
    // the *absence* of U+FFFD across the tree, and a literal one in the assertion
    // would make the test fail on itself forever.
    const replacement = String.fromCharCode(0xfffd);
    const damaged = files
      .map((path) => ({ path, count: countOccurrences(readFileSync(path, 'utf8'), replacement) }))
      .filter((entry) => entry.count > 0)
      .map((entry) => `${relative(ROOT, entry.path)}: ${entry.count}`);
    expect(damaged, 'files containing U+FFFD (a decode failed and the text is lost)').toEqual([]);
  });

  it('contains no stray control characters', () => {
    const damaged: string[] = [];
    for (const path of files) {
      const text = readFileSync(path, 'utf8');
      for (let index = 0; index < text.length; index += 1) {
        const code = text.charCodeAt(index);
        // Tab, LF and CR are the only control characters a source file may contain.
        if (code < 32 && code !== 9 && code !== 10 && code !== 13) {
          damaged.push(`${relative(ROOT, path)} offset ${index}: U+${code.toString(16).toUpperCase()}`);
          break;
        }
      }
    }
    expect(damaged, 'files containing a C0 control character outside tab/LF/CR').toEqual([]);
  });

  it('carries no byte-order mark', () => {
    const withBom = files
      .filter((path) => readFileSync(path).subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])))
      .map((path) => relative(ROOT, path));
    // Legal to every tool in this project, and the exact fingerprint of the
    // PowerShell 5.1 pipeline that caused the damage above.
    expect(withBom, 'source files starting with a UTF-8 BOM').toEqual([]);
  });

  it('keeps the Arabic strings intact, because an encoding fault destroys them silently', () => {
    // The product is Arabic-first. A mojibake fault in an i18n file would leave every
    // **key** intact — so every key-counting test in `i18n.spec.ts` would still pass —
    // while rendering nonsense to the primary audience. This asserts the characters
    // themselves survived.
    //
    // `العقود الموقّع` also carries a diacritic (`ّ`, U+0651) and a shadda-bearing
    // `ّ`, both of which a wrong code page destroys first. Two strings with different
    // letter counts, so this cannot pass on a substring coincidence.
    const core = readFileSync(join(ROOT, 'src', 'app', 'core', 'i18n', 'strings', 'core.ts'), 'utf8');
    expect(core, 'a plain Arabic nav label').toContain('المعرفة والوكيل');
    expect(core, 'an Arabic sentence with diacritics').toContain('العقد الموقّع');
    // The shadda above `ع` is a combining mark; if it were dropped, the sentence would
    // still render and still be readable, which is precisely why it is asserted here.
    expect(core, 'the diacritic survived').toContain('الموقّع');

    // And the count is what an Arabic reviewer would expect, not what a Latin-only
    // file would produce: the shell namespace is Arabic-first, so a majority of its
    // values contain Arabic script.
    const arabicValues = (core.match(/ar: '[^']*[\u0600-\u06FF][^']*'/g) ?? []).length;
    const latinOnlyValues = (core.match(/ar: '[^']*'/g) ?? []).length - arabicValues;
    expect(
      arabicValues,
      `Arabic values (${arabicValues}) must outnumber non-Arabic ones (${latinOnlyValues})`,
    ).toBeGreaterThan(latinOnlyValues);
  });
});

function countOccurrences(text: string, needle: string): number {
  let count = 0;
  let index = text.indexOf(needle);
  while (index !== -1) {
    count += 1;
    index = text.indexOf(needle, index + needle.length);
  }
  return count;
}
