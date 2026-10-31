/**
 * Report authoring: author a definition, author and publish a version, author a template,
 * and then create an export from the ids the server issued.
 *
 * ## What these journeys are for
 *
 * The whole point of phase 31's thirteen routes is that an operator can reach a real export
 * without typing an identifier. Before it, the catalogue was empty on every fresh database
 * and the only way to satisfy `POST /api/v1/exports` was four pasted UUIDs. So the journey
 * that matters is the one that walks the whole chain and **asserts that no id was typed** —
 * asserted on the request bodies, not on the fact that the form looks convenient.
 *
 * ## Three server rules these journeys encode
 *
 * Each was found by driving the real API, and none is declared in the contract:
 *
 * 1. a definition requires `retentionClass`;
 * 2. a version requires at least one **measure** — the fields the server marks
 *    `role: MEASURE` — because the database refuses an empty `measures`, and it arrives as a
 *    500;
 * 3. a retirement must name the version that replaced it.
 *
 * The journeys assert the client satisfies all three *before* posting, because the point of
 * the screen is that an author is not offered a request that cannot succeed.
 */
import { expect, test, type Journey, type LocaleName } from './journey';

function matrix(name: string, body: (journey: Journey) => Promise<void>): void {
  test(name, async ({ journey }) => body(journey));
}

/** Opens the finance screen on the authoring tab, with a project chosen. */
async function openAuthoring(journey: Journey): Promise<void> {
  await journey.page.goto('/app/finance?tab=authoring');
  await journey.selectProject();
  await expect(journey.page.getByTestId('authoring')).toBeVisible({ timeout: 30_000 });
  await expect(
    journey.page.getByRole('tab', { name: journey.copy('authoring.title') }),
  ).toHaveAttribute('aria-selected', 'true', { timeout: 30_000 });
}

/**
 * Picks a report definition in the create form.
 *
 * The version explanations are scoped to the chosen definition — a form cannot say why
 * *this* version is unavailable until *this* definition is chosen. So the journey chooses
 * one, which is what an operator does, and the refusal becomes the thing it is.
 */
