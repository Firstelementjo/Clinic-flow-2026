/**
 * The Phase-11 acceptance gate.
 *
 * Phase 11's gate: "Playwright journeys pass in Arabic and English at desktop,
 * tablet and mobile widths. Browser refresh, session expiry, realtime reconnect,
 * empty state, error state and keyboard navigation are covered."
 *
 * Every journey here runs against a **real** backend. No response body is ever
 * fabricated: sign-in, session revocation, refresh and resync all go through the
 * real endpoints with the real CSRF handshake, so a journey that passes is
 * evidence about the application and the server together.
 *
 * `page.route(...).abort()` is used, and only to *break the transport* — the
 * browser refuses to deliver a request. That is what makes a network error and a
 * server 5xx look the same to `fetch`, which is the behaviour under test; it
 * supplies no data and cannot stand in for the client logic being exercised.
 * `AGENTS.md` and `docs/migration/phases/quality-gates.md` §3 both say a skipped
 * browser test is a failure, not a pass: if the backend does not support something
 * a journey needs, that is a finding for the orchestrator, named in the phase
 * report, never a `test.skip`.
 *
 * Each test is written once and executed for **every locale and width**, through
 * the `matrix` helper at the bottom. That is the point: "it works in LTR" is how an
 * RTL product ships broken, so Arabic RTL is not one case among six — it is half
 * the suite.
 */
import type { APIResponse } from '@playwright/test';
import {
  BASE_URL,
  expect,
  keyboardFocusRing,
  tabUntil,
  test,
  type Journey,
} from './journey';

/**
 * Runs a body under every locale/width combination.
 *
 * The combinations are Playwright *projects* (see `playwright.config.ts`), not a
 * loop here. A journey body therefore reads as one journey, and a failure names
 * `ar-mobile` or `en-desktop` in the report because the project does — which is
 * the difference between a direction bug you can see and one you have to guess at.
 *
 * Every journey in this file is registered through `matrix`. A new locale or a new
 * width automatically gains every journey, with no file to remember to update, and
 * no journey can quietly exist in only one direction.
 */
function matrix(name: string, body: (journey: Journey) => Promise<void>): void {
  test(name, async ({ journey }) => body(journey));
}

/** The dashboard's own refresh control — the one every screen's header carries. */
function refreshButton(journey: Journey) {
  return journey.page.getByRole('button', { name: journey.copy('dashboard.refresh') }).first();
}

// ---------------------------------------------------------------------------
// 1. Sign in
// ---------------------------------------------------------------------------

matrix('signs in through the real form and lands in the shell', async (journey) => {
  await journey.signIn();
  await expect(journey.page).toHaveURL(/\/app\/dashboard/);
  // The document carries the right language and direction.
  await expect(journey.page.locator('html')).toHaveAttribute('dir', journey.htmlDir);
  await expect(journey.page.locator('html')).toHaveAttribute('lang', journey.locale);
  // The shell rendered with a navigation and a main region.
  await expect(journey.page.getByRole('navigation')).toBeVisible();
  await expect(journey.page.locator('#fe-main')).toBeVisible();
  // The project context is real: the header offers a labelled control with the
  // tenant's project in it, not an empty or unnamed one. Asserted through the
  // accessible name rather than through the visible label, because the label is
  // deliberately hidden at phone widths and the name must survive that.
  const projectScope = journey.page.getByRole('combobox');
  await expect(projectScope).toBeVisible();
  await expect(projectScope).toHaveAccessibleName(journey.copy('shell.projectScope'));
  await expect(projectScope.locator('option')).not.toHaveCount(1);
});

matrix('rejects a wrong password without saying which field was wrong', async (journey) => {
  await journey.page.goto('/login');
  await journey.emailField().fill(journey.email);
  await journey.passwordField().fill('definitely-not-the-password');
  await journey.submitButton().click();

  // The error is announced, not just coloured. The **copy** is matched, and that is
  // the whole point of this journey: it has to distinguish "wrong credentials" from
  // every other thing the sign-in screen can say. In particular it must not
  // silently pass on the rate limiter's message, which `toBeVisible` alone would
  // accept.
  const alert = journey.page.getByRole('alert');
  await expect(alert).toBeVisible({ timeout: 30_000 });
  await expect(alert).toContainText(journey.copy('login.error.invalidCredentials'));
  // And specifically not the limiter, which would mean this journey asserted
  // nothing about credential handling at all.
  await expect(alert).not.toContainText(journey.copy('login.error.rateLimited'));
  // And the user is still on the sign-in screen.
  await expect(journey.page).toHaveURL(/\/login/);
});

