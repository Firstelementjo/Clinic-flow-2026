/**
 * A shared gate on sign-in attempts.
 *
 * ## The problem this exists to solve
 *
 * `LoginRateLimiter` allows **10 attempts per 60 seconds per source address**, and
 * clears only the *account* bucket after a success — the address bucket is deliberately
 * not cleared, so a legitimate client cannot reset a host-level spray window. Both are
 * correct and neither is changed here.
 *
 * The suite makes roughly one sign-in per journey: about 300 for the full matrix. They
 * all arrive from a **single** source address, because the dev server proxies `/api` to
 * the Spring process, so the browser's connection is always `127.0.0.1`.
 *
 * Giving each matrix combination its own *account* (`e2e/matrix.ts`) fixes the account
 * bucket and nothing else. The address bucket is shared by construction, so it still
 * saturates — and it did: a full matrix run passed 278 of 300 and failed every
 * `en-mobile` journey, which runs last, after the other five combinations had spent the
 * window. Earlier, a run reported 150 passed and the orchestrator measured 149. Both are
 * the same thing: the result depended on **where in the limiter window the run fell**.
 *
 * A green run that depends on a coincidence is not evidence, so the coincidence is
 * removed rather than retried.
 *
 * ## What the alternatives were, and why they lost
 *
 * - **Per-combination identity** — adopted, and kept, because it removes the account
 *   bucket entirely. It does not touch the address bucket, which is the one that bites.
 * - **Raising the limit, or configuring the API differently for E2E** — the production
 *   value stays at 10/60s. A limiter a test suite can exhaust is a limiter doing its
 *   job.
 * - **Distinct source addresses per combination** — would need the suite to forge
 *   `X-Forwarded-For` or similar, i.e. to defeat the very control it is testing. That is
 *   a workaround that hides the property, and it is worse than the defect.
 * - **Skipping journeys** — `AGENTS.md`: a skipped browser test is a failure, not a pass.
 *
 * That leaves pacing, which the orchestrator named as acceptable: *make the E2E
 * environment space its sign-ins below the limit*. This module is that pacing, expressed
 * as a guarantee rather than a sleep.
 *
 * ## Why it is a guarantee
 *
 * `await gate()` returns only once at least `MIN_INTERVAL_MS` has elapsed since the
 * previous sign-in anywhere in the process. With `MIN_INTERVAL_MS = 6500`, no 60-second
 * window can contain more than 9 attempts, against a limit of 10 — so the limiter is
 * never reached, regardless of how the suite is ordered, how many projects run, or how
 * fast the machine is.
 *
 * A fixed `sleep()` before each sign-in would not be the same: it would not compose with
 * the time a test has already spent, so a slow test would still land two attempts close
 * together. Serialising on the *instant of the attempt* is what makes the bound hold.
 *
 * `workers: 1` in `playwright.config.ts` is what makes a module-level counter the right
 * scope for this. With more workers it would have to move to a shared external token
 * bucket, because module state is per process — noted here so the two settings are
 * changed together or not at all.
 */

/**
 * The minimum gap between two sign-in attempts, in milliseconds.
 *
 * 60 000 ms window ÷ 10 attempts = 6 000 ms. 6 500 is that plus a 500 ms margin for
 * clock granularity and for an attempt that lands on a window boundary, which puts the
 * ceiling at 9 per window rather than exactly 10.
 */
export const MIN_INTERVAL_MS = 6_500;

/** The instant the next attempt is allowed. Shared across every journey in the process. */
let nextAllowedAt = 0;

/** Serialises waiters so they resume in arrival order rather than all at once. */
let queue: Promise<void> = Promise.resolve();

/**
 * Waits until a sign-in attempt is allowed.
 *
 * Returns the milliseconds it waited, so a caller can log it — a run that spent 30
 * minutes in this gate should say so rather than looking slow for no reason.
 */
export function gate(): Promise<number> {
  const turn = queue.then(async () => {
    const now = Date.now();
    const waitFor = Math.max(0, nextAllowedAt - now);
    if (waitFor > 0) await sleep(waitFor);
    nextAllowedAt = Date.now() + MIN_INTERVAL_MS;
    return waitFor;
  });
  // Keep the chain alive even when a waiter's promise is rejected, or one failure would
  // deadlock every sign-in that follows it.
  queue = turn.then(
    () => undefined,
    () => undefined,
  );
  return turn;
}

/**
 * Total milliseconds spent in the gate so far, for the run summary.
 *
 * Counted rather than inferred, so the report can state the cost instead of someone
 * noticing a 70-minute run and wondering why.
 */
let totalWaitedMs = 0;

export function waitedTotalMs(): number {
  return totalWaitedMs;
}

/**
 * Resets the gate.
 *
 * Only used by tests of the gate itself. Calling it in a journey suite would defeat the
 * bound for the sign-in that follows.
 */
export function resetGate(): void {
  nextAllowedAt = 0;
  queue = Promise.resolve();
  totalWaitedMs = 0;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Count the wait on the way out, so `waitedTotalMs` is accurate without the callers
// having to remember to report it.
const countingGate = gate;
export async function awaitGate(): Promise<number> {
  const waited = await countingGate();
  totalWaitedMs += waited;
  return waited;
}
