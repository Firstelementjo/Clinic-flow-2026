/* global process, console */
/**
 * compare.mjs -- decides Phase-12 shadow parity from two capture files and
 * two database snapshots.
 *
 * Usage:
 *   node tests/shadow/compare.mjs --legacy cap-legacy.json --spring cap-spring.json \
 *        --scenarios tests/shadow/scenarios/reads.json \
 *        --db-legacy db-legacy.json --db-spring db-spring.json --out parity.json
 *
 * ------------------------------------------------------------------
 * THE DESIGN PROBLEM THIS FILE SOLVES
 * ------------------------------------------------------------------
 * A parity harness has one failure mode that is worse than a false pass on
 * an individual check: a harness whose checks cannot fail. A count that
 * compares a constant to a constant, a filter that both sides satisfy for
 * unrelated reasons, a normalisation that erases the field it was written to
 * examine -- each produces a permanent green and tells the reader nothing.
 *
 * So every check below carries a `redCondition` naming the INPUT that makes
 * it fail, in the imperative: "a customer row that exists in one database and
 * not in the other". `check-shadow-parity.ps1` then PROVES each redCondition
 * by perturbing that input and asserting the verdict flips. A redCondition
 * that cannot be provoked is a defect in this file, and the gate fails on it
 * rather than reporting it as parity.
 *
 * ------------------------------------------------------------------
 * WHAT IS COMPARED AND WHAT IS NOT
 * ------------------------------------------------------------------
 * Compared:
 *   - row counts and the IDENTIFIER SETS behind them (uuid, cxId, ticketNumber)
 *   - authorization decisions (allow/deny) and non-disclosure of denials
 *   - full response bodies for reads that both runtimes serve
 *   - source-row counts read straight out of both databases
 *
 * NOT compared, and why:
 *   - `un-comparable` scenarios: recorded with a reason. A missing legacy
 *     route is not a difference in behaviour, it is an absent behaviour, and
 *     pretending otherwise would either invent an expectation or hide a gap.
 *   - `divergence` scenarios: recorded as findings. When one runtime serves
 *     a route differently from the other, the answer is a difference to
 *     explain, not a value to reconcile.
 */

import { readFileSync, writeFileSync } from 'node:fs';

// ----------------------------------------------------------------------
// Structural equality, with the FIRST divergence named by JSON path.
// ----------------------------------------------------------------------

function firstDivergence(expected, actual, path = '$') {
  if (Array.isArray(expected) || Array.isArray(actual)) {
    if (!Array.isArray(expected) || !Array.isArray(actual)) {
      return `${path}: expected ${kind(expected)}, got ${kind(actual)}`;
    }
    if (expected.length !== actual.length) {
      return `${path}: expected ${expected.length} element(s), got ${actual.length}`;
    }
    for (let i = 0; i < expected.length; i += 1) {
      const found = firstDivergence(expected[i], actual[i], `${path}[${i}]`);
      if (found) return found;
    }
    return null;
  }
  if (expected !== null && actual !== null
      && typeof expected === 'object' && typeof actual === 'object') {
    const keys = new Set([...Object.keys(expected), ...Object.keys(actual)]);
    for (const key of [...keys].sort()) {
      if (!(key in expected)) return `${path}.${key}: absent on the legacy side, present on the Spring side`;
      if (!(key in actual)) return `${path}.${key}: present on the legacy side, absent on the Spring side`;
      const found = firstDivergence(expected[key], actual[key], `${path}.${key}`);
      if (found) return found;
    }
    return null;
  }
  if (expected !== actual) {
    return `${path}: legacy ${JSON.stringify(expected)}, spring ${JSON.stringify(actual)}`;
  }
  return null;
}

function kind(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'an array';
  return `a ${typeof value}`;
}

// ----------------------------------------------------------------------
// Identifier extraction. A count alone cannot detect a wrong identifier, and
// plan.md 6.1 is about identifiers, so the comparison is on sets.
// ----------------------------------------------------------------------

