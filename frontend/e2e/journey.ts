/**
 * Journey fixtures.
 *
 * Every fixture here drives the **real** application against the **real** backend.
 * There is no `page.route` interception anywhere in this suite, and that is a
 * deliberate constraint rather than an oversight: phase-11 hard rule 8 says a mock
 * that ships is a failure, and a mock in the test that "passes" proves the same
 * thing — that the mock works.
 *
 * What the fixtures own:
 *
 * - **the locale/direction matrix**, so a journey is written once and runs in
 *   Arabic RTL and English LTR;
 * - **sign-in**, through the real login endpoint with the real CSRF handshake;
 * - **the observable facts a journey asserts on** — the `dir` and `lang`
 *   attributes, the accessible names, the request log — gathered through the
 *   browser rather than by peeking at application state.
 */
import { expect, test as base, type Locator, type Page } from '@playwright/test';
import {
  LOCALES,
  VIEWPORTS,
  identityFor,
  journeyFor,
  type LocaleName,
  type ViewportName,
} from './matrix';
import { awaitGate } from './signin-gate';

export type { LocaleName, ViewportName };
export { LOCALES, VIEWPORTS };

/** Where the SPA is served from; a fresh context needs it spelled out in full. */
export const BASE_URL = process.env['FE_E2E_BASE_URL'] ?? 'http://localhost:4200';

/** How long to assume the login limiter holds an attempt, in milliseconds. */
const RATE_LIMIT_WAIT_MS = 61_000;

/** The ceiling on waiting for the limiter to let go before the journey fails. */
const SIGN_IN_DEADLINE_MS = 4 * 61_000;

/**
 * Presses Tab until `target` holds focus, and reports whether it ever did.
 *
 * The honest way to write a keyboard journey. Counting a fixed number of Tab
 * presses asserts the *current* tab order rather than the behaviour, so it passes
 * while the control has become unreachable and then fails for an unrelated reason
 * the moment a control is inserted earlier in the document — which is the exact
 * regression a keyboard journey exists to catch.
 *
 * Bounded, so a focus trap is a failed assertion rather than a hung journey.
 */
export async function tabUntil(
  page: Page,
  target: Locator,
  options: { max?: number; from?: Locator } = {},
): Promise<boolean> {
  const max = options.max ?? 40;
  if (options.from) await options.from.focus();
  for (let step = 0; step < max; step += 1) {
    const focused = await target.evaluate((node) => node === document.activeElement).catch(() => false);
    if (focused) return true;
    await page.keyboard.press('Tab');
  }
  return target.evaluate((node) => node === document.activeElement).catch(() => false);
}

/**
 * The focus ring a control actually shows.
 *
 * Measured after a **keyboard** Tab, not after a programmatic `.focus()`. The
 * product styles its ring on `:focus-visible` (correct — a mouse click should not
 * leave a ring behind), and Chromium does not consider a programmatic focus
 * keyboard-initiated. Asserting the ring after `.focus()` therefore measures the
 * test's own shortcut rather than the product, and a product that had removed its
 * focus styles entirely would still pass.
 */
export async function keyboardFocusRing(page: Page, target: Locator): Promise<string | null> {
  await target.focus();
  // A real Tab in and back out leaves the control focused via the keyboard.
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  const focused = await target.evaluate((node) => node === document.activeElement);
  if (!focused) return null;
  return target.evaluate((node) => getComputedStyle(node).outlineStyle);
}

/**
 * A page plus the credentials and the locale it is running under.
 *
 * The locale is applied through `localStorage` before the first script runs, so
 * the very first paint is already RTL with Arabic copy — the same thing a returning
 * Arabic-first operator sees, and the only way to catch a layout that assumes LTR
 * on first render.
 */
export class Journey {
  constructor(
    readonly page: Page,
    readonly locale: LocaleName,
    readonly viewport: ViewportName,
    /** The matrix project name; the identity is derived from it. */
    readonly project: string,
  ) {}

  get direction(): 'rtl' | 'ltr' {
    return LOCALES[this.locale].direction;
  }

  /** The matrix width this journey is running at, for a message that names it. */
  get viewportName(): ViewportName {
    return this.viewport;
  }

  /**
   * Whether the inbox's three panes have collapsed into one.
   *
   * The breakpoint lives in `inbox.component.css` at `max-width: 1100px`. Asserting
   * from here means a CSS change and this predicate can drift, which would make the
   * journey fail for the wrong reason — so the predicate is the *width*, and the
   * journey asserts both directions of it. A layout that stopped collapsing, or started
   * collapsing at the wrong width, is what this catches.
   */
  isCollapsed(): boolean {
    return VIEWPORTS[this.viewport].width <= 1100;
  }

  get htmlDir(): string {
    return LOCALES[this.locale].htmlDir;
  }

  /**
   * The credential this combination signs in as.
   *
   * One throwaway identity per matrix project rather than one shared account. This
   * is what removes the login rate limit as a scheduling coincidence without
   * touching the production limit — see `e2e/matrix.ts` for the reasoning and for
   * the alternatives that were rejected.
   */
  get email(): string {
    return identityFor(required('FE_E2E_EMAIL'), this.project);
  }