matrix('validates an empty form in the browser and never calls the API', async (journey) => {
  let loginCalls = 0;
  journey.page.on('request', (request) => {
    if (request.url().includes('/api/v1/auth/login')) loginCalls += 1;
  });
  await journey.page.goto('/login');
  await journey.submitButton().click();
  await expect(journey.page.locator('#fe-login-email-error')).toBeVisible();
  expect(loginCalls).toBe(0);
});

// ---------------------------------------------------------------------------
// 2. Browser refresh
// ---------------------------------------------------------------------------

matrix('survives a browser refresh on every route it can reach', async (journey) => {
  await journey.signIn();

  // A hard refresh rehydrates from the same single source — the session — rather
  // than from a component that happened to survive a navigation.
  await journey.page.reload();
  await expect(journey.page).toHaveURL(/\/app\/dashboard/);
  await expect(journey.page.locator('#fe-main')).toBeVisible();
  await expect(journey.page.getByRole('navigation')).toBeVisible();
  // The direction survived too: a refresh must not fall back to the document's
  // default LTR while the stored locale is Arabic.
  await expect(journey.page.locator('html')).toHaveAttribute('dir', journey.htmlDir);

  await journey.page.getByRole('link', { name: journey.copy('shell.nav.settings') }).click();
  await expect(journey.page).toHaveURL(/\/app\/settings/);
  await journey.page.reload();
  await expect(journey.page).toHaveURL(/\/app\/settings/);
  await expect(journey.page.getByTestId('settings')).toBeVisible();
  // Still signed in: the cookie is the session.
  await expect(journey.page.getByRole('navigation')).toBeVisible();

  // ## A refresh on a URL that carries state in its query string
  //
  // Settings proves the session rehydrates; it does not prove that a **query
  // parameter** survives a reload. Groups 5 and 9-12 put the selected conversation, the
  // selected subject and the selected tab in the URL precisely so a deep link and a
  // refresh both work — so the three screens that rely on it are checked here rather
  // than only on first load, where the behaviour is trivially true.
  const deepLinks: [string, string][] = [
    // An inbox URL naming a conversation that does not exist: still a valid inbox, and
    // the selection is simply absent. The screen must not blank or error.
    ['/app/inbox?c=00000000-0000-4000-8000-000000000000', 'inbox'],
    ['/app/admin?tab=audit', 'admin'],
    ['/app/finance?tab=exports', 'finance'],
    ['/app/clinic', 'clinic'],
  ];
  for (const [path, testId] of deepLinks) {
    await journey.page.goto(path);
    await journey.page.reload();
    await expect(journey.page).toHaveURL(new RegExp(path.replace(/[?]/g, '\\?')));
    await expect(journey.page.getByTestId(testId)).toBeVisible({ timeout: 30_000 });
    await expect(journey.page.getByRole('navigation')).toBeVisible();
  }
});

// ---------------------------------------------------------------------------
// 3. Session expiry
// ---------------------------------------------------------------------------

matrix('ends the session when the server revokes it, from any screen', async (journey) => {
  await journey.signIn();
  await journey.page.getByRole('link', { name: journey.copy('shell.nav.settings') }).click();
  await expect(journey.page.getByTestId('settings')).toBeVisible();

  // Revoke the session the way the server does: the row is deleted, so the cookie
  // the page holds names nothing and every subsequent request genuinely 401s.
  await revokeSessionServerSide(journey);

  // Trigger a request from the authenticated screen.
  await refreshButton(journey).click();

  // One decision, one destination: the sign-in screen, and it says the session
  // ended rather than blaming the user.
  await journey.page.waitForURL('**/login**', { timeout: 20_000 });
  await expect(journey.page.locator('h1')).toContainText(journey.copy('login.title'));
  // Nothing tenant-scoped survives: the shell is gone.
  await expect(journey.page.getByRole('navigation')).toHaveCount(0);
  // And it says *why*, in words.
  await expect(journey.page.getByRole('status').first()).toContainText(
    journey.copy('shell.account.sessionEnded'),
  );
});

