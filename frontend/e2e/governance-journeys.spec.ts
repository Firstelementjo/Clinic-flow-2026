/**
 * Groups 9-12: administration, finance, exports, and clinic.
 *
 * ## Real data, and what "real" means where there is none
 *
 * The admin and finance journeys read the **real** backend through the real session.
 * Nothing is intercepted: the same constraint as groups 5-8, and for the same reason —
 * a mock that passes proves only that the mock works.
 *
 * The clinic journeys are the opposite case, and they are the point of group 12. There
 * is no clinic route, so what they assert is **absence**: that the screen exists, names
 * the routes it is waiting for, and shows no figure of any kind. A clinic screen of
 * invented appointments is the worst outcome available, because a receptionist reads it
 * and acts on it.
 *
 * ## What these journeys assert about the permission model
 *
 * That finance and exports are gated on `analytics:read-organization`, which is what
 * `FinanceController` declares on all twenty-four of its routes — not on a permission
 * named after our own navigation. And that clinic, which has no route, is nonetheless
 * reachable, because an unreachable gap teaches an operator nothing.
 */
import { expect, test, type Journey } from './journey';

function matrix(name: string, body: (journey: Journey) => Promise<void>): void {
  test(name, async ({ journey }) => body(journey));
}

/**
 * A section's own heading, scoped to that section's panel.
 *
 * Not `getByRole('heading', { name })` over the whole screen: the tab strip carries the
 * same words as its tab labels, so an unscoped heading locator resolves to two elements
 * and fails as a strict-mode violation rather than as an assertion. The panel is found
 * by its `aria-labelledby`, which is what the ARIA tabs pattern wires up anyway, so this
 * also proves the labelling is right.
 */
async function sectionHeading(
  screen: ReturnType<Journey['page']['getByTestId']>,
  prefix: 'admin' | 'finance',
  tabId: string,
) {
  const tab = screen.locator(`#${prefix}-tab-${tabId}`);
  const label = (await tab.textContent())?.trim() ?? '';
  // Awaited, and interpolated as a string: `getAttribute` returns a Promise, and
  // interpolating one yields the literal text `[object Promise]` — which fails as
  // "element not found" and reads like a broken `aria-labelledby`.
  const labelledBy = (await tab.getAttribute('id')) ?? '';
  return {
    // Scoped to the card that carries `class="fe-card__title"` **and** matched exactly.
    //
    // Two looser versions of this both fail, and for reasons worth recording:
    //
    // - scoped to the panel with `level: 3` matches the panel title *and* the
    //   empty-state heading, which is also an `<h3>` — "No exports." is a heading the
    //   async-state component renders.
    // - an unscoped `getByRole('heading', { name })` over the screen matches the tab
    //   strip too, since each tab label is a button carrying the same words.
    //
    // So the locator names the *card title*, exactly. The class is the screen's own
    // convention for "this heading titles a section", which is a stable thing to key on,
    // and `exact` stops a longer empty-state message from being a substring hit.
    locator: screen
      .locator(`[role="tabpanel"][aria-labelledby="${labelledBy}"]`)
      .locator('h3.fe-card__title', { hasText: new RegExp(`^${escapeRegExp(label)}$`) }),
    label,
  };
}

