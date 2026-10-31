/**
 * The report-authoring screen: author a definition or a template, author versions of
 * them, publish and retire.
 *
 * ## Why this screen exists at all
 *
 * Until phase 31 the catalogue was **empty on every fresh database** — the migration created
 * `report_definitions` and `document_templates` without inserting a row, and no route could
 * create one. So the export-creation form was correctly wired and had nothing to offer, and
 * the only way to satisfy `POST /api/v1/exports` was to ask an operator to paste four
 * opaque UUIDs. Thirteen authoring routes close that, and this screen is the other end of
 * them: **every identifier here arrives in a server response, and none is typed.**
 *
 * ## The three things this screen refuses to do
 *
 * 1. **It does not compute exportability.** Whether a version can be pinned by an export is
 *    decided by the server against the caller's own permissions, and arrives as
 *    `exportable` plus a `refusal` string on the catalogue. Deriving it here — from a state
 *    name, say — would be a second opinion that disagrees with the boundary exactly when it
 *    matters. So after every publish or retire this screen **re-reads the catalogue** and
 *    lets the server's own words stand.
 *
 * 2. **It does not distinguish a 404 from a 403, because the server will not.** A caller
 *    with no `report:manage`, and a caller asking about a definition that is somebody else's,
 *    both get **404**. That is deliberate: it stops the route being an oracle for "does this
 *    exist?". So `reportRefusal` reports a refusal without claiming to know its cause, and
 *    never renders "not found".
 *
 * 3. **It does not let a draft look exportable.** A new version is a draft and the server
 *    refuses to pin one. The screen says so at the moment of authoring, so the operator is
 *    not left believing the thing they just made is ready.
 */
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { skip } from 'rxjs';

import { ApiError } from '../../core/api/api.service';
import { ExportsApi } from '../../core/api/endpoints';
import { PERMISSIONS } from '../../core/auth/permissions';
import { SessionService } from '../../core/auth/session.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { notBlank } from '../../core/validation/not-blank.validator';
import { AsyncStateComponent } from '../../ui/async-state.component';

import { authoringStrings } from './authoring.strings';

/**
 * The largest DOCX this screen will put on the wire.
 *
 * The server bounds the body independently and is the boundary; this is a courtesy so an
 * oversized file is refused before the upload rather than after it. Deliberately generous
 * for a report template and deliberately finite, because a screen that base64s a file into
 * memory with no ceiling is how a tab dies.
 */
const MAX_DOCX_BYTES = 10 * 1024 * 1024;

/** One field the server offers, as the authoring form reads it. */
interface AuthoringField {
  readonly key: string;
  readonly subject: string;
  readonly role: string;
  readonly kind: string;
  readonly requiredPermission: string | null;
  /**
   * Whether the field is sensitive.
   *
   * Read as the **string** `"true"` / `"false"`, because that is what the server sends — the
   * live probe returned `"sensitive": "true"` as a JSON string. A `typeof === 'boolean'`
   * test would classify every field as non-sensitive and the screen would stop marking the
   * email, phone, cost, revenue and margin columns as sensitive.
   */
  readonly sensitive: boolean;
  /** The server's own label in the screen's language, falling back to English then the key. */
  readonly label: string;
}

/** One subject the server offers, with the fields and filters it defines. */
interface AuthoringSubject {
  readonly key: string;
  readonly fields: readonly string[];
  readonly filters: readonly string[];
}

/** A version as this screen lists it, read from a free-form array. */
interface VersionRow {
  readonly number: number;
  readonly state: string;
  readonly columns: readonly string[];
  readonly changeNote: string | null;
  readonly publishedAt: string | null;
  readonly retiredAt: string | null;
}

/** A definition authored in this session. Its id came from a response, never from a person. */
interface AuthoredDefinition {
  readonly id: string;
  readonly key: string;
  /**
   * The names and descriptions as the server last reported them.
   *
   * Carried so the rename form **opens on what is actually stored** rather than on a blank,
   * which would make an author retype both languages to change one of them — and would make
   * a rename that clears a name look like an edit rather than a delete.
   */
  readonly nameAr: string;
  readonly nameEn: string;
  readonly descriptionAr: string;
  readonly descriptionEn: string;
  /**
   * `rowVersion` — the optimistic-concurrency token `PATCH` requires as `expectedVersion`.
   *
   * Held rather than read at submit time because the whole point of the column is that a
   * rename made against a stale read is **refused**. Sending the version the form was
   * opened with is what makes the refusal reachable: if the screen fetched the current
   * version immediately before writing, the guard could never fire and a lost update would
   * be applied silently, which is the one thing the column exists to prevent.
   */
  readonly rowVersion: number;
  readonly versions: readonly VersionRow[];
}

/** A template authored in this session. */
interface AuthoredTemplate {
  readonly id: string;
  readonly key: string;
  readonly versions: readonly VersionRow[];
}

@Component({
  selector: 'fe-exports-authoring',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncStateComponent, ReactiveFormsModule, TranslatePipe],
  templateUrl: './authoring.component.html',
  styleUrls: ['./authoring.component.css'],
})
export class AuthoringComponent implements OnInit {
  private readonly exports = inject(ExportsApi);
  private readonly formBuilder = inject(FormBuilder);
  private readonly format = inject(FormatService);
  private readonly i18n = inject(I18nService);
  private readonly projects = inject(ProjectContextService);
  private readonly sessions = inject(SessionService);