const IDENTIFIER_FIELDS = ['id', 'cxId', 'ticketNumber', 'externalMessageId', 'patientName'];

/** Every identifier-shaped string reachable in a response, as a sorted set. */
function identifierSet(value, out = new Set()) {
  if (Array.isArray(value)) {
    for (const child of value) identifierSet(child, out);
    return out;
  }
  if (value !== null && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      if (IDENTIFIER_FIELDS.includes(key) && (typeof child === 'string' || typeof child === 'number')) {
        out.add(`${key}=${child}`);
      }
      identifierSet(child, out);
    }
    return out;
  }
  return out;
}

function identifierList(body) {
  const items = body && Array.isArray(body.items) ? body.items : null;
  return items ? [...identifierSet(items)].sort() : [...identifierSet(body)].sort();
}

function difference(a, b) {
  const left = new Set(a);
  const right = new Set(b);
  const onlyA = [...left].filter((x) => !right.has(x));
  const onlyB = [...right].filter((x) => !left.has(x));
  return { onlyLegacy: onlyA, onlySpring: onlyB };
}

// ----------------------------------------------------------------------
// Authorization.
// ----------------------------------------------------------------------

const ALLOW_STATUSES = new Set([200, 201, 202, 204]);

function decisionOf(result) {
  if (result.transportError) return 'TRANSPORT_ERROR';
  if (ALLOW_STATUSES.has(result.status)) return 'ALLOW';
  if (result.status === 401) return 'DENY_UNAUTHENTICATED';
  if (result.status === 403) return 'DENY_FORBIDDEN';
  if (result.status === 404) return 'DENY_NOT_FOUND';
  return `DENY_OTHER_${result.status}`;
}

const DENIAL_DECISIONS = new Set([
  'DENY_UNAUTHENTICATED', 'DENY_FORBIDDEN', 'DENY_NOT_FOUND',
]);

/**
 * Strings that must never appear in the body of a denial.
 *
 * These come from the dataset, not from an expectation of what a runtime
 * "should" hide: every customer display name, every ticket number, every note
 * body and every message body that belongs to a project the principal in that
 * scenario cannot reach. If a refusal echoes any of them, the refusal
 * discloses. A denial that leaks is a finding even when both stacks refuse.
 */
function forbiddenStringsFor(scenarios) {
  return scenarios.forbiddenStrings ?? [];
}

function disclosureIn(result, forbidden) {
  if (!result.body) return [];
  const text = JSON.stringify(result.body);
  return forbidden.filter((needle) => text.includes(needle));
}

// ----------------------------------------------------------------------
// Checks
// ----------------------------------------------------------------------

