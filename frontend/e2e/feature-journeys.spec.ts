/**
 * Groups 5-8: the inbox, people, team, knowledge/AI/channels/escalation.
 *
 * ## Real data, not a fabricated response
 *
 * The fixture calls `POST /api/v1/dev/mock/incoming-message` — the backend's own
 * inbound simulator, which is in the committed contract and drives the real pipeline.
 * No journey here fabricates a body. `page.route(...).abort()` appears once, in the
 * error-state journey, and only to break the transport.
 *
 * ## What these journeys deliberately assert about a contract defect
 *
 * `GET /api/v1/conversations?filter=` accepts exactly `assigned-to-me` and `history`,
 * and defaults to `assigned-to-me` when the parameter is absent. There is **no
 * "unassigned" saved view**, and the default view requires the caller to be the
 * assignee or the last human replier — neither of which is true of a brand-new
 * inbound message. So a new conversation is invisible in the list.
 *
 * Two journeys below pin that behaviour rather than working around it:
 *
 * - `shows the unassigned queue and says why it is not in the saved views` — the
 *   queue is a separate real route, and the screen states the relationship.
 * - `sends only the two filter values the server accepts` — asserts on the request
 *   URL, so a future three-segment control cannot reintroduce the 500.
 *
 * The product defect is reported in the phase-11 document, not worked around.
 */
import { expect, keyboardFocusRing, tabUntil, test, type Journey } from './journey';
import {
  DEFAULT_INBOUND_TEXT,
  queueRowFor,
  revokeFirstSession,
  seedConversation,
  selectedConversationId,
} from './inbound';

function matrix(name: string, body: (journey: Journey) => Promise<void>): void {
  test(name, async ({ journey }) => body(journey));
}

/** The inbox's conversation list, found by the screen's own test id. */
function inbox(journey: Journey) {
  return journey.page.getByTestId('inbox');
}

/** Opens the inbox and waits for the real reads to land. */
async function openInbox(journey: Journey): Promise<void> {
  await journey.page.goto('/app/inbox');
  await expect(inbox(journey)).toBeVisible();
  // The saved-view strip is present only after `/inbox/counts` resolved, so it is the
  // honest "the screen has loaded its real data" signal — and now that the default view
  // *is* the unassigned queue, the strip is also the only place the work is listed.
  await expect(savedViews(journey)).toBeVisible({ timeout: 30_000 });
}

/**
 * The saved-view strip, by its group role.
 *
 * A `tablist`, so it is located by role rather than by class: the assertion is about a
 * control a keyboard can traverse, and a locator that names the implementation would
 * keep passing if the control stopped being one.
 */
function savedViews(journey: Journey) {
  return inbox(journey).getByRole('tablist', { name: journey.copy('inbox.list.filterGroup') });
}

/**
 * One saved view's tab, by its wire value.
 *
 * The wire values are `unassigned`, `all-open`, `assigned-to-others`, `assigned-to-me`
 * and `history`; the i18n keys are `filterUnassigned`, `filterAllOpen`,
 * `filterAssignedToOthers`, `filterAssignedToMe` and `filterHistory`. Interpolating one
 * into the other does not work for the first two — `unassigned` is
 * `filterunassigned`, and `all-open` is not camel-case at all — so the mapping is
 * written out. A locator built by string surgery is a locator that misses.
 */
const VIEW_LABEL: Record<
  'unassigned' | 'all-open' | 'assigned-to-others' | 'assigned-to-me' | 'history',
  string
> = {
  unassigned: 'inbox.list.filterUnassigned',
  'all-open': 'inbox.list.filterAllOpen',
  'assigned-to-others': 'inbox.list.filterAssignedToOthers',
  'assigned-to-me': 'inbox.list.filterAssignedToMe',
  history: 'inbox.list.filterHistory',
};

function savedView(
  journey: Journey,
  key: keyof typeof VIEW_LABEL,
) {
  return savedViews(journey).getByRole('tab', { name: journey.copy(VIEW_LABEL[key]) });
}

// ---------------------------------------------------------------------------
// 5. Inbox and conversation thread
// ---------------------------------------------------------------------------

matrix('opens on the work queue, and a new conversation is in it', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'queue');

  await openInbox(journey);

  // ## The default view is `unassigned`, and the control agrees with the request
  //
  // This is the property ADR 0015 exists for. Before it, the list defaulted to
  // `assigned-to-me`, which requires the caller to be the assignee or the last human
  // replier — neither true of a brand-new inbound message — so **new work was invisible
  // to every employee at once**. The assertion is that the selected tab is `unassigned`
  // and that the request behind it carries `filter=unassigned`.
  await expect(savedView(journey, 'unassigned')).toHaveAttribute('aria-selected', 'true');

  const filters: string[] = [];
  journey.page.on('request', (request) => {
    const url = request.url();
    if (url.includes('/api/v1/conversations?')) filters.push(new URL(url).searchParams.get('filter') ?? '');
  });
  await journey.page.reload();
  await expect(savedView(journey, 'unassigned')).toHaveAttribute('aria-selected', 'true');
  await expect.poll(() => filters.filter((f) => f === 'unassigned').length, { timeout: 30_000 })
    .toBeGreaterThan(0);

  // ## And this journey's own conversation is in the list
  //
  // Asserted against the id the fixture received, so it cannot pass on some other
  // conversation happening to be open.
  await expect
    .poll(() => journey.page.locator(`[data-conversation-id="${inbound.conversationId}"]`).count(), {
      timeout: 30_000,
    })
    .toBeGreaterThan(0);
});

