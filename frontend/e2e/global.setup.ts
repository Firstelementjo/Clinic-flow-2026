/**
 * Global setup: refuse to run, or prepare the disposable database.
 *
 * The suite needs a **real** backend, and a real backend needs a tenant with a
 * user, a project and a membership. There are exactly two acceptable ways for that
 * database to exist, and this script accepts only them:
 *
 * 1. `FE_E2E_SEED=1` — the harness creates the tenant in the named database. The
 *    name must be disposable (`check`, `rehearsal`, `gate`, `throwaway`,
 *    `scratch`, `tmp`), the seed refuses to touch `firstelement`, and the script
 *    prints what it will do *before* it does it.
 * 2. The operator already seeded it and sets `FE_E2E_EMAIL` / `FE_E2E_PASSWORD`.
 *    Nothing is written.
 *
 * With neither, the suite **fails**. It never skips, because a browser suite
 * that silently does not exist is the exact failure `docs/migration/phases/
 * quality-gates.md` §2 stage 12 was created to prevent.
 */
import { execFileSync } from 'node:child_process';
import { RefusedSeed, dropTenant, idsFor, psql, seedTenant, type SeedTarget } from './seed';
import { seedCatalogue } from './authoring.seed';
import { MATRIX, identityFor } from './matrix';

const container = process.env['FE_E2E_CONTAINER'] ?? 'firstelement-postgres';
const database = process.env['FE_E2E_DATABASE'] ?? 'firstelement_e2e_scratch';
const dbUser = process.env['FE_E2E_DB_USER'] ?? 'firstelement';

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} must be set. Phase 11 journeys drive a real backend; there is no mock, ` +
        'no recorded fixture and no default credential, and none is committed.',
    );
  }
  return value;
}

/** Fails fast with the exact command, rather than reporting "skipped". */
function assertApiReachable(): void {
  const url = process.env['FE_E2E_API_URL'] ?? 'http://localhost:4000';
  try {
    const response = execFileSync(
      'powershell',
      [
        '-NoProfile',
        '-Command',
        `(Invoke-WebRequest -Uri '${url}/api/v1/status' -UseBasicParsing -TimeoutSec 10).StatusCode`,
      ],
      { encoding: 'utf8', timeout: 20_000 },
    );
    if (!response.includes('200')) throw new Error(response);
  } catch (cause) {
    throw new Error(
      `The API is not answering on ${url}/api/v1/status.\n` +
        'Start it against the disposable database first:\n' +
        `  $env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/${database}"\n` +
        '  java -jar backend/app-bootstrap/build/libs/firstelement-api-0.1.0.jar\n' +
        `Original error: ${(cause as Error).message}`,
    );
  }
}

/**
 * Proves the dev server is answering **before** any journey runs.
 *
 * ## Why this exists
 *
 * The dev server died part-way through a full matrix run. Sixteen journeys then failed with
 * `net::ERR_CONNECTION_REFUSED at http://localhost:4200`, and every one of them looked like
 * a product defect: a screen that would not load reads exactly like a screen that is broken.
 * Nothing distinguished "the server is gone" from "the client is wrong", so the failures had
 * to be classified by hand, by reading timestamps and spotting which project they fell in.
 *
 * That is the same failure shape as the defects this phase kept finding — a client-side
 * problem wearing a server's clothes — and it produced the *opposite* misreading: a server
 * problem wearing a client's clothes.
 *
 * So the harness says so itself, at setup, with the command to run. It cannot prevent the
 * server dying mid-run, and this does not pretend otherwise: a journey that fails after the
 * start still needs reading. But a run that begins with the server down is now refused
 * outright instead of producing a page of connection errors.
 */
function assertDevServerReachable(): void {
  const url = process.env['FE_E2E_APP_URL'] ?? 'http://localhost:4200';
  try {
    const response = execFileSync(
      'powershell',
      [
        '-NoProfile',
        '-Command',
        `(Invoke-WebRequest -Uri '${url}/' -UseBasicParsing -TimeoutSec 20).StatusCode`,
      ],
      { encoding: 'utf8', timeout: 30_000 },
    );
    if (!response.includes('200')) throw new Error(response);
  } catch (cause) {
    throw new Error(
      `The Angular dev server is not answering on ${url}/.\n` +
        'Every journey drives the real application, so without it each one fails with\n' +
        'net::ERR_CONNECTION_REFUSED, which is indistinguishable from a product defect.\n' +
        'Start it and let the build finish before running the matrix:\n' +
        '  cd frontend\n' +
        '  npm start\n' +
        `Original error: ${(cause as Error).message}`,
    );
  }
}