/** Escapes a literal for use inside a `RegExp`. */
function escapeRegExp(literal: string): string {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Records every API call the browser makes, so a journey can prove what it asked for. */
function recordApi(journey: Journey): string[] {
  const seen: string[] = [];
  journey.page.on('request', (request) => {
    const url = request.url();
    if (url.includes('/api/v1/')) seen.push(`${request.method()} ${url.replace(/^https?:\/\/[^/]+/, '')}`);
  });
  return seen;
}

// ---------------------------------------------------------------------------
// 9. Administration
// ---------------------------------------------------------------------------

matrix('administration reads the server, and names the writes it will not offer', async (journey) => {
  await journey.signIn();
  const calls = recordApi(journey);
  await journey.page.goto('/app/admin');
  const screen = journey.page.getByTestId('admin');
  await expect(screen).toBeVisible({ timeout: 30_000 });
  await expect(
    screen.getByRole('heading', { name: journey.copy('admin.overview.title') }),
  ).toBeVisible();

  // ## The screen read the real control plane
  //
  // Asserted on the requests rather than on the rendering, because "it showed something"
  // is also true of a placeholder. These are the routes `AdminReadModelController`
  // serves, and they are `GET`s.
  await expect
    .poll(() => calls.filter((call) => call.startsWith('GET /api/v1/admin/')).length, {
      timeout: 30_000,
    })
    .toBeGreaterThan(4);

  // ## And it issued no write at all
  //
  // The seven governance writes exist in the contract and are deliberately not called.
  // This is the assertion that catches a future "helpful" purge button.
  const writes = calls.filter((call) => !call.startsWith('GET '));
  expect(writes, `this screen must not write: ${writes.join(', ')}`).toEqual([]);

  // ## The decision is stated in the product, not only in a commit message
  await journey.page.getByRole('tab', { name: journey.copy('admin.commands.title') }).click();
  await expect(
    screen.getByRole('heading', { name: journey.copy('admin.writes.title') }),
  ).toBeVisible({ timeout: 30_000 });
});

matrix('administration says a missing read model is missing, and shows no figure for it', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/admin');
  const screen = journey.page.getByTestId('admin');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  await journey.page.getByRole('tab', { name: journey.copy('admin.migrations.title') }).click();
  // The migrations route returns a free-form document with no declared shape. The screen
  // says so, names the route, and does not invent a column layout for it.
  await expect(
    screen.getByRole('heading', { name: journey.copy('admin.unavailable.migrations') }),
  ).toBeVisible({ timeout: 30_000 });
  await expect(screen.getByText('GET /api/v1/admin/migrations').first()).toBeVisible();

  // The purge is named as absent, with its route — the operator can file it.
  await journey.page.getByRole('tab', { name: journey.copy('admin.governance.title') }).click();
  await expect(
    screen.getByRole('heading', { name: journey.copy('admin.unavailable.purge') }),
  ).toBeVisible({ timeout: 30_000 });
  await expect(
    screen.getByText('POST /api/v1/admin/governance/maintenance/purge').first(),
  ).toBeVisible();
});

matrix('the audit-trail link arrives on the audit tab, not on the overview', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/dashboard');
  const auditLink = journey.page.getByRole('link', { name: journey.copy('shell.nav.audit') });
  await expect(auditLink).toBeVisible({ timeout: 30_000 });
  await auditLink.click();

  // Two entries share one route, so the entry has to say which tab it means. Landing on
  // the overview would send an operator looking for the thing they were sent to.
  await expect(journey.page).toHaveURL(/[?&]tab=audit/);
  const screen = journey.page.getByTestId('admin');
  await expect(screen).toBeVisible({ timeout: 30_000 });
  await expect(
    screen.getByRole('heading', { name: journey.copy('admin.audit.title') }),
  ).toBeVisible();
});