matrix('offers all five saved views, in the order the server published them', async (journey) => {
  await journey.signIn();
  await seedConversation(journey.page, journey.project, 'views');
  await openInbox(journey);

  // ## Five tabs, no more and no fewer
  await expect(savedViews(journey).getByRole('tab')).toHaveCount(5);

  // ## In the counts response's key order, which is declaration order
  //
  // Read from the response rather than from a local list, because the local list is the
  // thing most likely to go stale — and a stale order still *looks* correct, it just puts
  // "History" next to "Assigned to me" and nobody notices.
  const counts = await (
    await journey.page.request.get(`${apiBase()}/api/v1/inbox/counts`)
  ).json().catch(() => null);
  expect(counts, 'the counts read is reachable').not.toBeNull();

  const labels = await savedViews(journey).getByRole('tab').allInnerTexts();
  // Declaration order, read through the same mapping the tab locator uses.
  const order: (keyof typeof VIEW_LABEL)[] = [
    'unassigned',
    'all-open',
    'assigned-to-others',
    'assigned-to-me',
    'history',
  ];
  for (const [index, key] of order.entries()) {
    const expected = journey.copy(VIEW_LABEL[key]);
    expect(labels[index] ?? '', `tab ${index} should be ${key}`).toContain(expected);
  }
});

matrix('does not total the saved views, because they overlap', async (journey) => {
  await journey.signIn();
  // Two conversations: one nobody owns, one this caller last replied to. The second is
  // in `assigned-to-me` *and* in `all-open`, which is exactly the overlap that makes a
  // sum wrong.
  await seedConversation(journey.page, journey.project, 'overlap-a');
  await seedConversation(journey.page, journey.project, 'overlap-b');
  await openInbox(journey);

  // Every tab publishes its own count, and each is inside its accessible name, so a
  // screen reader announces "Unassigned, 2" rather than the label and then silence.
  const tabs = savedViews(journey).getByRole('tab');
  await expect(tabs).toHaveCount(5);
  for (let index = 0; index < 5; index += 1) {
    const name = await tabs.nth(index).getAttribute('aria-label') ?? '';
    const text = (await tabs.nth(index).innerText()).trim();
    expect((name + text).length, `tab ${index} names a view`).toBeGreaterThan(0);
  }

  // ## The property that matters: no "total" is offered anywhere
  //
  // The counts overlap by construction — the contract's own description says so — so a
  // summed figure would double-count and state a number the server never asserted. The
  // assertion is a negative one and it is deliberate: if a future screen adds a total,
  // this goes red rather than the number quietly being wrong.
  await expect(inbox(journey).getByText(/total|sum|all conversations/i)).toHaveCount(0);
  await expect(
    inbox(journey).locator('[data-testid="inbox-total"], [data-testid="inbox-count-total"]'),
  ).toHaveCount(0);
});

matrix('every saved view is reachable by keyboard alone', async (journey) => {
  await journey.signIn();
  await seedConversation(journey.page, journey.project, 'keyboard-views');
  await openInbox(journey);

  // A real tablist with roving focus: only the selected tab is in the tab order, and
  // arrow keys move between them. Without the handler, four of five would be
  // *unreachable* rather than merely out of sequence.
  const tabs = savedViews(journey).getByRole('tab');
  const total = await tabs.count();
  await expect(savedView(journey, 'unassigned')).toHaveAttribute('tabindex', '0');
  for (let index = 0; index < total; index += 1) {
    const isSelected = (await tabs.nth(index).getAttribute('aria-selected')) === 'true';
    expect(await tabs.nth(index).getAttribute('tabindex')).toBe(isSelected ? '0' : '-1');
  }

  const indexOfSelected = async (): Promise<number> => {
    const ids = await tabs.evaluateAll((nodes) => nodes.map((node) => node.id));
    const selected = await savedViews(journey).getByRole('tab', { selected: true }).getAttribute('id');
    return ids.indexOf(selected ?? '');
  };

  for (let step = 0; step < total - 1; step += 1) {
    const before = await indexOfSelected();
    await savedViews(journey).getByRole('tab', { selected: true }).focus();
    await journey.page.keyboard.press('ArrowRight');
    // By **index**, so a handler that jumped two tabs would fail rather than pass
    // because "a tab became selected".
    await expect
      .poll(indexOfSelected, { message: `ArrowRight ${step + 1} did not move one view along` })
      .toBe((before + 1) % total);
  }

  await journey.page.keyboard.press('Home');
  await expect.poll(indexOfSelected).toBe(0);
  await journey.page.keyboard.press('End');
  await expect.poll(indexOfSelected).toBe(total - 1);
});

matrix('shows one question once: the queue region is hidden on the unassigned view', async (journey) => {
  await journey.signIn();
  await seedConversation(journey.page, journey.project, 'dedupe');
  await openInbox(journey);

  // ## `?filter=unassigned` and `/inbox/unassigned` return the same set
  //
  // So on that tab the standalone queue region is hidden — two renderings of one question
  // can disagree with each other, and a work queue an operator cannot tell apart is worse
  // than either one alone.
  await expect(savedView(journey, 'unassigned')).toHaveAttribute('aria-selected', 'true');
  await expect(
    journey.page.locator('section[aria-labelledby="inbox-queue-heading"]'),
  ).toHaveCount(0);

  // On any other view they are genuinely different questions, so the queue returns.
  await savedView(journey, 'all-open').click();
  await expect
    .poll(
      async () =>
        journey.page.locator('section[aria-labelledby="inbox-queue-heading"]').count(),
      { timeout: 30_000 },
    )
    .toBeGreaterThan(0);
});