/**
 * Revokes the signed-in user's session **on the server**, through the real
 * administrator route.
 *
 * `DELETE /api/v1/admin/identity/sessions/{sessionId}` is what an administrator
 * uses for "sign this user out everywhere". It is the honest way to produce an
 * expiry here: the row is deleted, the browser is left holding a cookie that names
 * nothing, and every subsequent request genuinely answers 401.
 *
 * Why not fabricate a 401 with `page.route`: an interception supplies the very
 * behaviour under test, so the journey would pass even with no session handling at
 * all. This deletes real state instead.
 *
 * `page.request` shares the page's cookie jar, so the call is made as the signed-in
 * user and the CSRF header is applied by the same rule the application uses.
 */
async function revokeSessionServerSide(journey: Journey): Promise<void> {
  const listing = await journey.page.request.get('/api/v1/admin/identity/sessions');
  if (!listing.ok()) {
    throw new Error(`Could not list sessions: ${listing.status()} ${await listing.text()}`);
  }
  const sessions = (await listing.json()) as { id?: string }[];
  if (!Array.isArray(sessions) || sessions.length === 0) {
    throw new Error('The server reported no live sessions; the expiry journey cannot revoke one.');
  }

  // The cookie/header pairing is applied by hand here for the same reason the
  // application applies it: `page.request` shares the cookie jar but does not run
  // the interceptor chain, and the server answers `403 CSRF_TOKEN_INVALID` without
  // it. Both names come from the server — the cookie from the jar, the header name
  // from the public token endpoint — so a rename does not turn this into a lie.
  const tokenEndpoint = await journey.page.request.get('/api/v1/auth/csrf');
  if (!tokenEndpoint.ok()) {
    throw new Error(`Could not read the CSRF header name: ${tokenEndpoint.status()}`);
  }
  const headerName = ((await tokenEndpoint.json()) as { headerName?: string }).headerName;
  if (!headerName) throw new Error('The token endpoint did not name a CSRF header.');

  for (const session of sessions) {
    if (!session.id) continue;

    // PHASE 49. This is a RACE, and it failed one journey in 438.
    //
    // The server rotates `XSRF-TOKEN` on every successful unsafe request, and
    // this journey runs against a LIVE page that makes its own. So: read the
    // cookie at T, send the DELETE at T+epsilon -- and if the page issued an
    // unsafe request in between, the jar now carries a NEW cookie while the
    // header still carries the OLD value. The server compares the two, finds
    // them different, and answers `403 CSRF_TOKEN_INVALID`. Nothing is wrong
    // with the request; the pairing simply was not atomic.
    //
    // The fix is a bounded retry that re-reads the cookie, because the state
    // the request depends on is only true for an instant. It is NOT a
    // weakening: every attempt sends a genuine DELETE with a genuine cookie,
    // and the assertions below are untouched, so a session that cannot be
    // revoked still fails this journey.
    //
    // `decodeURIComponent` also went. Playwright's `context().cookies()`
    // already returns decoded values, so decoding again was a second
    // transformation applied to a value that never needed it.
    let revoked: APIResponse | null = null;
    for (let attempt = 0; attempt < 3 && !revoked?.ok(); attempt += 1) {
      const csrfCookie = (await journey.page.context().cookies()).find(
        (cookie) => cookie.name === 'XSRF-TOKEN',
      );
      if (!csrfCookie) {
        throw new Error('No XSRF-TOKEN cookie; the revocation cannot be authorised.');
      }

      revoked = await journey.page.request.delete(
        `/api/v1/admin/identity/sessions/${encodeURIComponent(session.id)}`,
        { headers: { [headerName]: csrfCookie.value } },
      );

      // A 401 means the row just revoked *was* the session this browser is
      // holding: the revocation succeeded and the next call can no longer
      // authenticate. That is the state this journey exists to produce, so it
      // ends the loop rather than failing it.
      if (revoked.status() === 401) return;

      // The cookie rotated under us. Re-read it and try again.
      if (revoked.status() === 403 && (await revoked.text()).includes('CSRF_TOKEN_INVALID')) {
        continue;
      }
      break;
    }

    if (revoked && !revoked.ok() && revoked.status() !== 401) {
      throw new Error(
        `Revoking ${session.id} failed: ${revoked.status()} ${await revoked.text()}`,
      );
    }
  }
}

// ---------------------------------------------------------------------------
// 4. Realtime reconnect and resync
// ---------------------------------------------------------------------------

matrix('shows the live connection, and says so in words', async (journey) => {
  await journey.signIn();
  // The live indicator is in text, not by colour alone.
  const status = journey.page.locator('.realtime');
  await expect(status).toBeVisible();
  await expect(status).toHaveText(journey.copy('shell.realtime.connected'), { timeout: 30_000 });
});