matrix('every administration tab is reachable with the keyboard alone', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/admin');
  const screen = journey.page.getByTestId('admin');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // ## The strip has all eight subjects, and says so before anything else
  //
  // Counted *before* the traversal, because the traversal itself has to wrap and a
  // silently short strip turns "pressed ArrowRight four times" into "wrapped to the
  // first tab" — which then fails somewhere unrelated, naming the wrong thing.
  const EXPECTED = [
    'admin.overview.title',
    'admin.backups.title',
    'admin.migrations.title',
    'admin.commands.title',
    'admin.audit.title',
    'admin.governance.title',
    'admin.sessions.title',
    'admin.jobs.title',
  ];
  await expect(screen.getByRole('tab')).toHaveCount(EXPECTED.length);
  for (const key of EXPECTED) {
    await expect(screen.getByRole('tab', { name: journey.copy(key), exact: true })).toBeVisible();
  }

  // A real `tablist` with roving focus: **only the selected tab is in the tab order**,
  // which is what the ARIA tabs pattern requires and what makes arrow-key traversal
  // meaningful rather than tabbing through eight controls to reach the seventh.
  //
  // So each tab is reached with arrow keys from the selected one, not with Tab. A tab
  // that is only reachable by Tab would be a tab that is not really part of the widget.
  const tabs = screen.getByRole('tab');
  const total = await tabs.count();

  await expect(screen.getByRole('tab', { selected: true })).toHaveAttribute('tabindex', '0');
  for (let index = 0; index < total; index += 1) {
    const tab = tabs.nth(index);
    const isSelected = (await tab.getAttribute('aria-selected')) === 'true';
    expect(await tab.getAttribute('tabindex'), `tab ${index} tabindex`).toBe(
      isSelected ? '0' : '-1',
    );
  }

  // ## Each press advances by exactly one, and the panel follows
  //
  // Checked by *index* rather than by "some panel appeared": a handler that jumped two
  // would still show a visible panel, and only the index says which one.
  const indexOf = async (id: string | null): Promise<number> => {
    const ids = await tabs.evaluateAll((nodes) => nodes.map((node) => node.id));
    return ids.indexOf(id ?? '');
  };

  for (let step = 0; step < total - 1; step += 1) {
    const before = await indexOf(await screen.getByRole('tab', { selected: true }).getAttribute('id'));
    await screen.getByRole('tab', { selected: true }).focus();
    await journey.page.keyboard.press('ArrowRight');

    await expect
      .poll(
        async () => indexOf(await screen.getByRole('tab', { selected: true }).getAttribute('id')),
        { message: `ArrowRight ${step + 1} did not move one tab along` },
      )
      .toBe((before + 1) % total);

    // And the panel that owns it is on screen and visible — selection and content move
    // together, which is the property a keyboard user would otherwise lose.
    const focused = await screen.getByRole('tab', { selected: true }).getAttribute('id');
    await expect(
      screen.locator(`[role="tabpanel"][aria-labelledby="${focused}"]`),
      `no panel for ${focused}`,
    ).toBeVisible();
  }

  // Home and End jump to the ends, as the pattern requires.
  await journey.page.keyboard.press('End');
  await expect
    .poll(async () => indexOf(await screen.getByRole('tab', { selected: true }).getAttribute('id')))
    .toBe(total - 1);
  await journey.page.keyboard.press('Home');
  await expect
    .poll(async () => indexOf(await screen.getByRole('tab', { selected: true }).getAttribute('id')))
    .toBe(0);

  // And the strip itself is one Tab stop, not eight. That is the point of roving
  // focus: a `tablist` is a single stop whose position the operator moves with arrows.
  const stop = screen.getByRole('tab', { selected: true });
  await stop.focus();
  expect(await journey.page.evaluate(() => document.activeElement?.getAttribute('role'))).toBe('tab');
  await journey.page.keyboard.press('Tab');
  // Focus left the strip rather than walking to the next tab.
  expect(await journey.page.evaluate(() => document.activeElement?.getAttribute('role'))).not.toBe(
    'tab',
  );
});

// ---------------------------------------------------------------------------
// 10 and 11. Finance and exports
// ---------------------------------------------------------------------------

matrix('finance reads the priced surfaces the server exposes', async (journey) => {
  await journey.signIn();
  const calls = recordApi(journey);
  await journey.page.goto('/app/finance');
  const screen = journey.page.getByTestId('finance');
  await expect(screen).toBeVisible({ timeout: 30_000 });
  await expect(
    (await sectionHeading(screen, 'finance', 'usage')).locator,
  ).toBeVisible();

  // The real routes, not a plausible set of them.
  await expect
    .poll(
      () =>
        calls.filter(
          (call) =>
            call.startsWith('GET /api/v1/finance/') ||
            call.startsWith('GET /api/v1/exports/'),
        ).length,
      { timeout: 30_000 },
    )
    .toBeGreaterThan(3);

  // Locking a period or approving an adjustment changes figures that are already fixed.
  // No write is offered, so none is sent.
  const writes = calls.filter((call) => !call.startsWith('GET '));
  expect(writes, `the finance screen must not write: ${writes.join(', ')}`).toEqual([]);

  await expect(calls).toContain('GET /api/v1/finance/periods');
  await expect(calls.some((call) => call.startsWith('GET /api/v1/finance/rate-cards'))).toBe(true);
});

