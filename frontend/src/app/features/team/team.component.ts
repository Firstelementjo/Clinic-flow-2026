/**
 * Team.
 *
 * ## Which routes this screen calls
 *
 * | Section | Route | Gated on |
 * |---|---|---|
 * | summary, users, memberships, service principals, credentials | `GET /api/v1/admin/identity` | `employee:read` |
 * | live sessions | `GET /api/v1/admin/identity/sessions` | `employee:read` |
 * | a user's effective permissions | `GET /api/v1/permissions/{userId}` | `employee:read` |
 *
 * ## What is deliberately absent, and why
 *
 * The frozen `TeamPage` had two halves: a table of staff, and an import card with a
 * dashed drop area for CSV/XLSX. **Only the table has a route.**
 *
 * `GET /api/v1/admin/identity` is an administration read model, and it is what this
 * screen reads. Working hours, availability, skills, capacity and employee import
 * have no route in the committed contract. Three things follow:
 *
 * 1. No import form is rendered. A drop area that 404s on submit is a promise the
 *    product cannot keep, and a file picker that silently discards a staff list is
 *    worse than no control.
 * 2. The absence is **stated** — a `fe-not-available` region naming the missing
 *    capability. Phase-11 hard rule 8 forbids a mock; it does not forbid saying
 *    "this does not exist yet".
 * 3. Nothing is invented. No fake availability grid, no synthetic capacity figure.
 *
 * When the backend adds the routes and re-promotes the contract, the region is
 * deleted and the screens added — a change a reviewer can see in the diff.
 *
 * ## Secrets
 *
 * `CredentialPresence` carries `hasCredentials` and `hasWebhookSecret` as booleans.
 * That is all this screen renders. The secret values are not in the response model,
 * and a screen that displayed one would be a defect regardless of what the server
 * sent — `AGENTS.md`: no secret display in the Admin UI.
 */
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ApiError } from '../../core/api/api.service';
import { AdminApi, PermissionsApi } from '../../core/api/endpoints';
import type * as Api from '../../core/api/generated';
import { SessionService } from '../../core/auth/session.service';
import { PERMISSIONS } from '../../core/auth/permissions';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { NotAvailableComponent } from '../../ui/not-available.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { teamStrings } from './team.strings';

@Component({
  selector: 'fe-team-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    NotAvailableComponent,
    PageHeaderComponent,
    StatusChipComponent,
    TranslatePipe,
  ],
  templateUrl: './team.component.html',
  styleUrl: './team.component.css',
})
export class TeamComponent implements OnInit {
  private readonly admin = inject(AdminApi);
  private readonly permissionsApi = inject(PermissionsApi);

  protected readonly format = inject(FormatService);
  protected readonly i18n = inject(I18nService);
  protected readonly sessions = inject(SessionService);

  protected readonly directory = new ResourceStore<Api.DirectoryView>();
  protected readonly liveSessions = new ResourceStore<Api.SessionRow[]>();
  protected readonly inspection = new ResourceStore<Api.PermissionInspection>();

  protected readonly inspectedUserId = signal<string | null>(null);
  protected readonly narrow = signal(false);

  protected readonly canRead = computed(() => this.sessions.can()(PERMISSIONS.employeeRead));

  protected readonly users = computed(() => this.directory.value()?.users ?? []);
  protected readonly memberships = computed(() => this.directory.value()?.memberships ?? []);
  protected readonly observedPrincipals = computed(
    () => this.directory.value()?.observedMachinePrincipals ?? [],
  );
  protected readonly credentials = computed(() => this.directory.value()?.credentialPresence ?? []);

  /** Memberships grouped by user, so the table can show "in 2 projects" without N+1. */
  protected readonly projectsByUser = computed(() => {
    const grouped = new Map<string, string[]>();
    for (const membership of this.memberships()) {
      const list = grouped.get(membership.userId ?? '') ?? [];
      if (membership.projectName) list.push(membership.projectName);
      grouped.set(membership.userId ?? '', list);
    }
    return grouped;
  });

  constructor() {
    this.i18n.register({ team: teamStrings as Namespace });
  }

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    if (!this.canRead()) return;
    await Promise.allSettled([
      this.directory.load(() => this.admin.identity()),
      this.liveSessions.load(() => this.admin.sessions(), (rows) => rows.length === 0),
    ]);
  }

  protected async inspect(userId: string): Promise<void> {
    this.inspectedUserId.set(userId);
    await this.inspection.load(() => this.permissionsApi.inspect(userId));
  }

  protected roleLabel(role: string | null | undefined): string {
    if (!role) return this.format.dash;
    const key = `team.role.${role}`;
    return this.i18n.has(key) ? this.i18n.t(key) : role;
  }

  protected principalTypeLabel(type: string | null | undefined): string {
    if (!type) return this.format.dash;
    const key = `team.principalType.${type}`;
    return this.i18n.has(key) ? this.i18n.t(key) : type;
  }

  protected projectsFor(userId: string | undefined): string {
    if (!userId) return this.format.dash;
    const names = this.projectsByUser().get(userId) ?? [];
    if (names.length === 0) return this.format.dash;
    return this.i18n.t('team.table.membershipCount', { count: names.length });
  }

  protected directoryErrorRequestId(): string | null {
    const error = this.directory.error();
    return error instanceof ApiError ? error.requestId : null;
  }

  protected summaryFacts(): { key: string; label: string; value: string }[] {
    const summary = this.directory.value()?.summary;
    if (!summary) return [];
    return [
      { key: 'users', label: this.i18n.t('team.summary.totalUsers'), value: this.format.integer(summary.totalUsers ?? null) },
      { key: 'inactive', label: this.i18n.t('team.summary.inactiveUsers'), value: this.format.integer(summary.inactiveUsers ?? null) },
      { key: 'admins', label: this.i18n.t('team.summary.administrators'), value: this.format.integer(summary.administrators ?? null) },
      { key: 'sessions', label: this.i18n.t('team.summary.liveSessions'), value: this.format.integer(summary.liveSessions ?? null) },
      { key: 'memberships', label: this.i18n.t('team.summary.memberships'), value: this.format.integer(summary.memberships ?? null) },
      { key: 'projects', label: this.i18n.t('team.summary.projects'), value: this.format.integer(summary.projects ?? null) },
      { key: 'archived', label: this.i18n.t('team.summary.archivedProjects'), value: this.format.integer(summary.archivedProjects ?? null) },
    ];
  }
}