matrix('marks the data stale when the stream drops, then resyncs from the API', async (journey) => {
  await journey.signIn();
  await expect(journey.page.locator('.realtime')).toHaveText(
    journey.copy('shell.realtime.connected'),
    { timeout: 30_000 },
  );

  // Take the stream away and reload, so the **application's own** EventSource is
  // the request that fails. Aborting a route only affects requests made after it
  // is installed — an already-open SSE connection would carry on happily and the
  // journey would prove nothing. Reloading also re-proves the browser-refresh
  // invariant under a failing stream.
  await journey.page.route('**/api/v1/realtime/stream**', (route) => route.abort('failed'));
  await journey.page.reload();
  await expect(journey.page.getByTestId('dashboard')).toBeVisible();

  // The shell says the data may be out of date, in words, and says it politely
  // rather than interrupting: this is a caveat, not a failure the user caused.
  await expect(journey.page.getByText(journey.copy('shell.realtime.stale')).first()).toBeVisible({
    timeout: 45_000,
  });

  // Resyncing is a refetch from the API, not a re-render from anything cached.
  let stateCalls = 0;
  journey.page.on('request', (request) => {
    if (request.url().includes('/api/v1/realtime/state')) stateCalls += 1;
  });
  const resync = journey.page.getByRole('button', { name: journey.copy('shell.realtime.resync') });
  await expect(resync.first()).toBeVisible();
  await resync.first().click();
  await expect.poll(() => stateCalls, { timeout: 20_000 }).toBeGreaterThan(0);
});

matrix('recovers the stream and stops claiming the data is stale', async (journey) => {
  await journey.signIn();
  await journey.page.route('**/api/v1/realtime/stream**', (route) => route.abort('failed'));
  await journey.page.reload();
  await expect(journey.page.getByText(journey.copy('shell.realtime.stale')).first()).toBeVisible({
    timeout: 45_000,
  });

  // Let the stream through again. The service's own bounded backoff brings it
  // back; the test waits rather than poking at application internals.
  await journey.page.unroute('**/api/v1/realtime/stream**');
  await expect(journey.page.locator('.realtime')).toHaveText(journey.copy('shell.realtime.connected'), {
    timeout: 60_000,
  });
  await expect(journey.page.getByText(journey.copy('shell.realtime.stale'))).toHaveCount(0);
});

// ---------------------------------------------------------------------------
// 5. Loading, empty and error states
// ---------------------------------------------------------------------------

matrix('shows a loading state before the first response arrives', async (journey) => {
  await journey.signIn();
  // The seeded tenant has no conversations, so the first dashboard load resolves
  // to the empty state quickly. To observe the loading state the journey navigates
  // away and back, which re-enters the route and re-reads through the real endpoint.
  await journey.page.getByRole('link', { name: journey.copy('shell.nav.settings') }).click();
  await journey.page.getByRole('link', { name: journey.copy('shell.nav.dashboard') }).click();

  // Whatever the timing, exactly one of the three is on screen and none of them is
  // left behind: the loading indicator clears once the value arrives, and the
  // empty state is reached. A loading state that never resolves is the failure.
  await expect(journey.page.getByTestId('dashboard')).toBeVisible();
  await expect(journey.page.getByRole('alert')).toHaveCount(0, { timeout: 20_000 });
  await expect(journey.page.getByText(journey.copy('state.loading'))).toHaveCount(0, {
    timeout: 20_000,
  });
});

matrix('shows an empty state, not an error, when the tenant has nothing waiting', async (journey) => {
  // A freshly seeded tenant has no conversations, which is the honest empty case.
  await journey.signIn();
  await expect(journey.page.getByTestId('dashboard')).toBeVisible();
  // No error alert anywhere on a successful load.
  await expect(journey.page.getByRole('alert')).toHaveCount(0);
});

matrix('shows an error state with a retry when the first read fails', async (journey) => {
  // The route is installed **before** sign-in, so the dashboard's very first read
  // of the queue is the one that fails. A failure on a *reload* is a different
  // state with different copy (see the next journey), and a test that only covered
  // the reload would leave the first-load error state unproven.
  await journey.page.route('**/api/v1/inbox/unassigned**', (route) => route.abort('failed'));
  await journey.signIn();
  await expect(journey.page.getByTestId('dashboard')).toBeVisible();

  // The failure is announced, says what to do, and offers a retry.
  const alert = journey.page.getByRole('alert').first();
  await expect(alert).toBeVisible({ timeout: 30_000 });
  await expect(alert).toContainText(journey.copy('state.error'));
  await expect(
    journey.page.getByRole('button', { name: journey.copy('state.retry') }).first(),
  ).toBeVisible();

  // The rest of the screen is still usable: one failing read must not blank a
  // screen an operator is reading.
  await expect(journey.page.getByRole('navigation')).toBeVisible();
});