function buildChecks(legacy, spring, scenarios, db) {
  const checks = [];
  const byId = (side) => new Map(side.results.map((r) => [r.id, r]));
  const legacyById = byId(legacy);
  const springById = byId(spring);
  const forbidden = forbiddenStringsFor(scenarios);

  const comparable = scenarios.requests.filter((r) => !['un-comparable', 'divergence'].includes(r.group));

  // --- CHK-1 row counts and identifier sets -------------------------
  const countEvidence = [];
  const countFailures = [];
  for (const request of comparable.filter((r) => r.group === 'row-counts')) {
    const a = legacyById.get(request.id);
    const b = springById.get(request.id);
    if (!a || !b) { countFailures.push(`${request.id}: missing from one capture`); continue; }
    const aIds = identifierList(a.body);
    const bIds = identifierList(b.body);
    const delta = difference(aIds, bIds);
    const aCount = Array.isArray(a.body?.items) ? a.body.items.length : null;
    const bCount = Array.isArray(b.body?.items) ? b.body.items.length : null;
    countEvidence.push({
      request: request.id, legacyStatus: a.status, springStatus: b.status,
      legacyItems: aCount, springItems: bCount,
      identifierSetSize: { legacy: aIds.length, spring: bIds.length },
      identifiersOnlyOnLegacy: delta.onlyLegacy,
      identifiersOnlyOnSpring: delta.onlySpring,
    });
    if (aCount !== bCount) {
      countFailures.push(`${request.id}: ${aCount} item(s) on legacy, ${bCount} on Spring`);
    }
    if (delta.onlyLegacy.length || delta.onlySpring.length) {
      countFailures.push(`${request.id}: identifier sets differ (only legacy: ${JSON.stringify(delta.onlyLegacy)}; only spring: ${JSON.stringify(delta.onlySpring)})`);
    }
  }
  checks.push({
    id: 'CHK-1-row-counts-and-identifiers',
    title: 'List reads return the same number of rows and the same identifiers',
    redCondition: 'a row that exists in one database and not in the other, or a row whose uuid / cxId / ticketNumber was rewritten during migration',
    verdict: countFailures.length === 0 ? 'PASS' : 'FAIL',
    failures: countFailures,
    evidence: countEvidence,
    demonstrable: true,
    demonstratedBy: 'PN-01 (an extra customer row in the Spring database) and PN-02 (a rewritten cxId)',
  });

  // --- CHK-2 response bodies ----------------------------------------
  const bodyEvidence = [];
  const bodyFailures = [];
  for (const request of comparable.filter((r) => ['row-counts', 'detail', 'identifier'].includes(r.group))) {
    const a = legacyById.get(request.id);
    const b = springById.get(request.id);
    if (!a || !b) { bodyFailures.push(`${request.id}: missing from one capture`); continue; }
    const divergence = firstDivergence(a.body ?? null, b.body ?? null);
    bodyEvidence.push({ request: request.id, identical: divergence === null, firstDivergence: divergence });
    if (divergence) bodyFailures.push(`${request.id}: ${divergence}`);
  }
  checks.push({
    id: 'CHK-2-response-bodies',
    title: 'Allowed reads are structurally identical, field for field',
    redCondition: 'one runtime renames a field, drops a field, renders a timestamp differently, or orders a list differently',
    verdict: bodyFailures.length === 0 ? 'PASS' : 'FAIL',
    failures: bodyFailures,
    evidence: bodyEvidence,
    demonstrable: true,
    demonstratedBy: 'PN-03 (a renamed status value in the Spring database)',
  });

  // --- CHK-3 authorization decisions -------------------------------
  const authzEvidence = [];
  const authzFailures = [];
  const leaks = [];
  for (const request of comparable.filter((r) => r.group === 'authorization')) {
    const a = legacyById.get(request.id);
    const b = springById.get(request.id);
    if (!a || !b) { authzFailures.push(`${request.id}: missing from one capture`); continue; }
    const legacyDecision = decisionOf(a);
    const springDecision = decisionOf(b);
    const legacyLeak = disclosureIn(a, forbidden);
    const springLeak = disclosureIn(b, forbidden);
    authzEvidence.push({
      request: request.id, principal: request.principal,
      legacyStatus: a.status, springStatus: b.status,
      legacyDecision, springDecision,
      agree: legacyDecision === springDecision,
      legacyDisclosure: legacyLeak, springDisclosure: springLeak,
    });
    if (legacyDecision !== springDecision) {
      authzFailures.push(`${request.id}: legacy ${legacyDecision} (${a.status}) but spring ${springDecision} (${b.status})`);
    }
    for (const [side, decision, found] of [
      ['legacy', legacyDecision, legacyLeak], ['spring', springDecision, springLeak],
    ]) {
      if (DENIAL_DECISIONS.has(decision) && found.length > 0) {
        leaks.push(`${request.id}: the ${side} denial (${decision}) disclosed ${JSON.stringify(found)}`);
      }
    }
  }
  for (const leak of leaks) authzFailures.push(leak);
  checks.push({
    id: 'CHK-3-authorization-decisions',
    title: 'Both stacks reach the same allow/deny decision, and a denial discloses nothing',
    redCondition: 'one stack allows a request the other refuses; or a refusal body echoes a display name, ticket number, note body or message body from a project the caller cannot reach',
    verdict: authzFailures.length === 0 ? 'PASS' : 'FAIL',
    failures: authzFailures,
    evidence: authzEvidence,
    demonstrable: true,
    demonstratedBy: 'PN-04 (an extra project membership granted to agent-a1 in the Spring database) and PN-05 (a customer row moved into another tenant)',
  });

  // --- CHK-4 source-row agreement straight out of the databases ----
  const tableEvidence = [];
  const tableFailures = [];
  if (db && db.legacy && db.spring) {
    for (const table of Object.keys(db.legacy.tables)) {
      const a = db.legacy.tables[table];
      const b = db.spring.tables[table];
      if (!b) { tableFailures.push(`${table}: absent from the Spring database snapshot`); continue; }
      // A table whose snapshot FAILED must never read as agreement. An absent
      // measurement compared with another absent measurement is not a pass;
      // it is two holes that happen to be the same shape, and treating it as
      // parity is exactly how a source-row check becomes decorative.
      if (a.count === null || b.count === null) {
        tableFailures.push(`${table}: no count was measured (legacy: ${a.error ?? 'null'}, spring: ${b.error ?? 'null'}) -- an unmeasured table is never a matching table`);
        continue;
      }
      if (a.count === 0 && b.count === 0) {
        tableFailures.push(`${table}: both snapshots are empty, so nothing was compared`);
        continue;
      }
      const delta = difference(a.identifiers, b.identifiers);
      tableEvidence.push({
        table, legacyRows: a.count, springRows: b.count,
        legacyDistinctIdentifiers: a.identifiers.length,
        springDistinctIdentifiers: b.identifiers.length,
        identifiersOnlyOnLegacy: delta.onlyLegacy.slice(0, 20),
        identifiersOnlyOnSpring: delta.onlySpring.slice(0, 20),
      });
      if (a.count !== b.count) tableFailures.push(`${table}: ${a.count} row(s) on legacy, ${b.count} on Spring`);
      if (delta.onlyLegacy.length || delta.onlySpring.length) {
        tableFailures.push(`${table}: identifier sets differ`);
      }
    }
    const tenantKeys = new Set([
      ...Object.keys(db.legacy.perTenant ?? {}), ...Object.keys(db.spring.perTenant ?? {}),
    ]);
    for (const key of tenantKeys) {
      const a = db.legacy.perTenant?.[key];
      const b = db.spring.perTenant?.[key];
      if (a === undefined || b === undefined) {
        tableFailures.push(`per-tenant ${key}: present in only one snapshot (legacy ${a ?? 'absent'}, spring ${b ?? 'absent'})`);
      } else if (a !== b) {
        tableFailures.push(`per-tenant ${key}: ${a} row(s) on legacy, ${b} on Spring`);
      }
    }
  } else {
    tableFailures.push('database snapshots were not supplied; the source-row comparison did not run');
  }
  checks.push({
    id: 'CHK-4-source-rows',
    title: 'The same rows exist in both databases, identified the same way',
    redCondition: 'a table whose row count or primary-key set differs between the two seeded databases',
    verdict: tableFailures.length === 0 ? 'PASS' : 'FAIL',
    failures: tableFailures,
    evidence: tableEvidence,
    demonstrable: true,
    demonstratedBy: 'PN-01 (an extra customer row in the Spring database)',
  });

  // --- CHK-5 no secret crossed the capture --------------------------
  const leakFailures = [];
  if (legacy.leakedSecret) leakFailures.push(`legacy capture carried ${JSON.stringify(legacy.leakedSecretFields)}`);
  if (spring.leakedSecret) leakFailures.push(`spring capture carried ${JSON.stringify(spring.leakedSecretFields)}`);
  const transportFailures = [...legacy.results, ...spring.results]
    .filter((r) => r.transportError)
    .map((r) => `${r.side ?? ''}${r.id}: ${r.transportError}`);
  checks.push({
    id: 'CHK-5-capture-integrity',
    title: 'Neither capture carried a credential, and no request failed in transport',
    redCondition: 'a projection selects users."passwordHash" or sessions."tokenHash", or a request never reached a runtime',
    verdict: leakFailures.length === 0 && transportFailures.length === 0 ? 'PASS' : 'FAIL',
    failures: [...leakFailures, ...transportFailures],
    evidence: {
      legacyLeakedSecret: legacy.leakedSecret, springLeakedSecret: spring.leakedSecret,
      legacyRequests: legacy.results.length, springRequests: spring.results.length,
    },
    demonstrable: false,
    demonstratedBy: null,
  });

  return { checks, comparable };
}