  /**
   * Emitted after a publish or retire, so the parent can re-read the catalogue.
   *
   * This is the wiring between authoring and export creation: the parent reloads the
   * catalogue, so its creation form offers exactly what the server now says is exportable.
   * The child announces the change and never reaches into the parent's state.
   */
  readonly catalogueChanged = output<void>();

  /**
   * The authoring summary, free-form as the contract declares it.
   *
   * `null` until it loads, and left `null` when no project is chosen — the summary is read
   * for one project, so asking without one is not a request the contract describes.
   */
  protected readonly summary = new ResourceStore<Record<string, unknown> | null>();

  /** Definitions authored in this session, with their versions. */
  protected readonly definitions = new ResourceStore<readonly AuthoredDefinition[]>();

  /** Templates authored in this session, with their versions. */
  protected readonly templates = new ResourceStore<readonly AuthoredTemplate[]>();

    protected readonly busy = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly refusal = signal<string | null>(null);
  protected readonly uploadRefusal = signal<string | null>(null);

  /** Which entries' version lists are open. */
  protected readonly openVersions = signal<readonly string[]>([]);

  /** Which definition's "add a version" form is open, or `null`. */
  protected readonly authoringVersionFor = signal<string | null>(null);

  /** Which template's upload form is open, or `null`. */
  protected readonly uploadingFor = signal<string | null>(null);

  /** The subject chosen for the version being authored. */
  protected readonly versionSubject = signal<string | null>(null);

  /** The mutable working copies. Accumulated from responses; never typed into. */
  private workingDefinitions: AuthoredDefinition[] = [];
  private workingTemplates: AuthoredTemplate[] = [];

  // -- permission -----------------------------------------------------------

  /**
   * Whether to draw authoring controls at all.
   *
   * **UX only.** The server checks `report:manage` on every one of the thirteen routes and
   * answers a caller without it with a non-disclosing 404, so a wrong answer here changes
   * what is drawn and nothing else. The server's own `mayAuthor` is shown beside this
   * decision as a separate fact rather than used as the gate, because the server itself
   * calls it "a hint for the UI, not an authorization decision".
   */
  protected readonly mayAuthor = computed(() => this.sessions.can()(PERMISSIONS.reportManage));

  /**
   * Registers this feature's dictionary.
   *
   * Public, not `protected`: Angular's `Type<T>` requires a public constructor, and a
   * protected one makes the class unusable in another component's `imports`. The visibility
   * of a constructor that only the framework calls is not a useful boundary.
   */
  constructor() {
    this.i18n.register({ authoring: authoringStrings as Namespace });

    // Re-read when the selected project changes. Without this the screen keeps authoring
    // against the previous project — the same defect the finance screen had, and on a
    // surface where an author would then publish a report to the wrong place.
    //
    // **In the constructor, deliberately.** `takeUntilDestroyed()` with no injector argument
    // needs an injection context, and `ngOnInit` is not one. Called there it does not
    // throw and does not warn: the subscription is simply never installed, so the screen
    // reads once — with no project — and then never reads again. The symptom is a permanent
    // "Loading", which is indistinguishable from a slow server.
    toObservable(this.projects.selectedProjectId)
      .pipe(
        // The first value is handled by the `load()` in `ngOnInit`; reloading on mount as
        // well would double every request on page load.
        skip(1),
        takeUntilDestroyed(),
      )
      .subscribe(() => void this.load());

    // Seeds the redaction policy from the catalogue's own list, once it arrives and only when
    // the operator has not chosen. A local guess would be a value the server might not list;
    // the catalogue's first entry is by definition one it accepts.
    //
    // **In the constructor, not `ngOnInit`.** `effect()` requires an injection context, and
    // `ngOnInit` is not one — it threw `NG0203: effect() can only be used within an injection
    // context` on every mount, so the select was never seeded and the console carried an
    // error nobody was reading. A constructor body is an injection context, which is the same
    // reason `takeUntilDestroyed()` above sits here.
    effect(() => {
      const policies = this.redactionPolicies();
      if (policies.length === 0) return;
      if (this.definitionForm.controls.redactionPolicy.value !== '') return;
      untracked(() => this.definitionForm.controls.redactionPolicy.setValue(policies[0]!));
    });
  }

  ngOnInit(): void {
    void this.load();
  }

  // -- the server's summary, read defensively ------------------------------

  protected readonly serverNote = computed(() => stringOrNull(this.summary.value()?.['note']));

  /** The server's own `mayAuthor`, shown as a fact and never used as the gate. */
  protected readonly serverMayAuthor = computed(() => {
    const value = this.summary.value()?.['mayAuthor'];
    return typeof value === 'boolean' ? value : null;
  });

  protected readonly serverPermission = computed(() =>
    stringOrNull(this.summary.value()?.['permission']),
  );

  protected readonly locales = computed(() => stringArray(this.summary.value()?.['locales']));

  protected readonly redactionPolicies = computed(() =>
    stringArray(this.summary.value()?.['redactionPolicies']),
  );

  protected readonly metadataPlaceholders = computed(() =>
    stringArray(this.summary.value()?.['metadataPlaceholders']),
  );