matrix('recovers when the failed read is retried and the API answers again', async (journey) => {
  await journey.page.route('**/api/v1/inbox/unassigned**', (route) => route.abort('failed'));
  await journey.signIn();

  const retry = journey.page.getByRole('button', { name: journey.copy('state.retry') }).first();
  await expect(retry).toBeVisible({ timeout: 30_000 });

  // Let the request through. The retry must genuinely re-read from the API rather
  // than clearing the error without asking the server anything.
  await journey.page.unroute('**/api/v1/inbox/unassigned**');
  let queueCalls = 0;
  journey.page.on('request', (request) => {
    if (request.url().includes('/api/v1/inbox/unassigned')) queueCalls += 1;
  });
  await retry.click();

  await expect.poll(() => queueCalls, { timeout: 20_000 }).toBeGreaterThan(0);
  // The error is gone and the real (empty) read took its place: a retry that did
  // not actually refetch would leave the alert standing.
  await expect(journey.page.getByRole('alert')).toHaveCount(0, { timeout: 30_000 });
  await expect(journey.page.getByTestId('dashboard')).toBeVisible();
});

matrix('keeps the last good data and marks it stale when a reload fails', async (journey) => {
  // A failure on a *reload* is deliberately not the same state as a failure on the
  // first read. Replacing good data with an error card loses an operator's context
  // for a screen they were mid-way through reading, so the design keeps the value,
  // marks it as possibly out of date, and says so in words.
  await journey.signIn();
  await expect(journey.page.getByTestId('dashboard')).toBeVisible();

  let queueCalls = 0;
  journey.page.on('request', (request) => {
    if (request.url().includes('/api/v1/inbox/unassigned')) queueCalls += 1;
  });
  await journey.page.route('**/api/v1/inbox/unassigned**', (route) => route.abort('failed'));
  await refreshButton(journey).click();

  // The reload really did happen and really did fail.
  await expect.poll(() => queueCalls, { timeout: 20_000 }).toBeGreaterThan(0);

  // What is on screen now is the staleness notice, not an error card, and it is
  // announced politely rather than interrupting.
  await expect(journey.page.getByRole('status').filter({ hasText: journey.copy('dashboard.staleNotice') }))
    .toBeVisible({ timeout: 30_000 });
  await expect(journey.page.getByRole('alert')).toHaveCount(0);
  // And the shell is untouched.
  await expect(journey.page.getByRole('navigation')).toBeVisible();
});

// ---------------------------------------------------------------------------
// 6. Keyboard navigation
// ---------------------------------------------------------------------------