matrix('reads the catalogue the server holds, with no project it cannot scope', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/finance?tab=exports');
  await journey.selectProject();
  const screen = journey.page.getByTestId('finance');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // ## The catalogue read is a real, typed call that **requires** a project
  //
  // `GET /api/v1/exports/catalogue` declares `projectId` as required, so the screen does
  // not ask without one — it says it needs a project instead. And the section is not
  // offered at all until one is chosen, which is the honest state rather than a form
  // whose every request would be refused.
  // The picker appears only once the catalogue read lands, so the wait is on the control
  // rather than on the section: a section that renders an empty form while it waits looks
  // exactly like a screen that has nothing to offer.
  const picker = journey.page.locator('#fe-export-definition');
  await expect(picker, 'the create form is offered once a project is selected')
    .toBeVisible({ timeout: 30_000 });
  // Scoped to the create section: other panes on this screen legitimately show a loading
  // state at the same time, so an unscoped count would race against them.
  const catalogueFacts = journey.page.locator('#finance-create-title').locator('xpath=../../..')
    .getByText(journey.copy('finance.catalogue.definitionsCount'));
  await expect(catalogueFacts).toBeVisible({ timeout: 30_000 });

  // ## The formats and locales on offer are the catalogue's own
  //
  // Read from the catalogue's rendered facts rather than from the `<option>` values. The
  // document declares `formats` and `locales` as bare string arrays while the wire types
  // them as closed unions, so what matters is that the screen offers what the **server**
  // declared and the client is willing to send — and the facts are the server's words
  // with no serialisation in between.
  const facts = journey.page.locator('dl.facts').first();
  await expect(
    facts.getByText(journey.copy('finance.catalogue.formats')),
  ).toBeVisible({ timeout: 30_000 });
  await expect(facts).toContainText('CSV');
  await expect(facts).toContainText('DOCX');

  // ## And nothing the server refuses is offered anywhere in the create form
  //
  // `XLSX`, `PDF` and `JSON` exist in the database's enum and are refused with 422
  // `EXPORT_FORMAT_UNSUPPORTED`. Asserted across the whole region rather than on one
  // select, because the property is "the operator cannot choose one of these anywhere" —
  // which is exactly the guarantee a single-select check would not give.
  const create = journey.page.locator('section[aria-labelledby="finance-create-title"]');
  await expect(create).toBeVisible();
  const options = await create.locator('option').allInnerTexts();
  for (const refused of ['XLSX', 'PDF', 'JSON']) {
    expect(
      options.some((text) => text.trim().toUpperCase() === refused),
      `${refused} is refused by the server with 422 and must not be offered`,
    ).toBe(false);
  }
});