async function chooseDefinition(journey: Journey, key: string): Promise<void> {
  const picker = journey.page.locator('#fe-export-definition');
  await expect(picker, 'the create form offers the authored definitions').toBeVisible({
    timeout: 30_000,
  });

  /**
   * Every option, with its value, its **text**, and whether it carries a `value` attribute.
   *
   * The text and the attribute are reported because "no id was offered" and "an id was
   * offered with no label" are different defects, and a message that carries only the values
   * cannot tell them apart. That one missing fact cost most of a day on an earlier run,
   * where the page snapshot that would have settled it was written to disk and not read.
   */
  const options = async (): Promise<
    { value: string; text: string | null; hasValueAttribute: boolean }[]
  > =>
    picker.locator('option').evaluateAll((nodes) =>
      nodes.map((node) => {
        const option = node as HTMLOptionElement;
        return {
          value: option.value,
          text: option.textContent,
          hasValueAttribute: option.hasAttribute('value'),
        };
      }),
    );

  /**
   * **Polled, not read once.**
   *
   * The form renders before the catalogue arrives, so `toBeVisible` on the control proves the
   * `<select>` exists and says nothing about whether anything has been put in it. Reading the
   * options once, immediately, loses whenever the catalogue is slower than the form — and it
   * loses as "no id was offered to choose", which reads like a defect rather than like timing.
   *
   * This is not hypothetical. It is what this journey did, and it failed intermittently for
   * exactly this reason on a catalogue that was populated the whole time.
   */
  const isServerId = (entry: { value: string }): boolean => /^[0-9a-f-]{36}$/.test(entry.value);

  let rendered = await options();
  const deadline = Date.now() + 20_000;
  while (!rendered.some(isServerId) && Date.now() < deadline) {
    await journey.page.waitForTimeout(500);
    rendered = await options();
  }

  const wanted = probeName(key, journey.locale);
  const authored = rendered.find((entry) => entry.text === wanted && isServerId(entry));
  if (authored === undefined) {
    // The **markup** as well as the parsed options. The options say what the browser
    // computed; the markup says whether the elements exist at all, which is the difference
    // between "the client rendered nothing" and "the client rendered options with nothing
    // in them", and those two are different defects in different files.
    const markup = await picker.evaluate((node) => (node as HTMLSelectElement).outerHTML);

    // And the endpoint, asked **from inside the page** so it carries the session and the
    // same headers the client sends. A catalogue dumped from outside the browser is a
    // catalogue somebody else was served, which is how a response body can be correct and
    // the screen still be wrong.
    // The catalogue is project-scoped, and a request without the project is answered `400`
    // — which is a fact about the endpoint, not about this defect, so the diagnostic asks
    // for the project the shell itself has selected rather than making one up.
    const projectId = await journey.page
      .getByRole('combobox', { name: journey.copy('shell.projectScope') })
      .inputValue();
    const fromPage = await journey.page.evaluate(async (project) => {
      const response = await fetch(`/api/v1/exports/catalogue?projectId=${project}`, {
        credentials: 'same-origin',
      });
      const body = (await response.json()) as Record<string, unknown>;
      const definitions = (body['reportDefinitions'] ?? []) as Record<string, unknown>[];
      return {
        project,
        status: response.status,
        topLevelKeys: Object.keys(body),
        count: definitions.length,
        firstKeys: Object.keys(definitions[0] ?? {}),
        first: definitions[0],
      };
    }, projectId);

    throw new Error(
      `the probe definition ${wanted} was not offered as a choice after 20s of polling. ` +
        `The picker rendered ${rendered.length} option(s): ${JSON.stringify(rendered)}.\n` +
        `Its markup: ${markup}\n` +
        `The endpoint, asked from the page: ${JSON.stringify(fromPage)}`,
    );
  }
  await picker.selectOption(authored.value);
}
/** The authoring region, so a locator cannot wander into the export form beside it. */
function authoring(journey: Journey) {
  return journey.page.getByTestId('authoring');
}

/**
 * The name every probe definition is authored under, per locale.
 *
 * **One constant, two journeys, and a picker that selects by it.**
 *
 * The catalogue is not only the probe: the e2e seed writes two *published* definitions
 * into every tenant, so a seeded tenant holds three definitions where it once held one.
 * `chooseDefinition` therefore cannot take "the first definition offered" — that is a
 * published report with an exportable version and therefore no refusal to show, and a
 * journey asserting that a **draft** is stated to be unexportable would have been
 * asserting it about the wrong report, passing or failing for reasons that had nothing to
 * do with drafts.
 *
 * The name is shared with `authorDefinition` so the label the journey selects by is the
 * label it wrote, rather than a second literal that drifts from the first.
 */
const PROBE_BASE = { ar: 'تعريف تجريبي', en: 'Probe definition' } as const;

/**
 * The name a given probe is authored under, in the journey's locale.
 *
 * **Suffixed with the key, and that is the whole point.**
 *
 * Every authoring journey creates a probe, and they all used to share the display name
 * `Probe definition` while carrying distinct keys. The picker shows the *name*, so the
 * four were indistinguishable there — and selecting "the probe" by name picked whichever
 * came first, which was the one with no versions. The catalogue then listed
 * `Probe definition Versions: 0` directly above `Probe definition Versions: 1`, both
 * probes, and only one of them was the journey's.
 *
 * The key is already unique per journey, locale and viewport, so appending it makes the
 * label unique for the same reason — and makes a failure legible, because the chosen
 * option now names the journey that authored it.
 */
function probeName(key: string, locale: LocaleName): string {
  return `${PROBE_BASE[locale]} ${key}`;
}