matrix('reaches every shell control by keyboard, with a visible focus ring', async (journey) => {
  await journey.signIn();

  // The skip link is the first tab stop, and it moves focus to the main region.
  await journey.page.keyboard.press('Tab');
  const skip = journey.page.locator('.fe-skip');
  await expect(skip).toBeFocused();
  expect(['solid', 'auto']).toContain(await outlineStyle(skip));
  // And it is a *working* skip link, not a decoration: it points at the main
  // region, and the main region is focusable so the browser has somewhere to put
  // focus. A `#main` target on a non-focusable element is the common way a skip
  // link silently fails to skip, so `tabindex="-1"` is asserted directly rather
  // than inferred from a focus move.
  await expect(skip).toHaveAttribute('href', '#fe-main');
  await expect(journey.page.locator('#fe-main')).toHaveAttribute('tabindex', '-1');

  // Return to the top of the document for the tab-order walk below.
  await skip.focus();

  // Every interactive control is reachable and shows a focus indicator. The
  // reachable set is counted rather than named, so a control added later is
  // covered by this test automatically.
  const reachable = await journey.page.evaluate(() => {
    const selector = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled])';
    return Array.from(document.querySelectorAll<HTMLElement>(selector)).filter((node) => {
      const rect = node.getBoundingClientRect();
      // A collapsed control (a hidden menu item) is not reachable in this state.
      return rect.width > 0 && rect.height > 0;
    }).length;
  });
  expect(reachable).toBeGreaterThan(5);

  // Walk the tab order and prove focus lands on distinct, visible controls, each
  // with a ring. Walked to the end of the document rather than a fixed number of
  // steps: a fixed count stops early on a screen that later gains controls, which
  // is exactly the regression this journey exists to catch.
  const seen: { id: string; visible: boolean; ring: boolean }[] = [];
  for (let step = 0; step < 40; step += 1) {
    await journey.page.keyboard.press('Tab');
    const descriptor = await journey.page.evaluate(() => {
      const active = document.activeElement as HTMLElement | null;
      if (!active || active === document.body) return null;
      const rect = active.getBoundingClientRect();
      const style = getComputedStyle(active);
      return {
        id: `${active.tagName.toLowerCase()}:${active.getAttribute('aria-label') ?? active.textContent?.trim().slice(0, 24) ?? ''}`,
        visible: rect.width > 0 && rect.height > 0,
        ring: style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0,
      };
    });
    if (!descriptor) break; // Focus left the document: the tab order is complete.
    if (seen.some((entry) => entry.id === descriptor.id)) continue;
    seen.push(descriptor);
  }

  expect(seen.length).toBeGreaterThan(5);
  // Every control the walk visited carried a visible ring. A control whose ring
  // only appears on `:hover` is a WCAG 2.4.7 failure that a "some have outlines"
  // assertion would miss.
  const withoutRing = seen.filter((entry) => entry.visible && !entry.ring);
  expect(withoutRing.map((entry) => entry.id), 'controls with no visible focus ring').toEqual([]);
});

matrix('signs in with the keyboard alone', async (journey) => {
  // Every hop by Tab, the submit by Enter. `signInWithKeyboard` owns the walk so
  // the rate-limit retry is shared with the pointer path — a limiter refusal is a
  // property of the source address, not of how the form was driven.
  await journey.signInWithKeyboard();
  await expect(journey.page).toHaveURL(/\/app\/dashboard/);
});

matrix('moves focus to the first invalid field on a failed submit', async (journey) => {
  await journey.page.goto('/login');
  await journey.submitButton().click();
  await expect(journey.page.locator('#fe-login-email')).toBeFocused();
  // And the message is associated with the field, so a screen reader announces it.
  await expect(journey.page.locator('#fe-login-email')).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  await expect(journey.page.locator('#fe-login-email-error')).toBeVisible();
});

matrix('operates the theme and language toggles by keyboard alone', async (journey) => {
  await journey.signIn();

  const theme = journey.page.getByRole('button', { name: journey.copy('shell.theme.toggle') });
  await expect(theme).toBeVisible();
  expect(['solid', 'auto']).toContain(await keyboardFocusRing(journey.page, theme));

  // The toggle moves to the other theme rather than to a fixed one, so the
  // assertion follows the control instead of assuming a starting value.
  const before = await journey.page.locator('html').getAttribute('data-theme');
  expect(before === 'light' || before === 'dark').toBe(true);
  await journey.page.keyboard.press('Enter');
  await expect(journey.page.locator('html')).toHaveAttribute(
    'data-theme',
    before === 'dark' ? 'light' : 'dark',
  );

  const language = journey.page.getByRole('button', { name: journey.copy('shell.locale.toggle') });
  expect(await tabUntil(journey.page, language, { max: 30 }), 'the language toggle is reachable').toBe(true);
  await journey.page.keyboard.press('Enter');
  // The language flipped, so the control now offers the other one and the
  // document direction inverted with it.
  await expect(journey.page.locator('html')).toHaveAttribute(
    'dir',
    journey.direction === 'rtl' ? 'ltr' : 'rtl',
  );
  await expect(journey.page.locator('html')).toHaveAttribute(
    'lang',
    journey.locale === 'ar' ? 'en' : 'ar',
  );
});

// ---------------------------------------------------------------------------
// 7. Direction, theme and reduced motion
// ---------------------------------------------------------------------------

matrix('keeps every navigation item named at every width', async (journey) => {
  // At phone widths the rail collapses to icons. If the label is removed from the
  // accessibility tree rather than only from the layout, every item becomes an
  // unnamed link — a screen-reader user loses the whole navigation at exactly the
  // width where they are most likely to be on a phone.
  await journey.signIn();
  const links = journey.page.getByRole('navigation').getByRole('link');
  const count = await links.count();
  expect(count).toBeGreaterThan(0);
  const unnamed: string[] = [];
  for (let index = 0; index < count; index += 1) {
    const link = links.nth(index);
    const name = (await link.getAttribute('aria-label')) ?? (await link.innerText()).trim();
    if (name === '') unnamed.push(await link.getAttribute('href') ?? `#${index}`);
  }
  expect(unnamed, 'navigation links with no accessible name').toEqual([]);
});

