/* global process, console */
/**
 * transitions.mjs -- feeds one status-transition sequence to BOTH runtimes
 * and compares the state each one leaves behind in its own database.
 *
 * Usage:
 *   SHADOW_PARITY_PASSWORD=... node tests/shadow/transitions.mjs \
 *     --scenarios tests/shadow/scenarios/transitions.json \
 *     --legacy-base http://127.0.0.1:4401 --spring-base http://127.0.0.1:4402 \
 *     --legacy-db shadow_check_legacy --spring-db shadow_check_spring \
 *     --out transitions.json
 *
 * ------------------------------------------------------------------
 * WHY THIS IS A SEPARATE HARNESS FROM THE READ MATRIX
 * ------------------------------------------------------------------
 * A transition is a WRITE, so it cannot run inside a read comparison: both
 * stacks would be writing into the rows the comparison is reading, and the
 * outcome would depend on which stack answered first. Here each runtime gets
 * its OWN database -- two identically seeded copies -- the SAME sequence is
 * issued to each, and the resulting rows are read back with SQL rather than
 * through either API. Reading the source rows is deliberate: the two clocks
 * are different processes and the two databases were written seconds apart, so
 * an absolute timestamp can never match. What must match is the STATE the
 * transition produced.
 *
 * WHAT IS COMPARED, and why each field is here:
 *   status          the transition itself
 *   resolvedAt set  a RESOLVED row with no resolution timestamp is a defect
 *   closedAt set    likewise for CLOSED
 *   monotonic       resolvedAt <= closedAt <= now: a close that stamps an
 *                   earlier resolution time is a clock-ordering bug
 *   modeVersion     the optimistic-concurrency counter the legacy runtime
 *                   already used for its guarded mode transition
 *   event log       which STATUS_CHANGED rows each side recorded, in order
 *
 * WHAT WOULD MAKE THIS FAIL. If either runtime refuses a legal transition,
 * silently ignores one, or leaves a null where the other stamped a timestamp,
 * the two state vectors differ and CHK-6 goes red. PN-07 proves it by feeding
 * the two stacks DIFFERENT sequences and asserting the check notices.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { Client, CookieJar, LEGACY_PREFIX, SPRING_PREFIX } from './lib/http.mjs';

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
    '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-F', '|', '-P', 'pager=off', '-c', sql,
  ], { encoding: 'utf8' }).trim();
}

function readState(container, user, database, conversationId) {
  const row = psql(container, user, database, `
    select "status" || '|' ||
           ("resolvedAt" is not null)::text || '|' ||
           ("closedAt" is not null)::text || '|' ||
           ("modeVersion")::text || '|' ||
           coalesce(("resolvedAt" is not null and "closedAt" is not null
                     and "resolvedAt" <= "closedAt")::text, 'false')
      from conversations where "id" = '${conversationId}'`);
  const parts = row.split('|');
  if (parts.length < 5) return { error: `row not found or unreadable: ${row}` };
  return {
    status: parts[0],
    resolvedAtSet: parts[1] === 'true',
    closedAtSet: parts[2] === 'true',
    modeVersion: Number(parts[3]),
    resolvedBeforeClosed: parts[4] === 'true',
  };
}

function readEvents(container, user, database, conversationId) {
  const rows = psql(container, user, database, `
    select "type" from conversation_events
     where "conversationId" = '${conversationId}' and "type" = 'STATUS_CHANGED'
     order by "occurredAt", "id"`);
  return rows.length === 0 ? [] : rows.split('\n').map((r) => r.trim()).filter(Boolean);
}

async function runSequence(side, baseUrl, session, scenario, password, useVariant) {
  const prefix = side === 'legacy' ? LEGACY_PREFIX : SPRING_PREFIX;
  const client = new Client(baseUrl, session ? CookieJar.load(session.cookies) : undefined);
  client.loginPath = `${prefix}/auth/login`;
  if (!session || !session.authenticated) {
    await client.login(scenario.principalEmail, password, { csrfFirst: side !== 'legacy' });
  } else if (side !== 'legacy') {
    client.csrfPath = '/api/v1/auth/csrf';
    await client.ensureCsrf();
  }

  // PN-07: --variant-side <side> feeds ONE runtime a shorter sequence. The
  // resulting state must differ, which is how CHK-6 is proven able to fail.
  const sequence = useVariant && scenario.sequenceVariant ? scenario.sequenceVariant : scenario.sequence;
  const steps = [];
  for (const step of sequence) {
    const path = `${prefix}${step.path.replace('$conversation', scenario.conversationId)}`;
    let response;
    try {
      response = await client.request(step.method, path, { body: step.body });
    } catch (error) {
      steps.push({ label: step.label, transportError: String(error.message ?? error) });
      continue;
    }
    steps.push({
      label: step.label,
      method: step.method,
      status: response.status,
      // A refusal body is recorded by its shape and its error code only. It
      // must never be copied into a report verbatim on the off-chance it
      // quotes a row from the conversation.
      errorCode: response.json && response.json.code ? response.json.code : null,
    });
  }
  return { side, steps, cookies: client.cookies() };
}

export async function runTransitions(args) {
  const scenarioDoc = JSON.parse(readFileSync(args.scenarios, 'utf8'));
  const password = process.env.SHADOW_PARITY_PASSWORD;
  if (!password) throw new Error('SHADOW_PARITY_PASSWORD is not set');

  const container = args.container ?? 'firstelement-postgres';
  const user = args.user ?? 'firstelement';

  const before = {
    legacy: readState(container, user, args['legacy-db'], scenarioDoc.conversationId),
    spring: readState(container, user, args['spring-db'], scenarioDoc.conversationId),
  };

  const session = args.session && readFileSyncSafe(args.session);
  const variantSide = typeof args['variant-side'] === 'string' ? args['variant-side'] : null;
  const legacy = await runSequence('legacy', args['legacy-base'], session, scenarioDoc, password, variantSide === 'legacy');
  const spring = await runSequence('spring', args['spring-base'], session, scenarioDoc, password, variantSide === 'spring');

  const after = {
    legacy: readState(container, user, args['legacy-db'], scenarioDoc.conversationId),
    spring: readState(container, user, args['spring-db'], scenarioDoc.conversationId),
  };
  const events = {
    legacy: readEvents(container, user, args['legacy-db'], scenarioDoc.conversationId),
    spring: readEvents(container, user, args['spring-db'], scenarioDoc.conversationId),
  };

  return {
    kind: 'shadow-transition-comparison',
    capturedAtUtc: new Date().toISOString(),
    scenario: {
      id: scenarioDoc.id,
      conversationId: scenarioDoc.conversationId,
      sequence: scenarioDoc.sequence.map((s) => s.label),
      // Recorded so a reader can tell a real run from the PN-07 perturbation
      // without having to know which flag was passed.
      variantAppliedTo: variantSide,
      why: scenarioDoc.why,
    },
    legacy: { steps: legacy.steps, before: before.legacy, after: after.legacy, statusEvents: events.legacy },
    spring: { steps: spring.steps, before: before.spring, after: after.spring, statusEvents: events.spring },
  };
}

function readFileSyncSafe(path) {
  try { return JSON.parse(readFileSync(path, 'utf8')); } catch { return null; }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.scenarios || !args['legacy-base'] || !args['spring-base'] || !args.out) {
    console.error('usage: transitions.mjs --scenarios FILE --legacy-base URL --spring-base URL --legacy-db NAME --spring-db NAME --out FILE');
    process.exitCode = 2;
    return;
  }
  const result = await runTransitions(args);
  writeFileSync(args.out, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  console.log(`transition sequence "${result.scenario.id}" replayed on both runtimes`);
  process.exitCode = 0;
}

if (process.argv[1] && process.argv[1].endsWith('transitions.mjs')) {
  main().catch((error) => {
    console.error(`transitions failed: ${error && error.message ? error.message : error}`);
    process.exitCode = 1;
  });
}