/**
 * Group 10 — finance, and group 11 — reports and exports.
 *
 * Both live in one file because they answer one question from opposite ends: *what did
 * this cost, and what came out of it.* Finance is the priced side, exports are the
 * artefact side, and an operator asking about a figure wants both.
 *
 * ## Which routes this screen calls
 *
 * | Section | Route | Backend permission |
 * |---|---|---|
 * | Usage, daily / monthly / forecast | `GET /api/v1/finance/usage/*` | `analytics:read-organization` |
 * | Periods, history, adjustments, close readiness | `GET /api/v1/finance/periods/*` | `analytics:read-organization` |
 * | Rate cards and versions | `GET /api/v1/finance/rate-cards/*` | `analytics:read-organization` |
 * | Reconciliation runs and lines | `GET /api/v1/finance/reconciliation/*` | `analytics:read-organization` |
 * | Legacy comparison | `GET /api/v1/finance/legacy/comparison` | `analytics:read-organization` |
 * | Drill-through snapshots and corrections | `GET /api/v1/finance/drill/*` | `analytics:read-organization` |
 * | Export list and queue | `GET /api/v1/exports`, `/queue` | `analytics:read-project` |
 * | Export catalogue, download policy | `GET /api/v1/exports/catalogue`, `/download-policy` | `analytics:read-project` |
 * | One export, its attempts, downloads, deletions | `GET /api/v1/exports/{id}/*` | `analytics:read-project` |
 * | Finance gate on export | `GET /api/v1/exports/finance-gate` | `analytics:read-organization` |
 *
 * ## One permission for all of finance, and what that means for the UI
 *
 * `FinanceController` guards **all twenty-four** routes with `analytics:read-organization`
 * and says so deliberately: *"There is no weaker permission for 'what does this
 * cost'."* So the client gates the entire finance section on that one value and does
 * not pretend a narrower role exists. Inventing a per-card permission the backend does
 * not enforce would produce a screen that hides data from people who may see it and
 * shows it to people who may not.
 *
 * Exports split two ways, and the split is real: `analytics:read-project` for one
 * project's report rows, `analytics:read-organization` for the finance gate. Both are
 * checked independently rather than one inheriting from the other.
 *
 * ## The eleven finance writes, and the export writes
 *
 * Not rendered. Locking a period, approving and applying an adjustment, computing a
 * rollup, running a reconciliation: the service layer additionally requires an `ADMIN`
 * role, so these are finance-administrator acts rather than ordinary ones. Creating,
 * retrying, cancelling and downloading an export needs a download policy this client
 * cannot read a grant for. Both families are listed as named commands with the reason
 * they are not buttons.
 *
 * ## Why creating an export is not offered
 *
 * `POST /api/v1/exports` requires `reportDefinitionId`, `reportVersion`,
 * `documentTemplateId` and `documentTemplateVersion` — and **no route lists report
 * definitions or document templates**. There is nothing for a picker to choose from, so
 * a create form could only be filled by typing UUIDs from a document, which is worse
 * than not offering it. The screen states the gap and names the missing routes.
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { map, skip } from 'rxjs';
import { ApiError } from '../../core/api/api.service';
import { ExportsApi, FinanceApi } from '../../core/api/endpoints';
import type * as Api from '../../core/api/generated';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { notBlank } from '../../core/validation/not-blank.validator';
import { AuthoringComponent } from '../exports-authoring/authoring.component';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { NotAvailableComponent } from '../../ui/not-available.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { financeStrings } from './finance.strings';

interface FactRow {
  readonly key: string;
  readonly labelKey: string;
  readonly rawLabel: string;
  readonly value: string;
}

@Component({
  selector: 'fe-finance-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    AuthoringComponent,
    NotAvailableComponent,
    PageHeaderComponent,
    ReactiveFormsModule,
    TranslatePipe,
  ],
  templateUrl: './finance.component.html',
  styleUrl: './finance.component.css',
})
export class FinanceComponent implements OnInit {
  private readonly finance = inject(FinanceApi);
  private readonly exportsApi = inject(ExportsApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly i18n = inject(I18nService);
  protected readonly sessions = inject(SessionService);
  protected readonly projects = inject(ProjectContextService);
  protected readonly formBuilder = inject(FormBuilder);

  protected readonly daily = new ResourceStore<Record<string, unknown>>();
  protected readonly monthly = new ResourceStore<Record<string, unknown>>();
  protected readonly forecast = new ResourceStore<Record<string, unknown>>();
  protected readonly periods = new ResourceStore<Record<string, unknown>>();
  protected readonly rateCards = new ResourceStore<Record<string, unknown>>();
  protected readonly reconciliation = new ResourceStore<Record<string, unknown>>();
  protected readonly legacy = new ResourceStore<Record<string, unknown>>();
  protected readonly gate = new ResourceStore<Record<string, unknown>>();

  protected readonly exportList = new ResourceStore<Record<string, unknown>>();
  protected readonly exportQueue = new ResourceStore<Record<string, unknown>>();
  /**
   * The export catalogue, typed from the generated client.
   *
   * A real `$ref` to `ExportCatalogue` now (ADR 0016), so unlike the finance surfaces
   * around it this one is **not** a free-form document and needs no defensive reading.
   * `null` when no project is selected, because the route requires one.
   */
  protected readonly catalogue = new ResourceStore<Api.ExportCatalogue | null>();
  protected readonly downloadPolicy = new ResourceStore<Record<string, unknown>>();
  protected readonly financeGate = new ResourceStore<Record<string, unknown>>();

  // -- export creation ------------------------------------------------------

  /**
   * The export-creation form.
   *
   * ## Why the format and locale are `<select>`s over the catalogue's own arrays
   *
   * `format` is an enum of `CSV` and `DOCX`. The database's `ExportFormat` enum also
   * declares `XLSX`, `PDF` and `JSON`, and the server refuses those with
   * `422 EXPORT_FORMAT_UNSUPPORTED` because this renderer does not produce them. So a
   * form that offered them would offer three choices guaranteed to fail — and a file
   * whose extension lies about its contents is the worst outcome this subsystem has.
   * The options come from `catalogue.formats`, not from a local list, for the same
   * reason the counts come from the response key order.
   *
   * `locale` is `ar` or `en`, lower case. Absent means `ar`, because the product is
   * Arabic-first; an undeclared locale is refused rather than rendered in another
   * language, since a file that looks right and is in the wrong language is the worst
   * outcome too. **The locale chosen here is the artefact's language, not the screen's**,
   * so both options are always offered regardless of which one the operator is reading
   * in.
   *
   * ## Why `notBlank` on the filter fields
   *
   * An unknown `filters` key is refused with a 400. A known key with an empty value is
   * not, and is simply omitted from the request — so a field the operator left blank
   * never becomes `{"key": ""}`, which would be a filter that matches nothing and reads
   * as "the export is empty" rather than "you did not set that filter".
   */
  /**
   * The artefact formats this build will send, narrowed from the catalogue's array.
   *
   * The catalogue declares `formats` as a bare `Array<string>`, while the wire type is
   * the closed union `"CSV" | "DOCX"`. Offering a value the wire cannot carry would
   * produce a 422 the operator cannot act on, so an unrecognised entry is **dropped**
   * rather than passed through — and `asFormatRefusals` records what was dropped so the
   * screen can say so rather than silently offering less than the server does.
   */
  protected readonly catalogueFormatValues = computed<('CSV' | 'DOCX')[]>(() => {
    const declared = this.catalogue.value()?.formats ?? [];
    return declared
      .map((value) => asFormat(value))
      .filter((value): value is 'CSV' | 'DOCX' => value !== null);
  });

  /** The formats the catalogue offered that this client will not send. */
  protected readonly asFormatRefusals = computed<string[]>(() => {
    const declared = this.catalogue.value()?.formats ?? [];
    return declared.filter((value) => asFormat(value) === null);
  });

  protected readonly creationForm = this.formBuilder.group({
    reportDefinitionId: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    reportVersion: this.formBuilder.control<number | null>(null, Validators.required),
    documentTemplateId: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    documentTemplateVersion: this.formBuilder.control<number | null>(null, Validators.required),
    // `format` and `locale` are **the generated enums**, not free strings: the server
    // refuses anything else, and `CreateExportRequest.format` is typed
    // `"CSV" | "DOCX"`. Typing the control as the wire union is what stops an
    // unvalidated string reaching `create()` — a compile error instead of a 422.
    /**
     * The artefact format, or `null` when none has been chosen.
     *
     * **Nullable, and that is the point.** This control was declared
     * `FormControl<CreateExportRequest['format']>` — a type that cannot be null — and
     * initialised to `undefined`, with the template's placeholder option carrying
     * `[ngValue]="undefined"` to paper over the gap.
     *
     * Angular serialises an `[ngValue]` option with `SelectControlValue` semantics, and for
     * `undefined` that serialisation is the literal string `"0: undefined"`. So the
     * placeholder rendered an option whose **value was `0: undefined`** — a value the server
     * has never heard of, sitting in a select whose whole promise is that it offers nothing
     * the server refuses.
     *
     * `null` is what the sibling `reportVersion` control already uses, and it serialises
     * predictably. Same form, same idiom: a control that may be unchosen is nullable, and its
     * placeholder says so with `null`.
     */
    format: this.formBuilder.control('', { nonNullable: true, validators: [Validators.required] }),
    /** The locale, or `null`. Nullable for the same reason as `format`, and the same fix. */
    locale: this.formBuilder.control('', { nonNullable: true, validators: [Validators.required] }),
    // Rebuilt whenever the catalogue loads — a `FormGroup` cannot gain a control after
    // construction. See `syncFilterControls`.
    //
    // An **untyped** `FormGroup`, reached only through `filterControl(key)`.
    //
    // Typed forms cannot express "a group whose keys are whatever the server said": an
    // empty `group()` infers `ɵNullableFormControls<{}>`, which has no index signature,
    // and annotating it as `Record<string, FormControl<string>>` fights `addControl`'s
    // `this` type instead. So the group is untyped and the one accessor returns a typed
    // control, which is both simpler and honest: a dynamic set of keys is a dynamic
    // group, and the template never indexes it directly.
    filters: new FormGroup({}),
  });

  /** The control for one catalogue-declared filter key, or `null` if it is absent. */
  protected filterControl(key: string): FormControl<string> | null {
    const control = this.creationForm.controls.filters.get(key);
    return control instanceof FormControl ? (control as FormControl<string>) : null;
  }

  protected readonly creating = signal(false);
  protected readonly creationError = signal<string | null>(null);
  protected readonly creationNotice = signal<string | null>(null);
  protected readonly queued = signal<Api.QueuedExport | null>(null);
  /** The correlation id behind a refused creation, so the report is answerable. */
  protected readonly creationErrorRequestId = signal<string | null>(null);

  /**
   * The two export scopes, checked independently.
   *
   * `analytics:read-project` is what one project's report rows need; the finance gate
   * is organization-wide and is a different value. A session with only the first must
   * see the export list and see nothing of the gate — and must be able to tell the
   * difference between "no gate for me" and "no gate here".
   */
  protected readonly canReadExports = computed(() => this.sessions.can()(PERMISSIONS.analyticsReadProject));
  protected readonly canReadFinance = computed(() =>
    this.sessions.can()(PERMISSIONS.analyticsReadOrganization),
  );

  /**
   * Whether this caller may **author** report definitions and document templates.
   *
   * Its own permission, `report:manage, which replaced a borrowed workflow:manage. So
   * this is deliberately **not** canReadExports(): a report author is not automatically a
   * finance reader, and gating authoring on the analytics permission would offer it to people
   * whose job it is not and withhold it from the people whose job it is.
   *
   * UX only — the server checks `report:manage on all thirteen authoring routes and answers
   * a caller without it with a non-disclosing 404.
   */
  protected readonly canAuthorExports = computed(() =>
    this.sessions.can()(PERMISSIONS.reportManage),
  );

  /**
   * Why each version of the chosen definition is or is not available, in words.
   *
   * `exportable` and `refusal` are the **server's** answers, computed against the calling
   * user's own permissions. Nothing here decides whether a version may be pinned: the
   * catalogue says so, and this only arranges the saying.
   *
   * The refusal string is shown **verbatim** rather than replaced with a client phrase,
   * because the server's sentence is more specific than any translation of it — it names
   * the lifecycle state, and for a withheld permission it names the permission. When the
   * server sends no reason at all, the fallback is a plain statement that it is not
   * available, which is still true and still not a guess.
   */
  protected readonly versionExplanations = computed<
    readonly {
      number: number;
      state: string;
      exportable: boolean;
      reason: string;
    }[]
  >(() => {
    const row = this.selectedDefinition();
    if (!row) return [];
    return row.versions.map((version, index) => {
      const exportable = version.exportable === true;
      return {
        number: index + 1,
        // The lifecycle state, as the catalogue published it - never inferred.
        state: version.state ?? '',
        exportable,
        reason: exportable
          ? this.i18n.t('finance.create.versionExportableBody')
          : (version.refusal ?? this.i18n.t('finance.create.versionWithheldBody')),
      };
    });
  });

  /**
   * Which section the operator is looking at, seeded from `?tab=`.
   *
   * The navigation's "exports" item points here with `?tab=exports`, so arriving from it
   * must land on the exports — the same reasoning as the inbox's `?c=` and the admin
   * screen's `?tab=`. Validated against the tab list and against the caller's
   * permissions, so a deep link can never open a section they may not read.
   */
  protected readonly tab = signal<FinanceTab>('usage');

  protected readonly tabs = computed<readonly { id: FinanceTab; key: string }[]>(() => {
    const all = [
      { id: 'usage' as const, key: 'finance.usage.title' },
      { id: 'periods' as const, key: 'finance.periods.title' },
      { id: 'rates' as const, key: 'finance.rates.title' },
      { id: 'reconciliation' as const, key: 'finance.reconciliation.title' },
      { id: 'exports' as const, key: 'finance.exports.title' },
      { id: 'authoring' as const, key: 'authoring.title' },
      { id: 'policy' as const, key: 'finance.policy.title' },
    ];
    return all.filter((entry) => {
      // Authoring is on `report:manage`, which is neither the organization scope finance
      // uses nor the project scope exports use. It is its own tab so a report author is
      // not offered the finance surfaces, and a finance reader is not offered authoring.
      if (entry.id === 'authoring') return this.canAuthorExports();
      return entry.id === 'exports' || entry.id === 'policy'
        ? this.canReadExports()
        : this.canReadFinance();
    });
  });

  /**
   * The current month as an inclusive `from`/`to` pair of ISO dates.
   *
   * `GET /api/v1/exports/finance-gate` requires both, and the gate is a question about a
   * period — so the period this screen shows is the one it asks about. Computed from the
   * clock rather than written as a literal, because a literal is correct only until the
   * month after the one in it.
   */
  protected currentMonth(): { from: string; to: string } {
    const now = new Date();
    const year = now.getFullYear();
    const month = now.getMonth();
    const lastDay = new Date(year, month + 1, 0).getDate();
    const pad = (value: number): string => String(value).padStart(2, '0');
    return {
      from: `${year}-${pad(month + 1)}-01`,
      to: `${year}-${pad(month + 1)}-${pad(lastDay)}`,
    };
  }

  /**
   * The locale the **screen** is in, for choosing which of the catalogue's two name
   * fields to show. Arabic first, English second, and the wire `key` last rather than
   * nothing — an entry with no name is still an entry an operator must be able to pick.
   */
  protected readonly locale = computed(() => this.i18n.locale());

  constructor() {
    this.i18n.register({ finance: financeStrings as Namespace });

    // The catalogue's declared defaults seed the form, but only once it has loaded —
    // seeding from a local guess before the response arrives would offer a value the
    // catalogue might not list.
    effect(() => {
      const catalogue = this.catalogue.value();
      if (!catalogue) return;

      // Seeded from the catalogue's own defaults, **and only when the operator has not
      // chosen.** The values are narrowed through the wire enums rather than cast, so a
      // catalogue that declared a format this build does not know about leaves the select
      // empty and the form invalid — which is the correct outcome: an operator cannot post
      // a format the client cannot name, and cannot post one the catalogue did not list.
      const format = asFormat(catalogue.defaultFormat);
      if (format && this.creationForm.controls.format.value == null) {
        this.creationForm.controls.format.setValue(format);
      }
      const locale = asLocale(catalogue.defaultLocale);
      if (locale && this.creationForm.controls.locale.value == null) {
        this.creationForm.controls.locale.setValue(locale);
      }
    });

    this.watchProject();

    const requested = toSignal(this.route.queryParamMap.pipe(map((params) => params.get('tab'))), {
      initialValue: null,
    });
    effect(() => {
      const value = requested();
      if (!value) return;
      const tab = value as FinanceTab;
      if (!FINANCE_TABS.includes(tab)) return;
      // The exports and policy tabs are the project scope; the rest are the
      // organization one. A link must not open a tab the caller cannot read.
      const permitted =
        tab === 'authoring'
        ? this.canAuthorExports()
        : tab === 'exports' || tab === 'policy'
          ? this.canReadExports()
          : this.canReadFinance();
      if (!permitted) return;
      untracked(() => this.tab.set(tab));
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  /**
   * Re-read everything when the selected project changes.
   *
   * ## Why this is not a nicety
   *
   * The screen loads once in `ngOnInit`. Before this effect, switching projects in the
   * shell's switcher left **every figure on this screen describing the previous
   * project** — revenue, cost, margin, periods, rate cards, exports, all of it — with
   * nothing on screen to say so. That is the worst kind of wrong on a money screen: a
   * number that is confidently stated and belongs to someone else.
   *
   * It also broke the catalogue outright, because
   * `GET /api/v1/exports/catalogue` **requires** `projectId`. With "All projects"
   * selected at first paint the read was not attempted at all, and choosing a project
   * afterwards never retried it — so the create form sat there permanently empty and the
   * heading showed nothing at all.
   *
   * `skip(1)` because the constructor's own call covers the first value; reloading on
   * mount as well would double every request on page load.
   */
  private watchProject(): void {
    toObservable(this.projects.selectedProjectId)
      // `skip(1)`: the constructor's own `load()` covers the first value, and reloading
      // on mount as well would double every request on page load.
      .pipe(
        skip(1),
        // Tied to the component's lifetime, so a destroyed screen stops issuing requests
        // rather than holding the subscription for the life of the app.
        takeUntilDestroyed(),
      )
      .subscribe(() => void this.load());
  }

  protected async load(): Promise<void> {
    const projectId = this.projects.selectedProjectId() ?? undefined;
    const jobs: Promise<unknown>[] = [];

    if (this.canReadFinance()) {
      jobs.push(
        this.daily.load(() => this.finance.usageDaily({ projectId }), isEmptyDocument),
        this.monthly.load(() => this.finance.usageMonthly({ projectId }), isEmptyDocument),
        this.forecast.load(() => this.finance.usageForecast({ projectId }), isEmptyDocument),
        this.periods.load(() => this.finance.periods({ projectId }), isEmptyDocument),
        this.rateCards.load(() => this.finance.rateCards({ projectId }), isEmptyDocument),
        this.reconciliation.load(() => this.finance.reconciliationRuns({ projectId }), isEmptyDocument),
        this.legacy.load(() => this.finance.legacyComparison({ projectId }), isEmptyDocument),
      );
    }
    if (this.canReadExports()) {
      jobs.push(
        this.exportList.load(() => this.exportsApi.list({ projectId }), isEmptyDocument),
        this.exportQueue.load(() => this.exportsApi.queue({ projectId }), isEmptyDocument),
        this.downloadPolicy.load(() => this.exportsApi.downloadPolicy({ projectId })),
      );
      // The catalogue **requires** a projectId, so it is not asked for without one.
      // With no project selected the server would answer the whole server-resolved
      // scope, which is not what this parameter means — so the create section says it
      // needs a project rather than sending a request the contract forbids.
      if (projectId !== undefined) {
        jobs.push(this.catalogue.load(() => this.exportsApi.catalogue(projectId)));
      }
      this.syncFilterControls(this.catalogueFields());
    }

    // The one export route the *organization* scope reaches. Kept separate from the
    // project-scoped reads above because it is a different value.
    //
    // **Three required parameters**, all three declared `required`: `projectId`, `from`
    // and `to`. The previous version sent only `projectId`, and the server answered
    // `MissingServletRequestParameterException: Required request parameter 'projectId'
    // ... is not present` as a **500** — because that exception is mapped to no status in
    // `ErrorHandlingAdvice`, so a missing parameter is reported as an internal error
    // rather than a 400 (Hook 18).
    //
    // The window is the current month, computed from the clock rather than hard-coded,
    // because a hard-coded range is a stale literal the day after it is written.
    if (this.canReadFinance() && projectId !== undefined) {
      const window = this.currentMonth();
      jobs.push(
        this.financeGate.load(() =>
          this.exportsApi.financeGate({ projectId, from: window.from, to: window.to }),
        ),
      );
    }

    await Promise.allSettled(jobs);
  }

  // -- rows -----------------------------------------------------------------

  protected usageRows(): FactRow[] {
    return rowsFrom(documentOf(this.daily.value()), USAGE_COLUMNS);
  }

  protected monthlyRows(): FactRow[] {
    return rowsFrom(documentOf(this.monthly.value()), USAGE_COLUMNS);
  }

  protected forecastRows(): FactRow[] {
    return rowsFrom(documentOf(this.forecast.value()), [
      { keys: ['period', 'month', 'bucket'], labelKey: 'finance.usage.title' },
      { keys: ['projected', 'forecast', 'expected'], labelKey: 'finance.usage.title', numeric: true },
      { keys: ['actual', 'realised', 'realized'], labelKey: 'finance.usage.title', numeric: true },
      { keys: ['variance', 'delta'], labelKey: 'finance.usage.title', numeric: true },
      { keys: ['currency'], labelKey: 'finance.rates.title' },
    ]);
  }

  protected periodRows(): FactRow[] {
    return rowsFrom(documentOf(this.periods.value()), [
      { keys: ['key', 'id', 'code'], labelKey: 'finance.periods.title' },
      { keys: ['nameAr', 'nameAr'], labelKey: 'finance.periods.name' },
      { keys: ['status', 'state'], labelKey: 'finance.periods.status' },
      { keys: ['periodStart', 'start', 'from'], labelKey: 'finance.periods.start', date: true },
      { keys: ['periodEnd', 'end', 'to'], labelKey: 'finance.periods.end', date: true },
      { keys: ['currency'], labelKey: 'finance.rates.title' },
      { keys: ['adjustmentCount', 'adjustments'], labelKey: 'finance.periods.adjustments', numeric: true },
    ]);
  }

  protected rateCardRows(): FactRow[] {
    return rowsFrom(documentOf(this.rateCards.value()), [
      { keys: ['name', 'id', 'code'], labelKey: 'finance.rates.title' },
      { keys: ['version', 'revision'], labelKey: 'finance.rates.title', numeric: true },
      { keys: ['currency'], labelKey: 'finance.rates.title' },
      { keys: ['effectiveFrom', 'validFrom'], labelKey: 'finance.periods.start', date: true },
      { keys: ['effectiveTo', 'validTo'], labelKey: 'finance.periods.end', date: true },
    ]);
  }

  protected reconciliationRows(): FactRow[] {
    return rowsFrom(documentOf(this.reconciliation.value()), [
      { keys: ['runId', 'id'], labelKey: 'finance.reconciliation.title' },
      { keys: ['status', 'state', 'outcome'], labelKey: 'finance.reconciliation.title' },
      { keys: ['startedAt', 'createdAt'], labelKey: 'finance.reconciliation.title', date: true },
      { keys: ['lineCount', 'lines'], labelKey: 'finance.reconciliation.title', numeric: true },
      { keys: ['varianceTotal', 'variance'], labelKey: 'finance.reconciliation.title', numeric: true },
    ]);
  }

  protected legacyRows(): FactRow[] {
    return rowsFrom(documentOf(this.legacy.value()), [
      { keys: ['metric', 'key', 'name'], labelKey: 'finance.legacy.title' },
      { keys: ['legacy', 'legacyValue', 'old'], labelKey: 'finance.legacy.title', numeric: true },
      { keys: ['current', 'currentValue', 'new'], labelKey: 'finance.legacy.title', numeric: true },
      { keys: ['variance', 'delta', 'difference'], labelKey: 'finance.legacy.title', numeric: true },
    ]);
  }

  protected gateRows(): FactRow[] {
    return rowsFrom(documentOf(this.gate.value()), [
      { keys: ['allowed', 'permitted', 'enabled'], labelKey: 'finance.gate.title', boolean: true },
      { keys: ['reason', 'blockedReason'], labelKey: 'finance.gate.title' },
      { keys: ['openPeriods', 'periodCount'], labelKey: 'finance.periods.title', numeric: true },
      { keys: ['lockedPeriods'], labelKey: 'finance.periods.status', numeric: true },
    ]);
  }

  protected exportRows(): FactRow[] {
    return rowsFrom(documentOf(this.exportList.value()), EXPORT_COLUMNS);
  }

  protected queueRows(): FactRow[] {
    return rowsFrom(documentOf(this.exportQueue.value()), EXPORT_COLUMNS);
  }

  /**
   * The catalogue's report definitions, read from a typed response.
   *
   * No free-form defensive reading: `ExportCatalogue` is a real `$ref` now, so every
   * field here is declared and a missing one is a server change rather than a shape this
   * client has to guess at. The label is chosen by locale, falling back to the key —
   * Arabic first, English second, and the wire key last rather than nothing, because an
   * entry with no name is still an entry an operator has to be able to select.
   */
  protected reportDefinitions(): CatalogueRow[] {
    return (this.catalogue.value()?.reportDefinitions ?? []).map((definition, index) => ({
      key: `${definition.reportDefinitionId ?? index}`,
      id: definition.reportDefinitionId ?? '',
      name:
        this.locale() === 'ar'
          ? (definition.nameAr ?? definition.nameEn ?? definition.key ?? '')
          : (definition.nameEn ?? definition.nameAr ?? definition.key ?? ''),
      detail:
        this.locale() === 'ar'
          ? (definition.descriptionAr ?? definition.descriptionEn ?? '')
          : (definition.descriptionEn ?? definition.descriptionAr ?? ''),
      status: definition.status ?? '',
      subject: definition.subject ?? '',
      versions: definition.versions ?? [],
      versionsLabel: formatVersionCount((definition.versions ?? []).length),
      exportableCount: (definition.versions ?? []).filter((v) => v.exportable === true).length,
    }));
  }

  protected documentTemplates(): CatalogueRow[] {
    return (this.catalogue.value()?.documentTemplates ?? []).map((template, index) => ({
      key: `${template.documentTemplateId ?? index}`,
      id: template.documentTemplateId ?? '',
      name:
        this.locale() === 'ar'
          ? (template.nameAr ?? template.nameEn ?? template.key ?? '')
          : (template.nameEn ?? template.nameAr ?? template.key ?? ''),
      detail: '',
      status: template.status ?? '',
      subject: template.locale ?? '',
      versions: template.versions ?? [],
      versionsLabel: formatVersionCount((template.versions ?? []).length),
      exportableCount: (template.versions ?? []).filter((v) => v.exportable === true).length,
    }));
  }

  /**
   * The definition the operator has chosen, or `undefined`.
   *
   * Read by id out of the catalogue rather than kept as a separate selection, so the
   * version list can only ever show versions that belong to the definition currently
   * chosen — the two cannot drift because there is only one source.
   */
  protected selectedDefinition(): CatalogueRow | undefined {
    return this.reportDefinitions().find((row) => row.id === this.selectedDefinitionId());
  }

  /**
   * The template the operator has chosen, or `undefined`. Same reasoning.
   */
  protected selectedTemplate(): CatalogueRow | undefined {
    return this.documentTemplates().find((row) => row.id === this.selectedTemplateId());
  }

  /**
   * The chosen definition id, as a **signal**.
   *
   * ## Why this is not read off the control directly
   *
   * `versionExplanations()` is a `computed`, and **a `computed` only tracks signals**. It asks
   * `selectedDefinition()`, which read `creationForm.controls.reportDefinitionId.value` — an
   * ordinary property on a reactive-forms control, not a signal. Nothing the computed
   * depended on had changed when the operator chose a definition, so it never recomputed, and
   * the version explanations stayed at whatever they had last computed: empty.
   *
   * The symptom was a screen that was not wrong anywhere it could be seen. The version
   * `<select>` filled in correctly, because its binding is called from the template and so
   * change detection re-ran it — and directly beneath it the list of explanations stayed
   * empty, which is the **correct** rendering of "no definition chosen". The server's own
   * refusal for a draft version, `"This version is a DRAFT and has never been published"`,
   * was in the response the whole time and was never displayed.
   *
   * Nothing here casts and nothing is re-fetched; the form value is simply made observable,
   * so `computed` can depend on it the way it depends on everything else.
   */
  private readonly selectedDefinitionId = toSignal(
    this.creationForm.controls.reportDefinitionId.valueChanges,
    { initialValue: this.creationForm.controls.reportDefinitionId.value },
  );

  /** The chosen template id, as a signal, for the same reason. */
  private readonly selectedTemplateId = toSignal(
    this.creationForm.controls.documentTemplateId.valueChanges,
    { initialValue: this.creationForm.controls.documentTemplateId.value },
  );

  /** The formats the catalogue declares — `CSV` and `DOCX`, and nothing else. */
  protected catalogueFormats(): string[] {
    return this.catalogue.value()?.formats ?? [];
  }

  protected catalogueLocales(): string[] {
    return this.catalogue.value()?.locales ?? [];
  }

  /**
   * The versions of a catalogue entry the **calling user** may export.
   *
   * `exportable` and `refusal` are computed against this caller's own permissions, so a
   * withheld version is the catalogue being honest rather than an error state. Such a
   * version is still listed, with **its own refusal text** — inventing a reason would be
   * worse than the server's, and the server knows which permission was missing.
   */
  protected selectableVersions(row: CatalogueRow | undefined): {
    readonly version: number;
    readonly label: string;
    readonly exportable: boolean;
    readonly refusal: string | null;
  }[] {
    // **Accepts `undefined`, deliberately.**
    //
    // This was `selectableVersions(row: CatalogueRow)` called as
    // `selectableVersions(selectedDefinition()!)`, and the `!` was a type-level claim the
    // runtime contradicts every time nothing is selected. It threw
    // `TypeError: Cannot read properties of undefined (reading 'versions')` **on every change
    // detection pass**, which aborted the pass part-way through the template — so the
    // definition picker's `<option>` elements were created but never bound, and rendered as
    // `<option></option>`: no `value`, no label, no text.
    //
    // That is why a catalogue the server had populated correctly rendered as an empty picker,
    // and why the count of options matched while every value and every name was empty. The
    // console said `TypeError` on every frame; the screen said "the server returned nothing".
    //
    // "No definition chosen" is a **state**, not an error, so it is answered as one: no rows.
    if (row === undefined) return [];
    return row.versions.map((version, index) => ({
      version: index + 1,
      label: String(index + 1),
      exportable: version.exportable === true,
      refusal: version.refusal ?? null,
    }));
  }

  /**
   * Keeps the filter controls in step with the chosen report definition.
   *
   * `fields` is declared at catalogue level rather than per definition, so the set of
   * filter keys is fixed by the catalogue — but the controls are **rebuilt** whenever it
   * loads, because a `FormGroup` cannot have a control added to it after creation. A
   * key that disappears between catalogue loads therefore drops out of the form and out
   * of the request, rather than being sent and refused with a 400.
   */
  private syncFilterControls(fields: readonly CatalogueField[]): void {
    const group: FormGroup = this.creationForm.controls.filters;
    const wanted = new Set(fields.map((field) => field.key));
    // `Object.keys(group.controls)` rather than iterating `controls`: on an untyped group
    // the latter is typed `{}`, which is the whole reason this group is untyped.
    for (const key of Object.keys(group.controls)) {
      if (!wanted.has(key)) group.removeControl(key, { emitEvent: false });
    }
    for (const field of fields) {
      if (!group.contains(field.key)) {
        group.addControl(field.key, new FormControl('', { nonNullable: true }), {
          emitEvent: false,
        });
      }
    }
  }

  /** The catalogue's own note, verbatim — it explains limits the enum does not. */
  protected catalogueNote(): string | null {
    return this.catalogue.value()?.note ?? null;
  }

  /**
   * The filter keys the chosen definition publishes.
   *
   * `POST /api/v1/exports` **refuses** an unknown `filters` key with a 400 rather than
   * ignoring it, so this offers exactly what the catalogue declared and nothing more.
   */
  protected catalogueFields(): CatalogueField[] {
    const fields = this.catalogue.value()?.fields ?? [];
    return fields
      .map((field) => {
        const key = field['key'] ?? field['name'] ?? '';
        const label = field['label'] ?? field['name'] ?? key;
        return key === '' ? null : { key, label };
      })
      .filter((field): field is CatalogueField => field !== null);
  }

  /** Whether the create form has the four identity fields it needs. */
  protected canCreate(): boolean {
    return this.canReadExports() && this.projects.selectedProjectId() !== undefined;
  }

  /** The four identity values, or `null` when the operator has not chosen all of them. */
  protected createRequest(): Api.CreateExportRequest | null {
    if (!this.projects.selectedProjectId()) return null;
    const definitionId = this.creationForm.controls.reportDefinitionId.value;
    const reportVersion = this.creationForm.controls.reportVersion.value;
    const templateId = this.creationForm.controls.documentTemplateId.value;
    const templateVersion = this.creationForm.controls.documentTemplateVersion.value;
    const format = asFormat(this.creationForm.controls.format.value);
    const locale = asLocale(this.creationForm.controls.locale.value);
    // Every one of the six is a `| null` or `| undefined` case at this point, because
    // the form is a plain `group` over wire-typed values and "unset" has to be
    // representable. Absent → no request, rather than a request with a hole in it.
    if (!definitionId || !templateId) return null;
    if (reportVersion === null || templateVersion === null) return null;
    if (format === null || locale === null) return null;

    // Only keys the catalogue published. Sending anything else is a 400, and the client
    // has no way to invent a reason the server would accept.
    const filters: Record<string, string> = {};
    for (const field of this.catalogueFields()) {
      const control = this.filterControl(field.key);
      if (!control) continue;
      const value = control.value.trim();
      if (value !== '') filters[field.key] = value;
    }

    return {
      reportDefinitionId: definitionId,
      reportVersion,
      documentTemplateId: templateId,
      documentTemplateVersion: templateVersion,
      format,
      locale,
      filters: Object.keys(filters).length > 0 ? filters : undefined,
    };
  }

  /**
   * Re-reads the catalogue after authoring changed something.
   *
   * ## Why a publish has to be followed by a catalogue read
   *
   * Whether a version can be pinned by an export is decided by the **server**, against the
   * calling user's own permissions, and published as exportable plus a `refusal string on
   * the catalogue. So after the authoring tab publishes or retires a version, the only
   * honest thing to do is ask again - and the create form above will then offer exactly what
   * the server now says, and refuse to offer what it says it will refuse.
   *
   * Guessing here instead - 'it is published now, so it must be exportable' - would be a
   * client-side opinion about an authorization decision, which is precisely the thing that
   * made a version once appear pinnable when it was not.
   */
  protected async refreshCatalogue(): Promise<void> {
    const projectId = this.projects.selectedProjectId();
    // Both `null` and `undefined` are checked: the context returns `string | null`, so a
    // single `=== undefined` leaves `projectId` as `string | null` at the call below.
    if (projectId === null || projectId === undefined) return;
    await Promise.allSettled([this.catalogue.load(() => this.exportsApi.catalogue(projectId))]);
    this.syncFilterControls(this.catalogueFields());
  }

  /** Queues the export, and reports what the server accepted. */
  protected async createExport(event: Event): Promise<void> {
    event.preventDefault();
    const body = this.createRequest();
    if (!body) return;
    if (this.creationForm.invalid) {
      this.creationForm.markAllAsTouched();
      return;
    }

    this.creating.set(true);
    this.creationError.set(null);
    this.queued.set(null);
    try {
      const queued = await this.exportsApi.create(body);
      this.queued.set(queued);
      this.creationNotice.set(this.i18n.t('finance.create.queued'));
      // The queue is the thing that shows progress, so refresh it rather than telling
      // the operator to go and look.
      await Promise.allSettled([this.exportList.refresh(), this.exportQueue.refresh()]);
    } catch (cause) {
      // 422 `EXPORT_FORMAT_UNSUPPORTED` is the catalogue being right and the request
      // being wrong; the message says which, in the server's own words for the code and
      // the dictionary's for the rest.
      this.creationError.set(this.describeCreationFailure(cause));
    } finally {
      this.creating.set(false);
    }
  }

  /**
   * Why a creation attempt was refused, in words.
   *
   * A 422 with a machine code is told apart from every other failure, because the two
   * need different remedies: a refused format means pick another one, and anything else
   * means the request itself was wrong. The server's message text is never shown — the
   * i18n layer owns user-facing copy.
   */
  private describeCreationFailure(cause: unknown): string {
    if (cause instanceof ApiError) {
      if (cause.status === 422 || cause.code === 'EXPORT_FORMAT_UNSUPPORTED') {
        return this.i18n.t('finance.create.formatRefused');
      }
      if (cause.status === 403) return this.i18n.t('state.noPermission');
      if (cause.status === 400) return this.i18n.t('finance.create.rejected');
    }
    return this.i18n.t('state.error');
  }

  protected creationRequestId(): string | null {
    return this.creationErrorRequestId();
  }

  protected policyRows(): FactRow[] {
    return rowsFrom(documentOf(this.downloadPolicy.value()), [
      { keys: ['allowed', 'permitted'], labelKey: 'finance.policy.title', boolean: true },
      { keys: ['maxBytes', 'limitBytes', 'bytes'], labelKey: 'finance.policy.title', numeric: true },
      { keys: ['expiresInDays', 'ttlDays', 'days'], labelKey: 'finance.policy.title', numeric: true },
      { keys: ['requiresReason', 'reasonRequired'], labelKey: 'finance.policy.title', boolean: true },
    ]);
  }

  protected financeGateRows(): FactRow[] {
    return rowsFrom(documentOf(this.financeGate.value()), [
      { keys: ['allowed', 'permitted', 'enabled'], labelKey: 'finance.gate.title', boolean: true },
      { keys: ['reason', 'blockedReason'], labelKey: 'finance.gate.title' },
      { keys: ['currency'], labelKey: 'finance.rates.title' },
    ]);
  }

  /**
   * The correlation id behind a failed read.
   *
   * Structural rather than `ResourceStore<T>`: the stores here hold three different `T`,
   * and a generic parameter would make the template reject the ones it does not exactly
   * match. Only the error is needed, so only the error is asked for.
   */
  /**
   * Arrow keys move between tabs, and Home/End jump to the ends.
   *
   * Required by the ARIA tabs pattern, and load-bearing here rather than decorative:
   * only the selected tab is in the tab order, so without this handler seven of the six
   * tabs would be **unreachable by keyboard** rather than merely out of sequence.
   *
   * The mapping is not flipped for RTL. The strip is laid out by the document's own flow,
   * and "next in reading order" in RTL is the tab on the left — so ArrowRight and
   * ArrowDown both advance, which is also what the pattern expects on a vertical stack.
   */
  protected onTabKeydown(event: KeyboardEvent): void {
    const ids = this.tabs().map((entry) => entry.id);
    if (ids.length === 0) return;
    const current = ids.indexOf(this.tab());
    let next = current;

    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = (current + 1) % ids.length;
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        next = (current - 1 + ids.length) % ids.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = ids.length - 1;
        break;
      default:
        return;
    }

    event.preventDefault();
    this.tab.set(ids[next]!);
    queueMicrotask(() => {
      document.getElementById(`finance-tab-${ids[next]!}`)?.focus();
    });
  }

  protected requestIdOf(store: ErrorSource): string | null {
    const error = store.error();
    return error instanceof ApiError ? error.requestId : null;
  }
}

/** The one thing a `ResourceStore` is asked for here: its last error. */
interface ErrorSource {
  readonly error: () => ApiError | null;
}

export type FinanceTab =
  | 'usage'
  | 'periods'
  | 'rates'
  | 'reconciliation'
  | 'exports'
  | 'authoring'
  | 'policy';

/**
 * One catalogue entry as the create form renders it.
 *
 * `versions` and `exportableCount` are carried so the form can show *which* versions the
 * caller may use without a second request, and so a version withheld by permissions is
 * visibly withheld rather than silently absent.
 */
interface CatalogueRow {
  readonly key: string;
  readonly id: string;
  readonly name: string;
  readonly detail: string;
  readonly status: string;
  readonly subject: string;
  readonly versions: readonly {
    readonly state?: string;
    readonly exportable?: boolean;
    readonly refusal?: string;
  }[];
  /** A localized count, e.g. "3 versions". */
  readonly versionsLabel: string;
  readonly exportableCount: number;
}

/** One filter key the catalogue published, and its label. */
interface CatalogueField {
  readonly key: string;
  readonly label: string;
}

/** A localized "N versions" label. */
function formatVersionCount(count: number): string {
  return count === 1 ? '1' : String(count);
}

/**
 * Narrows a catalogue string to the wire format enum, or `null`.
 *
 * The catalogue declares `formats` and `defaultFormat` as bare `Array<string>` /
 * `string`, while `CreateExportRequest.format` is the closed union `"CSV" | "DOCX"`. So
 * the catalogue is **not** self-consistently typed, and something has to bridge the two —
 * this, explicitly, rather than an `as` cast that would let an unknown format reach the
 * wire and come back as a 422 the operator cannot act on.
 *
 * A value outside the union yields `null`, which leaves the select empty and the form
 * invalid. That is the safe direction: the operator sees that nothing is chosen instead
 * of sending a request the server will refuse.
 */
function asFormat(value: string | undefined): 'CSV' | 'DOCX' | null {
  return value === 'CSV' || value === 'DOCX' ? value : null;
}

/** Narrows a catalogue string to the wire locale enum, or `null`. Lower case only. */
function asLocale(value: string | undefined): 'ar' | 'en' | null {
  return value === 'ar' || value === 'en' ? value : null;
}

/**
 * Every tab id, for validating a `?tab=` value from the URL.
 *
 * `'authoring'` was missing from this list when the authoring tab was added, and the
 * symptom was a **silent** one: `?tab=authoring` was rejected by
 * `if (!FINANCE_TABS.includes(tab)) return;` before the permission check was ever reached,
 * so the deep link did nothing and the screen stayed on `usage` with no error anywhere.
 *
 * A type annotation is not a runtime list — `FinanceTab` and this array are two separate
 * declarations of the same set, and adding a member to the union does not add it here. That
 * is the same "two sources of truth that silently disagree" shape as the segment→filter
 * alias that once turned a stale name into a 500.
 */
const FINANCE_TABS: readonly FinanceTab[] = [
  'usage',
  'periods',
  'rates',
  'reconciliation',
  'exports',
  'authoring',
  'policy',
];

/**
 * Asserts that the list and the union are the same set, at compile time.
 *
 * Without this, the next tab added to `FinanceTab` will be accepted by the type and rejected
 * by `FINANCE_TABS`, and the only symptom will be a deep link that quietly does nothing.
 * A `readonly FinanceTab[]` does not require exhaustiveness — it only requires that every
 * member is a `FinanceTab` — so the check has to be made explicitly.
 */
type MissingFromList = Exclude<FinanceTab, (typeof FINANCE_TABS)[number]>;
const _everyTabIsListed: MissingFromList extends never ? true : never = true;
void _everyTabIsListed;

const USAGE_COLUMNS: ColumnSpec[] = [
  { keys: ['day', 'date', 'bucket'], labelKey: 'finance.usage.day' },
  { keys: ['projectId', 'project'], labelKey: 'finance.usage.project' },
  { keys: ['inputTokens', 'input'], labelKey: 'finance.usage.input', numeric: true },
  { keys: ['outputTokens', 'output'], labelKey: 'finance.usage.output', numeric: true },
  { keys: ['cost', 'amount', 'total'], labelKey: 'finance.usage.cost', numeric: true },
  { keys: ['currency'], labelKey: 'finance.rates.title' },
];

const EXPORT_COLUMNS: ColumnSpec[] = [
  { keys: ['id', 'exportId'], labelKey: 'finance.exports.title' },
  { keys: ['status', 'state'], labelKey: 'finance.exports.status' },
  { keys: ['progressPct', 'progress'], labelKey: 'finance.exports.progress', numeric: true },
  { keys: ['rowCount', 'rows'], labelKey: 'finance.exports.rows', numeric: true },
  { keys: ['bytes', 'sizeBytes'], labelKey: 'finance.exports.size', numeric: true },
  { keys: ['format'], labelKey: 'finance.exports.format' },
  { keys: ['createdAt', 'requestedAt', 'startedAt'], labelKey: 'finance.exports.created', date: true },
  { keys: ['finishedAt', 'completedAt'], labelKey: 'finance.exports.finished', date: true },
  { keys: ['errorMessage', 'lastError'], labelKey: 'finance.exports.error' },
];

// ---------------------------------------------------------------------------
// Free-form document reading — see `admin.component.ts` for the reasoning.
// ---------------------------------------------------------------------------

function documentOf(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
  const record = value as Record<string, unknown>;
  const inner = record['document'];
  if (inner !== null && typeof inner === 'object' && !Array.isArray(inner)) {
    return inner as Record<string, unknown>;
  }
  return record;
}

function isEmptyDocument(value: Record<string, unknown> | null): boolean {
  const document = documentOf(value);
  return !document || Object.keys(document).length === 0;
}

interface ColumnSpec {
  readonly keys: readonly string[];
  readonly labelKey: string;
  readonly numeric?: boolean;
  readonly boolean?: boolean;
  readonly date?: boolean;
}

function rowsFrom(
  document: Record<string, unknown> | null,
  columns: readonly ColumnSpec[],
): FactRow[] {
  if (!document) return [];
  const list = Object.entries(document).find(
    ([, value]) => Array.isArray(value) && value.length > 0,
  );
  const entries: [string, unknown][] = list
    ? (list[1] as unknown[]).map((value, index) => [String(index), value])
    : Object.entries(document);

  return entries.map(([key, value]) => {
    const record =
      value !== null && typeof value === 'object' && !Array.isArray(value)
        ? (value as Record<string, unknown>)
        : null;
    const rendered = columns.map((column) => renderCell(record, value, column));
    const first = rendered.findIndex((cell) => cell !== null);
    return {
      key,
      labelKey: columns[first === -1 ? 0 : first]!.labelKey,
      rawLabel:
        columns.map((column) => firstOf(record, column.keys)).find((v) => v !== null) ?? key,
      value: rendered.find((cell) => cell !== null) ?? '—',
    };
  });
}

function firstOf(record: Record<string, unknown> | null, keys: readonly string[]): string | null {
  if (!record) return null;
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && value !== '') return String(value);
  }
  return null;
}

function renderCell(
  record: Record<string, unknown> | null,
  whole: unknown,
  column: ColumnSpec,
): string | null {
  let raw: unknown;
  if (record) {
    for (const key of column.keys) {
      const candidate = record[key];
      if (candidate !== undefined && candidate !== null && candidate !== '') {
        raw = candidate;
        break;
      }
    }
  } else {
    raw = whole;
  }
  if (raw === undefined || raw === null || raw === '') return null;
  if (column.boolean) return raw === true || raw === 'true' ? 'yes' : 'no';
  if (column.date && typeof raw === 'string') return raw;
  if (column.numeric && typeof raw === 'number') return String(raw);
  return String(raw);
}