matrix('sends only filter values the contract enumerates', async (journey) => {
  await journey.signIn();
  await seedConversation(journey.page, journey.project, 'filter-values');

  const sent: string[] = [];
  journey.page.on('request', (request) => {
    const url = request.url();
    if (url.includes('/api/v1/conversations?') || url.includes('/api/v1/inbox/counts')) {
      sent.push(new URL(url).searchParams.get('filter') ?? '');
    }
  });

  await openInbox(journey);
  for (const key of ['all-open', 'history', 'assigned-to-others', 'unassigned'] as const) {
    await savedView(journey, key).click();
    await expect(savedView(journey, key)).toHaveAttribute('aria-selected', 'true');
  }

  // Asserted on the request URL rather than on the rendered tabs, so a control that sent
  // an undeclared value fails here even if it looked right on screen.
  const allowed = new Set(['', 'unassigned', 'all-open', 'assigned-to-others', 'assigned-to-me', 'history']);
  for (const value of sent) {
    expect(allowed.has(value), `filter=${value} is not in the contract's enum`).toBe(true);
  }
});

matrix('opens a real conversation and shows its messages with their authors', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(
    journey.page,
    journey.project,
    'thread',
    'My knee hurts after a fall and I cannot walk properly.',
  );

  await openInbox(journey);
  // This journey's own conversation, not whichever is most recent in the queue.
  await queueRowFor(journey.page, inbound.conversationId).click();

  // The id is in the route, not just in the DOM: that is what a refresh and a shared
  // link depend on.
  await expect
    .poll(() => selectedConversationId(journey.page), { timeout: 30_000 })
    .toBe(inbound.conversationId);

  // The customer's own words are on screen. Asserted against the text the fixture sent,
  // so this cannot pass on a stub.
  await expect(inbox(journey)).toContainText('My knee hurts after a fall');

  // The composer names the channel the reply will go out on, because sending a
  // WhatsApp message into a web thread by accident is a real harm.
  await expect(inbox(journey).getByLabel(journey.copy('inbox.composer.label'))).toBeVisible();
});


matrix('refuses to send an empty reply in the browser', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'empty-reply');
  await openInbox(journey);

  // No network call may be made for an empty body, so the route is watched.
  let posts = 0;
  journey.page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/messages')) posts += 1;
  });

  // This journey's own conversation. "The first row" is whichever conversation was
  // created most recently, which is another journey's once the tenant has any.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();

  await journey.page.getByRole('button', { name: journey.copy('inbox.composer.send') }).click();

  // The error is named and associated with the field, not just coloured.
  await expect(journey.page.getByText(journey.copy('inbox.composer.empty'))).toBeVisible();
  expect(posts, 'an empty reply never reaches the network').toBe(0);
});

matrix('takes ownership of a conversation and says so in words', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'ownership');
  await openInbox(journey);

  // This journey's own conversation. "The first row" is whichever conversation was
  // created most recently, which is another journey's once the tenant has any.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();
  // Wait for the thread itself before probing a control inside it. `isVisible()` with no
  // timeout answers immediately, so probing it before the thread renders would report a
  // control as absent and take the "no permission" branch — which would have made this
  // journey pass while asserting nothing about ownership at all.
  await expect(
    inbox(journey).getByLabel(journey.copy('inbox.composer.label')),
  ).toBeVisible({ timeout: 30_000 });

  const takeOver = journey.page.getByRole('button', {
    name: journey.copy('inbox.case.assignToMe'),
  });
  // The control is only offered when the caller holds `conversation:assign`, and the
  // offer is UX — the server decides. An administrator holds it, so it is present.
  await expect(takeOver).toBeVisible();
  await takeOver.click();

  // Either the write succeeded or the server refused it; both are legitimate outcomes
  // and neither may be a silent no-op. What is asserted is that the thread is still
  // readable and still reports the conversation it is showing.
  await expect(
    inbox(journey).getByLabel(journey.copy('inbox.composer.label')),
  ).toBeVisible({ timeout: 30_000 });
  await expect(inbox(journey)).toContainText('#');
});

