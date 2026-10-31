/* global process, console */
/**
 * finance.mjs -- compares the new runtime's finance rollups against a
 * derivation computed from the SOURCE ROWS, and checks the ADR 0007 Part 1
 * invariants on the stored snapshots.
 *
 * Usage:
 *   SHADOW_PARITY_PASSWORD=... node tests/shadow/finance.mjs \
 *     --base http://127.0.0.1:4402 --database shadow_check_spring \
 *     --project a1a1a1a1-... --from 2026-01-01 --to 2026-01-31 \
 *     --out finance.json [--session FILE]
 *
 * ------------------------------------------------------------------
 * WHY THERE IS NO LEGACY SIDE
 * ------------------------------------------------------------------
 * The legacy runtime has no finance module, no usage ledger and no rate card.
 * There is therefore NO legacy figure to compare against, and the honest
 * thing is to record that as un-comparable rather than to invent an expected
 * number. What CAN be compared is the thing ADR 0007 Part 1 actually asserts:
 *
 *   "A usage event stores COST. A versioned rate card with an effective-dated
 *    markup converts that cost into an internal billable/revenue amount.
 *    Revenue is a derived value, never a stored input."
 *
 * So the expected revenue here is computed by SQL over `usage_events` and the
 * rate-card version, entirely outside the application, and the runtime's stored
 * `revenueAmount` is compared against it. The two computations share nothing
 * but the data.
 *
 * ------------------------------------------------------------------
 * WHY READ THE SNAPSHOT ROWS AND NOT THE RESPONSE
 * ------------------------------------------------------------------
 * The compute response serialises `Money` as a bag of boolean flags
 * (priced / absent / unpriced / nonZero ...) and carries no amount at all. A
 * comparison against that body would be comparing flags, which is not a
 * finance check. The snapshot row in `usage_rollup_snapshots` is where ADR 0007
 * rule 2 says the arithmetic lives -- the markup is PINNED next to the revenue
 * so the derivation can be re-performed -- so that is what is read.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { Client, CookieJar, SPRING_PREFIX } from './lib/http.mjs';

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

function psql(container, user, database, sql) {
  return execFileSync('docker', [
    'exec', '-i', container, 'psql', '-U', user, '-d', database,
    '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-F', '|', '-P', 'pager=off', '-f', '-',
  ], { input: sql, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 }).trim();
}

function num(value) {
  if (value === null || value === undefined || value === '') return null;
  return Number(value);
}

/**
 * The reference derivation, computed from `usage_events` and the ACTIVE rate
 * card version. Returns one entry per dimension that has usage.
 */
function deriveFromSourceRows({ container, user, database, projectId, from, to }) {
  const sql = `
with events as (
  select e."dimension" as dim,
         sum(e."quantity")::numeric(20,6) as qty,
         sum(e."costAmount")::numeric(20,6) as cost,
         count(*)::bigint as events,
         min(e."unit") as unit
    from usage_events e
   where e."projectId" = '${projectId}'
     and (e."occurredAt")::date between date '${from}' and date '${to}'
     and e."correctionOfId" is null
   group by e."dimension"
),
rate as (
  select v."id" as version_id,
         v."markupRate"::numeric(20,6) as markup,
         l."dimension" as dim
    from rate_card_versions v
    join rate_cards c on c."id" = v."rateCardId"
    left join rate_card_version_lines l on l."rateCardVersionId" = v."id"
   where c."status" = 'ACTIVE'
     and v."status" = 'ACTIVE'
     and v."effectiveFrom" <= date '${from}'
     and (v."effectiveTo" is null or v."effectiveTo" >= date '${to}')
)
-- The markup and version are cast to text before the coalesce: a bare
-- coalesce(numeric, '') is a type error in Postgres, and a harness that
-- cannot even read its own reference derivation is not a harness.
select events.dim, events.events, events.qty, events.cost, events.unit,
       coalesce(rate.markup::text, ''), coalesce(rate.version_id::text, '')
  from events
  left join rate on rate.dim = events.dim
 order by events.dim;
`;
  const rows = psql(container, user, database, sql);
  if (rows.length === 0) return [];
  return rows.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    const [dim, events, qty, cost, unit, markup, versionId] = line.split('|');
    const markupNumber = markup === '' ? null : Number(markup);
    // round(cost * markup, 6) HALF_UP -- the arithmetic ADR 0007 names.
    const revenue = markupNumber === null
      ? null
      : Math.round(Number(cost) * markupNumber * 1e6) / 1e6;
    return {
      dimension: dim,
      sourceEventCount: Number(events),
      quantity: Number(qty),
      cost: Number(cost),
      unit,
      priced: markupNumber !== null,
      markup: markupNumber,
      rateCardVersionId: versionId === '' ? null : versionId,
      expectedRevenue: revenue,
      // margin is derived, never stored: revenue - cost.
      expectedMargin: revenue === null ? null : Math.round((revenue - Number(cost)) * 1e6) / 1e6,
    };
  });
}