export default async function globalSetup(): Promise<void> {
  const email = required('FE_E2E_EMAIL');
  const password = required('FE_E2E_PASSWORD');
  assertApiReachable();
  assertDevServerReachable();

  // ## The seed runs BY DEFAULT
  //
  // This used to be opt-in: `if (process.env['FE_E2E_SEED'] !== '1') return;`. That was a
  // trap. A run without the variable skipped seeding entirely, so the catalogue the export
  // journeys read was empty, the exports panel showed a refusal card, and probe rows written
  // by earlier runs accumulated until the SECOND run of a spec failed on duplicate keys.
  // Both symptoms read as product defects and neither was one.
  //
  // The safety argument was never about the variable -- it is `assertDisposable` and the
  // `firstelement` refusal above, which run either way. Seeding is therefore on unless it is
  // explicitly turned off, so a matrix run is self-contained and repeatable:
  //
  //   FE_E2E_SEED=0   skip seeding, for an environment someone seeded deliberately.
  //
  // A harness that must be told to prepare itself is a harness that eventually runs
  // unprepared and reports the consequence as a bug in the product.
  if (process.env['FE_E2E_SEED'] === '0') return;

  const target: SeedTarget = { container, database, user: dbUser };

  // One tenant, one administrator per matrix project. Each project signs in as its
  // own account so the login limiter's account bucket is never saturated — see
  // `e2e/matrix.ts`. Every account shares the organization, so they all see the
  // same tenant data, which is what makes a cross-combination comparison
  // meaningful; only the credentials differ.
  //
  // All of them are seeded up front rather than lazily per project, so a missing
  // account fails here with a clear cause instead of halfway through a 150-journey
  // run as a sign-in timeout.
  const accounts = MATRIX.map((entry) => {
    const identity = identityFor(email, entry.name);
    return { ...idsFor(identity), email: identity, name: entry.name };
  });

  // ## Teardown first, then seed — and why the order is the whole point
  //
  // The journeys drive a **real** backend, and several of them change state: one closes
  // a conversation, one assigns it, one takes it over. A tenant that survives between runs
  // therefore arrives at the next run with those conversations closed or assigned, which
  // takes them out of the unassigned queue the inbox journeys open from. The symptom is
  // not a clean failure — it is `POST /api/v1/dev/mock/incoming-message` answering
  // `200 {"status":"DUPLICATE"}` with no `customerId`, because the idempotency key from
  // the previous run already exists, and then sixteen journeys failing for a reason that
  // has nothing to do with what they test.
  //
  // So the run starts from an empty tenant: `dropTenant` sweeps every `e2e-` slug, and
  // `seedTenant` writes six fresh ones. This is also the round trip the orchestrator asked
  // to see proved — seed, teardown, seed — except that it now happens automatically
  // before every run instead of by hand.
  //
  // It does mean two concurrent runs against one database would clobber each other. That
  // is already the rule in `AGENTS.md` (never two agents against the same migration
  // artefacts), and this only widens it to the throwaway database.
  for (const account of accounts) {
    try {
      dropTenant(target, { ...account, password, fullName: '', role: 'ADMIN' });
    } catch (cause) {
      if (cause instanceof RefusedSeed) throw cause;
      // A leftover row holding the tenant open through a foreign key must not stop the
      // run: report it and let the seed try, because the seed may still succeed and the
      // journeys are the thing that decides.
      console.log(
        `[phase-11 e2e] teardown could not fully remove the previous tenant: ` +
          `${(cause as Error).message}`,
      );
    }
  }

  console.log(
    `[phase-11 e2e] previous tenants removed; seeding ${accounts.length} disposable ` +
      `accounts in ${database} on ${container}`,
  );

  for (const account of accounts) {
    try {
      seedTenant(target, {
        ...account,
        password,
        fullName: `E2E Administrator (${account.name})`,
        role: 'ADMIN',
      });
    } catch (cause) {
      if (cause instanceof RefusedSeed) throw cause;
      throw new Error(
        `The seed failed for ${account.name}. Confirm the disposable database exists and carries the Flyway chain:\n` +
          `  .\\ops\\quality\\checks\\prepare-throwaway-db.ps1 -Database ${database}\n` +
          `Original error: ${(cause as Error).message}`,
      );
    }

    // The export catalogue, per account and inside the same loop.
    //
    // It has to be here and not only in `seed.cli.ts`, because **this is the hook Playwright
    // actually runs**. A catalogue written only by the CLI leaves every journey reading an
    // empty catalogue, and an empty catalogue is not an obvious failure: the picker renders
    // with its placeholder and nothing else, the export journeys find nothing pinnable, and
    // the screen's own empty state — which is correct behaviour for an empty catalogue —
    // stands in for a defect that is not there. That is precisely the ambiguity this seed
    // exists to remove.
    //
    // It throws on an unreadable catalogue, so a broken one is a setup failure with a named
    // cause rather than a green run that tested nothing.
    seedCatalogue(target, {
      organizationId: account.organizationId,
      projectId: account.projectId,
      userId: account.userId,
      email: account.email,
    });
  }

  // Prove the chain is present, so a failure here names the cause rather than
  // surfacing later as a confusing 500 from the API.
  const applied = psql(
    target,
    `SELECT count(*) FROM "public"."flyway_schema_history" WHERE success;`,
  ).trim();
  if (applied === '0') {
    throw new Error(
      `${database} has no successful Flyway migrations, so the API cannot serve the API surface.\n` +
        'Recreate it: prepare-throwaway-db.ps1 -Database <name>, then start the API against it.',
    );
  }
  console.log(`[phase-11 e2e] seeded. ${applied} migrations applied on ${database}.`);
}