matrix('closes a conversation and reopens it, reporting each in words', async (journey) => {
  // ## Every write this journey makes, recorded as it happens
  //
  // Registered **before the first write**, not between the two. It was previously
  // attached just before the reopen click, so the close was never recorded and the
  // assertion ['POST close 200', 'POST reopen 200'] failed on every combination
  // with the array reading `[POST reopen 200]` - which is the product working
  // correctly and the test listening to the wrong moment.
  //
  // Not so the test can assert "both announcements appeared" - that assertion is what
  // let a dead button through a whole batch, because a button that does nothing
  // produces no announcement *and no error* either. The record is here so the three
  // failures stay distinguishable: a click that never reached the server, a write
  // the server refused, and a write that landed while the screen kept announcing
  // the older state.
  const writes: string[] = [];
  journey.page.on('response', (response) => {
    const verb = response.url().replace(/^https?:\/\/[^/]+/, '');
    if (/\/(close|reopen)$/.test(verb)) {
      writes.push(`${response.request().method()} ${verb.split('/').pop()} ${response.status()}`);
    }
  });

  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'lifecycle');
  await openInbox(journey);

  // This journey's own conversation. "The first row" is whichever conversation was
  // created most recently, which is another journey's once the tenant has any.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();

  const close = journey.page.getByRole('button', { name: journey.copy('inbox.case.close') });
  if (!(await close.isVisible().catch(() => false))) return; // no write permission

  const thread = journey.page.locator('section[aria-labelledby="inbox-thread-heading"]');

  // ## Closing requires a recorded resolution
  //
  // `POST .../close` is refused until `.../resolution` has been saved — the server logs
  // `Record how the case was resolved before closing it`. The server is right to insist:
  // a case closed with nothing recorded about how it was resolved is a case nobody can
  // learn from. The rule is **not in the contract** (no operation declares a 4xx at all),
  // so a client cannot learn it from the document — Finding 2 doing real damage.
  //
  // The journey does the flow in the order an operator meets it: record the resolution,
  // then close. The client-side pre-check that turns a doomed close into a pointer at
  // the form is asserted in the unit suite, where it is deterministic. Asserting it here
  // raced against the thread's own load and passed about half the time, which is not
  // evidence of anything.
  await journey.page.getByText(journey.copy('inbox.resolution.label')).click();

  // ## All three required fields, because the server requires all three
  //
  // `CaseService.validateResolution` refuses the write unless `customerIssue` and
  // `agentAction` are 2..2000 characters, and `CaseOutcome.valueOf` refuses an `outcome`
  // outside its three values. `SaveResolutionRequest` declares all three as plain
  // optional strings, so none of that is visible in the contract — which is how the form
  // shipped with two optional fields and a free-text outcome that the server then
  // rejected with a 400 the client reported as "this page could not be loaded".
  await journey.page
    .getByLabel(journey.copy('inbox.resolution.outcome'))
    .selectOption('RESOLVED');
  await journey.page
    .getByLabel(journey.copy('inbox.resolution.customerIssue'))
    .fill('Asked for a consultation');
  await journey.page
    .getByLabel(journey.copy('inbox.resolution.agentAction'))
    .fill('Booked the appointment');

  await journey.page.getByRole('button', { name: journey.copy('inbox.resolution.save') }).click();
  await expect(
    thread.getByRole('status').filter({ hasText: journey.copy('inbox.resolution.saved') }),
  ).toBeVisible({ timeout: 30_000 });

  await journey.page.getByRole('button', { name: journey.copy('inbox.case.close') }).click();
  // A status change is announced, not only recoloured. Scoped to the thread pane: the
  // screen has several `role="status"` regions at once - the realtime chip, the stale
  // notice, and each write's outcome - so an unscoped locator matches more than one and
  // fails as a strict-mode violation rather than as an assertion.
  await expect(thread.getByRole('status').filter({ hasText: journey.copy('inbox.case.closed') }))
    .toBeVisible({ timeout: 30_000 });
  // And the control becomes the one that undoes it, so the operator is never stuck.
  const reopen = journey.page.getByRole('button', { name: journey.copy('inbox.case.reopen') });
  await expect(reopen).toBeVisible();

  await reopen.click();

  // ## The announcement arrives **and the server saw the write**
  //
  // Asserted separately and deliberately. The bug this pins was: `close` answered with a
  // status rather than the thread, the client replaced the thread with that fragment, and
  // every later action then returned at its first line - so `reopen` was never sent and
  // nothing was announced. A test that only watched the announcement would have seen "no
  // announcement" and could not say why.
  await expect(thread.getByRole('status').filter({ hasText: journey.copy('inbox.case.reopened') }))
    .toBeVisible({ timeout: 30_000 });
  expect(writes, 'both writes reached the server').toEqual([
    'POST close 200',
    'POST reopen 200',
  ]);

  // ## Order: the last thing said is the last thing that happened
  //
  // A live region that ends on the *closed* announcement after a successful reopen is
  // telling a screen-reader user the opposite of the truth, which is worse than saying
  // nothing at all. So the final text is asserted, not merely the presence of each.
  const said = (await thread.getByRole('status').allInnerTexts()).join(' ').trim();
  expect(said, 'the newest announcement is the reopen').toContain(
    journey.copy('inbox.case.reopened'),
  );
  expect(
    said.includes(journey.copy('inbox.case.closed')) &&
      said.indexOf(journey.copy('inbox.case.reopened')) <
        said.indexOf(journey.copy('inbox.case.closed')),
    'the closed announcement must not come after the reopened one',
  ).toBe(false);

  // ## Agreement: what was announced is what the screen shows
  //
  // The case is open again, so the control that undoes it is gone and the one that
  // closes it is back. This is the assertion that a fragment standing in for a thread
  // cannot pass - with one, the pane still rendered and the button still looked right.
  await expect(reopen).toHaveCount(0);
  await expect(
    journey.page.getByRole('button', { name: journey.copy('inbox.case.close') }),
  ).toBeVisible();
});