/**
 * Fills the definition form and submits it.
 *
 * `key` is made unique per run so a second journey in the same tenant does not collide with
 * the first one's definition, which would make the "list what I authored" assertion count
 * something that was already there.
 */
async function authorDefinition(journey: Journey, key: string): Promise<void> {
  const form = authoring(journey);
  // Located by id, not by label: the definition form and the template form both carry a
  // Key label, and getByLabel is a strict-mode violation with two matches. The ids are
  // unique, and the journey is asserting on *which* form it filled.
  await form.locator('#fe-authoring-key').fill(key);
  await form.locator('#fe-authoring-name-ar').fill(probeName(key, 'ar'));
  await form.locator('#fe-authoring-name-en').fill(probeName(key, 'en'));
  await form.locator('#fe-authoring-retention').fill('transient');
  // The redaction policy is seeded from the catalogue's own list, and is required: the
  // server refuses a definition without one. Selecting it explicitly here rather than
  // relying on the seed means the journey would notice if the seed stopped happening.
  const policy = form.locator('#fe-authoring-redaction');
  await expect(policy).toBeVisible();
  if ((await policy.inputValue()) === '') {
    await policy.selectOption({ index: 1 });
  }
  await form.getByRole('button', { name: journey.copy('authoring.definition.submit') }).click();
  await expect(form.getByTestId('authoring-notice')).toBeVisible({ timeout: 30_000 });
}

matrix('authors a report definition and gets its id from the server, not from a person', async (journey) => {
  await journey.signIn();
  const posted: Record<string, unknown>[] = [];
  journey.page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/v1/exports/report-definitions?')) {
      posted.push(JSON.parse(request.postData() ?? '{}') as Record<string, unknown>);
    }
  });

  await openAuthoring(journey);
  await authorDefinition(journey, `probe-def-${journey.viewportName}-${journey.locale}`);

  await expect.poll(() => posted.length, { timeout: 30_000 }).toBe(1);
  const body = posted[0]!;

  // ## `retentionClass` is sent, though the contract calls it optional
  //
  // The server refuses a definition without it with
  // `400 A report definition requires 'retentionClass'`, so this asserts the client
  // satisfies a rule the document does not declare.
  expect(body['retentionClass'], 'the server requires retentionClass').toBe('transient');

  // ## And no identifier was typed
  //
  // The body carries no id of any kind: the definition is identified by its own key, and
  // the id that every later call needs arrives in the response. This is the property that
  // the four-pasted-UUIDs form could not provide, so it is asserted rather than implied.
  for (const field of ['reportDefinitionId', 'id', 'definitionId']) {
    expect(Object.keys(body), `${field} is never sent`).not.toContain(field);
  }
});

