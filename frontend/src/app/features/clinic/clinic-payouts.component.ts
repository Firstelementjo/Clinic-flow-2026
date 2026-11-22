import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ClinicApi, type ClinicDoctor } from '../../core/api/endpoints';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { clinicAdminStrings } from './clinic-admin.strings';
import { ClinicPatientApi, type DoctorPayout } from './clinic-patient.api';

/**
 * Doctor payouts: the settled commission periods. The amount is computed by
 * the server from COMPLETED appointments in the period, at the rate stored on
 * the payout row, so this screen never sends money.
 */
@Component({
  selector: 'fe-clinic-payouts',
  standalone: true,
  imports: [CommonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-payouts">
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">{{ t('nav.home') }}</a>
        <span>»</span>
        <span class="active">{{ t('payouts.breadcrumb') }}</span>
      </div>

      <header class="tl-page-head">
        <div>
          <h1>{{ t('payouts.title') }}</h1>
          <p>{{ t('payouts.subtitle') }}</p>
        </div>
        <div class="tl-head-actions">
          <a class="tl-btn tl-btn-outline" routerLink="/app/clinic/commission">
            {{ t('commission.title') }}
          </a>
          <button
            type="button"
            class="tl-btn tl-btn-outline"
            [disabled]="rows().length === 0"
            (click)="exportCsv()"
          >
            {{ t('common.export') }}
          </button>
          <button type="button" class="tl-btn tl-btn-teal" (click)="openDrawer()">
            {{ t('payouts.newPayout') }}
          </button>
        </div>
      </header>

      <section class="tl-card tl-filters">
        <label class="tl-field">
          <span>{{ t('commission.doctor') }}</span>
          <select
            class="tl-input"
            [value]="doctorFilter()"
            (change)="doctorFilter.set($any($event.target).value); load()"
          >
            <option value="">{{ t('payouts.allDoctors') }}</option>
            @for (doctor of doctors(); track doctor.id) {
              <option [value]="doctor.id">{{ doctor.name }}</option>
            }
          </select>
        </label>
        <label class="tl-field">
          <span>{{ t('common.status') }}</span>
          <select
            class="tl-input"
            [value]="statusFilter()"
            (change)="statusFilter.set($any($event.target).value); load()"
          >
            <option value="">{{ t('payouts.allStatuses') }}</option>
            <option value="PENDING">{{ t('payouts.status.PENDING') }}</option>
            <option value="PAID">{{ t('payouts.status.PAID') }}</option>
          </select>
        </label>
        <button type="button" class="tl-btn tl-btn-teal" [disabled]="loading()" (click)="load()">
          {{ t('common.apply') }}
        </button>
      </section>

      <section class="tl-stats">
        <article class="tl-stat is-green">
          <span>{{ t('payouts.status.PAID') }}</span>
          <strong>{{ money(paidTotal()) }}</strong>
        </article>
        <article class="tl-stat is-amber">
          <span>{{ t('payouts.status.PENDING') }}</span>
          <strong>{{ money(pendingTotal()) }}</strong>
        </article>
      </section>

      <div class="tl-card">
        @if (loading()) {
          <p class="tl-note">{{ t('common.loading') }}</p>
        } @else if (error()) {
          <div class="tl-note is-error">
            <span>{{ t('payouts.loadFailed') }}</span>
            <button type="button" class="tl-link" (click)="load()">{{ t('common.retry') }}</button>
          </div>
        } @else if (rows().length === 0) {
          <div class="tl-note">
            <strong>{{ t('payouts.empty') }}</strong>
            <span>{{ t('payouts.emptyBody') }}</span>
          </div>
        } @else {
          <div class="tl-table-wrap">
            <table class="tl-table">
              <thead>
                <tr>
                  <th>{{ t('commission.doctor') }}</th>
                  <th>{{ t('payouts.period') }}</th>
                  <th class="num">{{ t('payouts.gross') }}</th>
                  <th class="num">{{ t('payouts.rate') }}</th>
                  <th class="num">{{ t('payouts.commission') }}</th>
                  <th>{{ t('payouts.currency') }}</th>
                  <th>{{ t('common.status') }}</th>
                  <th>{{ t('payouts.paidAt') }}</th>
                  <th class="num">{{ t('common.actions') }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.id) {
                  <tr>
                    <td><strong>{{ row.doctorName || '—' }}</strong></td>
                    <td>{{ row.periodStart }} → {{ row.periodEnd }}</td>
                    <td class="num">{{ money(row.grossAmount, row.currency) }}</td>
                    <td class="num">{{ percent(row.commissionRate) }}</td>
                    <td class="num">{{ money(row.commissionAmount, row.currency) }}</td>
                    <td>{{ row.currency || 'JOD' }}</td>
                    <td>
                      <span
                        class="tl-badge"
                        [class.is-paid]="row.status === 'PAID'"
                        [class.is-pending]="row.status !== 'PAID'"
                      >
                        {{ statusLabel(row.status) }}
                      </span>
                    </td>
                    <td>{{ row.paidAt ? format.date(row.paidAt) : '—' }}</td>
                    <td class="num">
                      <button
                        type="button"
                        class="tl-btn tl-btn-small"
                        [class.tl-btn-teal]="row.status !== 'PAID'"
                        [class.tl-btn-outline]="row.status === 'PAID'"
                        [disabled]="busyId() === row.id"
                        (click)="toggleStatus(row)"
                      >
                        {{ row.status === 'PAID' ? t('payouts.markPending') : t('payouts.markPaid') }}
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="tl-hint">{{ t('payouts.deleteHint') }}</p>
        }
      </div>

      @if (drawerOpen()) {
        <div class="tl-drawer-backdrop" (click)="closeDrawer()">
          <aside
            class="tl-drawer"
            data-testid="payouts-drawer"
            (click)="$event.stopPropagation()"
          >
            <header class="tl-drawer-header">
              <h2>{{ t('payouts.newPayout') }}</h2>
              <button type="button" class="tl-close-btn" (click)="closeDrawer()">&times;</button>
            </header>

            <div class="tl-drawer-body">
              <label class="tl-field">
                <span>{{ t('commission.doctor') }} *</span>
                <select
                  class="tl-input"
                  [value]="draftDoctorId()"
                  (change)="draftDoctorId.set($any($event.target).value)"
                >
                  <option value="" disabled>{{ t('commission.noDoctor') }}</option>
                  @for (doctor of doctors(); track doctor.id) {
                    <option [value]="doctor.id">{{ doctor.name }}</option>
                  }
                </select>
              </label>

              <div class="tl-form-grid-2">
                <label class="tl-field">
                  <span>{{ t('commission.from') }} *</span>
                  <input
                    type="date"
                    class="tl-input"
                    [value]="draftPeriodStart()"
                    (change)="draftPeriodStart.set($any($event.target).value)"
                  />
                </label>
                <label class="tl-field">
                  <span>{{ t('commission.to') }} *</span>
                  <input
                    type="date"
                    class="tl-input"
                    [value]="draftPeriodEnd()"
                    (change)="draftPeriodEnd.set($any($event.target).value)"
                  />
                </label>
              </div>

              <label class="tl-field">
                <span>{{ t('payouts.notes') }}</span>
                <textarea
                  rows="3"
                  class="tl-textarea"
                  [value]="draftNotes()"
                  (input)="draftNotes.set($any($event.target).value)"
                ></textarea>
              </label>
            </div>

            <footer class="tl-drawer-footer">
              <button type="button" class="tl-btn tl-btn-outline" (click)="closeDrawer()">
                {{ t('common.cancel') }}
              </button>
              <button
                type="button"
                class="tl-btn tl-btn-teal"
                [disabled]="saveBusy() || !draftDoctorId()"
                (click)="submitPayout()"
              >
                {{ saveBusy() ? t('common.saving') : t('common.save') }}
              </button>
            </footer>
          </aside>
        </div>
      }

      @if (toast(); as notice) {
        <div class="tl-toast" role="status" [class.is-error]="notice.error">
          <span>{{ notice.message }}</span>
          <button type="button" class="tl-toast-close" (click)="toast.set(null)">×</button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .tl-page {
        padding: 12px 18px 28px;
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .tl-breadcrumbs {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: var(--mute);
      }
      .tl-breadcrumbs a {
        color: var(--tl-teal);
        text-decoration: none;
        font-weight: 600;
      }
      .tl-breadcrumbs .active {
        color: var(--ink);
        font-weight: 700;
      }
      .tl-page-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 16px;
        flex-wrap: wrap;
      }
      .tl-page-head h1 {
        margin: 0 0 4px;
        font-size: 19px;
        color: var(--ink);
      }
      .tl-page-head p {
        margin: 0;
        font-size: 12.5px;
        color: var(--mute);
        max-width: 640px;
      }
      .tl-head-actions {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
      }
      .tl-card {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 14px 16px;
        box-shadow: var(--shadow-soft);
      }
      .tl-filters {
        display: flex;
        align-items: flex-end;
        gap: 12px;
        flex-wrap: wrap;
      }
      .tl-stats {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        gap: 10px;
      }
      .tl-stat {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 12px 14px;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .tl-stat span {
        font-size: 11.5px;
        color: var(--mute);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.02em;
      }
      .tl-stat strong {
        font-size: 19px;
        color: var(--ink);
      }
      .tl-stat.is-green {
        border-color: var(--accent-green);
        background: var(--accent-green-soft);
      }
      .tl-stat.is-green strong {
        color: var(--accent-green-ink);
      }
      .tl-stat.is-amber {
        border-color: var(--accent-amber-line);
        background: var(--accent-amber-soft);
      }
      .tl-stat.is-amber strong {
        color: var(--accent-amber-ink);
      }
      .tl-field {
        display: flex;
        flex-direction: column;
        gap: 5px;
        font-size: 12px;
        color: var(--mute);
        font-weight: 600;
      }
      .tl-input,
      .tl-textarea {
        border: 1px solid var(--line);
        border-radius: 6px;
        background: var(--surf);
        color: var(--ink);
        font-size: 13px;
        padding: 0 10px;
        height: 38px;
        font-family: inherit;
        min-width: 160px;
      }
      .tl-textarea {
        height: auto;
        padding: 9px 10px;
        min-height: 74px;
      }
      .tl-btn {
        height: 38px;
        padding: 0 14px;
        border-radius: 6px;
        border: 1px solid transparent;
        font-size: 13px;
        font-weight: 600;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: 6px;
        text-decoration: none;
      }
      .tl-btn:disabled {
        opacity: 0.55;
        cursor: not-allowed;
      }
      .tl-btn-teal {
        background: var(--tl-teal);
        color: var(--on-accent);
      }
      .tl-btn-outline {
        background: var(--surf2);
        border-color: var(--line);
        color: var(--ink);
      }
      .tl-btn-small {
        height: 30px;
        padding: 0 10px;
        font-size: 12px;
      }
      .tl-link {
        border: none;
        background: transparent;
        color: var(--tl-teal);
        font-weight: 700;
        cursor: pointer;
        padding: 0;
      }
      .tl-table-wrap {
        overflow-x: auto;
      }
      .tl-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 13px;
      }
      .tl-table th {
        text-align: left;
        font-size: 11.5px;
        text-transform: uppercase;
        letter-spacing: 0.03em;
        color: var(--mute);
        padding: 9px 10px;
        border-bottom: 1px solid var(--line);
        background: var(--surf2);
        white-space: nowrap;
      }
      .tl-table td {
        padding: 10px;
        border-bottom: 1px solid var(--line);
        color: var(--ink);
        vertical-align: middle;
      }
      .tl-table .num {
        text-align: right;
        white-space: nowrap;
      }
      .tl-badge {
        display: inline-block;
        padding: 3px 9px;
        border-radius: 999px;
        font-weight: 700;
        font-size: 11.5px;
      }
      .tl-badge.is-paid {
        background: var(--accent-green-soft);
        color: var(--accent-green-ink);
      }
      .tl-badge.is-pending {
        background: var(--accent-amber-soft);
        color: var(--accent-amber-ink);
      }
      .tl-note {
        display: flex;
        flex-direction: column;
        gap: 4px;
        font-size: 13px;
        color: var(--mute);
        padding: 18px 6px;
      }
      .tl-note.is-error {
        color: var(--accent-red-ink);
        flex-direction: row;
        align-items: center;
        gap: 10px;
      }
      .tl-hint {
        margin: 10px 0 0;
        font-size: 12px;
        color: var(--mute);
      }
      .tl-drawer-backdrop {
        position: fixed;
        inset: 0;
        background: var(--shadow-panel);
        display: flex;
        justify-content: flex-end;
        z-index: 40;
      }
      .tl-drawer {
        width: min(520px, 100%);
        background: var(--surf);
        height: 100%;
        display: flex;
        flex-direction: column;
        box-shadow: var(--shadow-card);
      }
      .tl-drawer-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 16px 20px;
        border-bottom: 1px solid var(--line);
      }
      .tl-drawer-header h2 {
        margin: 0;
        font-size: 16px;
        color: var(--ink);
      }
      .tl-close-btn {
        border: none;
        background: transparent;
        font-size: 22px;
        line-height: 1;
        color: var(--mute);
        cursor: pointer;
      }
      .tl-drawer-body {
        flex: 1;
        overflow-y: auto;
        padding: 18px 20px;
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .tl-form-grid-2 {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
        gap: 12px;
      }
      .tl-drawer-footer {
        display: flex;
        justify-content: flex-end;
        gap: 10px;
        padding: 14px 20px;
        border-top: 1px solid var(--line);
        background: var(--surf2);
      }
      .tl-toast {
        position: fixed;
        left: 20px;
        bottom: 20px;
        z-index: 50;
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 11px 14px;
        border-radius: 8px;
        background: var(--ink-strong);
        color: var(--on-accent);
        font-size: 13px;
        box-shadow: var(--shadow-card);
      }
      .tl-toast.is-error {
        background: var(--accent-red-ink);
      }
      .tl-toast-close {
        border: none;
        background: transparent;
        color: inherit;
        font-size: 16px;
        cursor: pointer;
        line-height: 1;
      }
    `,
  ],
})
export class ClinicPayoutsComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly clinic = inject(ClinicApi);
  private readonly projects = inject(ProjectContextService);
  protected readonly format = inject(FormatService);
  private readonly i18n = inject(I18nService);

  protected readonly rows = signal<readonly DoctorPayout[]>([]);
  protected readonly doctors = signal<readonly ClinicDoctor[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);
  protected readonly saveBusy = signal(false);
  protected readonly busyId = signal<string | null>(null);

  protected readonly doctorFilter = signal('');
  protected readonly statusFilter = signal('');

  protected readonly drawerOpen = signal(false);
  protected readonly draftDoctorId = signal('');
  protected readonly draftPeriodStart = signal(firstOfMonth());
  protected readonly draftPeriodEnd = signal(todayIso());
  protected readonly draftNotes = signal('');

  protected readonly toast = signal<{ message: string; error: boolean } | null>(null);

  protected readonly paidTotal = computed(() =>
    this.rows()
      .filter((row) => row.status === 'PAID')
      .reduce((sum, row) => sum + row.commissionAmount, 0),
  );
  protected readonly pendingTotal = computed(() =>
    this.rows()
      .filter((row) => row.status !== 'PAID')
      .reduce((sum, row) => sum + row.commissionAmount, 0),
  );

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
  }

  protected t(key: string): string {
    return this.i18n.t(`clinicAdmin.${key}`);
  }

  ngOnInit(): void {
    void this.load();
  }

  private projectQuery(): { projectId?: string } {
    const projectId = this.projects.selectedProjectId();
    return projectId ? { projectId } : {};
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(false);
    try {
      const query = this.projectQuery();
      const [payouts, doctors] = await Promise.all([
        this.api.doctorPayouts({
          ...query,
          ...(this.doctorFilter() ? { doctorId: this.doctorFilter() } : {}),
          ...(this.statusFilter() ? { status: this.statusFilter() } : {}),
        }),
        this.clinic.doctors(query),
      ]);
      this.rows.set(payouts);
      this.doctors.set(doctors.items ?? []);
    } catch {
      this.error.set(true);
      this.rows.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  protected money(value: number | null | undefined, currency = 'JOD'): string {
    return this.format.currency(value ?? 0, currency);
  }

  protected percent(value: number | null | undefined): string {
    return this.format.percent(value ?? 0, 2);
  }

  protected statusLabel(status: string): string {
    return status === 'PAID'
      ? this.t('payouts.status.PAID')
      : this.t('payouts.status.PENDING');
  }

  protected openDrawer(): void {
    this.draftDoctorId.set('');
    this.draftPeriodStart.set(firstOfMonth());
    this.draftPeriodEnd.set(todayIso());
    this.draftNotes.set('');
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  protected async submitPayout(): Promise<void> {
    const doctorId = this.draftDoctorId();
    if (!doctorId || this.saveBusy()) return;
    this.saveBusy.set(true);
    try {
      await this.api.createDoctorPayout(
        {
          doctorId,
          periodStart: this.draftPeriodStart(),
          periodEnd: this.draftPeriodEnd(),
          notes: this.draftNotes().trim() || undefined,
          ...this.projectQuery(),
        },
        this.projectQuery(),
      );
      this.drawerOpen.set(false);
      this.toast.set({ message: this.t('payouts.saved'), error: false });
      await this.load();
    } catch {
      this.toast.set({ message: this.t('payouts.saveFailed'), error: true });
    } finally {
      this.saveBusy.set(false);
    }
  }

  protected async toggleStatus(row: DoctorPayout): Promise<void> {
    if (this.busyId() !== null) return;
    this.busyId.set(row.id);
    const next = row.status === 'PAID' ? 'PENDING' : 'PAID';
    try {
      const updated = await this.api.updateDoctorPayoutStatus(
        row.id,
        next,
        this.projectQuery(),
      );
      this.rows.update((list) =>
        list.map((item) => (item.id === updated.id ? updated : item)),
      );
      this.toast.set({ message: this.t('payouts.statusUpdated'), error: false });
    } catch {
      this.toast.set({ message: this.t('common.permissionDenied'), error: true });
    } finally {
      this.busyId.set(null);
    }
  }

  protected exportCsv(): void {
    const rows = this.rows();
    if (rows.length === 0) return;
    const csv = [
      'Doctor,Period Start,Period End,Gross,Rate %,Commission,Currency,Status,Paid At',
      ...rows.map((row) =>
        [
          `"${(row.doctorName ?? '').replace(/"/g, '""')}"`,
          row.periodStart,
          row.periodEnd,
          row.grossAmount,
          row.commissionRate,
          row.commissionAmount,
          row.currency ?? 'JOD',
          row.status,
          row.paidAt ?? '',
        ].join(','),
      ),
    ].join('\r\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `doctor-payouts-${todayIso()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }
}

function todayIso(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function firstOfMonth(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-01`;
}