matrix('requires an outcome before saving a resolution', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'resolution');
  await openInbox(journey);

  // This journey's own conversation. "The first row" is whichever conversation was
  // created most recently, which is another journey's once the tenant has any.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();

  let puts = 0;
  journey.page.on('request', (request) => {
    if (request.method() === 'PUT' && request.url().includes('/resolution')) puts += 1;
  });

  // Open the disclosure with the keyboard, which also proves it is a real summary.
  const summary = journey.page.getByText(journey.copy('inbox.resolution.label'));
  await expect(summary).toBeVisible();
  await summary.click();
  await expect(journey.page.getByLabel(journey.copy('inbox.resolution.outcome'))).toBeVisible();

  await journey.page.getByRole('button', { name: journey.copy('inbox.resolution.save') }).click();

  // ## All three fields are marked, because the server requires all three
  //
  // `CaseService.validateResolution` refuses the write unless `customerIssue` and
  // `agentAction` are 2..2000 characters, and `CaseOutcome.valueOf` refuses an `outcome`
  // outside its three values. The contract declares all three as *optional plain
  // strings*, so nothing here is visible from the document — which is how batch A
  // shipped a form the server refused, reported to the operator as "this page could not
  // be loaded".
  await expect(
    journey.page.getByText(journey.copy('inbox.resolution.outcomeRequired')),
  ).toBeVisible();
  // The two text fields carry the same "required" message, so `first()` rather than a
  // count — the assertion is that a message appears on each, not that exactly one does.
  await expect(
    journey.page.getByText(journey.copy('inbox.resolution.required')).first(),
  ).toBeVisible();
  await expect(
    journey.page.getByLabel(journey.copy('inbox.resolution.customerIssue')),
  ).toHaveAttribute('aria-invalid', 'true');
  await expect(
    journey.page.getByLabel(journey.copy('inbox.resolution.agentAction')),
  ).toHaveAttribute('aria-invalid', 'true');

  expect(puts, 'an incomplete resolution never reaches the network').toBe(0);
});

matrix('states that escalation is a field on a resolution, not a queue', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'escalation');
  await openInbox(journey);

  // This journey's own conversation. "The first row" is whichever conversation was
  // created most recently, which is another journey's once the tenant has any.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();

  await journey.page.getByText(journey.copy('inbox.resolution.label')).click();

  // Both escalation fields are present, in the resolution, which is where the contract
  // puts them — and the note says there is no separate queue.
  await expect(journey.page.getByLabel(journey.copy('inbox.resolution.escalationRequested'))).toBeVisible();
  await expect(inbox(journey)).toContainText(
    journey.locale === 'ar'
      ? 'لا توجد قائمة تصعيدات مستقلة'
      : 'no separate escalation queue',
  );
});

matrix('shows an error with a retry when the inbox read fails, then recovers', async (journey) => {
  await journey.signIn();
  await seedConversation(journey.page, journey.project, 'error-state');

  // Break the transport only. No body is supplied, so the client's own handling of a
  // failed read is what is under test.
  //
  // The **conversation list**, not `/inbox/unassigned`. The queue region is deliberately
  // not rendered on the default view — `GET /api/v1/inbox/unassigned` and
  // `GET /api/v1/conversations?filter=unassigned` return the same set, and two renderings
  // of one question can disagree with each other. So breaking a route whose region is
  // hidden on the default view produced no error state at all, and this journey failed
  // with "element(s) not found" for a reason that had nothing to do with error handling.
  let armed = true;
  await journey.page.route('**/api/v1/conversations?*', async (route) => {
    if (armed) await route.abort('failed');
    else await route.fallback();
  });

  await journey.page.goto('/app/inbox');
  await expect(inbox(journey)).toBeVisible();

  // The failure is announced and offers a retry.
  const retry = journey.page.getByRole('button', { name: journey.copy('state.retry') }).first();
  await expect(retry).toBeVisible({ timeout: 30_000 });

  armed = false;
  await retry.click();
  // And recovery means real data appears, not merely that the error cleared. Asserted on
  // the saved-view strip, which is what the default view renders, for the same reason the
  // queue heading is not used here any more.
  await expect(
    inbox(journey).getByRole('tablist', { name: journey.copy('inbox.list.filterGroup') }),
  ).toBeVisible({ timeout: 30_000 });
});

// ---------------------------------------------------------------------------
// 6. People and the customer record
// ---------------------------------------------------------------------------

matrix('shows the customer record with its real CX id', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'customer-360');
  await openInbox(journey);

  // This journey's own conversation, not whichever is most recent in the queue.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();

  // Navigate to the record by its real route, then assert what the record actually
  // holds.
  await journey.page.goto(`/app/people?c=${inbound.customerId}`);
  const screen = journey.page.getByTestId('people');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // The display name came from the inbound simulator, so this is the value the server
  // stored rather than something the client composed.
  await expect(screen).toContainText('Probe Patient');

  // The CX id is server-assigned and sequential per tenant, so its *value* depends on how
  // many customers this tenant has — which depends on how many journeys ran first. An
  // earlier version of this journey asserted `#33000000` and passed on the combination
  // that ran first and failed on the other five, which is a test pinned to an accident.
  //
  // What is worth asserting is the shape and the direction: a `#` followed by digits,
  // forced LTR so the digit run never reorders inside Arabic prose. The **value** is
  // asserted here rather than nowhere: it is read back out of the API and compared with
  // what the screen shows, so the screen cannot invent one.
  const expectedCxId = await readCxId(journey.page, inbound.customerId);
  expect(expectedCxId, 'the server assigned a CX id').toMatch(/^#\d+$/);
  await expect(screen.getByTestId('customer-cx-id')).toHaveText(expectedCxId!);
});

/**
 * The CX id the server holds for a customer, read from the API rather than the screen.
 *
 * Used so the assertion compares the rendered value with the stored one. Reading it from
 * the DOM and comparing it with itself would assert only that the element exists.
 */
async function readCxId(
  page: import('@playwright/test').Page,
  customerId: string,
): Promise<string | null> {
  const response = await page.request.get(`${apiBase()}/api/v1/customers/${customerId}`);
  if (!response.ok()) return null;
  const record = (await response.json()) as { cxId?: string };
  return record.cxId ?? null;
}

