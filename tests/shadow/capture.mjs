/* global process, console, URLSearchParams */
/**
 * capture.mjs -- drives ONE runtime through the shadow-read matrix and writes
 * what it actually answered.
 *
 * Usage:
 *   node tests/shadow/capture.mjs --side legacy --base http://127.0.0.1:4401 \
 *        --scenarios tests/shadow/scenarios/reads.json --out <file.json>
 *
 * THE RULE THIS FILE EXISTS TO KEEP. Nothing here decides whether two answers
 * agree. It records status codes and bodies verbatim (minus a short, declared
 * list of volatile fields) so that `compare.mjs` makes the decision from the
 * record rather than from a value computed in the same breath as the request.
 * A comparison that shares code with the capture is a comparison that cannot
 * disagree with the capture.
 *
 * VOLATILE FIELDS. Exactly three things are replaced before anything is
 * written to disk, and each is named in the capture itself so a reader can
 * see what was normalised:
 *   - session cookie values and CSRF tokens (never written at all)
 *   - `passwordHash` / `tokenHash` if a projection ever leaked one: the
 *     capture replaces the value and sets `leakedSecret: true`, so leaking a
 *     secret is a LOUD failure rather than a silently masked one.
 * Nothing else is normalised. Timestamps, ordering, nulls and absent keys are
 * all preserved exactly as the runtime rendered them.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { Client, CookieJar, LEGACY_PREFIX, SPRING_PREFIX } from './lib/http.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token.startsWith('--')) {
      const name = token.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) args[name] = true;
      else { args[name] = next; i += 1; }
    }
  }
  return args;
}

function resolve(value, resources) {
  if (typeof value !== 'string') return value;
  if (value.startsWith('$')) {
    const resolved = resources[value.slice(1)];
    if (resolved === undefined) throw new Error(`scenario refers to unknown resource ${value}`);
    return resolved;
  }
  return value;
}

function withQuery(path, query, resources) {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, raw] of Object.entries(query)) params.set(key, String(resolve(raw, resources)));
  return `${path}?${params.toString()}`;
}

/**
 * Resolves `$name` placeholders inside a path AND percent-encodes every
 * segment afterwards.
 *
 * Both halves are load-bearing. Resolution is obvious. Encoding is not: a
 * cxId such as `#33000001` contains a `#`, which a URL reader treats as the
 * start of a fragment, so an unencoded request asks the server for
 * `/api/v1/customers/cx/` and both stacks answer 404 -- an agreement that is
 * pure coincidence and would have been recorded as parity for a resource that
 * was never fetched.
 */
function resolvePath(path, resources) {
  return path
    .split('/')
    .map((segment) => encodeURIComponent(String(resolve(segment, resources))))
    .join('/');
}

/**
 * Replaces a secret-looking VALUE, and says so. Masking a secret silently is
 * how a capture ends up being the place a secret survives, so the mask is a
 * flag on the capture rather than a quiet substitution.
 */
function scrub(value, report) {
  if (Array.isArray(value)) return value.map((v) => scrub(v, report));
  if (value !== null && typeof value === 'object') {
    const out = {};
    for (const [key, child] of Object.entries(value)) {
      if (key === 'passwordHash' || key === 'tokenHash' || key === 'credentials') {
        report.leakedSecret = true;
        report.leakedSecretFields.push(key);
        out[key] = '<masked-by-capture>';
      } else {
        out[key] = scrub(child, report);
      }
    }
    return out;
  }
  return value;
}

async function loginFor(side, baseUrl, principal, password, savedSession) {
  if (!principal.email) return null;
  const client = new Client(baseUrl, savedSession ? CookieJar.load(savedSession.cookies) : undefined);
  const prefix = side === 'legacy' ? LEGACY_PREFIX : SPRING_PREFIX;
  client.loginPath = `${prefix}/auth/login`;
  // One login per gate run, not one per capture: see CookieJar.load.
  if (savedSession && savedSession.authenticated) {
    // The CSRF token is deliberately NOT persisted. It is single-use on the
    // new runtime, so a saved one is worthless; `request` fetches a fresh one
    // before every unsafe method.
    if (side !== 'legacy') { client.csrfPath = '/api/v1/auth/csrf'; await client.ensureCsrf(); }
    return client;
  }
  await client.login(principal.email, password, { csrfFirst: side !== 'legacy' });
  return client;
}

