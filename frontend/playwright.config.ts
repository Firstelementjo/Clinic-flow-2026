import { defineConfig, devices } from '@playwright/test';
import { MATRIX, VIEWPORTS } from './e2e/matrix';

/** Where the SPA is served from. The dev server proxies `/api` to the API. */
export const BASE_URL = process.env['FE_E2E_BASE_URL'] ?? 'http://localhost:4200';

/** Where the Spring application is served from. */
export const API_URL = process.env['FE_E2E_API_URL'] ?? 'http://localhost:4000';

/**
 * The six combinations the acceptance gate names, as six Playwright projects.
 *
 * Projects rather than a loop inside the spec, for two reasons that both matter:
 * a failure names its locale and width in the report without a bespoke helper, and
 * `--project ar-mobile` runs exactly one combination, which is what you want when
 * diagnosing a direction or a breakpoint problem.
 *
 * The project **name** carries the locale and the width into the fixture. See
 * `e2e/matrix.ts` for why it is the name and not a custom `use` option.
 *
 * Each project is a separate serial run because the disposable tenant is a single
 * tenant and a session-expiry journey revokes the session another journey is
 * using.
 */
const MATRIX_PROJECTS = MATRIX.map(({ name, locale, viewport }) => ({
  name,
  use: {
    ...devices['Desktop Chrome'],
    viewport: VIEWPORTS[viewport],
    // BCP-47 tag the browser reports, so the browser's own formatting agrees with
    // the direction the application sets from its own locale. `journeyFor` is the
    // authority; this is the browser agreeing with it.
    locale,
  },
}));

/**
 * Refuses to run without a throwaway credential.
 *
 * Called from the config rather than only from the first sign-in so the failure
 * names the cause before a browser opens. `ops/quality/checks/prepare-throwaway-db.ps1`
 * creates the database this account belongs to; nothing here is committed and
 * there is no default, because a journey that signs in as nobody proves nothing.
 */
function requiredCredentials(): { email: string; password: string } {
  const email = process.env['FE_E2E_EMAIL'];
  const password = process.env['FE_E2E_PASSWORD'];
  if (!email || !password) {
    throw new Error(
      'FE_E2E_EMAIL and FE_E2E_PASSWORD must be set to a throwaway account.\n' +
        'Phase 11 journeys drive a real backend; there is no mock and no default ' +
        'credential, and none is committed. See frontend/README.md.',
    );
  }
  return { email, password };
}

export { requiredCredentials };

export default defineConfig({
  testDir: './e2e',
  // Only journeys run. `global.setup.ts` is a `globalSetup` hook, not a test file, and
  // a diagnostic spec has to be asked for by name.
  testMatch: /.*\.spec\.ts/,
  fullyParallel: false,
  // One worker: a session-expiry journey revokes the server-side session that
  // another journey is using. Serial execution is the honest configuration.
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['line']],
  use: {
    baseURL: BASE_URL,
    // The product honours the preference (`docs/migration-AGENT.md` §4.4) and a
    // journey that races an animation is a flaky journey. One journey asserts it
    // explicitly, so the default is not masking anything.
    reducedMotion: 'reduce',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  /**
   * Tears the disposable tenant down, seeds six fresh ones, and proves the API answers.
   *
   * Playwright's `globalSetup` hook, not a "setup" **project**. It was previously a
   * project with `testMatch: /global\.setup\.ts/`, and that does not work: the file
   * exports a `default async function` and declares no `test()`, so the project matched
   * zero tests and the exported function was **never called**. The suite had been
   * depending on an operator running `npm run e2e:seed` by hand, which is how a rerun
   * inherited closed and assigned conversations and sixteen journeys failed on
   * `200 {"status":"DUPLICATE"}` from the inbound simulator.
   *
   * `globalSetup` is the mechanism that actually runs it: exactly once, before any
   * project, and a throw aborts the whole run — which is what the old comment wanted
   * from the project and did not get.
   */
  globalSetup: './e2e/global.setup.ts',
  projects: MATRIX_PROJECTS,
});

/**
 * Refuses to load without a throwaway credential — unless the caller only asked
 * Playwright to *enumerate* the suite.
 *
 * `--list` never signs in: it loads the config, walks the specs and prints what
 * it found. Demanding a credential to do that is what made this suite impossible
 * to check from anywhere without a live stack, and a browser suite nobody can
 * enumerate is the exact failure `ops/quality/gate-stages.json` says the
 * playwright-journeys stage exists to prevent. The gate asserts on discovery —
 * 438 tests across six locale/width projects — which needs no database, no
 * server and no account.
 *
 * The requirement stays absolute for an actual run. This is the only exemption.
 */
if (!process.argv.includes('--list')) {
  requiredCredentials();
}