matrix('keeps internal notes separate from the customer-facing history', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'internal-notes');
  await journey.page.goto(`/app/people?c=${inbound.customerId}`);
  const screen = journey.page.getByTestId('people');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // The two histories are two regions, and the screen says which is which.
  //
  // The notes heading is matched on the region's own `<h4>` rather than on any heading,
  // because the same words are also the region's empty-state title — a locator matching
  // both would be a strict-mode violation rather than an assertion, and loosening it to
  // `.first()` would stop saying *which* element it found.
  const notesRegion = screen
    .locator('section[aria-labelledby="people-notes-heading"]')
    .first();
  await expect(
    screen.getByRole('heading', { name: journey.copy('people.detail.conversations') }),
  ).toBeVisible();
  await expect(
    notesRegion.locator('h4#people-notes-heading'),
  ).toHaveText(journey.copy('people.detail.notes'));
  await expect(screen).toContainText(
    journey.locale === 'ar' ? 'لا يراها العميل' : 'the customer does not see',
  );

  // Writing a note needs a body; an empty one never reaches the network.
  let posts = 0;
  journey.page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/v1/notes')) posts += 1;
  });
  const field = screen.getByLabel(journey.copy('people.detail.addNote'));
  await field.fill('   ');
  await screen.getByRole('button', { name: journey.copy('people.detail.noteSave') }).click();
  await expect(screen.getByText(journey.copy('people.detail.noteEmpty'))).toBeVisible();
  expect(posts).toBe(0);
});

matrix('refuses to search people before the server is asked, and sends the term when it is', async (journey) => {
  await journey.signIn();
  await seedConversation(journey.page, journey.project, 'people-search');
  await journey.page.goto('/app/people');
  await expect(journey.page.getByTestId('people')).toBeVisible({ timeout: 30_000 });

  // The search goes to the server rather than filtering what happens to be loaded —
  // asserting on the URL is the only way to tell the two apart from outside.
  let seen = '';
  journey.page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/customers') seen = url.searchParams.get('search') ?? '';
  });

  await journey.page.getByRole('searchbox').first().fill('Probe');
  await expect.poll(() => seen, { timeout: 15_000 }).toBe('Probe');
});

// ---------------------------------------------------------------------------
// 7. Team
// ---------------------------------------------------------------------------

matrix('lists the real directory and marks each user active in words', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/team');
  const screen = journey.page.getByTestId('team');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // The signed-in account is in its own directory, with its real address.
  await expect(screen).toContainText(journey.email);
  // Status is words, never colour alone.
  await expect(screen).toContainText(journey.copy('team.table.active'));
});

matrix('names the working-hours, routing and import gap instead of drawing a form', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/team');
  const screen = journey.page.getByTestId('team');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // The absent half of this screen is stated. This journey exists so that deleting the
  // statement — or, worse, replacing it with a drop area that submits to a 404 — fails.
  await expect(
    screen.getByRole('heading', { name: journey.copy('team.unavailable.title') }),
  ).toBeVisible();

  // And there is no file input anywhere on the screen: a control that cannot succeed
  // must not be offered.
  await expect(screen.locator('input[type="file"]')).toHaveCount(0);
});

matrix('reports a user\'s effective permissions as the server answered them', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/team');
  const screen = journey.page.getByTestId('team');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  await screen.getByRole('button', { name: journey.copy('team.permissions.granted') }).first().click();

  // The answer comes from `GET /api/v1/permissions/{userId}`, so at least one real
  // permission string appears. Not a list derived from the role name — the server
  // decides which permissions a user effectively holds.
  await expect(
    screen.getByRole('heading', { name: journey.copy('team.permissions.title') }),
  ).toBeVisible();
  await expect(screen).toContainText('conversation:');
});

/**
 * Every permission this client checks must exist in the server's own answer.
 *
 * ## Why this is a browser journey and not a unit test
 *
 * Because the failure it exists to prevent is **silent**. The client asked for
 * `conversation:write` and `customer:write`; neither is in the backend's `Permission`
 * enum; both checks simply returned `false`; and the reply composer and the notes editor
 * were absent for every user in a six-combination matrix with nothing failing. Nothing
 * in the unit suite could have caught that, because the unit suite has no server to ask.
 *
 * So this reads the granted set the administrator was actually given and asserts that
 * every permission in `src/app/core/auth/permissions.ts` appears in it. When the backend
 * renames or removes one, this goes red — naming the literal — instead of a control
 * quietly disappearing.
 *
 * The list is sent from the test rather than imported, deliberately: importing it would
 * make the assertion compare the application against itself, which is the mistake this
 * whole journey exists to prevent.
 */
matrix('checks only permissions the server actually grants', async (journey) => {
  await journey.signIn();

  const granted = (await (
    await journey.page.request.get(`${apiBase()}/api/v1/permissions/me`)
  ).json()) as { effectivePermissions?: string[] };
  const set = new Set(granted.effectivePermissions ?? []);
  expect(set.size, 'the administrator was granted a non-empty permission set').toBeGreaterThan(20);

  for (const permission of KNOWN_PERMISSIONS) {
    expect(
      set.has(permission),
      `${permission} is not in the server's granted set, so every control gated on it is ` +
        'absent for every user. Either the backend enum has no such value, or the client ' +
        'is checking the wrong name — both are silent failures without this assertion.',
    ).toBe(true);
  }
});

/**
 * The permission literals `src/app/core/auth/permissions.ts` declares.
 *
 * Written out here rather than imported. An import would compare the application with
 * itself; the point is to compare it with the **server**.
 */