matrix('never offers an export form the server cannot fulfil', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/finance?tab=exports');
  await journey.selectProject();
  const screen = journey.page.getByTestId('finance');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // ## This journey used to assert the empty catalogue, and no longer does — said plainly
  //
  // It was named *"says the catalogue is empty rather than offering an unusable form"*, and
  // its first assertion was that the empty state was shown. That rested on a premise that is
  // now **false in two independent ways**:
  //
  // 1. the premise in its own comment — *"`report_definitions` and `document_templates` are
  //    created by migration `V20261005060000` with no rows inserted, **and no route creates
  //    them**"* — died in Phase 32. Thirteen authoring routes now create them;
  // 2. the e2e fixture (`e2e/authoring.seed.ts`) **seeds two published definitions and a
  //    document template into every tenant**, which is what makes export journeys able to
  //    test anything at all.
  //
  // **The seed was not weakened to accommodate this journey, and this assertion was not
  // deleted to accommodate the seed.** Asserting an empty catalogue on a seeded tenant would
  // be asserting a state the product deliberately no longer has.
  //
  // ### Coverage actually lost, stated rather than glossed
  //
  // The screen's **empty-catalogue state is no longer covered by any journey.** It is
  // unreachable from the e2e fixture without a project that holds no definitions, and every
  // matrix identity runs every journey against its own project — so un-seeding one project
  // would break that combination's export journeys instead of testing this one. The right
  // home for it is a component test, where the catalogue can be stubbed as empty
  // deterministically without reshaping the shared fixture. **That is outstanding work, not
  // a passing test**, and it is recorded in `docs/migration/phases/phase-11.md`.
  //
  // ### What this journey asserts instead
  //
  // Its real subject — the thing its name was reaching for — is *"never offer a form the
  // server cannot fulfil"*. Against a **populated** catalogue that is a stronger claim than
  // emptiness, and it is the one that matters now:
  //
  // 1. every definition offered carries a **server-issued id**, so choosing one always yields
  //    an id the server recognises and never a blank or invented one;
  // 2. the create region is present and explained, rather than hidden;
  // 3. every filter key the catalogue declared is still reported, because `fields` is
  //    populated independently of whether any definition exists.
  const definitionPicker = journey.page.locator('#fe-export-definition');
  await expect(definitionPicker, 'the create form offers the definitions the catalogue holds').toBeVisible({
    timeout: 30_000,
  });

  // **Polled, not read once** — the create form renders before the catalogue arrives, so
  // `toBeVisible` on the `<select>` proves the control exists and says nothing about whether
  // anything has been put in it. Reading once, immediately, is a race that loses whenever the
  // catalogue is slower than the form, and it loses as "the catalogue offered nothing", which
  // reads like a defect rather than like timing. The authoring journeys hit exactly this and
  // it cost most of a day before anyone read the failure properly.
  const offered = async (): Promise<{ value: string; text: string | null }[]> =>
    definitionPicker.locator('option').evaluateAll((nodes) =>
      nodes.map((node) => ({
        value: (node as HTMLOptionElement).value,
        text: (node as HTMLOptionElement).textContent,
      })),
    );
  await expect
    .poll(async () => (await offered()).length, {
      timeout: 20_000,
      message: 'the catalogue fills the definition picker',
    })
    .toBeGreaterThan(1);
  const options = await offered();
  // The first option is the placeholder and carries no id; everything after it must be a
  // server-issued one. An option with an empty value here is the "empty picker with entries
  // in it" shape, which is a defect and not a state.
  const choices = options.slice(1);
  expect(choices.length, 'the catalogue offered at least one definition to choose').toBeGreaterThan(0);
  for (const choice of choices) {
    expect(
      choice.value,
      `the option "${choice.text}" carries a server-issued id, so choosing it cannot produce a request the server refuses`,
    ).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(choice.text?.trim(), `the option for ${choice.value} has a label`).not.toBe('');
  }

  // Scoped to the create region: other buttons on the pane share the word, and an
  // unscoped role query would resolve to the tab strip as well.
  const create = journey.page.locator('section[aria-labelledby="finance-create-title"]');
  await expect(create).toBeVisible();
  // The create form is **present and labelled** even though nothing can be chosen from
  // it. Asserted as presence rather than as a disabled submit: the four identity fields
  // cannot be satisfied from an empty catalogue, so the useful property here is that the
  // screen explains itself instead of hiding the capability — and a journey cannot prove
  // the form is *unusable* without also depending on the validators being wired, which
  // is a unit-test property, not a browser one.
  await expect(create.getByRole('combobox', { name: journey.copy('finance.create.report') })).toBeVisible();
  await expect(create.getByText(journey.copy('finance.create.description'))).toBeVisible();

  // ## The catalogue declared filter keys, and this client offers exactly those
  //
  // `fields` is a catalogue-level array and **is** populated — 34 keys on a fresh tenant,
  // independent of whether any definition exists to apply them to. Read from the rendered
  // facts rather than from the controls, so the assertion is about the server's data and
  // not about how this particular form chooses to render it.
  const filtersTerm = screen.getByText(journey.copy('finance.create.filters')).first();
  await expect(filtersTerm, 'the catalogue facts list the filter count').toBeVisible({ timeout: 30_000 });
  const declared = Number((await filtersTerm.locator('xpath=following-sibling::dd[1]').innerText()).trim());
  expect(declared, 'the catalogue declared filter keys').toBeGreaterThan(0);
});
matrix('offers no export format the server refuses', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/finance?tab=exports');
  await journey.selectProject();
  const screen = journey.page.getByTestId('finance');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  const formatSelect = journey.page.locator('#fe-export-format');
  await expect(formatSelect).toBeVisible({ timeout: 30_000 });
  const values = await formatSelect.locator('option').evaluateAll((nodes) =>
    nodes.map((node) => (node as HTMLOptionElement).value).filter((value) => value !== ''),
  );

  // The server's refusal is 422 EXPORT_FORMAT_UNSUPPORTED for anything outside CSV and
  // DOCX, so the client offers exactly those and nothing else. Asserted on the **values**
  // rather than the labels, because a label can be localized and a value cannot.
  for (const value of values) {
    expect(['CSV', 'DOCX'], `${value} is refused by the server`).toContain(value);
  }
});

// ---------------------------------------------------------------------------
// 12. Clinic
//
// REWRITTEN in Phase 38. The two journeys below previously asserted that the
// clinic screen showed a GAP: a heading saying the feature was unavailable, a
// list of routes that would satisfy it, and `expect(table).toHaveCount(0)`.
// Phase 37 replaced that screen with a real read model, so those journeys began
// failing `expect(locator).toBeVisible()` -- they were asserting the ABSENCE of
// the thing the migration was built to add.
//
// They were rewritten, NOT deleted, and not weakened. The intent of the old pair
// was sound and is preserved exactly: a clinic screen showing something invented
// is the worst outcome available, because a receptionist reads it and acts on
// it. Only the meaning of "invented" changed -- from "any appointment at all" to
// "a revenue figure, a utilisation figure, or a patient's contact details",
// which are the three things this screen is now capable of faking and is
// therefore the thing worth asserting.
// ---------------------------------------------------------------------------