  /**
   * The email and password fields, by their exact accessible names.
   *
   * `exact: true` is not fussiness. The password-visibility toggle is labelled
   * "Show password" — which *contains* the field's own label — so a substring
   * match resolves to two elements and the journey fails with a strict-mode
   * violation instead of a real signal. In Arabic the same collision happens in
   * the other direction: "إظهار كلمة المرور" ("Show password") contains
   * "كلمة المرور" ("Password"). Exact matching is the correct locator discipline
   * in both languages.
   */
  emailField(): Locator {
    return this.page.getByLabel(this.copy('login.email'), { exact: true });
  }

  passwordField(): Locator {
    return this.page.getByLabel(this.copy('login.password'), { exact: true });
  }

  submitButton(): Locator {
    return this.page.getByRole('button', { name: this.copy('login.submit'), exact: true });
  }

  /** Signs in through the real form, waiting for the authenticated shell. */
  async signIn(): Promise<void> {
    const email = this.email;
    const password = required('FE_E2E_PASSWORD');

    await this.attemptSignIn(async () => {
      await this.emailField().fill(email);
      await this.passwordField().fill(password);
      await this.submitButton().click();
    });
  }

  /**
   * Signs in driven entirely by the keyboard, and proves it.
   *
   * Reached by Tab, driven by Tab, operated by Enter. Every hop is `tabUntil`
   * rather than a fixed count, so this asserts the order is *sane and complete*
   * instead of asserting today's exact number of stops — a fixed count passes
   * while a control has become unreachable, and then fails for an unrelated reason
   * the moment one is inserted earlier in the document.
   */
  async signInWithKeyboard(): Promise<void> {
    const email = this.email;
    const password = required('FE_E2E_PASSWORD');

    await this.attemptSignIn(async () => {
      expect(
        await tabUntil(this.page, this.emailField(), { from: this.page.locator('body'), max: 12 }),
        'the email field is reachable by Tab',
      ).toBe(true);
      await this.page.keyboard.type(email);

      expect(
        await tabUntil(this.page, this.passwordField(), { max: 12 }),
        'the password field is reachable by Tab',
      ).toBe(true);
      await this.page.keyboard.type(password);

      expect(
        await tabUntil(this.page, this.submitButton(), { max: 12 }),
        'the submit button is reachable by Tab',
      ).toBe(true);
      await this.page.keyboard.press('Enter');
    });
  }

  /**
   * Runs one sign-in attempt, retrying if the login limiter refused it.
   *
   * **This is a backstop, not the mechanism.** Each matrix project signs in as its
   * own throwaway account, so the limiter's *account* bucket — the one that refuses
   * a legitimate retry — never trips. See `e2e/matrix.ts` for why that is the fix
   * and why pacing sign-ins was rejected as one.
   *
   * It stays because a backstop that is exercised zero times is a backstop nobody
   * has tested, and because the per-*address* bucket is shared across the six
   * projects and is deliberately never cleared by a success. If it ever does fire,
   * waiting and retrying is what a human operator does; skipping the journey is not
   * an option (`AGENTS.md`: a skipped browser test is a failure, not a pass).
   *
   * The wait is bounded and the bound is generous, so a limiter that has not let
   * go fails the journey naming the cause rather than hanging to the test timeout.
   *
   * Every sign-in in the suite goes through here, including the keyboard-only one —
   * a limiter refusal is a property of the account and the address, not of how the
   * form was driven, so a journey that bypassed this would fail for a reason that
   * has nothing to do with what it is testing.
   */
  private async attemptSignIn(fillAndSubmit: () => Promise<void>): Promise<void> {
    const deadline = Date.now() + SIGN_IN_DEADLINE_MS;

    for (let attempt = 1; ; attempt += 1) {
      // The gate, before the form is even filled. This is what keeps the whole suite
      // inside the limiter's per-address window — see `e2e/signin-gate.ts` for why the
      // per-combination account is not enough on its own.
      await awaitGate();
      await this.page.goto('/login');
      await expect(this.page.locator('html')).toHaveAttribute('dir', this.htmlDir);
      await fillAndSubmit();

      const outcome = await Promise.race([
        this.page.waitForURL('**/app/dashboard', { timeout: 30_000 }).then(() => 'signed-in' as const),
        this.rateLimited().then(() => 'rate-limited' as const),
      ]);

      if (outcome === 'signed-in') return;

      if (Date.now() > deadline) {
        throw new Error(
          `Sign-in is still rate limited after ${attempt} attempts as ${this.email} ` +
            `(${(SIGN_IN_DEADLINE_MS / 1000).toFixed(0)}s). The gate in e2e/signin-gate.ts ` +
            'spaces attempts below 10 per 60s per address, so reaching this means the gate ' +
            'is not in the path — check that every sign-in still goes through attemptSignIn.',
        );
      }
      // The server does not put `Retry-After` on this response (a documented contract
      // defect), so the window is assumed. Over-waiting is harmless; under-waiting costs
      // one more attempt.
      await this.page.waitForTimeout(RATE_LIMIT_WAIT_MS);
    }
  }

  /** Resolves when the sign-in screen reports the login limiter has spoken. */
  private rateLimited(): Promise<void> {
    return this.page
      .getByRole('alert')
      .filter({ hasText: this.copy('login.error.rateLimited') })
      .waitFor({ state: 'visible', timeout: 30_000 })
      .then(() => undefined);
  }

