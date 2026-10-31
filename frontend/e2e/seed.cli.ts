/**
 * Seeds or tears down the disposable E2E tenant from the command line.
 *
 * `global.setup.ts` seeds during a Playwright run. This is for the case where the
 * database needs preparing first, or clearing afterwards, without running the
 * whole suite — and it is what `npm run e2e:seed` invokes.
 *
 *     npm run e2e:seed        # create or refresh the tenant
 *     npm run e2e:teardown    # remove it
 *
 * Both refuse a non-disposable database name, and both print the target before
 * they write. Neither prints a credential: the password is read from the
 * environment and hashed in memory.
 */
import { RefusedSeed, dropTenant, idsFor, seedTenant, type SeedTarget } from './seed';
import { seedCatalogue } from './authoring.seed';
import { MATRIX, identityFor } from './matrix';

const target: SeedTarget = {
  container: process.env['FE_E2E_CONTAINER'] ?? 'firstelement-postgres',
  database: process.env['FE_E2E_DATABASE'] ?? 'firstelement_e2e_scratch',
  user: process.env['FE_E2E_DB_USER'] ?? 'firstelement',
};

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} must be set; see frontend/README.md.`);
  return value;
}

/**
 * Every identity this suite can sign in as.
 *
 * `npm run e2e:seed` seeds the same set `global.setup.ts` does, so an operator who
 * prepares the database by hand gets exactly what a Playwright run expects. Seed
 * and teardown therefore take the same argument — a list — rather than one
 * account, because the previous single-account shape is what let the two drift
 * apart in the first place.
 */
function identities(): { email: string; name: string; identity: ReturnType<typeof idsFor> }[] {
  const base = required('FE_E2E_EMAIL');
  return MATRIX.map((entry) => {
    const email = identityFor(base, entry.name);
    return { email, name: entry.name, identity: idsFor(email) };
  });
}

/**
 * Whether to run the catalogue phase.
 *
 * **The default, and that is the point.** A seeded tenant with an empty export catalogue
 * cannot support an export journey: nothing is pinnable, the picker is empty, and the
 * screen renders its empty state — which is indistinguishable from a catalogue the client
 * failed to read. That ambiguity is exactly how a real defect hid for a whole batch.
 *
 * So the seed writes the catalogue and **asserts** it, and a failure is a seed failure. It
 * is skipped for `teardown`, which cascades.
 */
const cataloguePhase = (process.argv[2] ?? 'seed') === 'seed';

function main(): void {
  const mode = process.argv[2] ?? 'seed';
  const all = identities();

  console.log(
    `[phase-11 e2e] ${mode}: ${target.database} on ${target.container}, ` +
      `${all.length} identities (${all.map((entry) => entry.name).join(', ')})`,
  );

  // The catalogue is written **inside** the loop, for every identity rather than for the
  // first: each identity administers its own tenant with its own `projectId`, and a journey
  // signs in as whichever combination it is running. Seeding one catalogue would leave five
  // projects unable to export, so five of the six combinations would exercise the empty
  // state while the sixth exercised a real one — a fixture correct for one project out of
  // six is a fixture that is wrong five times out of six.
  for (const { email, name, identity: ids } of all) {
    if (mode === 'teardown') {
      dropTenant(target, {
        ...ids,
        email,
        password: '',
        fullName: `E2E Administrator (${name})`,
        role: 'ADMIN',
      });
      continue;
    }

    seedTenant(target, {
      ...ids,
      email,
      password: required('FE_E2E_PASSWORD'),
      fullName: `E2E Administrator (${name})`,
      role: 'ADMIN',
    });

    if (cataloguePhase) {
      const summary = seedCatalogue(target, {
        organizationId: ids.organizationId,
        projectId: ids.projectId,
        userId: ids.userId,
        email,
      });
      console.log(
        `      [phase-11 e2e] ${name}: ${summary.reportDefinitions} report definition(s), ` +
          `${summary.documentTemplates} template(s) seeded and read back. Names, ids and ` +
          `publishable versions were asserted, not assumed.`,
      );
    }
  }

  console.log(
    mode === 'teardown'
      ? `[phase-11 e2e] ${all.length} tenant(s) removed.`
      : `[phase-11 e2e] ${all.length} tenant(s) seeded. No credential was printed.`,
  );
}

try {
  // A catalogue failure is a seed failure: it propagates out of `main` and exits non-zero.
  // A seed that tolerated an unreadable catalogue would turn a broken catalogue into an
  // empty one and hide the defect behind a green matrix.
  main();
} catch (cause) {
  if (cause instanceof RefusedSeed) {
    console.error(`[phase-11 e2e] REFUSED: ${cause.message}`);
    process.exit(2);
  }
  console.error(`[phase-11 e2e] FAILED: ${(cause as Error).message}`);
  process.exit(1);
}