  /** The subjects the server offers, each with the fields and filters it defines. */
  protected readonly subjects = computed<readonly AuthoringSubject[]>(() => {
    const vocabulary = asRecord(this.summary.value()?.['filterVocabulary']);
    const subjects = asRecord(vocabulary?.['subjects']);
    if (!subjects) return [];
    return Object.keys(subjects)
      .map((key) => {
        const subject = asRecord(subjects[key]);
        return {
          key,
          fields: stringArray(subject?.['fields']),
          filters: stringArray(subject?.['filters']),
        };
      })
      .sort((left, right) => left.key.localeCompare(right.key));
  });

  /**
   * The fields the server offers, as this screen renders them.
   *
   * The label is the server's own `labelAr`/`labelEn`, chosen by the screen's locale with
   * English as the fallback. Deliberate: the backend already carries both languages for
   * every field, and translating 34 field names into a client dictionary would duplicate an
   * authority that exists and drift from it on the first backend change.
   */
  protected readonly fields = computed<readonly AuthoringField[]>(() => {
    const rows = this.summary.value()?.['fields'];
    if (!Array.isArray(rows)) return [];
    const arabic = this.i18n.locale() === 'ar';
    return rows
      .map((row): AuthoringField | null => {
        const record = asRecord(row);
        const key = stringOrNull(record?.['key']);
        if (key === null) return null;
        return {
          key,
          subject: stringOrNull(record?.['subject']) ?? '',
          role: stringOrNull(record?.['role']) ?? '',
          kind: stringOrNull(record?.['kind']) ?? '',
          requiredPermission: stringOrNull(record?.['requiredPermission']),
          // The string form, on purpose. See `AuthoringField.sensitive`.
          sensitive: String(record?.['sensitive']).toLowerCase() === 'true',
          label:
            (arabic
              ? stringOrNull(record?.['labelAr']) ?? stringOrNull(record?.['labelEn'])
              : stringOrNull(record?.['labelEn']) ?? stringOrNull(record?.['labelAr'])) ?? key,
        };
      })
      .filter((field): field is AuthoringField => field !== null);
  });

  /** The fields of the chosen subject, for the version form. */
  protected readonly subjectFields = computed<readonly AuthoringField[]>(() => {
    const subject = this.versionSubject();
    if (subject === null) return [];
    return this.fields().filter((field) => field.subject === subject);
  });

  /**
   * The **measures** of the chosen subject — the fields the server marks `role: MEASURE`.
   *
   * A version cannot exist without at least one: the database's
   * `report_versions_measures_present_check` refuses an empty `measures`, and the refusal
   * arrives as a 500 because `DataIntegrityViolationException` is mapped to no status. So the
   * screen offers the measure fields separately from the dimension fields and requires one,
   * rather than sending a request whose outcome is already known. See `createVersion`.
   *
   * The distinction is the server's, read from `role` — not an inference from a field name.
   */
  protected readonly subjectMeasures = computed<readonly AuthoringField[]>(() => {
    const subject = this.versionSubject();
    if (subject === null) return [];
    return this.fields().filter(
      (field) => field.subject === subject && field.role.trim().toUpperCase() === 'MEASURE',
    );
  });

  /** The filter keys of the chosen subject, offered as read-only reference. */
  protected readonly subjectFilters = computed<readonly string[]>(() => {
    const subject = this.subjects().find((entry) => entry.key === this.versionSubject());
    return subject?.filters ?? [];
  });

  // -- loading --------------------------------------------------------------

  protected async load(): Promise<void> {
    const projectId = this.projects.selectedProjectId();
    if (projectId === null || projectId === undefined) {
      // No project means no scope to author within. Saying so beats issuing a request the
      // contract does not describe.
      this.summary.reset();
      this.definitions.reset();
      this.templates.reset();
      return;
    }
    await Promise.allSettled([
      this.summary.load(() => this.exports.authoring({ projectId })),
      this.definitions.load(() => Promise.resolve(this.workingDefinitions)),
      this.templates.load(() => Promise.resolve(this.workingTemplates)),
    ]);
  }

  // -- the definition form --------------------------------------------------