/**
 * Splits snapshots into the current generation per dimension and every older one.
 *
 * PHASE 43. Extracted because the inline version was WRONG, and wrong in the one
 * direction this gate exists to look in.
 *
 * The inline loop walked the snapshots -- which `readSnapshots` orders by
 * (dimension, generation ASC) -- and, for each, either installed it as current or pushed it onto
 * `frozenGenerations`. That loses a row:
 *
 *     gen1 -> no existing, becomes CURRENT
 *     gen2 -> 2 > 1, becomes CURRENT, and gen1 is dropped on the floor
 *
 * gen1 is never pushed, because by the time it is displaced it is no longer "the incoming
 * snapshot". So `olderGenerations` was ALWAYS EMPTY for an ascending result -- gen1,gen2,gen3 each
 * displaces its predecessor and each predecessor vanishes. A PN-08 run could not see generation 1
 * even when generation 1 was sitting in the table, and a flag that reports "no older generation
 * exists" cannot distinguish "correctly superseded" from "I never looked".
 *
 * That is the same class of defect as the unfalsifiable flag itself: a verdict that is true because
 * it saw nothing. It is written order-INDEPENDENTLY here -- group by dimension, take the maximum
 * generation as current, record everything else -- so it cannot be reintroduced by a query that
 * happens to sort differently.
 *
 * @param {Array<{dimension: string, generation: number}>} snapshots
 * @returns {{current: Map<string, object>, older: object[]}}
 */
export function partitionGenerations(snapshots) {
  const byDimension = new Map();
  for (const snapshot of snapshots) {
    const list = byDimension.get(snapshot.dimension) ?? [];
    list.push(snapshot);
    byDimension.set(snapshot.dimension, list);
  }

  const current = new Map();
  const older = [];
  for (const [dimension, list] of byDimension) {
    let best = list[0];
    for (const candidate of list) {
      if (candidate.generation > best.generation) best = candidate;
    }
    current.set(dimension, best);
    for (const snapshot of list) {
      if (snapshot !== best) older.push(snapshot);
    }
  }
  // Ascending by (dimension, generation) so a diff of two captures reads in a stable order.
  older.sort((a, b) =>
    a.dimension === b.dimension
      ? a.generation - b.generation
      : String(a.dimension).localeCompare(String(b.dimension)));
  return { current, older };
}

