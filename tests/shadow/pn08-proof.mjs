/* global process, console */
/**
 * pn08-proof.mjs -- decides PN-08, the finance-derivation perturbation proof.
 *
 * Usage:
 *   node tests/shadow/pn08-proof.mjs \
 *     --baseline   finance.json          (captured BEFORE the perturbation) \
 *     --perturbed  finance-PN-08.json    (captured AFTER  the perturbation) \
 *     --parity     parity-PN-08.json     (the comparator's verdict on the perturbed capture) \
 *     --dimension  MESSAGE \
 *     --added-cost 2.000000              (the costAmount the perturbation's SQL inserts) \
 *     --out        pn08-proof.json
 *
 * Exit codes: 0 proved, 1 not proved, 2 bad invocation.
 *
 * ---------------------------------------------------------------------
 * WHY PN-08'S VERDICT LIVES HERE AND NOT IN THE GATE SCRIPT
 * ---------------------------------------------------------------------
 * It used to be ~40 lines of PowerShell inside check-shadow-parity.ps1, and
 * that placement is the reason it was wrong twice. PowerShell's cast binds
 * looser than it looks, and a decision this small is exactly the size of
 * decision that reads correctly and compares a value with itself. A verdict
 * that cannot be executed without a database, a Spring jar and a compiled
 * NestJS runtime is a verdict that never gets executed at all -- which is how
 * PN-08 sat at `proved: false` for a whole phase with nobody able to look at
 * it. This file takes two JSON files and nothing else, so both of its states
 * can be produced on demand by `node --test`.
 *
 * ---------------------------------------------------------------------
 * WHAT THE FLAG MEANS, AND WHICH WAY ROUND IT POINTS
 * ---------------------------------------------------------------------
 * `proved: true` means: **the perturbation demonstrated what it claims.**
 * PN-08 appends one usage event to the ledger and recomputes. For the
 * demonstration to be valid the runtime must have
 *
 *   1. kept agreeing with the derivation from the source rows (CHK-7 PASS --
 *      the two stacks still agree AFTER the perturbation, and that is the
 *      correct outcome, not a missing one), AND
 *   2. actually MOVED its revenue by exactly the added cost times the markup
 *      that was pinned on the baseline snapshot, into a NEW generation, with
 *      one more contributing event, AND
 *   3. left the already-computed generation 1 byte-for-byte as the BASELINE
 *      capture recorded it.
 *
 * Note the polarity, because it is the opposite of the other eight
 * perturbations and it is easy to misread: for PN-01..PN-05 and PN-07
 * `proved: true` means "the named check went RED". For PN-08 the named check
 * (CHK-7) is expected to STAY GREEN, and what is proved is that the runtime
 * moved in the way a derivation is supposed to move. `proved: true` therefore
 * means "the finance derivation check is load-bearing", NOT "a disagreement
 * was found". A disagreement is reported as `proved: false` plus the reason.
 *
 * ---------------------------------------------------------------------
 * THE DEFECT THIS FILE FIXES
 * ---------------------------------------------------------------------
 * The old expression read generation 1's `sourceEventCount` out of
 * `$pn08Doc.olderGenerations`, wrote it to `$baselineEvents`, and then looped
 * over `$pn08Doc.olderGenerations` AGAIN comparing each generation-1 row's
 * `sourceEventCount` against `$baselineEvents`. Same array, same in-memory
 * document, same read of the same field. No input can make that comparison
 * false, so `generationOneFrozen` was `true` by construction. Worse, when no
 * generation-1 row was found at all the guard `$baselineEvents -gt 0` skipped
 * the comparison entirely and the flag still reported `true` -- a pass
 * awarded for the ABSENCE of evidence.
 *
 * The old expression also took the "baseline" revenue from the POST-
 * perturbation document rather than from the capture taken before it. So the
 * reference it compared against moved with the thing under test: if a
 * recomputation had rewritten generation 1 in place, the baseline would have
 * been rewritten along with it.
 *
 * The fix is the pre-perturbation capture, which the gate was already
 * producing and already passing into the comparator. Generation 1 as the
 * BASELINE recorded it, against generation 1 as the PERTURBED capture still
 * carries it. Two files, two reads, genuinely independent -- and the only way
 * to know whether a frozen snapshot stayed frozen is to compare it with
 * something recorded before the event that could have thawed it.
 *
 * This one is fail-CLOSED everywhere: a row that is missing, a field that is
 * null, a capture that cannot be read is `proved: false` with a reason, never
 * `true` by default.
 */

import { readFileSync, writeFileSync } from 'node:fs';

const TOLERANCE = 1e-9;

/**
 * Every field of a stored snapshot that a recomputation must not touch.
 * `generation` is excluded: it is the identity of the row, not its content.
 */
const FROZEN_FIELDS = [
  'cost', 'revenue', 'markup', 'sourceEventCount',
  'unpricedQuantity', 'unpricedCost', 'rateCardVersionId', 'supersedesSnapshotId',
];

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token.startsWith('--')) {
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) args[token.slice(2)] = true;
      else { args[token.slice(2)] = next; i += 1; }
    }
  }
  return args;
}

