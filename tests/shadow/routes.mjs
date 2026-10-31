/* global process, console */
/**
 * routes.mjs -- extracts each runtime's OWN route inventory from the log it
 * wrote at boot, and diffs the two inventories.
 *
 * WHY THE BOOT LOG. The harness has to decide, for every scenario it cannot
 * compare, whether the reason is "the legacy runtime does not implement this"
 * or "the legacy runtime refused this caller". Those two look identical from
 * the outside -- both are a 404 -- and guessing between them would let an
 * authorization defect be filed as a missing feature. So the basis is
 * structural: what each router said it mapped.
 *
 *   NestJS   "[RouterExplorer] Mapped {/api/customers, GET} route {/api/customers/:customerId, GET}"
 *   Spring   "Mapped "{GET /api/v1/customers}" ..."
 *
 * Both runtimes log their full table at INFO on a cold boot. Neither
 * inventory is written by hand, so neither can be edited to make a gap look
 * closed.
 *
 * Usage:
 *   node tests/shadow/routes.mjs --legacy-log a.log --spring-mappings mappings.json \
 *        --out routes.json
 *
 * The two inventories come from two different mechanisms, because the two
 * frameworks expose different things by default: NestJS logs its route table
 * at INFO and Spring Boot does not (its `Mapped "{GET /...}"` line is DEBUG on
 * 3.x). Rather than raise the new runtime's log level to DEBUG -- which would
 * mean shipping production DEBUG logging to satisfy a test -- the Spring
 * inventory is read from `/actuator/mappings` on the management port. That is
 * the runtime's own handler registry, not a log somebody chose to leave on.
 */

import { readFileSync, writeFileSync } from 'node:fs';

const LEGACY_RE = /Mapped \{(?<path>[^,}]+), (?<method>[A-Z]+)\} route/g;

function extractLegacy(text) {
  const out = [];
  // The NestJS logger writes ANSI colour codes. Stripping them keeps the
  // pattern readable and stops a future colour change from silently
  // producing an EMPTY inventory, which is the failure mode that would let
  // every legacy route look absent.
  const ESC = String.fromCharCode(27);
  const plain = text.replace(new RegExp(`${ESC}\\[[0-9;]*m`, 'g'), '');
  for (const match of plain.matchAll(LEGACY_RE)) {
    out.push({ method: match.groups.method, path: match.groups.path });
  }
  return out;
}

/**
 * `/actuator/mappings` -> a flat [{method, path}] list.
 *
 * Spring renders each predicate as `{POST [/api/v1/customers/resolve-identity]}`,
 * so the method is followed by a space and then a BRACKET before the path.
 * A pattern that expects the path immediately would match nothing and produce
 * an empty inventory, which this file then refuses to report -- an empty
 * inventory must never be mistaken for a runtime with no routes.
 */
function extractSpring(document_) {
  const out = [];
  const contexts = document_?.contexts ?? {};
  for (const key of Object.keys(contexts)) {
    const servlets = contexts[key]?.mappings?.dispatcherServlets ?? {};
    for (const handler of Object.keys(servlets)) {
      for (const details of servlets[handler]) {
        const predicate = details?.predicate;
        if (!predicate) continue;
        for (const match of String(predicate).matchAll(
          /\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+\[?([^\]\s,}]+)/g)) {
          out.push({ method: match[1], path: match[2] });
        }
      }
    }
  }
  return out;
}

/**
 * Spring's log lines contain a trailing handler suffix and, for some
 * mappings, a `produces` clause. Only the method and path are kept, and a
 * duplicate is collapsed so a route registered twice cannot inflate a count.
 */
function canonical(entries) {
  const seen = new Map();
  for (const entry of entries) {
    const key = `${entry.method} ${entry.path}`;
    if (!seen.has(key)) seen.set(key, entry);
  }
  return [...seen.values()].sort((a, b) => {
    const left = `${a.method} ${a.path}`;
    const right = `${b.method} ${b.path}`;
    return left < right ? -1 : (left > right ? 1 : 0);
  });
}

export function buildRouteInventory(legacyLogText, springMappingsDocument) {
  const legacy = canonical(extractLegacy(legacyLogText));
  const spring = canonical(extractSpring(springMappingsDocument));
  return {
    legacy: legacy.map((e) => `${e.method} ${e.path}`),
    spring: spring.map((e) => `${e.method} ${e.path}`),
    legacyCount: legacy.length,
    springCount: spring.length,
  };
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
  if (!args['legacy-log'] || !args['spring-mappings'] || !args.out) {
    console.error('usage: routes.mjs --legacy-log FILE --spring-mappings FILE --out FILE');
    process.exitCode = 2;
    return;
  }
  const legacyText = readFileSync(args['legacy-log'], 'utf8');
  const springDocument = JSON.parse(readFileSync(args['spring-mappings'], 'utf8'));
  const inventory = buildRouteInventory(legacyText, springDocument);

  if (inventory.legacyCount === 0) {
    console.error('no legacy routes were parsed from the boot log. The legacy runtime must be booted at LOG_LEVEL=info; a missing inventory is not an empty one.');
    process.exitCode = 1;
    return;
  }
  if (inventory.springCount === 0) {
    console.error('no Spring routes were parsed from /actuator/mappings; the same rule applies.');
    process.exitCode = 1;
    return;
  }

  writeFileSync(args.out, `${JSON.stringify(inventory, null, 2)}\n`, 'utf8');
  console.log(`route inventory: legacy ${inventory.legacyCount}, spring ${inventory.springCount}`);
  process.exitCode = 0;
}

main();