/** Every snapshot generation for the scope, oldest first. */
function readSnapshots({ container, user, database, projectId }) {
  const sql = `
select s."dimension", s."generation"::text, coalesce(s."costAmount"::text, ''),
       coalesce(s."revenueAmount"::text, ''), coalesce(s."markupRate"::text, ''),
       coalesce(s."rateCardVersionId"::text, ''), coalesce(s."unpricedQuantity"::text, ''),
       coalesce(s."unpricedCostAmount"::text, ''), coalesce(s."supersedesSnapshotId"::text, ''),
       s."sourceEventCount"::text
  from usage_rollup_snapshots s
 where s."projectId" = '${projectId}'
   and s."grain" = 'MONTHLY'
 order by s."dimension", s."generation";
`;
  const rows = psql(container, user, database, sql);
  if (rows.length === 0) return [];
  return rows.split('\n').map((l) => l.trim()).filter(Boolean).map((line) => {
    const [dimension, generation, cost, revenue, markup, versionId, unpricedQty, unpricedCost, supersedes, sourceCount] = line.split('|');
    return {
      dimension,
      generation: Number(generation),
      cost: num(cost),
      revenue: num(revenue),
      markup: num(markup),
      rateCardVersionId: versionId === '' ? null : versionId,
      unpricedQuantity: num(unpricedQty),
      unpricedCost: num(unpricedCost),
      supersedesSnapshotId: supersedes === '' ? null : supersedes,
      sourceEventCount: Number(sourceCount),
    };
  });
}

export async function runFinance(args) {
  const container = args.container ?? 'firstelement-postgres';
  const user = args.user ?? 'firstelement';
  const password = process.env.SHADOW_PARITY_PASSWORD;
  if (!password) throw new Error('SHADOW_PARITY_PASSWORD is not set');

  let session = null;
  if (args.session) {
    try { session = JSON.parse(readFileSync(args.session, 'utf8')); } catch { session = null; }
  }

  const client = new Client(args.base, session && session.adminA ? CookieJar.load(session.adminA.cookies) : undefined);
  client.loginPath = `${SPRING_PREFIX}/auth/login`;
  if (session && session.adminA && session.adminA.authenticated) {
    client.csrfPath = '/api/v1/auth/csrf';
    await client.ensureCsrf();
  } else {
    await client.login(args.email, password, { csrfFirst: true });
    if (args.session) {
      writeFileSync(args.session, `${JSON.stringify({ adminA: { authenticated: true, cookies: client.cookies() } }, null, 2)}\n`, 'utf8');
    }
  }

  const compute = await client.request('POST', `${SPRING_PREFIX}/finance/rollup/compute`, {
    body: { projectId: args.project, grain: 'MONTHLY', from: args.from, to: args.to },
  });
  if (compute.status !== 200) {
    throw new Error(`rollup compute answered ${compute.status}`);
  }

  const expected = deriveFromSourceRows({
    container, user, database: args.database, projectId: args.project, from: args.from, to: args.to,
  });
  const snapshots = readSnapshots({ container, user, database: args.database, projectId: args.project });

  const { current, older: frozenGenerations } = partitionGenerations(snapshots);

  return {
    kind: 'shadow-finance-comparison',
    capturedAtUtc: new Date().toISOString(),
    scope: { projectId: args.project, from: args.from, to: args.to, database: args.database },
    computeStatus: compute.status,
    computedBuckets: compute.json?.computedBuckets ?? null,
    // The reference, derived from usage_events + the ACTIVE rate card, with no
    // help from the application.
    derivedFromSourceRows: expected,
    currentGeneration: [...current.entries()].map(([dimension, s]) => ({ dimension, ...s })),
    olderGenerations: frozenGenerations,
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.base || !args.database || !args.project || !args.out) {
    console.error('usage: finance.mjs --base URL --database NAME --project UUID --from DATE --to DATE --out FILE [--session FILE]');
    process.exitCode = 2;
    return;
  }
  const result = await runFinance(args);
  writeFileSync(args.out, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  console.log(`finance: ${result.derivedFromSourceRows.length} dimension(s) with usage, ${result.currentGeneration.length} current bucket(s)`);
  process.exitCode = 0;
}

if (process.argv[1] && process.argv[1].endsWith('finance.mjs')) {
  main().catch((error) => {
    console.error(`finance failed: ${error && error.message ? error.message : error}`);
    process.exitCode = 1;
  });
}