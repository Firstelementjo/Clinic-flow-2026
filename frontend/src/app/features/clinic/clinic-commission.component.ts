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
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { clinicAdminStrings } from './clinic-admin.strings';
import {
  ClinicPatientApi,
  type DoctorCommission,
  type DoctorPayout,
} from './clinic-patient.api';

/**
 * Doctor commission: what each doctor earned in a window, what has been paid,
 * and a way to settle the balance. Every figure is computed by the server from
 * COMPLETED appointments and the rate stored on the doctor row; this screen
 * never derives money on the client.
 */
@Component({
  selector: 'fe-clinic-commission',
  standalone: true,
  imports: [CommonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tl-page" data-testid="clinic-commission">
      <div class="tl-breadcrumbs">
        <a routerLink="/app/dashboard">{{ t('nav.home') }}</a>
        <span>»</span>
        <span class="active">{{ t('commission.breadcrumb') }}</span>
      </div>

      <header class="tl-page-head">
        <div>
          <h1>{{ t('commission.title') }}</h1>
          <p>{{ t('commission.subtitle') }}</p>
        </div>
        <div class="tl-head-actions">
          <a class="tl-btn tl-btn-outline" routerLink="/app/clinic/payouts">
            {{ t('commission.paid') }}
          </a>
          <button
            type="button"
            class="tl-btn tl-btn-outline"
            [disabled]="rows().length === 0"
            (click)="exportCsv()"
          >
            {{ t('commission.export') }}
          </button>
        </div>
      </header>

      <section class="tl-card tl-filters">
        <label class="tl-field">
          <span>{{ t('commission.from') }}</span>
          <input
            type="date"
            class="tl-input"
            [value]="from()"
            (change)="from.set($any($event.target).value)"
          />
        </label>
        <label class="tl-field">
          <span>{{ t('commission.to') }}</span>
          <input
            type="date"
            class="tl-input"
            [value]="to()"
            (change)="to.set($any($event.target).value)"
          />
        </label>
        <button type="button" class="tl-btn tl-btn-teal" [disabled]="loading()" (click)="load()">
          {{ t('common.apply') }}
        </button>
      </section>

      <section class="tl-stats">
        <article class="tl-stat">
          <span>{{ t('commission.totalGross') }}</span>
          <strong>{{ money(totalGross()) }}</strong>
        </article>
        <article class="tl-stat is-teal">
          <span>{{ t('commission.totalCommission') }}</span>
          <strong>{{ money(totalCommission()) }}</strong>
        </article>
        <article class="tl-stat is-green">
          <span>{{ t('commission.totalPaid') }}</span>
          <strong>{{ money(totalPaid()) }}</strong>
        </article>
        <article class="tl-stat is-amber">
          <span>{{ t('commission.totalBalance') }}</span>
          <strong>{{ money(totalBalance()) }}</strong>
        </article>
      </section>

      <div class="tl-card">
        @if (loading()) {
          <p class="tl-note">{{ t('common.loading') }}</p>
        } @else if (error()) {
          <div class="tl-note is-error">
            <span>{{ t('commission.loadFailed') }}</span>
            <button type="button" class="tl-link" (click)="load()">{{ t('common.retry') }}</button>
          </div>
        } @else if (rows().length === 0) {
          <div class="tl-note">
            <strong>{{ t('commission.empty') }}</strong>
            <span>{{ t('commission.emptyBody') }}</span>
          </div>
        } @else {
          <div class="tl-table-wrap">
            <table class="tl-table">
              <thead>
                <tr>
                  <th>{{ t('commission.doctor') }}</th>
                  <th>{{ t('commission.specialty') }}</th>
                  <th class="num">{{ t('commission.rate') }}</th>
                  <th class="num">{{ t('commission.appointments') }}</th>
                  <th class="num">{{ t('commission.gross') }}</th>
                  <th class="num">{{ t('commission.commission') }}</th>
                  <th class="num">{{ t('commission.paid') }}</th>
                  <th class="num">{{ t('commission.balance') }}</th>
                  <th class="num">{{ t('common.actions') }}</th>
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.doctorId) {
                  <tr>
                    <td><strong>{{ row.doctorName }}</strong></td>
                    <td>{{ row.specialty || '—' }}</td>
                    <td class="num">{{ percent(row.commissionRate) }}</td>
                    <td class="num">{{ row.appointmentCount }}</td>
                    <td class="num">{{ money(row.grossRevenue, row.currency) }}</td>
                    <td class="num">{{ money(row.commissionAmount, row.currency) }}</td>
                    <td class="num">{{ money(row.paidAmount, row.currency) }}</td>
                    <td class="num">
                      <span
                        class="tl-badge"
                        [class.is-open]="row.balanceDue > 0"
                        [class.is-settled]="row.balanceDue <= 0"
                      >
                        {{ money(row.balanceDue, row.currency) }}
                      </span>
                    </td>
                    <td class="num">
                      <button
                        type="button"
                        class="tl-btn tl-btn-small tl-btn-teal"
                        [disabled]="row.balanceDue <= 0"
                        (click)="openPayout(row)"
                      >
                        {{ t('commission.createPayout') }}
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>

      @if (drawerOpen()) {
        <div class="tl-drawer-backdrop" (click)="closeDrawer()">
          <aside
            class="tl-drawer"
            data-testid="commission-payout-drawer"
            (click)="$event.stopPropagation()"
          >
            <header class="tl-drawer-header">
              <h2>{{ t('commission.createPayout') }}</h2>
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
                  @for (row of payableRows(); track row.doctorId) {
                    <option [value]="row.doctorId">{{ row.doctorName }}</option>
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

              @if (draftDoctorId()) {
                <p class="tl-hint">{{ payoutHint() }}</p>
              }
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
        min-width: 150px;
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
      .tl-stats {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
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
      .tl-stat.is-teal {
        border-color: var(--tl-teal);
        background: var(--tl-teal-soft);
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
      .tl-badge.is-open {
        background: var(--accent-amber-soft);
        color: var(--accent-amber-ink);
      }
      .tl-badge.is-settled {
        background: var(--accent-green-soft);
        color: var(--accent-green-ink);
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
        margin: 0;
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
export class ClinicCommissionComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly projects = inject(ProjectContextService);
  private readonly format = inject(FormatService);
  private readonly i18n = inject(I18nService);

  protected readonly rows = signal<readonly DoctorCommission[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal(false);
  protected readonly saveBusy = signal(false);

  protected readonly from = signal(firstOfMonth());
  protected readonly to = signal(todayIso());

  protected readonly drawerOpen = signal(false);
  protected readonly draftDoctorId = signal('');
  protected readonly draftPeriodStart = signal(firstOfMonth());
  protected readonly draftPeriodEnd = signal(todayIso());
  protected readonly draftNotes = signal('');

  protected readonly toast = signal<{ message: string; error: boolean } | null>(null);

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
  }

  protected t(key: string): string {
    return this.i18n.t(`clinicAdmin.${key}`);
  }

  protected readonly totalGross = computed(() =>
    this.rows().reduce((sum, row) => sum + row.grossRevenue, 0),
  );
  protected readonly totalCommission = computed(() =>
    this.rows().reduce((sum, row) => sum + row.commissionAmount, 0),
  );
  protected readonly totalPaid = computed(() =>
    this.rows().reduce((sum, row) => sum + row.paidAmount, 0),
  );
  protected readonly totalBalance = computed(() =>
    this.rows().reduce((sum, row) => sum + row.balanceDue, 0),
  );

  protected readonly payableRows = computed(() =>
    this.rows().filter((row) => row.balanceDue > 0),
  );

  protected readonly payoutHint = computed(() => {
    const row = this.rows().find((item) => item.doctorId === this.draftDoctorId());
    if (!row) return '';
    return `${row.doctorName} · ${this.percent(row.commissionRate)} · ${this.money(row.balanceDue, row.currency)}`;
  });

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
      const rows = await this.api.doctorCommission(
        `${this.from()}T00:00:00`,
        `${this.to()}T23:59:59`,
        this.projectQuery(),
      );
      this.rows.set(rows);
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

  protected openPayout(row?: DoctorCommission): void {
    const target = row ?? this.payableRows()[0];
    this.draftDoctorId.set(target?.doctorId ?? '');
    this.draftPeriodStart.set(this.from());
    this.draftPeriodEnd.set(this.to());
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
      const payout: DoctorPayout = await this.api.createDoctorPayout(
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
      this.toast.set({
        message: `${this.t('payouts.saved')} ${this.money(payout.commissionAmount, payout.currency)}`,
        error: false,
      });
      await this.load();
    } catch {
      this.toast.set({ message: this.t('payouts.saveFailed'), error: true });
    } finally {
      this.saveBusy.set(false);
    }
  }

  protected exportCsv(): void {
    const rows = this.rows();
    if (rows.length === 0) return;
    const csv = [
      'Doctor,Specialty,Rate %,Appointments,Gross,Commission,Paid,Balance,Currency',
      ...rows.map((row) =>
        [
          `"${row.doctorName.replace(/"/g, '""')}"`,
          `"${(row.specialty ?? '').replace(/"/g, '""')}"`,
          row.commissionRate,
          row.appointmentCount,
          row.grossRevenue,
          row.commissionAmount,
          row.paidAmount,
          row.balanceDue,
          row.currency ?? 'JOD',
        ].join(','),
      ),
    ].join('\r\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `doctor-commission-${this.from()}-${this.to()}.csv`;
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