  /**
   * The Arabic or English text for a key, from a literal expectation table.
   *
   * Deliberately **not** imported from `src/`: the dictionary is TypeScript
   * modules and is not reachable from a browser test, and a journey that imported
   * the very strings it is checking would pass even if the template stopped
   * rendering them. These are literals, so a screen that stops using the
   * dictionary fails here.
   *
   * A key with no entry is a **hard failure**, not a fallback to the key: a
   * locator built from `"dashboard.refresh"` would never match anything, and the
   * journey would then fail with a timeout that names neither the missing
   * translation nor the key.
   */
  /**
   * Selects the single project this identity belongs to, through the shell's own
   * switcher.
   *
   * `GET /api/v1/exports/catalogue` **requires** `projectId`, and the seeded identity
   * is a member of exactly one project, so the switcher's default of "All projects" is a
   * state the export catalogue cannot be read in. Selected through the real control
   * rather than by writing to storage, so the assertion covers the path an operator takes.
   */
  async selectProject(): Promise<void> {
    const select = this.page.getByRole('combobox', {
      name: this.copy('shell.projectScope'),
    });
    await select.waitFor({ timeout: 30_000 });

    const values = await select
      .locator('option')
      .evaluateAll((nodes) => nodes.map((node) => (node as HTMLOptionElement).value));
    const project = values.find((value) => value !== '');
    if (!project) throw new Error('this identity is in no project, so nothing can be scoped');
    if ((await select.inputValue()) === project) return;

    await select.selectOption(project);
    await expect
      .poll(() => select.inputValue(), { timeout: 30_000 })
      .toBe(project);
  }