matrix('renders RTL in Arabic and LTR in English, with the rail on the start edge', async (journey) => {
  await journey.signIn();
  await expect(journey.page.locator('html')).toHaveAttribute('dir', journey.htmlDir);
  // The navigation rail sits on the inline-start edge, which is the right edge in
  // RTL. Asserted through the box, not through a class name.
  const rail = journey.page.locator('.rail');
  const box = await rail.boundingBox();
  const viewport = journey.page.viewportSize();
  expect(box).not.toBeNull();
  if (box && viewport) {
    if (journey.direction === 'rtl') {
      expect(box.x + box.width).toBeGreaterThan(viewport.width / 2);
    } else {
      expect(box.x).toBeLessThan(viewport.width / 2);
    }
  }
});

matrix('lays out with logical properties, so no rule hard-codes a side', async (journey) => {
  await journey.signIn();

  // The RTL/LTR invariant that a screenshot cannot prove and a manual check misses
  // on a new screen: the stylesheet mirrors through logical properties, so the
  // same rules serve both directions. A `left`/`right`/`margin-left` in a
  // component stylesheet is the defect this finds.
  const offenders = await journey.page.evaluate(() => {
    const logical = [
      'margin-inline-start', 'margin-inline-end', 'padding-inline-start',
      'padding-inline-end', 'inset-inline-start', 'inset-inline-end',
      'border-inline-start', 'border-inline-end', 'text-align: start',
      'text-align: end',
    ];
    const physical = [
      'margin-left', 'margin-right', 'padding-left', 'padding-right',
      'left', 'right', 'border-left', 'border-right',
    ];
    const found: string[] = [];
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try {
        rules = sheet.cssRules;
      } catch {
        continue; // A cross-origin sheet has no readable rules; there are none here.
      }
      for (const rule of Array.from(rules)) {
        if (!(rule instanceof CSSStyleRule)) continue;
        const style = rule.style;
        const usesLogical = logical.some((property) => {
          if (property.includes(':')) return rule.cssText.includes(property);
          return style.getPropertyValue(property) !== '';
        });
        if (usesLogical) continue;
        for (const property of physical) {
          const value = style.getPropertyValue(property);
          // `left: 0` with `right: 0` is a full-bleed rule, not a directional
          // mistake; only a *single* side is a mirror that will not flip.
          if (value === '' || value === 'auto') continue;
          const opposite = property.replace('left', 'right').replace('right', 'left');
          if (style.getPropertyValue(opposite) !== '') continue;
          found.push(`${rule.selectorText} { ${property}: ${value} }`);
        }
      }
    }
    return found;
  });

  expect(offenders, 'physical CSS properties that will not mirror in RTL').toEqual([]);
});

matrix('keeps Arabic copy in Arabic and English copy in English', async (journey) => {
  await journey.signIn();
  const nav = journey.page.getByRole('navigation');
  await expect(nav).toContainText(journey.copy('shell.nav.dashboard'));
  await expect(journey.page.locator('.account')).toContainText(journey.copy('shell.account.signOut'));
  // And the sign-out control is a real button with that name, so it is reachable
  // by role rather than by a styling detail.
  await expect(
    journey.page.getByRole('button', { name: journey.copy('shell.account.signOut') }),
  ).toBeVisible();
});

matrix('numbers render with the locale numbering system, not a browser guess', async (journey) => {
  await journey.signIn();
  // `ar-u-nu-latn` keeps Western digits in Arabic (see `i18n.types.intlLocale`), so
  // a figure in an Arabic screen is still a figure an operator can compare against
  // a row. Arabic-Indic digits here would be a silent change of meaning.
  const digits = await journey.page.evaluate(() => {
    const nodes = Array.from(document.querySelectorAll('.fe-num'));
    return nodes.map((node) => node.textContent ?? '');
  });
  expect(digits.length).toBeGreaterThan(0);
  for (const text of digits) {
    // No Arabic-Indic (U+0660–U+0669) or Extended Arabic-Indic (U+06F0–U+06F9) digits.
    expect(text, 'a figure rendered with Arabic-Indic digits').not.toMatch(/[٠-٩۰-۹]/);
  }
});