export async function capture(side, baseUrl, scenarios, password, sessionFile) {
  const resources = scenarios.resources;
  const requests = [
    ...scenarios.requests.map((r) => ({ ...r, runsOn: 'both' })),
    ...(side === 'legacy' ? scenarios.legacyOnly ?? [] : scenarios.springOnly ?? [])
      .map((r) => ({ ...r, runsOn: side })),
  ];

  let persisted = {};
  if (sessionFile) {
    try { persisted = JSON.parse(readFileSync(sessionFile, 'utf8')); } catch { persisted = {}; }
  }

  const clients = new Map();
  const results = [];
  const report = { leakedSecret: false, leakedSecretFields: [] };

  for (const request of requests) {
    const key = request.principal ?? 'anonymous';
    if (!clients.has(key)) {
      const principal = scenarios.principals[key];
      if (!principal) throw new Error(`scenario ${request.id} names unknown principal ${key}`);
      clients.set(key, await loginFor(side, baseUrl, principal, password, persisted[key]));
    }
    const client = clients.get(key);
    const prefix = side === 'legacy' ? LEGACY_PREFIX : SPRING_PREFIX;
    const fullPath = `${prefix}${resolvePath(request.path, resources)}${
      withQuery('', request.query, resources)}`;

    const caller = client ?? new Client(baseUrl);
    let response;
    try {
      response = await caller.request(request.method, fullPath);
    } catch (error) {
      results.push({
        id: request.id, group: request.group, principal: key, method: request.method,
        path: fullPath, transportError: String(error && error.message ? error.message : error),
      });
      continue;
    }
    results.push({
      id: request.id, group: request.group, principal: key, method: request.method,
      path: fullPath,
      status: response.status, parseError: response.parseError,
      body: scrub(response.json, report),
    });
  }

  // Sessions are persisted so the next capture against the same runtime
  // reuses them. The file lives in the gate's temporary work directory and is
  // never part of the capture artefact, so a session cookie cannot travel
  // into a report.
  if (sessionFile) {
    const next = {};
    for (const [key, client] of clients) {
      if (client !== null) next[key] = { authenticated: true, cookies: client.cookies() };
    }
    writeFileSync(sessionFile, `${JSON.stringify(next, null, 2)}\n`, 'utf8');
  }

  return {
    side,
    baseUrl,
    capturedAtUtc: new Date().toISOString(),
    volatileFields: ['session cookie values', 'csrf token', 'passwordHash', 'tokenHash', 'credentials'],
    leakedSecret: report.leakedSecret,
    leakedSecretFields: [...new Set(report.leakedSecretFields)],
    results,
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.side || !args.base || !args.scenarios || !args.out) {
    console.error('usage: capture.mjs --side legacy|spring --base URL --scenarios FILE --out FILE [--session FILE]');
    process.exitCode = 2;
    return;
  }
  const scenarios = JSON.parse(readFileSync(args.scenarios, 'utf8'));
  const password = process.env.SHADOW_PARITY_PASSWORD;
  if (!password) {
    console.error('SHADOW_PARITY_PASSWORD is not set. The harness refuses to guess a credential.');
    process.exitCode = 2;
    return;
  }
  return capture(args.side, args.base, scenarios, password, args.session)
    .then((captureResult) => {
      writeFileSync(args.out, `${JSON.stringify(captureResult, null, 2)}\n`, 'utf8');
      const failures = captureResult.results.filter((r) => r.transportError).length;
      console.log(`captured ${captureResult.results.length} request(s) against ${args.side}; ${failures} transport error(s)`);
      process.exitCode = failures > 0 ? 1 : 0;
    });
}

// `process.exitCode` rather than `process.exit()`: an explicit exit while an
// idle socket is still open aborts inside libuv on Windows and the process
// dies with a native assertion instead of the intended exit code. Letting the
// loop drain is both correct and quieter.
if (process.argv[1] && process.argv[1].endsWith('capture.mjs')) {
  main().catch((error) => {
    console.error(`capture failed: ${error && error.message ? error.message : error}`);
    process.exitCode = 1;
  });
}

export { parseArgs, HERE, join };