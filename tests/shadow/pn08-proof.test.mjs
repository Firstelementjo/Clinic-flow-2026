/* global process, console, test */
/**
 * pn08-proof.test.mjs -- the two states of the PN-08 parity flag, both produced
 * by real execution.
 *
 * Run:
 *   node --test tests/shadow/pn08-proof.test.mjs
 *
 * ---------------------------------------------------------------------
 * WHY THIS TEST EXISTS INSTEAD OF A COMMENT SAYING "I CHECKED IT"
 * ---------------------------------------------------------------------
 * PN-08 sat at `proved: false` for a whole phase and nobody could look at it,
 * because exercising it meant booting two runtimes and two databases. A flag
 * that can only be evaluated in a five-minute full-stack run is a flag that
 * gets evaluated five-minute-full-stack-run-never. Splitting the verdict into
 * `pn08-proof.mjs` makes both of its states reachable from `node --test`, which
 * is what turns "I looked at it" into "here are the two outputs".
 *
 * Every case below runs the REAL comparator (`compare.mjs`) to produce the
 * parity artefact and the REAL verdict (`pn08-proof.mjs`) to read it. Nothing
 * here re-implements CHK-7.
 *
 * The `pre-fix expression` cases run the pre-fix PowerShell verbatim, kept in
 * `pn08-prefix-reproduction.ps1`, to pin down what used to happen. Those are
 * regression guards on the SHAPE of the old bug, not on product behaviour.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const fixtures = join(here, 'fixtures', 'pn08');
const compareScript = join(here, 'compare.mjs');
const proofScript = join(here, 'pn08-proof.mjs');
const prefixScript = join(here, 'pn08-prefix-reproduction.ps1');

/** The cost the perturbation's SQL file inserts, and the value the gate declares. */
const ADDED_COST = 2.0;

function runNode(script, args) {
  try {
    const stdout = execFileSync('node', [script, ...args], { encoding: 'utf8' });
    return { code: 0, stdout };
  } catch (error) {
    return { code: error.status ?? -1, stdout: `${error.stdout ?? ''}${error.stderr ?? ''}` };
  }
}

function runPowerShell(args) {
  try {
    const stdout = execFileSync(
      'powershell',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', prefixScript, ...args],
      { encoding: 'utf8' },
    );
    return { code: 0, stdout };
  } catch (error) {
    return { code: error.status ?? -1, stdout: `${error.stdout ?? ''}${error.stderr ?? ''}` };
  }
}

/** Run the real comparator over a finance fixture; returns the parity artefact. */
function compare(perturbedName, workDir) {
  const finance = join(fixtures, perturbedName);
  const parityPath = join(workDir, `${perturbedName}.parity.json`);
  const args = [
    '--legacy', join(fixtures, 'stubs', 'legacy.json'),
    '--spring', join(fixtures, 'stubs', 'spring.json'),
    '--scenarios', join(fixtures, 'stubs', 'scenarios.json'),
    '--finance', finance,
    '--out', parityPath,
  ];
  runNode(compareScript, args);
  assert.ok(existsSync(parityPath), `the comparator wrote no parity artefact for ${perturbedName}`);
  return { finance, parityPath, parity: JSON.parse(readFileSync(parityPath, 'utf8')) };
}

/** Run the real PN-08 verdict over a finance fixture; returns stdout + artefact. */
function prove(perturbedName, workDir, overrides = {}) {
  const { finance, parityPath } = compare(perturbedName, workDir);
  const outPath = join(workDir, `${perturbedName}.proof.json`);
  const args = [
    '--baseline', overrides.baseline ?? join(fixtures, 'finance-baseline.json'),
    '--perturbed', finance,
    '--parity', overrides.parity ?? parityPath,
    '--dimension', 'MESSAGE',
    '--added-cost', String(overrides.addedCost ?? ADDED_COST),
    '--out', outPath,
  ];
  const result = runNode(proofScript, args);
  return {
    code: result.code,
    stdout: result.stdout,
    proof: existsSync(outPath) ? JSON.parse(readFileSync(outPath, 'utf8')) : null,
  };
}

function withWorkDir(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'pn08-test-'));
  try { return fn(dir); } finally { rmSync(dir, { recursive: true, force: true }); }
}

// =====================================================================
// STATE 1 -- the product behaved. PN-08 is PROVED.
// =====================================================================

test('PN-08 state 1: a rollup that followed the appended event into a new generation, leaving generation 1 frozen, is PROVED', () => {
  withWorkDir((workDir) => {
    const { code, stdout, proof } = prove('finance-perturbed-gen2.json', workDir);
    assert.equal(proof.derivationVerdict, 'PASS', 'CHK-7 must agree with the source rows after the perturbation');
    assert.equal(proof.revenueFollowedTheEvent, true);
    assert.equal(proof.sourceEventCountAdvanced, true);
    assert.equal(proof.generationOneFrozen, true);
    assert.equal(proof.proved, true);
    assert.deepEqual(proof.reasons, []);
    assert.equal(code, 0);
    assert.match(stdout, /^PROVED {2}PN-08/m);
  });
});