  protected readonly definitionForm = this.formBuilder.group({
    key: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    nameAr: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    nameEn: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    descriptionAr: this.formBuilder.control('', { nonNullable: true }),
    descriptionEn: this.formBuilder.control('', { nonNullable: true }),
    /**
     * The zone a report's dates are rendered in.
     *
     * **`required` by the contract.** It was not, when this client found the rule by probing
     * the live API: six of the ten fields were required in practice and optional on paper,
     * and each refusal was a 400 with a message the contract did not predict. Phase 32
     * declares all six, with `minItems: 1` on the version arrays so `required` alone — which
     * still permits `[]` — is not the whole rule.
     *
     * The client check is **kept**, and that is a decision worth stating: it now duplicates
     * a *declared* rule rather than guessing one, which is the only kind of duplication worth
     * keeping. The reason is that a refused request costs a round trip and leaves the
     * operator reading a refusal, where a disabled control costs nothing and says why. The
     * server remains the boundary either way — this is about not offering a doomed request,
     * not about permitting anything.
     *
     * Seeded from the browser's own zone rather than a written literal, because a hard-coded
     * default is correct only for whoever typed it. It is a **default, not a decision** — the
     * control stays editable, and a report's dates are its author's choice.
     */
    timezone: this.formBuilder.control(browserTimeZone(), {
      nonNullable: true,
      validators: [notBlank],
    }),
    localeDefault: this.formBuilder.control('', { nonNullable: true }),
    /**
     * Which redaction policy this report's artefacts are built with.
     *
     * **`required` by the contract**, and constrained to the policies the catalogue
     * publishes. The client sends one of those, never an invented value and never nothing.
     *
     * Found by probing before the document said so: an absent policy was refused with
     * `Report definition names the redaction policy 'null', which is not one of none,
     * no-contact-details, no-personal-detail, strict`. That message also showed the server
     * reading an absent field as the literal string `"null"` rather than as missing — a
     * separate defect from the missing `required`, and **not re-checked since Phase 32**, so
     * it is reported as observed rather than as current.
     */
    redactionPolicy: this.formBuilder.control('', {
      nonNullable: true,
      validators: [notBlank],
    }),
    /**
     * How long a generated artefact of this report is kept.
     *
     * **Required by the server and optional in the contract** — `CreateReportDefinitionRequest`
     * declares it as an ordinary optional string, and posting a definition without it is
     * refused with `400 {"code":"BAD_REQUEST","message":"A report definition requires
     * 'retentionClass'"}`. So the contract understates the requirement and the screen states
     * it. See Hook 22 in the phase report.
     */
    retentionClass: this.formBuilder.control('transient', { nonNullable: true, validators: [notBlank] }),
    // One permission per line, split on submit. A textarea is the only honest control for
    // "a list I cannot enumerate for you".
    requiredPermissions: this.formBuilder.control('', { nonNullable: true }),
  });