// ----------------------------------------------------------------------
// CHK-6 -- status transitions
// ----------------------------------------------------------------------

function transitionChecks(transitions) {
  if (!transitions) {
    return [{
      id: 'CHK-6-status-transitions',
      title: 'The same transition sequence leaves the same state in both databases',
      redCondition: 'a transition one runtime performs and the other does not, or a timestamp one stamps and the other leaves null',
      verdict: 'FAIL',
      failures: ['no transition capture was supplied; the comparison did not run'],
      evidence: [],
      demonstrable: true,
      demonstratedBy: null,
    }];
  }

  const failures = [];
  const evidence = [];
  const fields = ['status', 'resolvedAtSet', 'closedAtSet', 'modeVersion', 'resolvedBeforeClosed'];
  for (const field of fields) {
    const a = transitions.legacy.after[field];
    const b = transitions.spring.after[field];
    if (a !== b) {
      failures.push(`after the sequence, ${field}: legacy ${JSON.stringify(a)}, spring ${JSON.stringify(b)}`);
    }
  }
  evidence.push({
    sequence: transitions.scenario.sequence,
    legacy: { steps: transitions.legacy.steps, before: transitions.legacy.before, after: transitions.legacy.after, statusEvents: transitions.legacy.statusEvents },
    spring: { steps: transitions.spring.steps, before: transitions.spring.before, after: transitions.spring.after, statusEvents: transitions.spring.statusEvents },
  });

  // The per-step answers are compared too. Two runtimes can leave the same
  // final row while one of them silently ignored a step and another step
  // happened to produce the same state -- comparing only the end state is the
  // mistake this extra loop exists to prevent.
  const stepCount = Math.min(transitions.legacy.steps.length, transitions.spring.steps.length);
  for (let i = 0; i < stepCount; i += 1) {
    const a = transitions.legacy.steps[i];
    const b = transitions.spring.steps[i];
    if (a.label !== b.label) continue;
    if (a.status !== b.status) {
      failures.push(`step ${i + 1} (${a.label}): legacy answered ${a.status}, spring answered ${b.status}`);
    }
  }
  if (transitions.legacy.steps.length !== transitions.spring.steps.length) {
    failures.push(`step count differs: legacy ${transitions.legacy.steps.length}, spring ${transitions.spring.steps.length}`);
  }

  return [{
    id: 'CHK-6-status-transitions',
    title: 'The same transition sequence leaves the same state in both databases',
    redCondition: 'one runtime answers a transition differently, silently skips one, or leaves a timestamp null where the other stamped it',
    verdict: failures.length === 0 ? 'PASS' : 'FAIL',
    failures,
    evidence,
    demonstrable: true,
    demonstratedBy: 'PN-07 (the close step is removed from one side only)',
  }];
}