  /**
   * The literal the screen must render for `key`, in the journey's locale by default.
   *
   * `locale` is an explicit override for the rare assertion that is about **one** language's
   * wording regardless of which language the journey runs in.
   *
   * That case is real and it was a live bug: the non-disclosing-refusal journey asserted its
   * English wording with `/same way/i` against **this** method, so it passed in `en-*` and
   * failed in all three `ar-*` combinations — 3 failures out of 6 that looked like an RTL
   * defect and were a test that could only read one language. The override lets such a
   * journey assert the English wording *and* separately assert that the journey's own locale
   * is what the screen actually shows, which is the stronger pair of claims.
   */
  copy(key: string, locale: LocaleName = this.locale): string {
    const text = COPY[locale][key];
    if (text === undefined) {
      throw new Error(
        `e2e/journey.ts has no ${this.locale} expectation for "${key}". ` +
          'Add the literal the screen must render; do not let a journey locate by key name.',
      );
    }
    return text;
  }
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} must be set; see playwright.config.ts.`);
  return value;
}

/**
 * Copy the journeys assert on.
 *
 * Every entry is the exact string the screen must render, in both languages. The
 * keys are the *i18n keys* the application uses, so a rename in the dictionary and
 * a stale table here disagree loudly instead of quietly.
 */
const COPY: Record<LocaleName, Readonly<Record<string, string>>> = {
  ar: {
    'login.title': 'مرحباً بكم في العنصر الأول',
    'login.email': 'البريد الإلكتروني',
    'login.password': 'كلمة المرور',
    'login.submit': 'تسجيل الدخول',
    'login.showPassword': 'إظهار كلمة المرور',
    'login.error.invalidCredentials':
      'البريد الإلكتروني أو كلمة المرور غير مطابقين لسجلاتنا. تحقق من الحقلين وحاول مرة أخرى.',
    'login.error.rateLimited': 'محاولات كثيرة في وقت قصير. انتظر قليلاً ثم حاول مرة أخرى.',
    'shell.nav.dashboard': 'اللوحة',
    'shell.nav.admin': 'الإدارة',
    'shell.nav.audit': 'سجل التدقيق',
    'shell.nav.finance': 'المالية',
    'shell.nav.exports': 'التقارير والتصدير',
    'shell.nav.clinic': 'العيادة',
    'admin.title': 'الإدارة',
    'admin.refresh': 'تحديث',
    'admin.overview.title': 'نظرة عامة',
    'admin.backups.title': 'النسخ الاحتياطية',
    'admin.migrations.title': 'ترحيلات القاعدة',
    'admin.commands.title': 'أوامر الصيانة',
    'admin.audit.title': 'سجل التدقيق',
    'admin.governance.title': 'الحوكمة القانونية',
    'admin.sessions.title': 'الجلسات النشطة',
    'admin.jobs.title': 'مهام التصدير',
    'admin.writes.title': 'عمليات لا يقدّمها هذا التطبيق',
    'admin.unavailable.purge': 'تنقية البيانات',
    'admin.unavailable.migrations': 'عرض تفاصيل كل ترحيل على حدة',
    'finance.title': 'التقارير والمالية',
    'finance.create.title': 'إنشاء تصدير',
    'finance.create.choose': 'اختر…',
    'finance.create.chooseVersion': 'اختر نسخة…',
    'finance.create.chooseFormat': 'اختر صيغة…',
    'finance.create.chooseLocale': 'اختر لغة…',
    'finance.create.report': 'تعريف التقرير',
    'finance.create.reportVersion': 'نسخة التقرير',
    'finance.create.template': 'قالب المستند',
    'finance.create.templateVersion': 'نسخة القالب',
    'finance.create.format': 'الصيغة',
    'finance.create.locale': 'لغة الملف',
    'finance.create.localeHint': 'لغة التسميات والتواريخ والأرقام داخل الملف المُنشأ، وهي مستقلة عن لغة هذه الشاشة.',
    'finance.create.filters': 'عوامل التصفية',
    'finance.create.filtersHint': 'الحقول التي يوفّرها الدليل فقط. أي مفتاح غيرها يُرفض.',
    'finance.create.submit': 'أنشئ التصدير',
    'finance.create.submitting': 'جارٍ الإنشاء…',
    'finance.create.queued': 'قبل الخادم التصدير. ستظهر حالته في القائمة أدناه.',
    'finance.create.formatRefused': 'رفض الخادم هذه الصيغة. اختر صيغة أخرى من الدليل.',
    'finance.create.needsProject': 'اختر مشروعاً 먼저. الدليل يُقرأ لمشروع بعينه.',
    'finance.catalogue.emptyBody': 'لا يوجد هنا ما يمكن تصديره. هذا ما يقوله الخادم، لا فشل في العرض.',
    'finance.catalogue.title': 'دليل ما يمكن تصديره',
    'finance.exports.title': 'التصدير',
    'finance.refresh': 'تحديث',
    'finance.usage.title': 'الاستهلاك',
    'finance.periods.title': 'الفترات المالية',
    'finance.rates.title': 'بطاقات الأسعار',
    'finance.reconciliation.title': 'المطابقة',
    'finance.policy.title': 'سياسة التنزيل والاحتفاظ',
    'finance.gate.title': 'بوابة المالية على التصدير',
    'finance.writes.title': 'عمليات لا يقدّمها هذا التطبيق',
    'finance.unavailable.reports': 'التقارير ومكتبة القوالب',
    'finance.unavailable.clinic': 'المواعيد والإجراءات',
    'finance.catalogue.formats': 'الصيغ المتاحة',
    'finance.catalogue.definitionsCount': 'تعريفات التقارير',
    'finance.catalogue.templatesCount': 'قوالب المستندات',
    'finance.create.needsChoices': 'اختر تعريف تقرير ونسخته، وقالب مستند ونسخته، قبل الإنشاء.',
    'finance.create.description': 'يُنشأ التصدير من دليل ما يوفّره الخادم لهذا المشروع: تختار تعريف تقرير ونسخته، وقالب مستند ونسخته.',
    'finance.create.versionExportable': 'متاحة للتصدير',
    'finance.create.versionWithheld': 'غير متاحة للتصدير',
    'finance.create.versionExportableBody': 'هذه النسخة منشورة، ويمكن تثبيتها في تصدير.',
    'finance.create.versionWithheldBody': 'لم يردّ الخادم على سبب، فهذه النسخة غير متاحة للتصدير الآن.',
    'clinic.title': 'العيادة',
    // Clinic read model (Phase 37). These four replace the gap-screen strings below, which
    // asserted a screen that no longer exists.
    'clinic.analytics.sourceTable': 'مصدر الحساب',
    'clinic.analytics.noRevenue':
      'لا تُعرض هنا أي مبالغ. التسعير قرار مالي بمراجعة مُصدَّرة وسارية، وليس عمودًا في سجل الإجراءات؛ ولذلك لا يُحتسب أي مبلغ من هذا العدد.',
    'clinic.analytics.noUtilization':
      'لا تُعرض نسبة استغلال. المقام فيها — ساعات العمل، وطول الموعد، وعدد أيام الأسبوع — سياسة عيادة لا صفّ في قاعدة البيانات.',
    'clinic.analytics.sampleNote':
      'قائمة معرّفات المواعيد محدودة بعيّنة. العدد المعروض هو الإجمالي الكامل، والعيّنة جزء منه فقط.',
    'clinic.unavailable.title': 'شاشة العيادة غير متاحة في هذه النسخة',
    'clinic.unavailable.missingRoutes': 'المسارات المنتظرة:',
    'shell.nav.settings': 'الإعدادات',
    'shell.account.signOut': 'خروج',
    'shell.account.sessionEnded': 'انتهت الجلسة. سجّل الدخول من جديد للمتابعة.',
    'shell.account.sessionRevoked': 'أُنهيت الجلسة من مكان آخر. سجّل الدخول من جديد للمتابعة.',
    'shell.theme.toggle': 'تبديل المظهر',
    'shell.locale.toggle': 'تغيير اللغة',
    'shell.realtime.connected': 'متصل مباشرة',
    'shell.realtime.reconnecting': 'جارٍ إعادة الاتصال…',
    'shell.realtime.offline': 'لا يوجد اتصال مباشر',
    'shell.realtime.stale': 'البيانات قد تكون قديمة',
    'shell.realtime.resync': 'مزامنة الآن',
    'shell.projectScope': 'المشاريع المتاحة لك',
    'shell.projectAll': 'كل المشاريع',
    'state.mainContent': 'تخطَّ إلى المحتوى',
    'state.loading': 'جارٍ التحميل',
    'state.error': 'تعذّر تحميل هذه الصفحة.',
    'state.retry': 'إعادة المحاولة',
    'state.requestId': 'رقم الطلب',
    'state.denied.title': 'لا تملك صلاحية لهذا',
    'state.notFound.title': 'الصفحة غير موجودة',
    'dashboard.title': 'لوحة العمليات',
    'dashboard.refresh': 'تحديث',
    'dashboard.resync': 'مزامنة الآن',
    'dashboard.staleNotice':
      'الاتصال المباشر غير متاح، فالبيانات المعروضة قد تكون قديمة. زر المزامنة الآن يعيد قراءتها من الخادم.',
    'settings.title': 'الإعدادات',
    'settings.refresh': 'تحديث',
    'inbox.title': 'الوارد',
    'inbox.refresh': 'تحديث',
    'inbox.queue.title': 'في الانتظار، بلا مالك',
    'inbox.queue.waiting': 'بانتظار منذ',
    'inbox.list.filterUnassigned': 'غير مُسندة',
    'inbox.list.filterAllOpen': 'كل المفتوحة',
    'inbox.list.filterAssignedToOthers': 'مُسندة إلى غيري',
    'inbox.list.filterAssignedToMe': 'المسندة إليّ',
    'inbox.list.filterHistory': 'السجل',
    'inbox.list.filterGroup': 'العروض المحفوظة',
    'inbox.thread.empty': 'اختر محادثة',
    'inbox.thread.selectPrompt': 'لم تُختر أي محادثة بعد.',
    'inbox.composer.label': 'اكتب رداً',
    'inbox.composer.send': 'إرسال',
    'inbox.composer.sending': 'جارٍ الإرسال…',
    'inbox.composer.empty': 'اكتب شيئاً قبل الإرسال.',
    'inbox.case.close': 'إغلاق المحادثة',
    'inbox.case.reopen': 'إعادة فتح',
    'inbox.case.closed': 'أُغلقت المحادثة.',
    'inbox.case.reopened': 'أُعيد فتح المحادثة.',
    'inbox.case.assignToMe': 'إسنادها إليّ',
    'inbox.case.closeNeedsResolution': 'سجّل كيف حُلّت الحالة قبل الإغلاق.',
    'inbox.mode.human': 'موظف',
    'inbox.mode.ai': 'الذكاء الاصطناعي',
    'inbox.mode.takeOver': 'تولّي الرد بنفسك',
    'inbox.mode.changed': 'تغيّر وضع الرد.',
    'inbox.resolution.label': 'تسجيل الحل',
    'inbox.resolution.outcome': 'النتيجة',
    'inbox.resolution.save': 'حفظ التسجيل',
    'inbox.resolution.customerIssue': 'مشكلة العميل',
    'inbox.resolution.agentAction': 'الإجراء المتخذ',
    'inbox.resolution.outcomeRequired': 'اختر نتيجة للحالة قبل الحفظ.',
    'inbox.resolution.required': 'هذا الحقل مطلوب قبل الحفظ.',
    'inbox.resolution.saved': 'حُفظ تسجيل الحل.',
    'inbox.resolution.escalationRequested': 'طُلب التصعيد',
    'inbox.resolution.escalatedToSupervisor': 'صُعّد للمشرف',
    'inbox.resolution.escalationNote':
      'التصعيد حقل ضمن هذا التسجيل. لا توجد قائمة تصعيدات مستقلة في النظام.',
    'inbox.status.OPEN': 'مفتوحة',
    'inbox.status.CLOSED': 'مغلقة',
    'inbox.list.label': 'قائمة المحادثات',
    'people.title': 'الأشخاص',
    'people.refresh': 'تحديث',
    'people.detail.title': 'ملف العميل',
    'people.detail.selectPrompt': 'اختر عميلاً',
    'people.detail.notes': 'ملاحظات داخلية',
    'people.detail.addNote': 'أضف ملاحظة داخلية',
    'people.detail.noteSave': 'حفظ الملاحظة',
    'people.detail.noteEmpty': 'اكتب شيئاً قبل الحفظ.',
    'people.detail.noteSaved': 'حُفظت الملاحظة.',
    'people.detail.cxId': 'معرّفCX',
    'people.detail.conversations': 'المحادثات',
    'people.separation.note':
      'هذه ملاحظات داخلية لا يراها العميل، وليست سجلّه الذي يراه.',
    'team.title': 'الفريق',
    'team.refresh': 'تحديث',
    'team.table.active': 'نشط',
    'team.permissions.granted': 'ممنوحة',
    'team.permissions.title': 'صلاحيات مستخدم',
    'team.unavailable.title': 'ساعات العمل والتوزيع والاستيراد',
    'knowledge.title': 'المعرفة والوكيل',
    'knowledge.refresh': 'تحديث',
    'knowledge.freshness.title': 'نضج البحث',
    'knowledge.ai.title': 'حوكمة الذكاء الاصطناعي',
    'knowledge.ai.readOnly':
      'هذا القسم للقراءة فقط. كل قرار يُتخذ عبر أمر صريح، لا من هنا.',
    'knowledge.channels.title': 'القنوات',
    'knowledge.channels.connectionsUnavailable':
      'لا يمكن عرض حالة الاتصالات: المسار في العقد يطلب مُدخل TenantContext لا يمكن إرساله على سلسلة الاستعلام، ويحلّه الخادم من الجلسة أصلاً.',
    'knowledge.escalation.title': 'التصعيد',
    'knowledge.escalation.inConversation': 'تسجيل الحل داخل المحادثة',
    'authoring.title': 'تأليف التقارير',
    'authoring.definition.title': 'تعريف تقرير',
    'authoring.definition.submit': 'أنشئ التعريف',
    'authoring.definition.list': 'التعريفات التي يمكنك تأليفها',
    'authoring.definition.listEmpty': 'لا يوجد تعريف بعد. أنشئ واحداً بالأعلى لتبدأ.',
    'authoring.version.author': 'أضف نسخة',
    'authoring.version.submit': 'أنشئ النسخة',
    'authoring.version.publish': 'انشر النسخة',
    'authoring.version.retire': 'أقعد النسخة',
    'authoring.version.measures': 'المقاييس',
    'authoring.version.noMeasures': 'اختر مقياساً واحداً على الأقل. الخادم يرفض نسخة بلا مقياس.',
    'authoring.version.created': 'أُنشئت النسخة كمسودة. لا يمكن تثبيتها في تصدير حتى تُنشر.',
    'authoring.version.published': 'نُشرت النسخة. أُعيد قراءة الدليل، و exportable في الدليل هو ما يقرره الخادم.',
    'authoring.version.empty': 'لا توجد نسخة بعد.',
    'authoring.refusal.notAvailable': 'لم يردّ الخادم على هذا العنصر. يردّ بالطريقة نفسها سواء لم يوجد أو لم تكن لك صلاحية التأليف، ولذلك لا يستطيع هذا العرض أن يخبرك أيّهما هو.',
    'authoring.refusal.permission': 'هذه العملية تحتاج صلاحية أخرى.',
    'authoring.template.title': 'قالب مستند',
    'authoring.template.submit': 'أنشئ القالب',
    'authoring.template.upload': 'ارفع النسخة',
    'authoring.template.list': 'القوالب التي يمكنك تأليفها',
    'authoring.template.listEmpty': 'لا يوجد قالب بعد. أنشئ واحداً بالأعلى لتبدأ.',
    'authoring.template.created': 'أُنشئ القالب. ارفع له نسخة بملف DOCX ثم انشرها.',
    'authoring.template.uploaded': 'رُفعت النسخة كمسودة. لا يمكن تثبيتها في تصدير حتى تُنشر.',
    'authoring.template.noFile': 'اختر ملفاً أولاً.',
    'authoring.needsProject': 'اختر مشروعاً أولاً. تأليف التقارير يُقرأ لمشروع بعينه.',
    'authoring.summary.empty': 'لم يردّ الخادم على ملخص التأليف لهذا المشروع.',
    'authoring.field.key': 'المعرّف',
    'authoring.field.retentionClass': 'صنف الاحتفاظ',
    'authoring.mayAuthor.granted': 'ممنوحة لك',
    'authoring.mayAuthor.absent': 'غير ممنوحة لك',
    'authoring.state.draft': 'مسودة: موجودة على الخادم، ولا يمكن تثبيتها في تصدير.',
    'authoring.state.published': 'منشورة: هذه هي النسخة التي يثبّت عليها التصدير.',
    'authoring.state.retired': 'مقاعدة: لم تعد تُستخدم، وحلّت محلها نسخة أخرى.',
  },
  en: {
    'login.title': 'Welcome to FirstElement',
    'login.email': 'Email address',
    'login.password': 'Password',
    'login.submit': 'Sign in',
    'login.showPassword': 'Show password',
    'login.error.invalidCredentials':
      'The email or password does not match our records. Check both fields and try again.',
    'login.error.rateLimited': 'Too many attempts in a short time. Wait a little, then try again.',
    'shell.nav.dashboard': 'Dashboard',
    'shell.nav.admin': 'Administration',
    'shell.nav.audit': 'Audit trail',
    'shell.nav.finance': 'Finance',
    'shell.nav.exports': 'Reports & exports',
    'shell.nav.clinic': 'Clinic',
    'admin.title': 'Administration',
    'admin.refresh': 'Refresh',
    'admin.overview.title': 'Overview',
    'admin.backups.title': 'Backups',
    'admin.migrations.title': 'Database migrations',
    'admin.commands.title': 'Maintenance commands',
    'admin.audit.title': 'Audit trail',
    'admin.governance.title': 'Legal governance',
    'admin.sessions.title': 'Active sessions',
    'admin.jobs.title': 'Export jobs',
    'admin.writes.title': 'Writes this screen does not offer',
    'admin.unavailable.purge': 'Purging data',
    'admin.unavailable.migrations': 'Per-migration detail rows',
    'finance.title': 'Reports and finance',
    'finance.create.title': 'Create an export',
    'finance.create.choose': 'Choose…',
    'finance.create.chooseVersion': 'Choose a version…',
    'finance.create.chooseFormat': 'Choose a format…',
    'finance.create.chooseLocale': 'Choose a language…',
    'finance.create.report': 'Report definition',
    'finance.create.reportVersion': 'Report version',
    'finance.create.template': 'Document template',
    'finance.create.templateVersion': 'Template version',
    'finance.create.format': 'Format',
    'finance.create.locale': 'Artefact language',
    'finance.create.localeHint': 'The language of the labels, dates and numbers inside the generated file, which is independent of the language used on screen.',
    'finance.create.filters': 'Filters',
    'finance.create.filtersHint': 'Only the fields the catalogue declares. Any other key is refused.',
    'finance.create.submit': 'Create export',
    'finance.create.submitting': 'Creating…',
    'finance.create.queued': 'The server accepted the export. Its progress is in the list below.',
    'finance.create.formatRefused': 'The server refused this format. Choose another one from the catalogue.',
    'finance.create.needsProject': 'Choose a project first. The catalogue is read for one project.',
    'finance.catalogue.title': 'What can be exported',
    'finance.catalogue.emptyBody': 'Nothing here can be exported. That is what the server reports, not a client failure.',
    'finance.exports.title': 'Exports',
    'finance.refresh': 'Refresh',
    'finance.usage.title': 'Usage',
    'finance.periods.title': 'Accounting periods',
    'finance.rates.title': 'Rate cards',
    'finance.reconciliation.title': 'Reconciliation',
    'finance.policy.title': 'Download and retention policy',
    'finance.gate.title': 'Finance gate on export',
    'finance.writes.title': 'Writes this screen does not offer',
    'finance.unavailable.reports': 'Report definitions and the template library',
    'finance.unavailable.clinic': 'Appointments and procedures',
    'finance.catalogue.formats': 'Formats offered',
    'finance.catalogue.definitionsCount': 'Report definitions',
    'finance.catalogue.templatesCount': 'Document templates',
    'finance.create.needsChoices': 'Choose a report definition and its version, and a document template and its version, before creating.',
    'finance.create.description': 'An export is made from the catalogue the server holds for this project: choose a report definition and its version, and a document template and its version.',
    'finance.create.versionExportable': 'Available to export',
    'finance.create.versionWithheld': 'Not available to export',
    'finance.create.versionExportableBody': 'This version is published, and an export may pin it.',
    'finance.create.versionWithheldBody': 'The server gave no reason, so this version is not available to export.',
    'clinic.title': 'Clinic',
    // Clinic read model (Phase 37). These four replace the gap-screen strings below, which
    // asserted a screen that no longer exists.
    'clinic.analytics.sourceTable': 'Derived from',
    'clinic.analytics.noRevenue':
      'No amounts are shown here. Pricing is a finance decision behind an effective-dated rate card, not a column on a procedure row, so no money is computed from these counts.',
    'clinic.analytics.noUtilization':
      'No utilisation ratio is shown. Its denominator — working hours, slot length, days per week — is a clinic policy, not a row.',
    'clinic.analytics.sampleNote':
      'The id list per row is a bounded sample. The count shown is the full total; the sample is part of it.',
    'clinic.unavailable.title': 'The clinic is not available in this build',
    'clinic.unavailable.missingRoutes': 'The routes that are missing:',    'shell.nav.settings': 'Settings',
    'shell.account.signOut': 'Sign out',
    'shell.account.sessionEnded': 'Your session ended. Sign in again to continue.',
    'shell.account.sessionRevoked': 'Your session was ended elsewhere. Sign in again to continue.',
    'shell.theme.toggle': 'Switch theme',
    'shell.locale.toggle': 'Change language',
    'shell.realtime.connected': 'Live',
    'shell.realtime.reconnecting': 'Reconnecting…',
    'shell.realtime.offline': 'No live connection',
    'shell.realtime.stale': 'This data may be out of date',
    'shell.realtime.resync': 'Resync now',
    'shell.projectScope': 'Projects available to you',
    'shell.projectAll': 'All projects',
    'state.mainContent': 'Skip to main content',
    'state.loading': 'Loading',
    'state.error': 'This page could not be loaded.',
    'state.retry': 'Try again',
    'state.requestId': 'Request id',
    'state.denied.title': 'You do not have access to this',
    'state.notFound.title': 'Page not found',
    'dashboard.title': 'Operations',
    'dashboard.refresh': 'Refresh',
    'dashboard.resync': 'Resync now',
    'dashboard.staleNotice':
      'The live connection is unavailable, so what you see may be out of date. Resync now re-reads it from the server.',
    'settings.title': 'Settings',
    'settings.refresh': 'Refresh',
    'inbox.title': 'Inbox',
    'inbox.refresh': 'Refresh',
    'inbox.queue.title': 'Waiting, unowned',
    'inbox.queue.waiting': 'Waiting',
    'inbox.list.filterUnassigned': 'Unassigned',
    'inbox.list.filterAllOpen': 'All open',
    'inbox.list.filterAssignedToOthers': 'Assigned to others',
    'inbox.list.filterAssignedToMe': 'Assigned to me',
    'inbox.list.filterHistory': 'History',
    'inbox.list.filterGroup': 'Saved views',
    'inbox.thread.empty': 'Choose a conversation',
    'inbox.thread.selectPrompt': 'No conversation is selected yet.',
    'inbox.composer.label': 'Write a reply',
    'inbox.composer.send': 'Send',
    'inbox.composer.sending': 'Sending…',
    'inbox.composer.empty': 'Write something before sending.',
    'inbox.case.close': 'Close conversation',
    'inbox.case.reopen': 'Reopen',
    'inbox.case.closed': 'The conversation was closed.',
    'inbox.case.reopened': 'The conversation was reopened.',
    'inbox.case.assignToMe': 'Assign to me',
    'inbox.case.closeNeedsResolution': 'Record how the case was resolved before closing it.',
    'inbox.mode.human': 'Human',
    'inbox.mode.ai': 'AI',
    'inbox.mode.takeOver': 'Take over replying',
    'inbox.mode.changed': 'The reply mode changed.',
    'inbox.resolution.label': 'Record the resolution',
    'inbox.resolution.outcome': 'Outcome',
    'inbox.resolution.save': 'Save resolution',
    'inbox.resolution.customerIssue': 'Customer issue',
    'inbox.resolution.agentAction': 'Action taken',
    'inbox.resolution.outcomeRequired': 'Choose how the case ended before saving.',
    'inbox.resolution.required': 'This field is required before saving.',
    'inbox.resolution.saved': 'The resolution was saved.',
    'inbox.resolution.escalationRequested': 'Escalation requested',
    'inbox.resolution.escalatedToSupervisor': 'Escalated to a supervisor',
    'inbox.resolution.escalationNote':
      'Escalation is a field on this record. The system has no separate escalation queue.',
    'inbox.status.OPEN': 'Open',
    'inbox.status.CLOSED': 'Closed',
    'inbox.list.label': 'Conversation list',
    'people.title': 'People',
    'people.refresh': 'Refresh',
    'people.detail.title': 'Customer record',
    'people.detail.selectPrompt': 'Choose a customer',
    'people.detail.notes': 'Internal notes',
    'people.detail.addNote': 'Add an internal note',
    'people.detail.noteSave': 'Save note',
    'people.detail.noteEmpty': 'Write something before saving.',
    'people.detail.noteSaved': 'The note was saved.',
    'people.detail.cxId': 'CX id',
    'people.detail.conversations': 'Conversations',
    'people.separation.note':
      'These are internal notes the customer does not see, and are not the record they see.',
    'team.title': 'Team',
    'team.refresh': 'Refresh',
    'team.table.active': 'Active',
    'team.permissions.granted': 'Granted',
    'team.permissions.title': 'A user’s permissions',
    'team.unavailable.title': 'Working hours, routing and import',
    'knowledge.title': 'Knowledge & agent',
    'knowledge.refresh': 'Refresh',
    'knowledge.freshness.title': 'Search freshness',
    'knowledge.ai.title': 'AI governance',
    'knowledge.ai.readOnly':
      'This section is read-only. Every decision is taken as an explicit command, not from here.',
    'knowledge.channels.title': 'Channels',
    'knowledge.channels.connectionsUnavailable':
      'Connection health cannot be shown: the route in the contract requires a TenantContext query parameter that cannot be serialised onto a query string, and the server resolves it from the session anyway.',
    'knowledge.escalation.title': 'Escalation',
    'knowledge.escalation.inConversation': 'The case resolution inside the conversation',
    'authoring.title': 'Report authoring',
    'authoring.definition.title': 'A report definition',
    'authoring.definition.submit': 'Create definition',
    'authoring.definition.list': 'Definitions you may author',
    'authoring.definition.listEmpty': 'No definition yet. Create one above to begin.',
    'authoring.version.author': 'Add a version',
    'authoring.version.submit': 'Create version',
    'authoring.version.publish': 'Publish version',
    'authoring.version.retire': 'Retire version',
    'authoring.version.measures': 'Measures',
    'authoring.version.noMeasures': 'Choose at least one measure. The server refuses a version without one.',
    'authoring.version.created': 'The version was created as a draft. It cannot be pinned by an export until it is published.',
    'authoring.version.published': 'The version is published. The catalogue has been re-read; exportable in it is the server decision, not this screen.',
    'authoring.version.empty': 'No version yet.',
    'authoring.refusal.notAvailable': 'The server did not return this item. It answers the same way whether it does not exist or you may not author it, so this screen cannot tell you which.',
    'authoring.refusal.permission': 'That operation needs a different permission.',
    'authoring.template.title': 'A document template',
    'authoring.template.submit': 'Create template',
    'authoring.template.upload': 'Upload version',
    'authoring.template.list': 'Templates you may author',
    'authoring.template.listEmpty': 'No template yet. Create one above to begin.',
    'authoring.template.created': 'The template was created. Upload a version as a DOCX file, then publish it.',
    'authoring.template.uploaded': 'The version was uploaded as a draft. It cannot be pinned by an export until it is published.',
    'authoring.template.noFile': 'Choose a file first.',
    'authoring.needsProject': 'Choose a project first. Report authoring is read for one project.',
    'authoring.summary.empty': 'The server did not answer with an authoring summary for this project.',
    'authoring.field.key': 'Key',
    'authoring.field.retentionClass': 'Retention class',
    'authoring.mayAuthor.granted': 'Granted to you',
    'authoring.mayAuthor.absent': 'Not granted to you',
    'authoring.state.draft': 'Draft: it exists on the server and cannot be pinned by an export.',
    'authoring.state.published': 'Published: this is the version an export pins.',
    'authoring.state.retired': 'Retired: no longer used, and superseded by another version.',
  },
};

/**
 * The base test, parameterised.
 *
 * Every journey is written as a function of the matrix, so "it works in English"
 * can never be the only thing that was checked: a new locale or a new width
 * automatically gains every journey, with no file to remember to add.
 */
export const test = base.extend<{ journey: Journey }>({
  journey: async ({ page }, use, testInfo) => {
    const { locale, viewport } = journeyFor(testInfo.project.name);
    await page.addInitScript(
      ([key]) => {
        window.localStorage.setItem('fe.locale.v1', String(key));
      },
      [locale],
    );
    await page.setViewportSize(VIEWPORTS[viewport]);
    await use(new Journey(page, locale, viewport, testInfo.project.name));
  },
});

export { expect };