  protected async createDefinition(event: Event): Promise<void> {
    event.preventDefault();
    const projectId = this.projects.selectedProjectId();
    if (projectId === null || projectId === undefined) return;
    if (this.definitionForm.invalid) {
      this.definitionForm.markAllAsTouched();
      return;
    }

    const value = this.definitionForm.getRawValue();
    this.busy.set('definition');
    this.refusal.set(null);
    this.notice.set(null);

    try {
      const created = await this.exports.createReportDefinition(projectId, {
        key: value.key.trim(),
        nameAr: value.nameAr.trim(),
        nameEn: value.nameEn.trim(),
        descriptionAr: blankToUndefined(value.descriptionAr),
        descriptionEn: blankToUndefined(value.descriptionEn),
        timezone: blankToUndefined(value.timezone),
        localeDefault: blankToUndefined(value.localeDefault),
        // A `null` here would be a catalogue value this build does not know, which cannot
        // happen while the select is populated from the same list the union came from. The
        // guard keeps it from becoming a silent untyped value if that ever stops being true.
        redactionPolicy: asRedactionPolicy(value.redactionPolicy) ?? 'none',
        retentionClass: value.retentionClass,
        // Split on newlines and drop blanks: an empty line is a typo, not a permission.
        requiredPermissions: splitLines(value.requiredPermissions),
      });

      // The id comes from the response. Nothing was typed. The names and the row version
      // come from it too, because a rename needs both: the names to prefill, and the
      // version to guard the write with.
      this.workingDefinitions = [
        ...this.workingDefinitions,
        {
          id: created.reportDefinitionId ?? '',
          key: created.key ?? value.key.trim(),
          nameAr: created.nameAr ?? value.nameAr.trim(),
          nameEn: created.nameEn ?? value.nameEn.trim(),
          descriptionAr: created.descriptionAr ?? value.descriptionAr.trim(),
          descriptionEn: created.descriptionEn ?? value.descriptionEn.trim(),
          // `rowVersion` is declared as optional on the response, and a definition created
          // without one would make every rename of it unstattable. `0` is the schema's own
          // default for the column, so it is the honest stand-in rather than a guess at a
          // real version.
          rowVersion: created.rowVersion ?? 0,
          versions: [],
        },
      ];
      await this.definitions.load(() => Promise.resolve(this.workingDefinitions));
      this.definitionForm.reset();
            this.catalogueChanged.emit();
this.notice.set(this.i18n.t('authoring.definition.created'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  // -- rename ---------------------------------------------------------------

  /**
   * The definition whose rename form is open, or `null`.
   *
   * A single id rather than a list, because renaming two definitions at once is not a thing
   * an operator does, and two open forms on one screen would make the stale-version refusal
   * ambiguous — the operator would have to work out which of the two names the server was
   * refusing.
   */
  protected readonly renamingFor = signal<string | null>(null);

  protected readonly renameForm = this.formBuilder.group({
    nameAr: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    nameEn: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    descriptionAr: this.formBuilder.control('', { nonNullable: true }),
    descriptionEn: this.formBuilder.control('', { nonNullable: true }),
  });

  /**
   * Opens the rename form prefilled with the definition's stored names.
   *
   * Prefilled, and deliberately so: a rename clears a name when the field is emptied, and
   * `report_definitions_name_ar_check` refuses a blank Arabic name — so a blank-by-default
   * form would have an author who means to change only the English name send an empty
   * Arabic name and be refused, which reads as the server being obstructive rather than as
   * the form having been wrong.
   */
  protected openRenameForm(definition: AuthoredDefinition): void {
    this.renamingFor.set(definition.id);
    this.renameForm.reset({
      nameAr: definition.nameAr,
      nameEn: definition.nameEn,
      descriptionAr: definition.descriptionAr,
      descriptionEn: definition.descriptionEn,
    });
  }

  /**
   * Renames a definition under optimistic concurrency.
   *
   * `expectedVersion` is the version the form was **opened** with, so a definition renamed
   * by someone else in between is refused rather than overwritten. That refusal is routed
   * through `reportRefusal` like every other, and the form stays open with what the author
   * typed, because discarding their text on a conflict is its own small cruelty.
   */
  protected async renameDefinition(definitionId: string, event: Event): Promise<void> {
    event.preventDefault();
    if (this.renameForm.invalid) {
      this.renameForm.markAllAsTouched();
      return;
    }

    const definition = this.workingDefinitions.find((entry) => entry.id === definitionId);
    if (definition === undefined) return;

    const value = this.renameForm.getRawValue();
    this.busy.set(`rename:${definitionId}`);
    this.refusal.set(null);
    this.notice.set(null);

    try {
      const renamed = await this.exports.renameReportDefinition(definitionId, {
        expectedVersion: definition.rowVersion,
        nameAr: value.nameAr.trim(),
        nameEn: value.nameEn.trim(),
        descriptionAr: blankToUndefined(value.descriptionAr),
        descriptionEn: blankToUndefined(value.descriptionEn),
      });

      this.workingDefinitions = this.workingDefinitions.map((entry) =>
        entry.id === definitionId
          ? {
              ...entry,
              nameAr: renamed.nameAr ?? value.nameAr.trim(),
              nameEn: renamed.nameEn ?? value.nameEn.trim(),
              descriptionAr: renamed.descriptionAr ?? entry.descriptionAr,
              descriptionEn: renamed.descriptionEn ?? entry.descriptionEn,
              // Advanced from the response, not by incrementing locally. If the server had
              // incremented it the local guess would agree this once and the *next* rename
              // would be sent with a version the row never had.
              rowVersion: renamed.rowVersion ?? definition.rowVersion + 1,
            }
          : entry,
      );
      await this.definitions.load(() => Promise.resolve(this.workingDefinitions));
      this.renamingFor.set(null);
            this.catalogueChanged.emit();
this.notice.set(this.i18n.t('authoring.rename.done'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  // -- versions -------------------------------------------------------------

  protected readonly versionForm = this.formBuilder.group({
    changeNote: this.formBuilder.control('', { nonNullable: true }),
    columns: this.formBuilder.control<readonly string[]>([], { nonNullable: true }),
    /**
     * The measures this version aggregates.
     *
     * **Required by the server, and not declared anywhere in the contract.** A version whose
     * `measures` is empty violates the database's `report_versions_measures_present_check`,
     * and that arrives as a **500** — `DataIntegrityViolationException` is mapped to no
     * status, so the same `ErrorHandlingAdvice` gap that turns an unknown `filter` and a
     * missing parameter into internal errors turns this into one too.
     *
     * The server is right that a report with no measure is not a report. So the screen
     * offers the fields whose `role` is `MEASURE` and requires at least one, rather than
     * letting an author send a request that cannot succeed. See Hook 23.
     */
    measures: this.formBuilder.control<readonly string[]>([], { nonNullable: true }),
    /**
     * The version that replaces this one, when retiring.
     *
     * **Required by the server**, with an unusually clear reason:
     * `report_versions_retired_check requires supersededByVersionId, and a retirement that
     * does not say where the body went is not a rollback, it is a deletion with extra steps.`
     * So the control is required and the hint is the server's own argument.
     */
    successorVersion: this.formBuilder.control<number | null>(null, Validators.required),
  });

  protected openVersionForm(definitionId: string): void {
    this.authoringVersionFor.set(definitionId);
    this.versionSubject.set(this.subjects()[0]?.key ?? null);
    this.versionForm.reset({
      changeNote: '',
      columns: [],
      measures: [],
      successorVersion: null,
    });
  }

  protected columnChosen(key: string): boolean {
    return this.versionForm.controls.columns.value.includes(key);
  }

  protected toggleColumn(key: string, checked: boolean): void {
    const current = this.versionForm.controls.columns.value;
    this.versionForm.controls.columns.setValue(
      checked ? [...current, key] : current.filter((entry) => entry !== key),
    );
  }

  protected measureChosen(key: string): boolean {
    return this.versionForm.controls.measures.value.includes(key);
  }

  protected toggleMeasure(key: string, checked: boolean): void {
    const current = this.versionForm.controls.measures.value;
    this.versionForm.controls.measures.setValue(
      checked ? [...current, key] : current.filter((entry) => entry !== key),
    );
  }

  protected async createVersion(definitionId: string, event: Event): Promise<void> {
    event.preventDefault();
    const columns = this.versionForm.controls.columns.value;
    const measures = this.versionForm.controls.measures.value;

    // ## Both `columns` and `measures` are required, and neither is declared as such
    //
    // Two separate server rules, both found by driving the real API:
    //
    // - empty `measures` violates `report_versions_measures_present_check`, a database
    //   constraint, and arrives as a **500**;
    // - empty `columns` is refused with `400 REPORT_COLUMN_UNDECLARED` — *"A report version
    //   with no columns exports nothing, which is not a report."*
    //
    // So both are checked here rather than sent, because the point of the screen is that an
    // author is not offered a request that cannot succeed. `noMeasures` and `noColumns` are
    // separate sentences because an author who checked one and not the other needs to know
    // which one is still missing.
    if (measures.length === 0) {
      this.notice.set(this.i18n.t('authoring.version.noMeasures'));
      return;
    }
    if (columns.length === 0) {
      this.notice.set(this.i18n.t('authoring.version.noColumns'));
      return;
    }

    this.busy.set(`version:${definitionId}`);
    this.refusal.set(null);
    this.notice.set(null);
    try {
      const created = await this.exports.createReportVersion(definitionId, {
        columns: [...columns],
        measures: [...measures],
        changeNote: blankToUndefined(this.versionForm.controls.changeNote.value),
      });
      await this.refreshVersions(definitionId);
      this.authoringVersionFor.set(null);
      // A draft cannot be pinned. Saying it here is the difference between an author who
      // knows to publish and one who wonders why their export form is empty.
            this.catalogueChanged.emit();
this.notice.set(this.i18n.t('authoring.version.created'));
      this.catalogueChanged.emit();
      void created;
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  protected async publishVersion(
    definitionId: string,
    versionNumber: number,
    event: Event,
  ): Promise<void> {
    event.preventDefault();
    this.busy.set(`publish:${definitionId}:${versionNumber}`);
    this.refusal.set(null);
    this.notice.set(null);
    try {
      await this.exports.publishReportVersion(definitionId, versionNumber);
      await this.refreshVersions(definitionId);
      // Publishing is what changes what an export may pin, and the **server** decides
      // that. So re-read the catalogue rather than assuming the version is now offered.
      this.catalogueChanged.emit();
      this.notice.set(this.i18n.t('authoring.version.published'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  protected async retireVersion(
    definitionId: string,
    versionNumber: number,
    event: Event,
  ): Promise<void> {
    event.preventDefault();
    const successor = this.versionForm.controls.successorVersion.value;
    this.busy.set(`retire:${definitionId}:${versionNumber}`);
    this.refusal.set(null);
    this.notice.set(null);
    try {
      await this.exports.retireReportVersion(definitionId, versionNumber, {
        successorVersion: successor ?? undefined,
      });
      await this.refreshVersions(definitionId);
      this.catalogueChanged.emit();
      this.notice.set(this.i18n.t('authoring.version.retired'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  protected async toggleVersions(definitionId: string): Promise<void> {
    const open = this.openVersions();
    if (open.includes(definitionId)) {
      this.openVersions.set(open.filter((id) => id !== definitionId));
      return;
    }
    this.openVersions.set([...open, definitionId]);
    await this.refreshVersions(definitionId);
  }

  private async refreshVersions(definitionId: string): Promise<void> {
    if (!this.openVersions().includes(definitionId)) {
      this.openVersions.set([...this.openVersions(), definitionId]);
    }
    try {
      const rows = await this.exports.reportVersions(definitionId);
      const versions = readVersions(rows, 'reportVersion');
      this.workingDefinitions = this.workingDefinitions.map((definition) =>
        definition.id === definitionId ? { ...definition, versions } : definition,
      );
      await this.definitions.load(() => Promise.resolve(this.workingDefinitions));
    } catch (cause) {
      // A refusal on the version list is the non-disclosing kind, and is reported as such.
      this.reportRefusal(cause);
    }
  }

  // -- templates ------------------------------------------------------------

  protected readonly templateForm = this.formBuilder.group({
    key: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    nameAr: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    nameEn: this.formBuilder.control('', { nonNullable: true, validators: [notBlank] }),
    locale: this.formBuilder.control('', { nonNullable: true }),
    timezone: this.formBuilder.control('', { nonNullable: true }),
  });

  protected readonly uploadForm = this.formBuilder.group({
    successorVersion: this.formBuilder.control<number | null>(null),
  });

  /** The chosen DOCX, held as a name, a size and a base64 string. Never persisted. */
  protected readonly chosenDocx = signal<{ name: string; bytes: number; base64: string } | null>(
    null,
  );

  protected async createTemplate(event: Event): Promise<void> {
    event.preventDefault();
    const projectId = this.projects.selectedProjectId();
    if (projectId === null || projectId === undefined) return;
    if (this.templateForm.invalid) {
      this.templateForm.markAllAsTouched();
      return;
    }
    const value = this.templateForm.getRawValue();

    this.busy.set('template');
    this.refusal.set(null);
    this.notice.set(null);
    try {
      const created = await this.exports.createDocumentTemplate(projectId, {
        key: value.key.trim(),
        nameAr: value.nameAr.trim(),
        nameEn: value.nameEn.trim(),
        locale: blankToUndefined(value.locale),
        timezone: blankToUndefined(value.timezone),
      });
      this.workingTemplates = [
        ...this.workingTemplates,
        {
          id: created.documentTemplateId ?? '',
          key: created.key ?? value.key.trim(),
          versions: [],
        },
      ];
      await this.templates.load(() => Promise.resolve(this.workingTemplates));
      this.templateForm.reset();
            this.catalogueChanged.emit();
this.notice.set(this.i18n.t('authoring.template.created'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  /**
   * Reads a chosen DOCX and holds it as base64.
   *
   * Read through `FileReader` rather than posted directly because the contract's body is a
   * base64 **string inside JSON**, not a multipart part — so the screen has to do the
   * encoding the wire expects. The size is checked before the read, so an oversized file is
   * refused without first being pulled into memory.
   */
  protected onDocxChosen(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.uploadRefusal.set(null);
    this.chosenDocx.set(null);
    if (!file) return;

    if (file.size > MAX_DOCX_BYTES) {
      this.uploadRefusal.set(this.i18n.t('authoring.template.tooLarge'));
      input.value = '';
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result;
      if (typeof result !== 'string') {
        this.uploadRefusal.set(this.i18n.t('authoring.refusal.error'));
        return;
      }
      // `readAsDataURL` yields `data:…;base64,XXXX`; the wire wants the payload alone.
      const comma = result.indexOf(',');
      this.chosenDocx.set({
        name: file.name,
        bytes: file.size,
        base64: comma === -1 ? result : result.slice(comma + 1),
      });
    };
    reader.onerror = () => this.uploadRefusal.set(this.i18n.t('authoring.refusal.error'));
    reader.readAsDataURL(file);
  }

  protected async uploadVersion(templateId: string, event: Event): Promise<void> {
    event.preventDefault();
    const chosen = this.chosenDocx();
    if (chosen === null) {
      this.uploadRefusal.set(this.i18n.t('authoring.template.noFile'));
      return;
    }
    this.busy.set(`upload:${templateId}`);
    this.refusal.set(null);
    this.uploadRefusal.set(null);
    this.notice.set(null);
    try {
      await this.exports.createDocumentTemplateVersion(templateId, chosen.base64);
      await this.refreshTemplateVersions(templateId);
      this.chosenDocx.set(null);
      this.uploadingFor.set(null);
            this.catalogueChanged.emit();
this.notice.set(this.i18n.t('authoring.template.uploaded'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  protected async publishTemplateVersion(
    templateId: string,
    versionNumber: number,
    event: Event,
  ): Promise<void> {
    event.preventDefault();
    this.busy.set(`publishTemplate:${templateId}:${versionNumber}`);
    this.refusal.set(null);
    this.notice.set(null);
    try {
      await this.exports.publishDocumentTemplateVersion(templateId, versionNumber);
      await this.refreshTemplateVersions(templateId);
      this.catalogueChanged.emit();
      this.notice.set(this.i18n.t('authoring.version.published'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  protected async retireTemplateVersion(
    templateId: string,
    versionNumber: number,
    event: Event,
  ): Promise<void> {
    event.preventDefault();
    const successor = this.uploadForm.controls.successorVersion.value;
    this.busy.set(`retireTemplate:${templateId}:${versionNumber}`);
    this.refusal.set(null);
    this.notice.set(null);
    try {
      await this.exports.retireDocumentTemplateVersion(templateId, versionNumber, {
        successorVersion: successor ?? undefined,
      });
      await this.refreshTemplateVersions(templateId);
      this.catalogueChanged.emit();
      this.notice.set(this.i18n.t('authoring.version.retired'));
      this.catalogueChanged.emit();
    } catch (cause) {
      this.reportRefusal(cause);
    } finally {
      this.busy.set(null);
    }
  }

  protected async toggleTemplateVersions(templateId: string): Promise<void> {
    const open = this.openVersions();
    if (open.includes(templateId)) {
      this.openVersions.set(open.filter((id) => id !== templateId));
      return;
    }
    this.openVersions.set([...open, templateId]);
    await this.refreshTemplateVersions(templateId);
  }

  private async refreshTemplateVersions(templateId: string): Promise<void> {
    try {
      const rows = await this.exports.documentTemplateVersions(templateId);
      const versions = readVersions(rows, 'documentTemplateVersion');
      this.workingTemplates = this.workingTemplates.map((template) =>
        template.id === templateId ? { ...template, versions } : template,
      );
      await this.templates.load(() => Promise.resolve(this.workingTemplates));
    } catch (cause) {
      this.reportRefusal(cause);
    }
  }

  // -- refusals -------------------------------------------------------------

  /**
   * Turns a failed authoring call into words the operator can act on.
   *
   * **The 404 is never reported as "not found".** The backend answers 404 for both "you may
   * not author this" and "this is not yours", deliberately, so the route cannot be used to
   * discover what exists. A screen that said "not found" would assert a fact it has no way
   * to know; one that said "forbidden" would assert the opposite. So this reports the
   * refusal and states plainly that the server will not say which it was.
   *
   * No second probe is attempted to find out. Distinguishing the two by trying a different
   * id would turn the non-disclosure back into an oracle, which is the thing being defended.
   */
  private reportRefusal(cause: unknown): void {
    this.notice.set(null);
    this.refusalRequestId.set(null);
    if (cause instanceof ApiError) {
      this.refusalRequestId.set(cause.requestId ?? null);
      if (cause.isNotFound) {
        this.refusal.set(this.i18n.t('authoring.refusal.notAvailable'));
        return;
      }
      if (cause.isPermissionDenied) {
        this.refusal.set(this.i18n.t('authoring.refusal.permission'));
        return;
      }
      if (cause.status === 400 || cause.status === 409 || cause.status === 422) {
        this.refusal.set(this.i18n.t('authoring.refusal.rejected'));
        return;
      }
    }
    this.refusal.set(this.i18n.t('authoring.refusal.error'));
  }

  private readonly refusalRequestId = signal<string | null>(null);

  protected refusalCorrelationId(): string | null {
    return this.refusalRequestId();
  }

  // -- rendering helpers ----------------------------------------------------

  /**
   * The gloss for a lifecycle state, or `null` when this build has never heard of it.
   *
   * The state itself is the server's word and is always shown verbatim beside the gloss —
   * this translates the meaning, it does not replace the fact. An unrecognised state gets
   * no gloss at all, because hiding a state the server stated would be worse than an absent
   * explanation.
   */
  protected stateGloss(state: string): string | null {
    const key = state.trim().toUpperCase();
    if (key === 'DRAFT') return this.i18n.t('authoring.state.draft');
    if (key === 'PUBLISHED') return this.i18n.t('authoring.state.published');
    if (key === 'RETIRED') return this.i18n.t('authoring.state.retired');
    return null;
  }

  protected isDraft(state: string): boolean {
    return state.trim().toUpperCase() === 'DRAFT';
  }

  protected isPublished(state: string): boolean {
    return state.trim().toUpperCase() === 'PUBLISHED';
  }

  protected isRetired(state: string): boolean {
    return state.trim().toUpperCase() === 'RETIRED';
  }

  /** A date, locale-formatted, or an em dash when the server published none. */
  protected when(value: string | null): string {
    return value === null ? '—' : this.format.dateTime(value);
  }

  protected byteCount(value: number): string {
    return this.format.integer(value);
  }

  protected requestIdOf<T>(store: ResourceStore<T>): string | null {
    const error = store.error();
    return error instanceof ApiError ? (error.requestId ?? null) : null;
  }

  /**
   * The definitions authored in this session, for the template.
   *
   * A computed over the store rather than the store itself, so the template reads
   * `authoredDefinitions()` instead of `definitions().value()?.length ?? 0` in five places,
   * and so "no definitions" is an explicit empty list rather than a nullable chain that
   * quietly becomes an empty one in a template.
   *
   * ## Why the list is what *this session* authored
   *
   * There is no "list all report definitions" route — only "read one" and "list its
   * versions". So this list is what this session created, accumulated from the server's own
   * responses. A definition authored by somebody else in another session is not shown, and
   * the screen does not pretend otherwise: offering an id box to look one up would be the
   * four-UUIDs problem in a smaller disguise. Every id here came from a response.
   */
  protected readonly authoredDefinitions = computed<readonly AuthoredDefinition[]>(
    () => this.definitions.value() ?? [],
  );

  /** The templates authored in this session. Same limit, same reason. */
  protected readonly authoredTemplates = computed<readonly AuthoredTemplate[]>(
    () => this.templates.value() ?? [],
  );

  /** Called by the parent when it wants this screen to re-read after its own changes. */
  async refresh(): Promise<void> {
    await this.load();
  }
}

/**
 * The browser's own IANA zone, or `UTC` when the runtime will not say.
 *
 * Used as the default for a report's time zone. Not a decision — it is a starting value the
 * author can change — and computed from the runtime rather than written as a literal, because
 * a hard-coded default is correct only for whoever typed it and silently wrong for everyone
 * else.
 */
/**
 * Narrows a chosen redaction policy to the wire union, or `null`.
 *
 * Phase 32 typed `CreateReportDefinitionRequest.redactionPolicy` as a closed union of the
 * four published policies rather than a free string, which is the right change: the client
 * can no longer assemble a value the server will refuse. The narrowing is explicit rather
 * than a cast for the same reason as everywhere else in this file — a cast would let an
 * unrecognised value reach the wire and come back as a 400 the operator cannot act on.
 */
function asRedactionPolicy(
  value: string,
): 'none' | 'no-contact-details' | 'no-personal-detail' | 'strict' | null {
  if (value === 'none') return 'none';
  if (value === 'no-contact-details') return 'no-contact-details';
  if (value === 'no-personal-detail') return 'no-personal-detail';
  if (value === 'strict') return 'strict';
  return null;
}
function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** Reads a free-form response array into version rows, skipping anything unusable. */
function readVersions(rows: unknown, numberKey: string): VersionRow[] {
  if (!Array.isArray(rows)) return [];
  return rows
    .map((row): VersionRow | null => {
      const record = asRecord(row);
      if (record === null) return null;
      const value = record[numberKey];
      if (typeof value !== 'number') return null;
      return {
        number: value,
        state: stringOrNull(record['state']) ?? '',
        columns: stringArray(record['columns']),
        changeNote: stringOrNull(record['changeNote']),
        publishedAt: stringOrNull(record['publishedAt']),
        retiredAt: stringOrNull(record['retiredAt']),
      };
    })
    .filter((row): row is VersionRow => row !== null);
}

/** An object, or `null`. Never throws on a primitive or an array. */
function asRecord(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

/** A non-empty string, or `null`. An empty string is an absent value, not a blank one. */
function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

/** An array of non-empty strings; `[]` for anything else. */
function stringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is string => typeof entry === 'string' && entry !== '');
}

/** Blank to `undefined`, so an untouched optional field is omitted rather than sent empty. */
function blankToUndefined(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

/** One entry per non-blank line. An empty line is a typo, not a value. */
function splitLines(value: string): string[] {
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '');
}