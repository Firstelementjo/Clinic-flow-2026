/**
 * The design-system rules, enforced rather than asserted.
 *
 * `docs/design-system.md` says "Colors come from tokens. A hex value in a feature
 * component is a bug." and `docs/migration-AGENT.md` §5 says "Use CSS logical
 * properties everywhere". Both are conventions until something fails a build when
 * they are broken, and this file is that something.
 *
 * These are cheap, mechanical checks with no false-positive tolerance: a rule that
 * cries wolf gets ignored, so each one only fires on something genuinely wrong.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Paths are resolved from the workspace root, not from `__dirname`.
 *
 * The suite is bundled before it runs, so `__dirname` is the bundler's output
 * directory rather than the source tree; `process.cwd()` is the workspace root
 * that `npm test` runs from, which is stable.
 */
const workspaceRoot = process.cwd();
const appRoot = join(workspaceRoot, 'src', 'app');
const stylesRoot = join(workspaceRoot, 'src', 'styles');

function walk(directory: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(directory)) {
    const full = join(directory, entry);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

const sourceFiles = walk(appRoot)
  .filter((file) => /\.(ts|css|html)$/.test(file))
  .filter((file) => !file.includes(`${'generated'}`))
  .filter((file) => !file.endsWith('.spec.ts'));

function relative(file: string): string {
  return file.replace(workspaceRoot, '').replace(/\\/g, '/');
}

/** The one place raw colour values are allowed to live. */
const TOKEN_FILE = join(stylesRoot, 'tokens.css');

describe('design tokens', () => {
  it('keeps every colour value in the token file', () => {
    const offenders: string[] = [];
    const colour = /(#[0-9a-fA-F]{3,8}\b|rgba?\(|oklch\()/;
    for (const file of sourceFiles) {
      if (file === TOKEN_FILE) continue;
      const source = readFileSync(file, 'utf8');
      source.split('\n').forEach((line, index) => {
        // A comment explaining a token name is not a colour value.
        const withoutComment = line.replace(/\/\/.*$/, '').replace(/\/\*.*?\*\//g, '');
        if (colour.test(withoutComment)) {
          offenders.push(`${relative(file)}:${index + 1}`);
        }
      });
    }
    expect(offenders, 'raw colour values outside tokens.css').toEqual([]);
  });

  it('defines both themes for the clinical ground tokens', () => {
    // Dark mode is not an inversion: it has its own values. `migration-AGENT.md`
    // §4.2 and the frozen client both say so, and an inversion is the mistake
    // that makes a night shift's screen unreadable.
    const tokens = readFileSync(TOKEN_FILE, 'utf8');
    for (const token of ['--bg', '--surf', '--surf2', '--ink', '--mute', '--line', '--pri', '--pri-ink', '--pri-soft']) {
      const occurrences = tokens.split(`  ${token}:`).length - 1;
      expect(occurrences, `${token} is defined ${occurrences} time(s), expected light and dark`).toBe(2);
    }
  });

  it('reserves the status colours: green, amber and red are not decoration', () => {
    const tokens = readFileSync(TOKEN_FILE, 'utf8');
    for (const token of ['--ok', '--warn', '--bad']) {
      expect(tokens).toContain(`  ${token}:`);
    }
    // One primary colour. A second action colour is how a palette stops meaning
    // anything.
    expect(tokens).toContain('  --pri:');
    expect(tokens).not.toContain('  --purple:');
  });
});

describe('directional CSS', () => {
  it('uses logical properties rather than physical left/right', () => {
    const offenders: string[] = [];
    // `margin-left`, `padding-right`, `border-left`, `left:`, `right:` and
    // `text-align: left/right` are all physical. In an RTL document they resolve
    // to the wrong edge, which is the whole bug this rule prevents.
    const physical =
      /(margin|padding|border)-(left|right)\s*:|^\s*(left|right)\s*:|text-align\s*:\s*(left|right)\b/;
    for (const file of sourceFiles) {
      if (!file.endsWith('.css')) continue;
      const source = readFileSync(file, 'utf8');
      source.split('\n').forEach((line, index) => {
        const withoutComment = line.replace(/\/\*.*?\*\//g, '').replace(/\/\/.*$/, '');
        if (physical.test(withoutComment)) {
          offenders.push(`${relative(file)}:${index + 1} — ${line.trim()}`);
        }
      });
    }
    expect(offenders, 'physical directional CSS').toEqual([]);
  });

  it('keeps a visible focus ring in the global stylesheet and never removes one', () => {
    const base = readFileSync(join(stylesRoot, 'base.css'), 'utf8');
    expect(base).toContain(':focus-visible');
    expect(base).toContain('outline: 2px solid var(--fe-focus)');

    const offenders: string[] = [];
    for (const file of sourceFiles) {
      if (!file.endsWith('.css')) continue;
      const source = readFileSync(file, 'utf8');
      if (/outline\s*:\s*(none|0)\s*;/.test(source)) {
        offenders.push(relative(file));
      }
      if (/outline\s*:\s*[^;]*transparent/i.test(source)) {
        offenders.push(`${relative(file)} (transparent outline)`);
      }
    }
    expect(offenders, 'a removed focus ring').toEqual([]);
  });

  it('respects reduced motion globally', () => {
    const base = readFileSync(join(stylesRoot, 'base.css'), 'utf8');
    expect(base).toContain('prefers-reduced-motion: reduce');
    const components = readFileSync(join(stylesRoot, 'components.css'), 'utf8');
    expect(components).toContain('prefers-reduced-motion: reduce');
  });
});

describe('accessibility affordances', () => {
  it('provides a skip link to the main region', () => {
    const shell = readFileSync(join(appRoot, 'shell', 'shell.component.html'), 'utf8');
    expect(shell).toContain('class="fe-skip"');
    expect(shell).toContain('href="#fe-main"');
    expect(shell).toContain('id="fe-main"');
  });

  it('marks every status as text, not by colour alone', () => {
    // `docs/migration-AGENT.md` §6: "Status is never shown by color alone." The
    // chip takes its label as projected content, so a chip cannot render colour
    // without a word.
    const chip = readFileSync(join(appRoot, 'ui', 'status-chip.component.ts'), 'utf8');
    expect(chip).toContain('<ng-content />');
  });

  it('keeps icons out of the accessibility tree', () => {
    const icon = readFileSync(join(appRoot, 'ui', 'icon.component.ts'), 'utf8');
    expect(icon).toContain('aria-hidden="true"');
    expect(icon).toContain('focusable="false"');
  });

  it('uses no emoji in the UI', () => {
    // `docs/migration-AGENT.md` §4.5: "No emojis in the UI."
    const emoji = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;
    const offenders: string[] = [];
    for (const file of sourceFiles) {
      if (file.endsWith('.spec.ts')) continue;
      const source = readFileSync(file, 'utf8');
      source.split('\n').forEach((line, index) => {
        if (emoji.test(line)) offenders.push(`${relative(file)}:${index + 1}`);
      });
    }
    expect(offenders, 'emoji in the UI').toEqual([]);
  });

  it('uses no all-caps text in a template', () => {
    // `docs/migration-AGENT.md` §4.1: sentence case everywhere. The design system
    // uses a small, spaced, uppercase *class* for section markers, which is a
    // `fe-eyebrow`, not a label typed in capitals.
    const offenders: string[] = [];
    for (const file of sourceFiles) {
      if (!file.endsWith('.html')) continue;
      const source = readFileSync(file, 'utf8');
      for (const match of source.matchAll(/>([A-Z][A-Z ]{3,})</g)) {
        offenders.push(`${relative(file)} — ${match[1]}`);
      }
    }
    expect(offenders, 'all-caps label in a template').toEqual([]);
  });

  it('never hides a label with display:none where that strips an accessible name', () => {
    // The defect this catches was real and shipped once: the shell collapsed the
    // navigation rail to icons under 860px using `display: none` on the label, and
    // `display: none` removes an element from the accessibility tree as well as the
    // layout. Every navigation item became an unnamed link — a screen-reader user
    // lost the entire navigation at exactly the width where they are most likely to
    // be on a phone, and it read as a responsive-design decision rather than as an
    // accessibility regression.
    //
    // The sanctioned way to hide text visually is the clip pattern
    // (`position: absolute` + `clip-path: inset(50%)`), which keeps the name. So the
    // rule is: a label-bearing selector may not be `display: none` in a responsive
    // block, and `fe-skip` — which is *supposed* to be invisible until focused — is
    // the one exemption, handled by position rather than by display.
    const labelSelectors =
      /^\s*\.(nav__label|brand__name|account__meta|toggle__text)|^\s*\.header__context \.fe-eyebrow|^\s*\.field__label|^\s*\.nav__group/gm;

    const offenders: string[] = [];
    for (const file of sourceFiles) {
      if (!file.endsWith('.css')) continue;
      const source = readFileSync(file, 'utf8');
      // Each responsive/at-rule block, so a `display: none` is judged in the context
      // it was written in rather than anywhere in the file.
      for (const block of source.matchAll(/@media[^{]*\{([\s\S]*?\n\})\s*(?=@media|\n\S|$)/g)) {
        const body = block[1] ?? '';
        for (const rule of body.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
          const selector = (rule[1] ?? '').trim();
          if (!labelSelectors.test(selector)) continue;
          labelSelectors.lastIndex = 0;
          if (/(^|;)\s*display\s*:\s*none/.test(rule[2] ?? '')) {
            offenders.push(`${relative(file)} — ${selector}`);
          }
        }
      }
    }
    expect(
      offenders,
      'labels hidden with display:none, which also removes their accessible name',
    ).toEqual([]);
  });

  it('uses sentence case in the translation dictionary', () => {
    // The same rule, enforced on the copy that is actually rendered. A route that
    // shouted was rewritten when this fired.
    const offenders: string[] = [];
    for (const file of walk(join(appRoot, '..')).filter((f) => f.endsWith('.strings.ts'))) {
      const source = readFileSync(file, 'utf8');
      for (const match of source.matchAll(/en:\s*'([^']{3,})'/g)) {
        const value = match[1] ?? '';
        // An acronym or a route is legitimate; a shouted sentence is not.
        const letters = value.replace(/[^A-Za-z]/g, '');
        if (letters.length >= 4 && letters === letters.toUpperCase() && !/^[A-Z]{2,}$/.test(letters)) {
          offenders.push(`${relative(file)} — ${value}`);
        }
      }
    }
    expect(offenders, 'all-caps English copy').toEqual([]);
  });
});

describe('numerals', () => {
  it('sets tabular figures on the operational number classes', () => {
    const base = readFileSync(join(stylesRoot, 'base.css'), 'utf8');
    expect(base).toContain('font-variant-numeric: tabular-nums');
    const components = readFileSync(join(stylesRoot, 'components.css'), 'utf8');
    expect(components).toContain('font-variant-numeric: tabular-nums');
  });
});