test('PN-08 state 1 is only reachable when the revenue really moved by cost x the pinned markup', () => {
  withWorkDir((workDir) => {
    // The baseline snapshot pins markup 1.5 and revenue 15; the perturbation
    // adds cost 2.000000; a derivation therefore has to land on exactly 18.
    const { proof } = prove('finance-perturbed-gen2.json', workDir);
    assert.equal(proof.observed.baselineSnapshot.revenue, 15);
    assert.equal(proof.observed.baselineSnapshot.markup, 1.5);
    assert.equal(proof.observed.perturbedCurrent.generation, 2, 'the recomputation must land in a NEW generation');
    assert.equal(proof.observed.perturbedCurrent.revenue, 18);
    assert.equal(proof.observed.perturbedCurrent.sourceEventCount, 2);
  });
});

// =====================================================================
// STATE 2 -- the product did not. PN-08 turns RED. These are the cases
// the flag exists for; state 1 alone proves nothing.
// =====================================================================

test('PN-08 state 2: a rollup whose revenue did NOT move is NOT PROVED', () => {
  withWorkDir((workDir) => {
    const { code, stdout, proof } = prove('finance-perturbed-revenue-cached.json', workDir);
    assert.equal(proof.derivationVerdict, 'FAIL', 'the real comparator must reject a cached revenue');
    assert.equal(proof.revenueFollowedTheEvent, false);
    assert.equal(proof.proved, false);
    assert.ok(proof.reasons.some((r) => r.includes('A rollup that did not move cannot have been derived from the ledger')));
    assert.equal(code, 1);
    assert.match(stdout, /^NOT PROVED {2}PN-08/m);
  });
});

test('PN-08 state 2: generation 1 rewritten in place by the recomputation is NOT PROVED -- and this is the case the pre-fix flag could not see', () => {
  withWorkDir((workDir) => {
    const { code, proof } = prove('finance-perturbed-gen1-rewritten.json', workDir);

    // The comparator genuinely cannot see this: CHK-7 compares the CURRENT
    // generation against the source rows, and generation 2 is correct here.
    // If PN-08 does not catch it, nothing in this gate does.
    assert.equal(proof.derivationVerdict, 'PASS');

    // The runtime moved its revenue correctly...
    assert.equal(proof.revenueFollowedTheEvent, true);
    assert.equal(proof.sourceEventCountAdvanced, true);
    // ...and still rewrote an already-computed, append-only generation-1
    // snapshot in place: cost 10 -> 12, events 1 -> 2.
    assert.equal(proof.generationOneFrozen, false);
    assert.equal(proof.proved, false);
    assert.ok(proof.reasons.some((r) => r.includes('generation 1 MESSAGE was rewritten in place')));
    assert.ok(proof.reasons.some((r) => r.includes('sourceEventCount: 1 at baseline, 2 after the perturbation')));
    assert.equal(code, 1);
  });
});

test('PN-08 state 2: a generation-1 snapshot that cannot be found is NOT reported as frozen', () => {
  withWorkDir((workDir) => {
    const { code, proof } = prove('finance-perturbed-gen1-missing.json', workDir);
    assert.equal(proof.derivationVerdict, 'PASS');
    assert.equal(proof.revenueFollowedTheEvent, true);
    assert.equal(proof.generationOneFrozen, false, 'absence of a row is not evidence that it stayed frozen');
    assert.equal(proof.proved, false);
    assert.ok(proof.reasons.some((r) => r.includes('is not present in the post-perturbation capture')));
    assert.equal(code, 1);
  });
});

test('PN-08 state 2: a baseline capture with no MESSAGE snapshot is NOT reported as proved', () => {
  withWorkDir((workDir) => {
    const emptyBaseline = join(workDir, 'finance-baseline-empty.json');
    execFileSync('node', ['-e', `
      const fs = require('node:fs');
      const doc = JSON.parse(fs.readFileSync(${JSON.stringify(join(fixtures, 'finance-baseline.json'))}, 'utf8'));
      doc.currentGeneration = doc.currentGeneration.filter((r) => r.dimension !== 'MESSAGE');
      fs.writeFileSync(${JSON.stringify(emptyBaseline)}, JSON.stringify(doc, null, 2));
    `], { encoding: 'utf8' });

    const { code, proof } = prove('finance-perturbed-gen2.json', workDir, { baseline: emptyBaseline });
    assert.equal(proof.generationOneFrozen, false);
    assert.equal(proof.revenueFollowedTheEvent, false);
    assert.equal(proof.sourceEventCountAdvanced, false);
    assert.equal(proof.proved, false, 'there is no independent baseline, so there is no proof');
    assert.ok(proof.reasons.some((r) => r.includes('records no MESSAGE snapshot')));
    assert.ok(proof.reasons.some((r) => r.includes('nothing to compare" is not evidence')));
    assert.equal(code, 1);
  });
});