// ----------------------------------------------------------------------
// CHK-7 / CHK-8 -- finance
// ----------------------------------------------------------------------

function financeChecks(finance) {
  if (!finance) {
    const missing = (id, title, condition) => ({
      id, title, redCondition: condition, verdict: 'FAIL',
      failures: ['no finance capture was supplied; the comparison did not run'],
      evidence: [], demonstrable: true, demonstratedBy: null,
    });
    return [
      missing('CHK-7-finance-derivation', 'Derived revenue equals cost x the effective rate card markup, computed from the source rows', 'a revenue figure that is not cost x markup, or a priced/unpriced classification that differs from the rate card'),
      missing('CHK-8-finance-invariants', 'The ADR 0007 Part 1 invariants hold on every stored snapshot', 'a snapshot with a rate and no revenue, a revenue with no rate, a recomputation that mutates an older generation, or unpriced usage recorded against a priced row'),
    ];
  }

  const byDimension = new Map(finance.currentGeneration.map((c) => [c.dimension, c]));
  const derivationFailures = [];
  const derivationEvidence = [];

  for (const expected of finance.derivedFromSourceRows) {
    const actual = byDimension.get(expected.dimension);
    if (!actual) {
      derivationFailures.push(`${expected.dimension}: usage exists in usage_events but the runtime materialised no bucket`);
      continue;
    }
    if (actual.cost !== expected.cost) {
      derivationFailures.push(`${expected.dimension}: cost ${actual.cost} against ${expected.cost} summed from usage_events`);
    }
    if (actual.sourceEventCount !== expected.sourceEventCount) {
      derivationFailures.push(`${expected.dimension}: ${actual.sourceEventCount} contributing event(s) against ${expected.sourceEventCount} in usage_events`);
    }
    const actuallyPriced = actual.revenue !== null;
    if (actuallyPriced !== expected.priced) {
      derivationFailures.push(`${expected.dimension}: runtime ${actuallyPriced ? 'PRICED' : 'UNPRICED'} but the rate card says ${expected.priced ? 'PRICED' : 'UNPRICED'}`);
    }
    if (expected.priced && actuallyPriced && actual.revenue !== expected.expectedRevenue) {
      derivationFailures.push(`${expected.dimension}: revenue ${actual.revenue} against ${expected.expectedRevenue} (cost ${expected.cost} x markup ${expected.markup}), derived from the source rows`);
    }
    if (actuallyPriced && actual.markup !== expected.markup) {
      derivationFailures.push(`${expected.dimension}: pinned markup ${actual.markup} against ${expected.markup} on the effective rate card version`);
    }
    derivationEvidence.push({
      dimension: expected.dimension,
      derived: {
        sourceEventCount: expected.sourceEventCount, cost: expected.cost,
        priced: expected.priced, markup: expected.markup, revenue: expected.expectedRevenue,
      },
      stored: {
        sourceEventCount: actual.sourceEventCount, cost: actual.cost,
        priced: actual.revenue !== null, markup: actual.markup, revenue: actual.revenue,
        rateCardVersionId: actual.rateCardVersionId,
      },
    });
  }
  // A bucket the runtime invented for a dimension with no usage is as much a
  // defect as a missing one: ADR 0007 rule 5 says an empty period and a
  // zero-valued period are different states.
  for (const actual of finance.currentGeneration) {
    if (!finance.derivedFromSourceRows.some((e) => e.dimension === actual.dimension)) {
      derivationFailures.push(`${actual.dimension}: a bucket was materialised for a dimension with no usage events`);
    }
  }

  // --- CHK-8 invariants ------------------------------------------------
  const invariantFailures = [];
  for (const snapshot of finance.currentGeneration) {
    const pinnedRate = snapshot.rateCardVersionId !== null;
    const pinnedMarkup = snapshot.markup !== null;
    const pinnedRevenue = snapshot.revenue !== null;
    if (pinnedRate !== pinnedMarkup || pinnedRate !== pinnedRevenue) {
      invariantFailures.push(`${snapshot.dimension}: the pricing biconditional is broken -- rate ${pinnedRate}, markup ${pinnedMarkup}, revenue ${pinnedRevenue} (ADR 0007 rule 2)`);
    }
    if (pinnedRevenue && pinnedMarkup !== null && snapshot.cost !== null) {
      const expectedRevenue = Math.round(snapshot.cost * snapshot.markup * 1e6) / 1e6;
      if (Math.abs(expectedRevenue - snapshot.revenue) > 1e-9) {
        invariantFailures.push(`${snapshot.dimension}: the stored revenue ${snapshot.revenue} is not its own stored cost ${snapshot.cost} x its own stored markup ${snapshot.markup}`);
      }
    }
    // ADR 0007 rule 5 / the migration's own column comment: unpricedCostAmount
    // is the cost of usage that was NOT priced. A row that carries a rate and
    // a revenue must not also report its cost as unpriced -- that is money
    // counted twice, once as billed and once as unbilled.
    if (pinnedRevenue && snapshot.unpricedQuantity === 0 && snapshot.unpricedCost > 0) {
      invariantFailures.push(`${snapshot.dimension}: the bucket is PRICED (revenue ${snapshot.revenue}) yet reports ${snapshot.unpricedCost} as unpriced cost with zero unpriced quantity. Any unpriced-usage view or reconciliation that sums unpricedCostAmount double-counts this row.`);
    }
    if (pinnedRevenue && snapshot.unpricedQuantity === 0 && snapshot.unpricedCost !== 0) {
      // already reported above; kept separate so the arithmetic is unambiguous
    }
    if (!pinnedRevenue && snapshot.unpricedCost === 0 && snapshot.unpricedQuantity === 0) {
      invariantFailures.push(`${snapshot.dimension}: the bucket is UNPRICED but records neither unpriced quantity nor unpriced cost, so "unpriced" here means "we did not bother"`);
    }
  }
  // Recomputation must be a NEW generation, never an update.
  const byDimGeneration = new Map();
  for (const snapshot of [...finance.currentGeneration, ...finance.olderGenerations]) {
    const list = byDimGeneration.get(snapshot.dimension) ?? [];
    list.push(snapshot);
    byDimGeneration.set(snapshot.dimension, list);
  }
  for (const [dimension, list] of byDimGeneration) {
    const generations = list.map((s) => s.generation).sort((a, b) => a - b);
    const distinct = new Set(generations);
    if (distinct.size !== generations.length) {
      invariantFailures.push(`${dimension}: two snapshot rows share a generation number (${generations.join(', ')}), so at most one of them can be "the" current bucket`);
    }
  }

  return [
    {
      id: 'CHK-7-finance-derivation',
      title: 'Derived revenue equals cost x the effective rate card markup, computed from the source rows',
      redCondition: 'a revenue figure that is not cost x markup, a cost that does not sum the usage events, or a priced/unpriced classification the rate card does not support',
      verdict: derivationFailures.length === 0 ? 'PASS' : 'FAIL',
      failures: derivationFailures,
      evidence: derivationEvidence,
      demonstrable: true,
      demonstratedBy: 'PN-08 (the ACTIVE rate card markup is changed from 1.5 to 2.0; the derivation must follow it and the older generation must stay frozen)',
    },
    {
      id: 'CHK-8-finance-invariants',
      title: 'The ADR 0007 Part 1 invariants hold on every stored snapshot',
      redCondition: 'a snapshot with a rate and no revenue, a revenue with no rate, a stored revenue that is not its own cost x its own markup, two rows claiming one generation, or unpriced cost recorded against a fully priced row',
      verdict: invariantFailures.length === 0 ? 'PASS' : 'FAIL',
      failures: invariantFailures,
      evidence: finance.currentGeneration,
      demonstrable: true,
      demonstratedBy: 'PN-09 (a DETECTOR perturbation: a captured snapshot is edited so its revenue is not its cost x markup, and the check must reject it)',
    },
  ];
}