function readJsonOrNull(path) {
  if (!path) return null;
  try { return JSON.parse(readFileSync(path, 'utf8')); } catch { return null; }
}

function sameValue(a, b) {
  if (a === null || b === null || a === undefined || b === undefined) return a === b;
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) <= TOLERANCE;
  return a === b;
}

/** Every snapshot row the capture carries for one dimension, current and frozen. */
function allSnapshots(doc, dimension) {
  if (!doc) return [];
  const rows = [...(doc.currentGeneration ?? []), ...(doc.olderGenerations ?? [])];
  return rows.filter((r) => r && r.dimension === dimension);
}

/** The highest generation recorded for a dimension -- "the" current bucket. */
function currentSnapshot(doc, dimension) {
  const rows = allSnapshots(doc, dimension);
  if (rows.length === 0) return null;
  return rows.reduce((best, r) => (best === null || r.generation > best.generation ? r : best), null);
}

function snapshotAt(doc, dimension, generation) {
  return allSnapshots(doc, dimension).find((r) => r.generation === generation) ?? null;
}

function fmt(value) {
  return value === null || value === undefined ? 'null' : JSON.stringify(value);
}

/**
 * Decide PN-08.
 *
 * @param {object}  input
 * @param {object}  input.baseline   finance capture taken BEFORE the perturbation
 * @param {object}  input.perturbed  finance capture taken AFTER  the perturbation
 * @param {object}  input.parity     the comparator's output over the perturbed capture
 * @param {string}  input.dimension  the dimension the perturbation added usage to
 * @param {number}  input.addedCost  the costAmount the perturbation's SQL inserted
 */