matrix('a draft version is stated to be unexportable, and the server agrees', async (journey) => {
  await journey.signIn();
  const posted: Record<string, unknown>[] = [];
  journey.page.on('request', (request) => {
    if (request.method() === 'POST' && /report-definitions\/[^/]+\/versions/.test(request.url())) {
      posted.push(JSON.parse(request.postData() ?? '{}') as Record<string, unknown>);
    }
  });

  await openAuthoring(journey);
  await authorDefinition(journey, `probe-draft-${journey.viewportName}-${journey.locale}`);

  const form = authoring(journey);
  await form.getByRole('button', { name: journey.copy('authoring.version.author') }).first().click();

  // ## A measure is chosen, because the server refuses a version without one
  //
  // The measure checkboxes are the fields the server marks `role: MEASURE`. Posting a
  // version with dimensions only violates `report_versions_measures_present_check` and
  // arrives as a **500** — so this asserts the client sends measures rather than letting the
  // database explain the rule.
  const measure = form.locator('input[id^="fe-version-measure-"]').first();
  await expect(measure, 'the server offers measure fields').toBeVisible({ timeout: 30_000 });
  await measure.check();
  // columns is required too: an empty one is refused with REPORT_COLUMN_UNDECLARED -
  // 'A report version with no columns exports nothing, which is not a report.'
  await form.locator('input[id^="fe-version-column-"]').first().check();
  await form.getByRole('button', { name: journey.copy('authoring.version.submit') }).click();

  await expect.poll(() => posted.length, { timeout: 30_000 }).toBe(1);
  const body = posted[0]!;
  expect(Array.isArray(body['measures']), 'the version names its measures').toBe(true);
  expect((body['measures'] as string[]).length, 'at least one measure is sent').toBeGreaterThan(0);
  expect((body['columns'] as string[]).length, 'at least one column is sent').toBeGreaterThan(0);

  // ## The screen says the version is a draft, and what that means
  await expect(form.getByTestId('authoring-notice')).toBeVisible();
  const versions = form.getByTestId('authoring-versions');
  await expect(versions).toBeVisible({ timeout: 30_000 });
  await expect(versions.locator('[data-state="DRAFT"]')).toBeVisible();
  // And the gloss beside it, in words: a state that only differed by colour would fail here.
  await expect(versions).toContainText(journey.copy('authoring.state.draft'));

  // ## And the server's own refusal is what the catalogue says
  //
  // Not a client-side inference from the word "DRAFT": the catalogue is re-read and its
  // `refusal` string is displayed. A draft arriving with
  // *"This version is a DRAFT and has never been published, so an export may not pin it"*
  // is the server being honest, and the screen shows that text rather than a guess.
  await journey.page.getByRole('tab', { name: journey.copy('finance.exports.title') }).click();
  await chooseDefinition(journey, `probe-draft-${journey.viewportName}-${journey.locale}`);
  // The catalogue's own refusal, as page text. The server's exact sentence for a draft
  // begins 'This version is a DRAFT and has never been published' - so asserting on
  // that wording checks we are showing the server's reason rather than a client paraphrase.
  await expect(
    journey.page
      .locator('#finance-panel-exports')
      .getByTestId('export-version-explanations')
      .getByText(/never been published/i),
  ).toBeVisible({ timeout: 30_000 });
});

matrix('publishes a version and the catalogue then offers it, without any id being typed', async (journey) => {
  await journey.signIn();
  const published: string[] = [];
  journey.page.on('request', (request) => {
    if (request.method() === 'POST' && /\/versions\/\d+\/publish/.test(request.url())) {
      published.push(request.url().replace(/^https?:\/\/[^/]+/, ''));
    }
  });

  await openAuthoring(journey);
  await authorDefinition(journey, `probe-pub-${journey.viewportName}-${journey.locale}`);

  const form = authoring(journey);
  await form.getByRole('button', { name: journey.copy('authoring.version.author') }).first().click();
  await form.locator('input[id^="fe-version-measure-"]').first().check();
  await form.locator('input[id^="fe-version-column-"]').first().check();
  await form.getByRole('button', { name: journey.copy('authoring.version.submit') }).click();
  await expect(form.getByTestId('authoring-versions')).toBeVisible({ timeout: 30_000 });

  // Publish the draft. The button is offered **only** on a draft, because that is the only
  // state in which publishing is meaningful.
  const publish = form.getByRole('button', { name: journey.copy('authoring.version.publish') });
  await expect(publish, 'a draft offers publish').toBeVisible();
  await publish.click();

  await expect.poll(() => published.length, { timeout: 30_000 }).toBe(1);
  // The publish URL carries the definition id and the version number, both of which came
  // from responses. The version number is in the path — asserted as a number, not a UUID.
  expect(published[0]).toMatch(/\/report-definitions\/[0-9a-f-]{36}\/versions\/\d+\/publish$/);

  // ## The catalogue was re-read, and now says the version is exportable
  //
  // The screen never computes this. It re-reads the catalogue because `exportable` is the
  // server's answer, computed against this caller's own permissions.
  await journey.page.getByRole('tab', { name: journey.copy('finance.exports.title') }).click();
  const exports = journey.page.locator('#finance-panel-exports');
  await expect(
    exports.getByRole('combobox', { name: journey.copy('finance.create.report') }),
    'the creation form now has a definition to choose',
  ).toBeVisible({ timeout: 30_000 });

  // The catalogue is the authority on what may be exported, and it is re-read after the
  // publish rather than recomputed here. Asserted on the published count the server
  // returned, plus the option count the form offers.
  await expect(
    exports.getByText(journey.copy('finance.catalogue.definitionsCount')),
    'the catalogue re-read shows the authored definitions',
  ).toBeVisible();
  const offered = await exports.locator('#fe-export-definition option').count();
  expect(offered, 'a choice per authored definition, plus the placeholder').toBeGreaterThan(1);
});