// ----------------------------------------------------------------------
// Divergences and un-comparable entries: recorded, never reconciled.
// ----------------------------------------------------------------------

function recordAsymmetries(legacy, spring, scenarios, legacyRoutes, springRoutes) {
  const legacyById = new Map(legacy.results.map((r) => [r.id, r]));
  const springById = new Map(spring.results.map((r) => [r.id, r]));
  const unComparable = [];
  const divergences = [];
  const legacyOnlyRoutes = (legacyRoutes ?? []).filter((r) => !(springRoutes ?? []).includes(r));
  const springOnlyRoutes = (springRoutes ?? []).filter((r) => !(legacyRoutes ?? []).includes(r));

  for (const request of scenarios.requests) {
    const a = legacyById.get(request.id);
    const b = springById.get(request.id);
    if (request.group === 'un-comparable') {
      unComparable.push({
        request: request.id, why: request.why,
        legacyStatus: a?.status ?? null, springStatus: b?.status ?? null,
        structuralBasis: routeBasis(request, legacyRoutes, springRoutes),
      });
    } else if (request.group === 'divergence') {
      divergences.push({
        request: request.id, why: request.why,
        legacyStatus: a?.status ?? null, springStatus: b?.status ?? null,
        legacyBodySummary: summarise(a), springBodySummary: summarise(b),
      });
    } else if (a && b && a.status !== b.status) {
      divergences.push({
        request: request.id, why: request.why,
        legacyStatus: a.status, springStatus: b.status,
        legacyBodySummary: summarise(a), springBodySummary: summarise(b),
        note: 'status divergence on a comparable resource',
      });
    }
  }
  return { unComparable, divergences, legacyOnlyRoutes, springOnlyRoutes };
}