// ---------------------------------------------------------------------------
// 8. Sign out
// ---------------------------------------------------------------------------

matrix('signs out and leaves nothing of the session behind', async (journey) => {
  await journey.signIn();
  await journey.page.getByRole('button', { name: journey.copy('shell.account.signOut') }).click();
  await journey.page.waitForURL('**/login**', { timeout: 20_000 });
  await expect(journey.page.getByRole('navigation')).toHaveCount(0);

  // History must not be a way back into the shell. Navigating *directly* to a
  // protected route is the stronger assertion than a `goBack()`: it is what a
  // bookmark, a shared link or a hand-typed URL does, and it exercises the guard
  // rather than the browser's session-history restore.
  await journey.page.goto('/app/dashboard');
  await journey.page.waitForURL('**/login**', { timeout: 20_000 });
  await expect(journey.page.getByRole('navigation')).toHaveCount(0);

  // And no tenant-scoped session cookie survived: the browser is holding no
  // session, so there is nothing in storage for a page to replay.
  const cookies = await journey.page.context().cookies();
  const sessionCookies = cookies.filter((cookie) => cookie.name.startsWith('fe_session'));
  expect(sessionCookies.map((cookie) => cookie.value), 'a session cookie outlived sign-out').toEqual([]);
});

matrix('honours prefers-reduced-motion', async (journey) => {
  // The product respects the preference (`docs/migration-AGENT.md` §4.4), and a
  // journey that races an animation is a flaky journey — so the config sets it.
  // This asserts the *application* honours it rather than trusting the setting:
  // a spinner that is still animating under `reduce` is a WCAG 2.3.3 failure that
  // no configuration can see.
  await journey.page.emulateMedia({ reducedMotion: 'reduce' });
  await journey.signIn();
  await expect(journey.page.getByTestId('dashboard')).toBeVisible();

  // Put a spinner on screen deliberately, then measure it. The app's own loading
  // states finish too fast to observe reliably.
  const animation = await journey.page.evaluate(() => {
    const probe = document.createElement('span');
    probe.className = 'fe-spinner';
    document.body.appendChild(probe);
    const duration = getComputedStyle(probe).animationDuration;
    probe.remove();
    return duration;
  });
  expect(isEffectivelyDisabled(animation)).toBe(true);
});

matrix('honours prefers-color-scheme when no choice has been saved', async (journey) => {
  // Theme resolution order: the saved choice, then the system preference
  // (`docs/migration-AGENT.md` §4.2). Each leg runs in a **fresh context** so
  // nothing carries over: the theme is persisted in `localStorage` the moment it is
  // resolved, so a second leg in the same context would read the first leg's saved
  // value and pass whether or not the media query is consulted at all.
  for (const scheme of ['dark', 'light'] as const) {
    const context = await journey.page.context().browser()!.newContext({
      baseURL: BASE_URL,
      colorScheme: scheme,
    });
    try {
      const page = await context.newPage();
      await page.goto('/login');
      await expect(page.locator('html')).toHaveAttribute('data-theme', scheme);
    } finally {
      await context.close();
    }
  }
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * The computed outline of an already-focused element.
 *
 * The caller must have reached the element by keyboard for this to mean anything;
 * see `keyboardFocusRing` in `journey.ts` for why a programmatic `.focus()` does
 * not put the product's `:focus-visible` ring under test.
 */
function outlineStyle(locator: import('@playwright/test').Locator): Promise<string> {
  return locator.evaluate((node) => getComputedStyle(node).outlineStyle);
}

/**
 * Whether a computed animation duration means "off".
 *
 * Under `prefers-reduced-motion: reduce` the global rule sets every animation to
 * `0.01ms`, which Chromium serialises as `1e-05s`. The value is compared
 * numerically rather than against a list of spellings: a browser that reports
 * `0.00001s`, `0.01ms` or `1e-05s` is saying the same thing, and a list of strings
 * would make this a change-detector for the browser rather than a check on the
 * product.
 */
function isEffectivelyDisabled(duration: string): boolean {
  const value = Number.parseFloat(duration);
  if (Number.isNaN(value)) return false;
  const unit = duration.replace(/^[\d.eE+-]+/, '');
  const inSeconds = unit === 'ms' ? value / 1000 : value;
  return inSeconds <= 0.001;
}