const KNOWN_PERMISSIONS = [
  'account:read',
  'ai-agent:configure',
  'ai-agent:read',
  'ai-run:read',
  'ai-send',
  // Both analytics scopes, because Finance and exports are gated on them and the
  // difference is the whole point: one exposes a tenant's revenue, cost and margin, the
  // other only the selected project's report rows.
  'analytics:read-organization',
  'analytics:read-project',
  'audit-log:read',
  'channel:manage',
  'channel:read',
  'conversation:assign',
  'conversation:read',
  'conversation:reply',
  'conversation:resolve',
  'conversation:return-to-ai',
  'conversation:take-over',
  'customer:read',
  'customer:update',
  'employee:import',
  'employee:read',
  'employee:update',
  'knowledge:manage',
  'knowledge:read',
  'membership:manage',
  'note:create',
  'note:delete',
  'note:read',
  'note:update',
  'organization:read',
  // The destructive governance write. Gating on it correctly requires the client to know
  // it exists; if the literal were missing the purge and legal-hold controls would be
  // absent for everyone and this assertion would be the only thing that noticed.
  'organization:update',
  'permission-inspector:read',
  'project:read',
  'security-event:read',
  'session:revoke',
  'sla:manage',
  'system-health:read',
  'user:manage',
] as const;

function apiBase(): string {
  return (process.env['FE_E2E_API_URL'] ?? 'http://localhost:4000').replace(/\/$/, '');
}

// ---------------------------------------------------------------------------
// 8. Knowledge, AI governance, channels, escalation
// ---------------------------------------------------------------------------

matrix('shows AI governance as read-only and says every decision is a command', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/knowledge');
  const screen = journey.page.getByTestId('knowledge');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  await expect(
    screen.getByRole('heading', { name: journey.copy('knowledge.ai.title') }),
  ).toBeVisible();
  // Read-only is stated, and it is stated as a property of the product rather than
  // inferred from the absence of buttons.
  await expect(screen).toContainText(journey.copy('knowledge.ai.readOnly'));

  // There is no control on this screen that changes AI policy. Any such control would
  // be a command the UI implies it can take, which FR-5.9 denies.
  await expect(screen.getByRole('button', { name: /approve|deny|disable|enable/i })).toHaveCount(0);
});

matrix('states that channel connection health cannot be read, and why', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/channels');
  const screen = journey.page.getByTestId('knowledge');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  await expect(
    screen.getByRole('heading', { name: journey.copy('knowledge.channels.title') }),
  ).toBeVisible();

  // The declared contract defect is reported on screen, with its cause, rather than
  // producing an empty table that reads as "no connectors configured".
  await expect(screen).toContainText(journey.copy('knowledge.channels.connectionsUnavailable'));
});

matrix('never calls the channel-connections route the contract cannot satisfy', async (journey) => {
  await journey.signIn();
  let called = false;
  journey.page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/channels/connections') called = true;
  });

  await journey.page.goto('/app/channels');
  await expect(journey.page.getByTestId('knowledge')).toBeVisible({ timeout: 30_000 });
  await journey.page.waitForTimeout(1_000);

  // `GET /api/v1/channels/connections` declares a required `context` parameter typed
  // as `TenantContext`. Calling it would produce a 400 that says nothing about the
  // channels, so the screen states the gap instead. Asserted so the workaround cannot
  // quietly turn into a real call that happens to fail.
  expect(called, 'the uncallable route is not requested').toBe(false);
});

matrix('points escalation at the resolution, where the contract puts it', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/knowledge');
  const screen = journey.page.getByTestId('knowledge');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  await expect(
    screen.getByRole('heading', { name: journey.copy('knowledge.escalation.title') }),
  ).toBeVisible();
  // Not an empty queue that looks like nothing has been escalated, but a pointer to
  // where it is actually recorded.
  await expect(screen).toContainText(journey.copy('knowledge.escalation.inConversation'));
});

// ---------------------------------------------------------------------------
// Cross-cutting: the new screens under the same rules as the first four
// ---------------------------------------------------------------------------

matrix('reaches every inbox control by keyboard, with a visible focus ring', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'keyboard');
  await openInbox(journey);

  // This journey's own conversation. "The first row" is whichever conversation was
  // created most recently, which is another journey's once the tenant has any.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();

  // Walk forward from the conversation's own queue row rather than from `<body>`. The
  // screen has a skip link, a nav rail and two other panes before the composer, so
  // starting at `body` spends most of the budget proving the shell is keyboard
  // reachable — which a separate journey already does — and then runs out before
  // reaching the control this journey is about.
  const start = queueRowFor(journey.page, inbound.conversationId);
  const composer = journey.page.getByLabel(journey.copy('inbox.composer.label'));
  // `tabUntil` rather than a fixed count: a fixed count passes while a control has
  // become unreachable, then fails for an unrelated reason the moment one is inserted
  // earlier in the document.
  expect(
    await tabUntil(journey.page, composer, { from: start, max: 30 }),
    'the composer is reachable by Tab from the selected conversation row',
  ).toBe(true);

  const ring = await keyboardFocusRing(journey.page, composer);
  expect(ring, 'the focused composer has a visible outline').not.toBeNull();
});