matrix('clinic shows the read model, and every metric drills to its source rows', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/clinic');
  const screen = journey.page.getByTestId('clinic');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // ## The three real subjects
  await expect(screen.getByTestId('clinic-doctors')).toBeVisible();
  await expect(screen.getByTestId('clinic-procedures')).toBeVisible();
  await expect(screen.getByTestId('clinic-schedule')).toBeVisible();

  // ## Provenance is rendered, not hidden behind a tooltip
  //
  // A number a reader cannot trace is a number they cannot judge, and this is
  // the surface where that judgement matters most. The requirement is that every
  // metric names the table it came from, so the assertion is on the provenance
  // line rather than on the metric's own value.
  const metrics = screen.locator('[data-testid^="clinic-metric-"]');
  await expect(metrics.first()).toBeVisible({ timeout: 30_000 });
  // `.first()` because a metric card carries TWO provenance lines -- which table it came from,
  // and how it was derived -- and both are part of the claim. Asserting on the first names the
  // source-table line specifically rather than "some provenance text exists somewhere".
  await expect(metrics.first().locator('.provenance').first()).toContainText(
    journey.copy('clinic.analytics.sourceTable'),
  );

  // ## And the drill-through actually drills
  //
  // This is the assertion that carries the whole read model. The ids must be
  // ABSENT before the control is used and PRESENT after, or the screen would be
  // rendering a number whose provenance nobody can open. Asserted as state
  // rather than as the presence of a button, because a button that does nothing
  // passes a weaker test.
  const disclosure = metrics.first().locator('button[aria-expanded]').first();
  await expect(disclosure).toBeVisible();
  await expect(disclosure).toHaveAttribute('aria-expanded', 'false');

  const controlledId = await disclosure.getAttribute('aria-controls');
  expect(controlledId, 'the control must name what it controls').toBeTruthy();
  // An ATTRIBUTE selector, not `#id`. The ids are built as `rows-<group>:<index>`,
  // which contains colons -- valid in an id but needing escaping in a CSS id selector, and
  // `CSS.escape` is a browser global that does not exist in this Node context.
  const controlled = screen.locator(`[id="${controlledId}"]`);
  await expect(controlled).toHaveCount(0);

  await disclosure.click();
  await expect(disclosure).toHaveAttribute('aria-expanded', 'true');
  const opened = screen.locator(`[id="${controlledId}"]`);
  await expect(opened).toBeVisible();
  await expect(opened.locator('.source-id').first()).toBeVisible();

  // Keyboard-operable, because a disclosure that needs a mouse is not one.
  await disclosure.press('Enter');
  await expect(disclosure).toHaveAttribute('aria-expanded', 'false');
});

matrix('clinic invents no revenue, no utilisation, and no patient contact', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/clinic');
  const screen = journey.page.getByTestId('clinic');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // ## The screen says what it will not do, rather than quietly omitting it
  const disclosures = screen.getByTestId('clinic-disclosures');
  await expect(disclosures).toBeVisible();
  for (const key of [
    'clinic.analytics.noRevenue',
    'clinic.analytics.noUtilization',
    'clinic.analytics.sampleNote',
  ]) {
    await expect(disclosures.getByText(journey.copy(key), { exact: false }).first()).toBeVisible();
  }

  // ## `appointment:read` names the schedule, not a patient
  //
  // Asserted directly rather than inferred from the absence of a phone number,
  // because "no leaked PII" is not provable by counting what is absent.
  await expect(screen.getByTestId('clinic-no-contact')).toBeVisible();

  // ## And no invented figure appears anywhere on the screen
  //
  // The legacy dashboard published `estimatedRevenueJod: Math.max(4850,
  // totalRevenue)`. A floor is a constant wearing a number's clothes. If any
  // screen ever shows a revenue total again, this is where it should fail.
  const body = await screen.innerText();
  expect(body, 'the legacy screen published a revenue floor; it must not come back').not.toMatch(
    /4850|estimatedRevenue/i,
  );
});