/**
 * The STRUCTURAL reason a scenario cannot be compared: whether the route
 * exists in each runtime's own route table. This is read from what each
 * runtime logged at boot, not inferred from a 404 -- a 404 is also what a
 * denial looks like, and using it to justify "the route is absent" would let
 * an authorization bug masquerade as a missing feature.
 */
function routeBasis(request, legacyRoutes, springRoutes) {
  const canonical = request.path;
  const legacyHit = (legacyRoutes ?? []).filter((r) => r.endsWith(canonical) || r.includes(canonical));
  const springHit = (springRoutes ?? []).filter((r) => r.endsWith(canonical) || r.includes(canonical));
  return {
    canonicalPath: canonical,
    legacyRoutesMatching: legacyHit,
    springRoutesMatching: springHit,
    legacyHasRoute: legacyHit.length > 0,
    springHasRoute: springHit.length > 0,
  };
}

function summarise(result) {
  if (!result) return null;
  if (result.transportError) return `transport error: ${result.transportError}`;
  const body = result.body;
  if (body === null || body === undefined) return null;
  if (typeof body !== 'object') return JSON.stringify(body);
  if (Array.isArray(body)) return `array of ${body.length}`;
  return Object.keys(body).sort().join(',');
}

// ----------------------------------------------------------------------

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