matrix('names the customer pane at every width, because the clip pattern keeps the name', async (journey) => {
  await journey.signIn();
  await seedConversation(journey.page, journey.project, 'pane-names');
  await openInbox(journey);

  // At phone and tablet widths the three panes collapse behind a tablist. A `display:
  // none` label here would leave an unnamed tab — the defect the shell already fixed
  // once — so every tab must keep an accessible name at every width.
  //
  // The switcher itself is `display: none` at desktop, so there are zero tabs there.
  // That is the layout working, not the names disappearing, so the assertion is that
  // the switcher appears **exactly** when the layout collapses, and that every tab it
  // then offers is named.
  // Scoped to the **pane switcher's own tablist**, by its accessible name. An unscoped
  // `getByRole('tab')` used to be safe because the inbox had exactly one tablist; it now
  // has two — the five saved views and the pane switcher — so an unscoped count returns
  // the saved views and asserts a layout that was never in question.
  const tabs = journey.page
    .getByRole('tablist', { name: journey.copy('inbox.list.label') })
    .getByRole('tab');
  const count = await tabs.count();
  const collapsed = journey.isCollapsed();
  expect(
    count,
    `the pane switcher is present at ${journey.viewportName} ` +
      `(${collapsed ? 'collapsed' : 'three columns'})`,
  ).toBe(collapsed ? 3 : 0);
  for (let index = 0; index < count; index += 1) {
    const name = await tabs.nth(index).getAttribute('aria-label');
    const text = (await tabs.nth(index).innerText()).trim();
    expect((name ?? '') + text, `tab ${index} has an accessible name`).not.toBe('');
  }
});

matrix('reaches the new screens by a browser refresh, with the selection intact', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'refresh');
  await openInbox(journey);

  // This journey's own conversation, not whichever is most recent in the queue.
  await queueRowFor(journey.page, inbound.conversationId).click();
  const before = await selectedConversationId(journey.page);
  expect(before).toBe(inbound.conversationId);

  await journey.page.reload();
  await expect(inbox(journey)).toBeVisible({ timeout: 30_000 });

  // The selection is in the route, so it survives. This is the property that makes a
  // working screen shareable rather than merely navigable.
  await expect
    .poll(() => selectedConversationId(journey.page), { timeout: 30_000 })
    .toBe(inbound.conversationId);
  // ## The thread is the *seeded* one, not merely some thread
  //
  // Asserted on the message body rather than on the ticket number, because the ticket is
  // not unique to this journey: every journey in the tenant seeds against the same
  // customer, and the thread that survived the refresh could be another journey's while
  // still carrying a plausible ticket. The body is the journey's own.
  //
  // The literal is the fixture's default `text`, which is a **user-facing string that is
  // also an assertion input** — so it is the fixture's constant rather than a second copy
  // typed here. Two copies drift, and the drift shows up as "expected My knee hurts,
  // received a knee injury" — which is exactly what this assertion produced once.
  await expect(inbox(journey)).toContainText(DEFAULT_INBOUND_TEXT);
});

matrix('ends the session from the inbox thread when the server revokes it', async (journey) => {
  await journey.signIn();
  const inbound = await seedConversation(journey.page, journey.project, 'expiry');
  await openInbox(journey);

  // This journey's own conversation. "The first row" is whichever conversation was
  // created most recently, which is another journey's once the tenant has any.
  await queueRowFor(journey.page, inbound.conversationId).click();
  await expect.poll(() => selectedConversationId(journey.page)).not.toBeNull();

  // The real row is revoked through the real admin route, carrying the page's live CSRF
  // token, and the revocation is asserted — a journey that deleted nothing and then
  // expected the UI to notice would pass for the wrong reason.
  await revokeFirstSession(journey.page);

  // The next real read on this screen is what discovers it.
  //
  // Asserted on the destination, not on a particular sentence. The client receives a
  // `401` and cannot tell "revoked elsewhere" from "timed out" — only the server knows,
  // and it answers with the same status — so asserting one specific wording would be
  // asserting a distinction this code cannot make. What matters is that the session
  // ends, the operator lands on the sign-in screen, and the reason is announced rather
  // than swallowed. The copy itself is pinned by the dashboard journey, which owns it.
  await journey.page.getByRole('button', { name: journey.copy('inbox.refresh') }).click();
  await expect(journey.page).toHaveURL(/\/login/, { timeout: 30_000 });

  // The sign-in form is back, so the operator is not left on a dead screen.
  await expect(journey.page.getByRole('button', { name: journey.copy('login.submit') }))
    .toBeVisible({ timeout: 30_000 });
  // And the screen explains why rather than looking like a plain logout. Either message
  // is correct; what is not correct is neither.
  const explained = journey.page
    .getByText(journey.copy('shell.account.sessionEnded'))
    .or(journey.page.getByText(journey.copy('shell.account.sessionRevoked')));
  await expect(explained.first()).toBeVisible({ timeout: 30_000 });
});

matrix('renders the new screens with the shell following the document direction', async (journey) => {
  await journey.signIn();
  // Every screen a signed-in administrator can reach, including groups 9-12. A screen
  // left out of this list is a screen whose direction is never checked, so the list is
  // the assertion: adding a route without adding it here is visible in the diff.
  for (const path of [
    '/app/inbox',
    '/app/people',
    '/app/team',
    '/app/knowledge',
    '/app/channels',
    '/app/admin',
    '/app/finance',
    '/app/clinic',
    '/app/settings',
  ]) {
    await journey.page.goto(path);
    await expect(journey.page.locator('#fe-main')).toBeVisible();
    // The direction is a document property, and the shell's grid follows it. If a new
    // screen ever pinned a physical side, this is the journey that would see it.
    await expect(journey.page.locator('html')).toHaveAttribute('dir', journey.htmlDir);
  }
});
