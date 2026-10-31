/* global process, console */
/**
 * db-snapshot.mjs -- reads row counts and identifier sets straight out of a
 * database and writes them as JSON.
 *
 * Usage:
 *   node tests/shadow/db-snapshot.mjs --database shadow_check_legacy --out db-legacy.json
 *
 * WHY THIS EXISTS AT ALL. The read-path comparison in `compare.mjs` proves
 * that two runtimes render the same rows. It cannot prove that both databases
 * were seeded the same, because if they were not, "the two runtimes agree"
 * could just mean "both were reading the same wrong data out of two copies".
 * This snapshot is the independent check on the input to the comparison.
 *
 * IT IS ALSO A NEGATIVE CONTROL. The comparison is over sets of identifiers,
 * not over counts, and a set of one element cannot distinguish two different
 * single-element sets unless the element itself is compared. That is why this
 * file emits the identifiers and not only the counts.
 *
 * READ ONLY. Every statement here is a SELECT. There is no code path in this
 * file that can write, and the database name is checked against the
 * repository's disposable-name rule before anything connects -- the same rule
 * `prepare-throwaway-db.ps1` enforces, re-stated here so this file cannot be
 * pointed at `firstelement` by accident.
 */

import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const DISPOSABLE_NAME = /(check|rehearsal|gate|throwaway|scratch|tmp)/i;
const PROTECTED_DATABASE = 'firstelement';

/** Tables the plan.md 6.1 identifier comparison covers, with their id columns. */
const TABLES = [
  { table: 'organizations', id: 'id' },
  { table: 'projects', id: 'id' },
  { table: 'users', id: 'id' },
  { table: 'employees', id: 'id' },
  { table: 'project_memberships', id: 'id' },
  { table: 'customers', id: 'id', extra: ['"cxId"'] },
  { table: 'conversations', id: 'id', extra: ['"ticketNumber"', '"messageCount"'] },
  { table: 'messages', id: 'id', extra: ['"externalMessageId"'] },
  { table: 'conversation_events', id: 'id' },
  { table: 'case_resolutions', id: 'id' },
  { table: 'appointments', id: 'id', extra: ['"ticketNumber"'] },
  { table: 'doctors', id: 'id' },
  { table: 'procedures', id: 'id' },
  { table: 'notes', id: 'id' },
];

function runPsql(container, user, database, sql) {
  return execFileSync('docker', [
    'exec', '-i', container, 'psql', '-U', user, '-d', database,
    '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-F', '\u001f', '-P', 'pager=off',
    '-c', sql,
  ], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

export function snapshot({ container, user, database }) {
  if (database === PROTECTED_DATABASE) {
    throw new Error(`refusing to snapshot the protected database ${PROTECTED_DATABASE}`);
  }
  if (!DISPOSABLE_NAME.test(database)) {
    throw new Error(`refusing to snapshot '${database}': a shadow database name must look disposable`);
  }

  const tables = {};
  for (const { table, id, extra } of TABLES) {
    const identifiers = [];
    let count = null;
    let error = null;
    // One query per column. Postgres has no multi-column string_agg, and a
    // row count and an identifier set are different claims: the count says
    // how many rows there are, the identifier set says WHICH rows those are.
    // Both are emitted, so a table whose count matches while the set does
    // not is visible rather than averaged away.
    for (const column of [id, ...(extra ?? [])]) {
      const sql = `select coalesce(string_agg(${column}::text, ',' order by ${column}::text), '') from ${table}`;
      let raw;
      try {
        raw = runPsql(container, user, database, sql).trim();
      } catch (queryError) {
        error = String(queryError.message).slice(0, 300);
        break;
      }
      const values = raw.length === 0 ? [] : raw.split(',').filter((x) => x.length > 0);
      for (const value of values) identifiers.push(`${column}=${value}`);
      if (column === id) count = values.length;
    }
    identifiers.sort();
    tables[table] = { count, identifiers };
    if (error) tables[table].error = error;
  }

  // A per-tenant breakdown, because a table can have the right total and the
  // wrong distribution and a total-only check would not see it.
  const perTenantSql = [
    'select \'customers\', "organizationId", count(*) from customers group by 2',
    'select \'conversations\', "organizationId", count(*) from conversations group by 2',
    'select \'messages\', "organizationId", count(*) from messages group by 2',
    'select \'appointments\', "organizationId", count(*) from appointments group by 2',
    'select \'notes\', "organizationId", count(*) from notes group by 2',
  ].join(' union all ');
  let perTenant = {};
  try {
    const raw = runPsql(container, user, database, perTenantSql);
    for (const line of raw.split('\n')) {
      const parts = line.split('\u001f');
      if (parts.length === 3 && parts[0]) perTenant[`${parts[0]}/${parts[1]}`] = Number(parts[2]);
    }
  } catch (error) {
    perTenant = { error: String(error.message).slice(0, 300) };
  }

  return { database, container, capturedAtUtc: new Date().toISOString(), tables, perTenant };
}

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

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.database || !args.out) {
    console.error('usage: db-snapshot.mjs --database NAME --out FILE [--container NAME] [--user NAME]');
    process.exitCode = 2;
    return;
  }
  const result = snapshot({
    container: args.container ?? 'firstelement-postgres',
    user: args.user ?? 'firstelement',
    database: args.database,
  });
  writeFileSync(args.out, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  const rows = Object.values(result.tables).reduce((sum, t) => sum + (t.count ?? 0), 0);
  console.log(`snapshot of ${args.database}: ${Object.keys(result.tables).length} table(s), ${rows} row(s)`);
  process.exitCode = 0;
}

if (process.argv[1] && process.argv[1].endsWith('db-snapshot.mjs')) main();