export function decidePn08({ baseline, perturbed, parity, dimension, addedCost }) {
  const reasons = [];

  const derivation = (parity?.checks ?? []).find((c) => c.id === 'CHK-7-finance-derivation') ?? null;
  const derivationVerdict = derivation ? derivation.verdict : null;
  if (!derivation) {
    reasons.push('no CHK-7 check was found in the parity artefact, so the derivation comparison did not run');
  } else if (derivationVerdict !== 'PASS') {
    reasons.push(`CHK-7 is ${derivationVerdict} after the perturbation, so the runtime and the derivation from the source rows no longer agree`);
  }

  const cost = typeof addedCost === 'number' ? addedCost : Number(addedCost);
  if (!Number.isFinite(cost)) {
    reasons.push(`the added cost the perturbation declares is not a number (${fmt(addedCost)}), so the expected movement cannot be computed`);
  }

  // --- the independent record of generation 1, taken before the event ---
  const baselineCurrent = currentSnapshot(baseline, dimension);
  const baselineGen1 = baselineCurrent ? snapshotAt(baseline, dimension, baselineCurrent.generation) : null;
  const perturbedCurrent = currentSnapshot(perturbed, dimension);
  // The row the perturbed capture still carries for the generation the BASELINE
  // capture recorded as current. Keyed on the baseline's generation on purpose:
  // a runtime that renumbered generations has changed the identity the
  // comparison is about, and that must not silently become "nothing to find".
  const perturbedGen1Row = baselineGen1 ? snapshotAt(perturbed, dimension, baselineGen1.generation) : null;

  // --- 1. did the revenue follow the appended event? ------------------
  let revenueFollowedTheEvent = false;
  if (!baselineGen1) {
    reasons.push(`the BASELINE capture (taken before the perturbation) records no ${dimension} snapshot, so there is no independent record of the revenue the event had to move from`);
  } else if (typeof baselineGen1.revenue !== 'number' || typeof baselineGen1.markup !== 'number') {
    reasons.push(`the baseline ${dimension} snapshot is unpriced (revenue ${fmt(baselineGen1.revenue)}, markup ${fmt(baselineGen1.markup)}), so a priced perturbation cannot be demonstrated on it`);
  } else if (!perturbedCurrent) {
    reasons.push(`after the perturbation the runtime materialised no ${dimension} bucket at all`);
  } else if (perturbedCurrent.generation <= baselineGen1.generation) {
    reasons.push(`after the perturbation the current ${dimension} bucket is still generation ${perturbedCurrent.generation}; a recomputation must produce a NEW generation, never an update (ADR 0007 Part 1)`);
  } else {
    const expected = Math.round((baselineGen1.revenue + cost * baselineGen1.markup) * 1e6) / 1e6;
    if (Math.abs(perturbedCurrent.revenue - expected) > TOLERANCE) {
      reasons.push(`the ${dimension} revenue is ${fmt(perturbedCurrent.revenue)} after an event of cost ${cost} at markup ${baselineGen1.markup}; a derivation has to move it to ${expected} (${fmt(baselineGen1.revenue)} + ${cost} x ${baselineGen1.markup}). A rollup that did not move cannot have been derived from the ledger`);
    } else {
      revenueFollowedTheEvent = true;
    }
  }

  // --- 2. did one more event land in the bucket? ----------------------
  let sourceEventCountAdvanced = false;
  if (!baselineGen1) {
    reasons.push('the contributing event count cannot be advanced against a baseline that records no snapshot');
  } else if (!perturbedCurrent) {
    reasons.push(`after the perturbation there is no ${dimension} bucket whose contributing event count could be read`);
  } else if (perturbedCurrent.sourceEventCount !== baselineGen1.sourceEventCount + 1) {
    reasons.push(`the ${dimension} bucket counts ${fmt(perturbedCurrent.sourceEventCount)} contributing event(s) after a perturbation that appends exactly one, against ${baselineGen1.sourceEventCount} in the baseline capture`);
  } else {
    sourceEventCountAdvanced = true;
  }

  // --- 3. did generation 1 stay frozen? -------------------------------
  // THIS is the comparison the old expression could not make: the baseline
  // capture against the perturbed capture, not one array against itself.
  let generationOneFrozen = false;
  if (!baselineGen1) {
    reasons.push('there is no pre-perturbation record of generation 1 to compare against, and "nothing to compare" is not evidence that generation 1 stayed frozen');
  } else if (!perturbedGen1Row) {
    reasons.push(`generation ${baselineGen1.generation} is not present in the post-perturbation capture at all; a frozen generation that cannot be found has not been shown to be frozen`);
  } else {
    const changed = FROZEN_FIELDS.filter((f) => !sameValue(baselineGen1[f], perturbedGen1Row[f]));
    if (changed.length > 0) {
      const detail = changed.map((f) => `${f}: ${fmt(baselineGen1[f])} at baseline, ${fmt(perturbedGen1Row[f])} after the perturbation`).join('; ');
      reasons.push(`generation ${baselineGen1.generation} ${dimension} was rewritten in place by the recomputation -- ${detail}. The finance ledger is append-only: a recomputation is a NEW generation, never an update (ADR 0007 Part 1)`);
    } else {
      generationOneFrozen = true;
    }
  }

  const proved = derivationVerdict === 'PASS' && revenueFollowedTheEvent
    && sourceEventCountAdvanced && generationOneFrozen;

  return {
    kind: 'shadow-pn08-proof',
    schemaVersion: 1,
    id: 'PN-08',
    dimension,
    addedCost: cost,
    polarity: 'proved=true means the derivation check is LOAD-BEARING: CHK-7 stayed green AND the revenue moved by exactly (added cost x the pinned markup) into a NEW generation AND the already-computed generation 1 was not touched. It does NOT mean a disagreement was found.',
    proved,
    derivationVerdict,
    revenueFollowedTheEvent,
    sourceEventCountAdvanced,
    generationOneFrozen,
    reasons,
    observed: {
      baselineSnapshot: baselineGen1,
      perturbedCurrent,
      perturbedBaselineGeneration: perturbedGen1Row,
      frozenFieldsCompared: FROZEN_FIELDS,
      tolerance: TOLERANCE,
    },
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.baseline || !args.perturbed || !args.parity || !args.out) {
    console.error('usage: pn08-proof.mjs --baseline FILE --perturbed FILE --parity FILE --dimension NAME --added-cost N --out FILE');
    process.exitCode = 2;
    return;
  }
  const baseline = readJsonOrNull(args.baseline);
  const perturbed = readJsonOrNull(args.perturbed);
  const parity = readJsonOrNull(args.parity);
  if (!baseline || !perturbed || !parity) {
    console.error(`could not read the captures: baseline=${Boolean(baseline)} perturbed=${Boolean(perturbed)} parity=${Boolean(parity)}. A capture that cannot be read is not agreement.`);
    process.exitCode = 2;
    return;
  }

  const result = decidePn08({
    baseline, perturbed, parity,
    dimension: String(args.dimension ?? 'MESSAGE'),
    addedCost: Number(args['added-cost']),
  });
  result.sources = { baseline: args.baseline, perturbed: args.perturbed, parity: args.parity };
  writeFileSync(args.out, `${JSON.stringify(result, null, 2)}\n`, 'utf8');

  console.log(`${result.proved ? 'PROVED' : 'NOT PROVED'}  PN-08  ${result.dimension} x ${result.addedCost}`);
  console.log(`        CHK-7 after the perturbation: ${result.derivationVerdict}`);
  console.log(`        revenue followed the event:     ${result.revenueFollowedTheEvent}`);
  console.log(`        contributing event count +1:    ${result.sourceEventCountAdvanced}`);
  console.log(`        generation 1 stayed frozen:     ${result.generationOneFrozen}`);
  for (const reason of result.reasons) console.log(`        - ${reason}`);
  process.exitCode = result.proved ? 0 : 1;
}

if (process.argv[1] && process.argv[1].endsWith('pn08-proof.mjs')) {
  main().catch((error) => {
    console.error(`pn08-proof failed: ${error && error.message ? error.message : error}`);
    process.exitCode = 2;
  });
}