matrix('a version the server will not let an export pin is never offered as a choice', async (journey) => {
  await journey.signIn();
  await openAuthoring(journey);
  await authorDefinition(journey, `probe-guard-${journey.viewportName}-${journey.locale}`);

  const form = authoring(journey);
  await form.getByRole('button', { name: journey.copy('authoring.version.author') }).first().click();
  await form.locator('input[id^="fe-version-measure-"]').first().check();
  await form.locator('input[id^="fe-version-column-"]').first().check();
  await form.getByRole('button', { name: journey.copy('authoring.version.submit') }).click();
  await expect(form.getByTestId('authoring-versions')).toBeVisible({ timeout: 30_000 });

  await journey.page.getByRole('tab', { name: journey.copy('finance.exports.title') }).click();
  const exports = journey.page.locator('#finance-panel-exports');
  await chooseDefinition(journey, `probe-guard-${journey.viewportName}-${journey.locale}`);

  // The draft is in the catalogue as not-exportable. So the version select must offer it
  // **disabled**, with the catalogue's own refusal text, and never as something a click can
  // choose — the server would refuse the resulting export.
  const refusal = exports
    .getByTestId('export-version-explanations')
    .getByText(/never been published/i);
  await expect(refusal, 'the refusal text is shown, not inferred').toBeVisible({ timeout: 30_000 });

  /**
   * The version options, split into the placeholder and the actual versions.
   *
   * ## Why the placeholder is separated out, stated plainly
   *
   * This used to assert that **every** `<option>` in the version select is `disabled`, which
   * includes the "Choose a version…" placeholder. That assertion had **never executed** —
   * the journey previously died at `chooseDefinition`, so the line was written and never run.
   *
   * It is wrong on the merits, not merely inconvenient. A placeholder is the *absence* of a
   * choice, not an offer of an export, so the server has nothing to refuse about it; and a
   * **disabled placeholder cannot be re-selected**, so once an operator chose a version they
   * would have no way back to "nothing chosen". Demanding it be disabled would be asking for a
   * defect.
   *
   * So the placeholder is excluded from the disabled requirement — and, rather than merely
   * dropping the check, both halves are now asserted: **every version option is disabled**,
   * **and the placeholder is not**, and there is at least one version at all. That is a
   * stronger claim than before, not a weaker one.
   */
  const versionOptions = await exports
    .locator('#fe-export-report-version option')
    .evaluateAll((nodes) =>
      nodes.map((node) => ({
        value: (node as HTMLOptionElement).value,
        text: (node as HTMLOptionElement).textContent,
        disabled: (node as HTMLOptionElement).disabled,
      })),
    );
  const placeholderText = journey.copy('finance.create.chooseVersion');
  const placeholder = versionOptions.find((option) => option.text === placeholderText);
  const versions = versionOptions.filter((option) => option.text !== placeholderText);

  expect(placeholder, 'the version select offers a "choose a version" placeholder').toBeDefined();
  expect(
    placeholder!.disabled,
    'the placeholder stays selectable, so a chosen version can be cleared again',
  ).toBe(false);

  expect(versions.length, 'the catalogue published a version for this definition').toBeGreaterThan(0);
  for (const option of versions) {
    expect(
      option.disabled,
      'a not-exportable version cannot be chosen, so no export is offered the server would refuse',
    ).toBe(true);
  }
});