function main() {
  const args = parseArgs(process.argv.slice(2));
  const legacy = readJsonOrNull(args.legacy);
  const spring = readJsonOrNull(args.spring);
  const scenarios = readJsonOrNull(args.scenarios);
  if (!legacy || !spring || !scenarios) {
    console.error('usage: compare.mjs --legacy FILE --spring FILE --scenarios FILE [--db-legacy FILE --db-spring FILE] --out FILE');
    process.exit(2);
  }
  const db = {
    legacy: readJsonOrNull(args['db-legacy']),
    spring: readJsonOrNull(args['db-spring']),
  };
  const routeInventory = readJsonOrNull(args.routes) ?? { legacy: [], spring: [] };
  const transitions = readJsonOrNull(args.transitions);
  const finance = readJsonOrNull(args.finance);

  const { checks } = buildChecks(legacy, spring, scenarios, db);
  const allChecks = [...checks, ...transitionChecks(transitions), ...financeChecks(finance)];
  const asymmetries = recordAsymmetries(
    legacy, spring, scenarios, routeInventory.legacy, routeInventory.spring);

  const verdict = allChecks.every((c) => c.verdict === 'PASS') ? 'PASS' : 'FAIL';
  const result = {
    kind: 'shadow-parity-comparison',
    schemaVersion: 1,
    comparedAtUtc: new Date().toISOString(),
    verdict,
    comparedRequests: legacy.results.filter((r) => spring.results.some((s) => s.id === r.id)).length,
    checks: allChecks,
    ...asymmetries,
  };
  writeFileSync(args.out, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  for (const check of allChecks) {
    console.log(`${check.verdict}  ${check.id}  ${check.title}`);
    for (const failure of check.failures) console.log(`        ${failure}`);
  }
  console.log(`un-comparable: ${asymmetries.unComparable.length}   divergences: ${asymmetries.divergences.length}`);
  process.exitCode = verdict === 'PASS' ? 0 : 1;
}

main();