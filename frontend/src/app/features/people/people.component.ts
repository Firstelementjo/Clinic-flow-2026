/**
 * People, and the 360 view behind one person.
 *
 * ## Which routes this screen calls
 *
 * | Pane | Route | Purpose |
 * |---|---|---|
 * | list | `GET /api/v1/customers` | the customer page, cursor-paginated |
 * | detail | `GET /api/v1/customers/{id}` | the record, its identities and its conversations |
 * | detail | `PATCH /api/v1/customers/{id}` | editing, with `If-Match` |
 * | detail | `GET /api/v1/notes?resourceType=customer&resourceId=…` | that customer's internal notes |
 * | detail | `POST /api/v1/notes` | writing one |
 * | detail | `DELETE /api/v1/notes/{noteId}` | removing one |
 * | detail | `GET /api/v1/inbox/counts` | nothing — the customer's own counts come on the record |
 *
 * ## The separation rule, and where it lives in the UI
 *
 * `PROJECT_REQUIREMENTS` FR-3.4: customer-facing history and internal history must
 * remain separate. `CustomerDetail.conversations` is the customer's thread list;
 * `ForResource` notes are internal. This screen shows them as two distinct regions
 * with a stated relationship, and **never merges them into one timeline** — a
 * single merged list would put an internal note in the customer's history, which is
 * the specific confusion FR-3.4 exists to prevent.
 *
 * Appointments and follow-ups appear on the record as `AppointmentSummary` and
 * `FollowUpSummary`. There is no clinic route and no lead route, so they are shown
 * where the customer's record puts them and nowhere else.
 */
import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { notBlank } from '../../core/validation/not-blank.validator';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ApiError } from '../../core/api/api.service';
import { CustomerApi, NotesApi, versionOf } from '../../core/api/endpoints';
import type * as Api from '../../core/api/generated';
import { SessionService } from '../../core/auth/session.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { peopleStrings } from './people.strings';

@Component({
  selector: 'fe-people-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    PageHeaderComponent,
    ReactiveFormsModule,
    RouterLink,
    StatusChipComponent,
    TranslatePipe,
  ],
  templateUrl: './people.component.html',
  styleUrl: './people.component.css',
})
export class PeopleComponent implements OnInit, OnDestroy {
  private readonly api = inject(CustomerApi);
  private readonly notes = inject(NotesApi);
  private readonly formBuilder = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly format = inject(FormatService);
  protected readonly i18n =inject(I18nService);
  protected readonly sessions = inject(SessionService);
  protected readonly projects = inject(ProjectContextService);
  protected readonly realtime = inject(RealtimeService);

  protected readonly list = new ResourceStore<Api.CursorPageCustomerListItem>();
  protected readonly record = new ResourceStore<Api.CustomerDetail>();
  protected readonly internalNotes = new ResourceStore<Api.ForResource[]>();

  protected readonly search = signal('');
  protected readonly selectedId = signal<string | null>(null);
  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly conflict = signal<string | null>(null);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly narrow = signal(false);

  protected readonly editForm = this.formBuilder.nonNullable.group({
    displayName: ['', [Validators.maxLength(200)]],
    primaryEmail: ['', [Validators.maxLength(320)]],
    primaryPhone: ['', [Validators.maxLength(40)]],
    tags: ['', [Validators.maxLength(400)]],
  });

  protected readonly noteForm = this.formBuilder.nonNullable.group({
    // `required` alone would accept `"   "`, so a whitespace-only note would reach the
    // server and record nothing on a record the operator believes they annotated.
    body: ['', [Validators.required, notBlank, Validators.maxLength(4000)]],
  });

