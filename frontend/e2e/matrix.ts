/**
 * The locale/width matrix, in one place.
 *
 * ## Why this file exists
 *
 * The matrix has to be readable from two very different contexts: the Playwright
 * **config**, which needs it to declare projects, and the **journey fixture**,
 * which needs to know which locale and width the current project is running.
 *
 * The obvious way to carry that across — a custom `use` option plus a
 * `declare global { namespace PlaywrightTest { interface PlaywrightTestOptions … } }`
 * augmentation — is silently broken on the Playwright version this workspace pins.
 * In `playwright/types/test.d.ts` the option set is a module-level
 * `export interface PlaywrightTestOptions`, and the *global* `PlaywrightTest`
 * namespace that the documented augmentation targets only declares `Matchers`.
 * An augmentation there therefore creates an unrelated interface that nothing
 * references: it type-checks clean and is then invisible to every reader, which
 * is worse than a compile error because the reader believes the option exists.
 *
 * So the matrix is data rather than an option. A project's **name** is the carrier
 * — it is already unique, already visible in every failure message, and already
 * what `--project ar-mobile` selects. `journeyFor` turns a name back into a
 * `(locale, viewport)` pair, and a name it does not recognise is a loud error
 * rather than a silent default.
 */

/** The three viewports, named the way the acceptance gate names them. */
export const VIEWPORTS = {
  desktop: { width: 1440, height: 900 },
  tablet: { width: 900, height: 1200 },
  mobile: { width: 390, height: 844 },
} as const;

/** The two locales, with the direction each one implies. */
export const LOCALES = {
  ar: { name: 'ar', direction: 'rtl', htmlDir: 'rtl' },
  en: { name: 'en', direction: 'ltr', htmlDir: 'ltr' },
} as const;

export type ViewportName = keyof typeof VIEWPORTS;
export type LocaleName = keyof typeof LOCALES;

/**
 * One throwaway identity per matrix combination.
 *
 * The six combinations the acceptance gate names, each with its own account.
 *
 * Arabic is half of this matrix by construction, not by habit: `LOCALES` starts
 * with `ar` and the product's authoring language is Arabic, so "we tested it in
 * English and it looked fine" cannot be the only evidence a change was reviewed.
 *
 * ## Why each combination has its own identity
 *
 * `LoginRateLimiter` allows 10 attempts per 60 seconds **per source address**, and
 * clears only the *account* bucket on success — the address bucket is deliberately
 * not cleared, so a legitimate client cannot reset a host-level spray window. That
 * is correct and it stays at 10/60s.
 *
 * With a single shared account, a 25-journey x 6-combination run signs in ~150
 * times from one address, so it lands outside a limiter window purely by luck. A
 * run that passes because it happened to fit is not evidence, and one journey in
 * six combinations failed on the rate limit while the rest passed.
 *
 * Giving each combination its own account fixes the coincidence rather than the
 * symptom, because the limiter's account bucket **is** reset on a successful
 * login. Ten attempts per account per 60 s is never reached, so no journey waits
 * and none is skipped.
 *
 * ## What was *not* done
 *
 * The alternatives were both rejected:
 *
 * - **Pacing sign-ins below the limit.** That means sleeping between attempts, so
 *   the suite's runtime becomes a function of the limiter rather than of the
 *   product. A full matrix took ~19 minutes largely for this reason.
 * - **Relaxing the limiter, or asserting less often.** A limiter a test suite can
 *   exhaust is a limiter doing its job. The production limit stays at 10/60s.
 *
 * ## Why the address limit is still hit, and why that is fine
 *
 * The per-address bucket is shared across all six accounts and is *not* reset by a
 * successful login. So the suite still crosses 10 attempts per 60 s in aggregate.
 * What changes is that a given account is only ever attempted a handful of times
 * inside any one window, so the **account** bucket — which is what refuses a
 * legitimate retry — never trips. The retries in `Journey.attemptSignIn` remain as
 * a backstop, and are now expected not to fire.
 */
export const MATRIX = (Object.keys(LOCALES) as LocaleName[]).flatMap((locale) =>
  (Object.keys(VIEWPORTS) as ViewportName[]).map((viewport) => ({
    name: `${locale}-${viewport}`,
    locale,
    viewport,
  })),
);

/**
 * The email a combination signs in as.
 *
 * Derived from `FE_E2E_EMAIL` by replacing the local part with the project name,
 * so the identities stay inside the domain the operator configured and all resolve
 * through the same mail domain as the base account. If `FE_E2E_EMAIL` is not
 * usable as a base (no `@`), the project name is used as a local part and the
 * account is skipped rather than written to a domain nobody owns.
 */
export function identityFor(baseEmail: string, projectName: string): string {
  const at = baseEmail.lastIndexOf('@');
  if (at <= 0) return `${projectName}@invalid.example`;
  const local = baseEmail.slice(0, at);
  const domain = baseEmail.slice(at + 1);
  return `${local}+${projectName}@${domain}`;
}

export interface JourneyNames {
  readonly locale: LocaleName;
  readonly viewport: ViewportName;
}

const BY_PROJECT_NAME: ReadonlyMap<string, JourneyNames> = new Map(
  MATRIX.map((entry) => [entry.name, { locale: entry.locale, viewport: entry.viewport }]),
);

/**
 * The locale and width a project runs under.
 *
 * Throws for an unrecognised name. The alternative — defaulting to `ar-desktop` —
 * is how a journey ends up asserting Arabic copy against an English page and
 * reporting a direction bug that does not exist.
 */
export function journeyFor(projectName: string): JourneyNames {
  const names = BY_PROJECT_NAME.get(projectName);
  if (!names) {
    throw new Error(
      `Playwright project "${projectName}" is not part of the acceptance matrix. ` +
        `Expected one of: ${MATRIX.map((entry) => entry.name).join(', ')}. ` +
        'Add the combination to e2e/matrix.ts rather than defaulting it.',
    );
  }
  return names;
}