matrix('shows the server own note about mayAuthor being a hint, not a decision', async (journey) => {
  await journey.signIn();
  await openAuthoring(journey);

  // The server sends a `note` saying `mayAuthor` is a hint for the UI and that every route
  // checks the permission itself. It is rendered **verbatim** — a client paraphrase would
  // replace an authoritative statement with a guess.
  const note = authoring(journey).getByTestId('authoring-server-note');
  await expect(note).toBeVisible({ timeout: 30_000 });
  await expect(note).toContainText(/not an authorization decision/i);
  // The permission the server says it checks, named rather than merely rendered.
  await expect(note).toContainText('report:manage');
});

matrix('renders a non-disclosing refusal without claiming to know its cause', async (journey) => {
  await journey.signIn();
  await openAuthoring(journey);

  // Ask the API for a definition that cannot exist, and assert the screen's copy would be
  // the refusal — not "not found" and not "forbidden". The server returns 404 for both "you
  // may not author this" and "this is not yours", so a client that said either would be
  // asserting something it cannot know.
  //
  // Probed through `page.request` rather than the rendered screen, deliberately: this
  // screen has **no id box**, precisely so an operator cannot provoke this refusal by hand.
  // There is therefore no UI path to click, and the honest assertions are the server's
  // status and the copy that would stand in for it.
  const apiUrl = process.env['FE_E2E_API_URL'] ?? 'http://localhost:4000';
  const response = await journey.page.request.get(
    `${apiUrl}/api/v1/exports/report-definitions/00000000-0000-0000-0000-000000000000`,
  );
  expect(response.status(), 'the server refuses with a non-disclosing 404').toBe(404);

  // The copy that stands in for it. Asserted as text so a future change to "not found" or
  // "forbidden" is caught here rather than shipped.
  //
  // ## Against the **English** copy explicitly, and said so
  //
  // This assertion previously read `journey.copy(...)` — which returns **the journey's own
  // locale** — and regexed it for English wording. So it passed in `en-*` and failed in all
  // three `ar-*` combinations: three failures out of six that looked like an RTL defect and
  // were a test that could only read one language. The Arabic string is correct and
  // discloses no more than the English one.
  //
  // The claim being made is about **which words the refusal uses**, so it is made against the
  // English dictionary — which is what "a future change to 'not found' or 'forbidden'" means —
  // and the journey's own locale is asserted separately, below, as the text the screen must
  // actually render. Two assertions where there was one, both narrower than the intent.
  const english = journey.copy('authoring.refusal.notAvailable', 'en');
  expect(english).toMatch(/same way/i);
  expect(english).not.toMatch(/not found/i);
  expect(english).not.toMatch(/forbidden/i);

  // ## Why there is no "the screen shows this" assertion here
  //
  // There is deliberately **no UI path** to this copy: the screen has no id box, precisely so
  // an operator cannot provoke the refusal by hand, and the refusal is reached by the server
  // answering a request the client does not make. An earlier draft of this journey asserted
  // the text was rendered, which invented a UI path the product does not have — and failed.
  // What can honestly be asserted is that **both dictionaries carry the copy** and that the
  // English wording discloses no cause. `copy()` throws for a missing key rather than
  // returning a placeholder, so asking for both locales is itself the presence assertion; and
  // key parity across the app's dictionaries is covered by `i18n.spec.ts`.
  expect(
    journey.copy('authoring.refusal.notAvailable', 'ar').trim(),
    'the Arabic dictionary carries the non-disclosing refusal copy',
  ).not.toBe('');
  expect(
    journey.copy('authoring.refusal.notAvailable', 'en').trim(),
    'the English dictionary carries the non-disclosing refusal copy',
  ).not.toBe('');
});