  /**
   * The customer id carried by the URL.
   *
   * A query parameter on one static route rather than a path segment; see the route
   * table in `app.routes.ts` for why. Only the first value is read: a repeated
   * `?c=a&c=b` is a hand-edited URL and the first is the deterministic choice.
   */
  private readonly routeId = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('c'))),
    { initialValue: null },
  );

  protected readonly rows = computed(() => this.list.value()?.items ?? []);
  protected readonly hasMore = computed(() => (this.nextCursor() ?? '') !== '');
  protected readonly recordVersion = computed(() => versionOf(this.record.value()));

  protected readonly identities = computed(() => this.record.value()?.identities ?? []);
  protected readonly conversations = computed(() => this.record.value()?.conversations ?? []);
  protected readonly followUps = computed(() => this.record.value()?.leads ?? []);
  protected readonly appointments = computed(() => this.record.value()?.appointments ?? []);

  /**
   * Editing a customer record needs `customer:update`.
   *
   * It used to ask for `customer:write`, which is not in the backend's enum, so the
   * Edit button never appeared for anyone.
   */
  protected readonly canEdit = computed(() => this.sessions.can()(PERMISSIONS.customerUpdate));

  /**
   * Internal notes are gated on the note permissions, not the customer ones: a user who
   * may read a customer record still must not be able to write to it, and the server
   * checks `note:create` and `note:delete` on those routes respectively.
   */
  protected readonly canWriteNotes = computed(() => this.sessions.can()(PERMISSIONS.noteCreate));
  protected readonly canDeleteNotes = computed(() => this.sessions.can()(PERMISSIONS.noteDelete));

  private unsubscribeRealtime: (() => void) | null = null;

  constructor() {
    this.i18n.register({ people: peopleStrings as Namespace });

    // The route is the single source of truth for the selection, and the navigation is
    // what loads. Selecting navigates; the route effect adopts and fetches. That is the
    // only ordering under which a click, a shared link and the Back button all behave
    // the same — and an earlier version adopted the id in `ngOnInit` without loading it,
    // so `/app/people/<id>` rendered an empty record pane.
    effect(() => {
      const id = this.routeId();
      untracked(() => this.adopt(id));
    });
  }

  /**
   * Loads whatever the route says is selected.
   *
   * No-op when the id is unchanged, so a realtime frame does not re-fetch the record an
   * operator is editing. Clearing resets every pane.
   */
  private adopt(customerId: string | null): void {
    if (customerId === this.selectedId()) return;
    this.selectedId.set(customerId);
    this.editing.set(false);
    this.notice.set(null);
    this.conflict.set(null);
    if (!customerId) {
      this.record.reset();
      this.internalNotes.reset();
      return;
    }
    void this.loadRecord(customerId);
  }

  ngOnInit(): void {
    // No adoption here; the constructor's route effect covers the first render and
    // doing it in both places would fetch the record twice.
    void this.loadList();
    this.unsubscribeRealtime = this.realtime.onSignal((signal) => {
      if (signal.kind === 'heartbeat') return;
      void this.loadList();
      const id = this.selectedId();
      if (id) void this.loadRecord(id);
    });
  }

  ngOnDestroy(): void {
    this.unsubscribeRealtime?.();
  }

  // -- loading --------------------------------------------------------------

  protected async loadList(): Promise<void> {
    const term = this.search().trim();
    await this.list.load(
      () =>
        this.api.list({
          projectId: this.projects.selectedProjectId() ?? undefined,
          search: term === '' ? undefined : term,
          limit: 25,
        }),
      (page) => (page.items ?? []).length === 0,
    );
    this.nextCursor.set(this.list.value()?.nextCursor ?? null);
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (!cursor) return;
    const page = await this.api.list({
      projectId: this.projects.selectedProjectId() ?? undefined,
      cursor,
      limit: 25,
    });
    this.list.set({
      items: [...(this.list.value()?.items ?? []), ...(page.items ?? [])],
      nextCursor: page.nextCursor,
    });
    this.nextCursor.set(page.nextCursor ?? null);
  }

  protected async loadRecord(customerId: string): Promise<void> {
    await Promise.allSettled([
      this.record.load(() => this.api.detail(customerId)),
      this.internalNotes.load(() =>
        this.notes.forResource({ resourceType: 'customer', resourceId: customerId }),
      ),
    ]);
  }

  protected select(customerId: string): void {
    // Navigation only; `adopt()` loads. See the constructor for why.
    void this.router.navigate(['/app/people'], { queryParams: { c: customerId } });
  }

  protected clearSelection(): void {
    void this.router.navigate(['/app/people'], { queryParams: {} });
  }

  // -- editing --------------------------------------------------------------

  protected startEditing(): void {
    const record = this.record.value();
    if (!record) return;
    this.editForm.patchValue({
      displayName: record.displayName ?? '',
      primaryEmail: record.primaryEmail ?? '',
      primaryPhone: record.primaryPhone ?? '',
      tags: (record.tags ?? []).join(', '),
    });
    this.editing.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(false);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const id = this.selectedId();
    if (!id) return;
    this.saving.set(true);
    this.conflict.set(null);
    const value = this.editForm.getRawValue();
    try {
      const updated = await this.api.update(
        id,
        {
          displayName: value.displayName,
          primaryEmail: value.primaryEmail,
          primaryPhone: value.primaryPhone,
          // Split on commas and trimmed, because a tag with a stray space is a
          // different tag to a search and nobody notices until it does.
          tags: value.tags
            .split(',')
            .map((tag) => tag.trim())
            .filter((tag) => tag !== ''),
        },
        this.recordVersion(),
      );
      this.record.set(updated);
      this.editing.set(false);
      this.notice.set(this.i18n.t('people.detail.saved'));
      await this.loadList();
    } catch (cause) {
      this.reportConflict(cause);
    } finally {
      this.saving.set(false);
    }
  }

  // -- internal notes -------------------------------------------------------

  protected noteFieldError(): string | null {
    const body = this.noteForm.controls.body;
    if (!body.touched) return null;
    // `required` and `blank` are the same message to the operator — "write something" —
    // and are checked together so a whitespace-only note cannot slip past one of them.
    if (body.hasError('required') || body.hasError('blank')) {
      return this.i18n.t('people.detail.noteEmpty');
    }
    return null;
  }

  protected async addNote(event: Event): Promise<void> {
    event.preventDefault();
    const id = this.selectedId();
    if (!id) return;
    // The form is hidden without `note:create`, so this is belt and braces: a stale
    // render must not turn into a request the server will refuse.
    if (!this.canWriteNotes()) return;
    if (this.noteForm.invalid) {
      this.noteForm.markAllAsTouched();
      return;
    }
    const body = this.noteForm.getRawValue().body;
    try {
      await this.notes.create({
        resourceType: 'customer',
        resourceId: id,
        body,
        projectId: this.projects.selectedProjectId() ?? undefined,
      });
      // The form is cleared only after the server has taken it.
      this.noteForm.reset({ body: '' });
      this.notice.set(this.i18n.t('people.detail.noteSaved'));
      await this.internalNotes.refresh();
    } catch (cause) {
      this.reportConflict(cause);
    }
  }

  protected async deleteNote(noteId: string | undefined): Promise<void> {
    if (!noteId) return;
    // Deleting a note is `note:delete`, which is not the same grant as writing one.
    if (!this.canDeleteNotes()) return;
    try {
      await this.notes.delete(noteId, this.projects.selectedProjectId() ?? undefined);
      this.notice.set(this.i18n.t('people.detail.noteDeleted'));
      await this.internalNotes.refresh();
    } catch (cause) {
      this.reportConflict(cause);
    }
  }

  protected noteAuthor(note: Api.ForResource): string {
    return note.author?.fullName ?? this.format.dash;
  }

  // -- presentation ---------------------------------------------------------

  protected followUpLabel(status: string | null | undefined): string {
    if (!status) return this.format.dash;
    const key = `people.followUp.status.${status.toUpperCase()}`;
    return this.i18n.has(key) ? this.i18n.t(key) : status;
  }

  protected appointmentLabel(status: string | null | undefined): string {
    if (!status) return this.format.dash;
    const key = `people.appointment.status.${status.toUpperCase()}`;
    return this.i18n.has(key) ? this.i18n.t(key) : status;
  }

  protected appointmentTone(status: string | null | undefined): 'ok' | 'warn' | 'bad' | 'neutral' {
    switch ((status ?? '').toUpperCase()) {
      case 'CONFIRMED':
        return 'ok';
      case 'CANCELLED':
        return 'bad';
      case 'COMPLETED':
        return 'neutral';
      default:
        return 'warn';
    }
  }

  protected recordErrorRequestId(): string | null {
    const error = this.record.error();
    return error instanceof ApiError ? error.requestId : null;
  }

  private reportConflict(cause: unknown): void {
    if (cause instanceof ApiError && (cause.status === 409 || cause.status === 412)) {
      this.conflict.set(this.i18n.t('people.detail.conflict'));
      return;
    }
    if (cause instanceof ApiError && cause.isPermissionDenied) {
      this.conflict.set(this.i18n.t('state.noPermission'));
      return;
    }
    this.conflict.set(this.i18n.t('state.error'));
  }
}