// RENAMED in Phase 38. This journey asserted that the clinic page shows no figure at
// every width, and it passed for the wrong reason: the screen had nothing on it. It
// now carries a schedule table, rosters and drill-through source ids, which is a much
// better overflow test than a route path was -- so the assertion is KEPT and only the
// name is corrected, because the name is what tells a future reader what is protected.
matrix('clinic does not overflow its width at any viewport, including the narrowest', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/clinic');
  const screen = journey.page.getByTestId('clinic');
  await expect(screen).toBeVisible({ timeout: 30_000 });

  // A long route path in monospace is the thing that overflows a 360px screen, so the
  // layout assertion is made where it would actually break.
  const overflow = await screen.evaluate(
    (node) => node.scrollWidth - node.clientWidth,
  );
  expect(overflow, 'the clinic page overflows its width').toBeLessThanOrEqual(1);
});

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------

matrix('finance and exports are offered only with the permission the server enforces', async (journey) => {
  await journey.signIn();
  await journey.page.goto('/app/dashboard');

  // `FinanceController` declares `ANALYTICS_READ_ORGANIZATION` on all twenty-four of its
  // routes. The seeded identity is an administrator and holds all 67 values, so both
  // entries are present — and the assertion below is that the *server's* granted set is
  // what makes them visible, not the client inventing a permission.
  const granted = (await (
    await journey.page.request.get(
      `${(process.env['FE_E2E_API_URL'] ?? 'http://localhost:4000')}/api/v1/permissions/me`,
    )
  ).json()) as { effectivePermissions?: string[] };

  expect(granted.effectivePermissions ?? []).toContain('analytics:read-organization');
  expect(granted.effectivePermissions ?? []).toContain('analytics:read-project');

  await expect(journey.page.getByRole('link', { name: journey.copy('shell.nav.finance') })).toBeVisible();
  await expect(
    journey.page.getByRole('link', { name: journey.copy('shell.nav.exports') }),
  ).toBeVisible();
});

matrix('the administration screen keeps the two analytics scopes apart', async (journey) => {
  await journey.signIn();
  const calls = recordApi(journey);
  await journey.page.goto('/app/finance?tab=exports');
  // A project must be selected before the gate is read.
  //
  // `GET /api/v1/exports/finance-gate` declares **three** required parameters -
  // `projectId`, `from` and `to` - so with "All projects" chosen the only correct
  // behaviour is *not to call it*. The screen used to call it anyway and the server
  // answered `MissingServletRequestParameterException` as a **500** (Hook 18), because
  // that exception is mapped to no status in `ErrorHandlingAdvice`.
  //
  // So this journey now selects a project first. That is not weakening the assertion: it
  // is the only state in which the two scopes can both be observed, and it keeps the
  // property under test - that each scope reads its own routes rather than one merged
  // read standing in for both.
  await journey.selectProject();
  const screen = journey.page.getByTestId('finance');
  await expect(screen).toBeVisible({ timeout: 30_000 });
  await expect(
    (await sectionHeading(screen, 'finance', 'exports')).locator,
  ).toBeVisible();

  // The finance gate is the one export route under `analytics:read-organization`, and
  // the export list is under the weaker `analytics:read-project`. Both were requested,
  // on their own paths - not merged into one.
  await expect.poll(() => calls.filter((call) => call.startsWith('GET /api/v1/exports/')).length, {
    timeout: 30_000,
  }).toBeGreaterThan(1);
  // ## Asserted on the *path*, and on the parameters the contract requires
  //
  // `recordApi` records the whole URL, query string included, and
  // `expect(array).toContain(x)` is an **exact element** match — so an assertion on the
  // bare path failed the moment the request correctly carried its parameters. It passed
  // before only because `projectId` was `undefined`, the query was dropped, and the
  // malformed request produced a bare URL. A journey that goes green when the request is
  // broken is worse than one that is absent.
  //
  // So the property is stated properly: the gate is read, on its own path, **and it
  // carries the three parameters it requires** (`projectId`, `from`, `to` — Hook 18).
  const gate = calls.find((call) => call.startsWith('GET /api/v1/exports/finance-gate'));
  expect(gate, 'the organization-scoped gate was read on its own path').toBeDefined();
  expect(gate, 'the gate names a project').toContain('projectId=');
  expect(gate, 'the gate names a period').toContain('from=');
  expect(gate, 'the gate names a period').toContain('to=');
  // And the project-scoped route is a **separate** request, not folded into the gate.
  expect(
    calls.some((call) => call.startsWith('GET /api/v1/exports/download-policy')),
  ).toBe(true);
});