test('PN-08 refuses to decide when a capture cannot be read at all', () => {
  withWorkDir((workDir) => {
    const out = join(workDir, 'unreadable.proof.json');
    const result = runNode(proofScript, [
      '--baseline', join(workDir, 'does-not-exist.json'),
      '--perturbed', join(fixtures, 'finance-perturbed-gen2.json'),
      '--parity', join(fixtures, 'stubs', 'scenarios.json'),
      '--dimension', 'MESSAGE', '--added-cost', '2.000000', '--out', out,
    ]);
    assert.equal(result.code, 2);
    assert.ok(!existsSync(out), 'no artefact may be written when the inputs cannot be read');
    assert.match(result.stdout, /A capture that cannot be read is not agreement/);
  });
});

// =====================================================================
// The regression guard on the pre-fix bug itself.
// =====================================================================

test('the pre-fix expression was vacuous: it reported frozen=True and proved=True on a rewritten generation 1', () => {
  withWorkDir((workDir) => {
    const { finance, parityPath } = compare('finance-perturbed-gen1-rewritten.json', workDir);

    // 1. What the old expression said. frozen=True because it read
    //    baselineEvents out of the very row it then compared it against.
    const before = runPowerShell(['-Perturbed', finance, '-Parity', parityPath, '-Label', 'gen1-rewritten']);
    assert.match(before.stdout, /pre-fix PN-08 -> proved=True moved=True frozen=True/);
    assert.match(before.stdout, /baselineEvents=2/);
    assert.equal(before.code, 0);

    // 2. What it says now. Same two files.
    const after = runNode(proofScript, [
      '--baseline', join(fixtures, 'finance-baseline.json'),
      '--perturbed', finance,
      '--parity', parityPath,
      '--dimension', 'MESSAGE', '--added-cost', '2.000000',
      '--out', join(workDir, 'after.json'),
    ]);
    assert.equal(after.code, 1);
    assert.match(after.stdout, /generation 1 stayed frozen:\s+false/);
  });
});

test('the pre-fix expression returned frozen=True on the ABSENCE of any generation-1 evidence', () => {
  withWorkDir((workDir) => {
    const { finance, parityPath } = compare('finance-perturbed-gen1-missing.json', workDir);
    const before = runPowerShell(['-Perturbed', finance, '-Parity', parityPath, '-Label', 'gen1-missing']);
    // baselineEvents=0 and baselineRevenue=0 -- nothing was found -- and the
    // flag still reported frozen=True, because the comparison was skipped and
    // `$frozen` had been initialised to `$true` and never reached.
    assert.match(before.stdout, /frozen=True baselineRevenue=0 baselineEvents=0/);
  });
});

// =====================================================================
// The fixture is bound to the perturbation it claims to model.
// =====================================================================

test('the added cost the gate declares is the cost the perturbation SQL actually inserts', () => {
  const sql = readFileSync(join(here, 'perturbations', 'pn08-extra-usage-event-spring.sql'), 'utf8');
  const gate = readFileSync(join(here, '..', '..', 'ops', 'quality', 'checks', 'check-shadow-parity.ps1'), 'utf8');

  const inserted = [...sql.matchAll(/(\d+\.\d{6}),\s*'\{\}',\s*'2026-01-20/g)].map((m) => Number(m[1]));
  assert.ok(inserted.length > 0, 'no costAmount could be read out of the perturbation file');
  assert.equal(inserted[0], ADDED_COST, 'the fixture cost and the SQL cost have drifted apart');

  assert.ok(
    /'--added-cost',\s*'2\.000000'/.test(gate),
    'the gate must pass the perturbation\'s own costAmount, not a literal that can drift from the SQL',
  );
  assert.match(gate, /pn08-proof\.mjs/, 'the gate must delegate the PN-08 verdict to pn08-proof.mjs');
  assert.ok(
    !/\$baselineEvents = \$older\.sourceEventCount/.test(gate),
    'the self-comparing expression must not come back',
  );
});

// =====================================================================
// The comparator is really consulted -- the proof is not self-referential.
// =====================================================================

test('CHK-7 verdicts come from the real comparator, not from the proof module', () => {
  withWorkDir((workDir) => {
    const green = compare('finance-perturbed-gen2.json', workDir).parity;
    const cached = compare('finance-perturbed-revenue-cached.json', workDir).parity;
    const chk7 = (p) => p.checks.find((c) => c.id === 'CHK-7-finance-derivation').verdict;
    assert.equal(chk7(green), 'PASS');
    assert.equal(chk7(cached), 'FAIL');

    // And the fixtures still reproduce the known FIN-01 defect through CHK-8,
    // so the finance captures here are faithful to today's product rather
    // than a whitened version of it.
    const chk8 = green.checks.find((c) => c.id === 'CHK-8-finance-invariants');
    assert.equal(chk8.verdict, 'FAIL');
    assert.ok(chk8.failures.some((f) => f.includes('double-counts this row')));
  });
});
