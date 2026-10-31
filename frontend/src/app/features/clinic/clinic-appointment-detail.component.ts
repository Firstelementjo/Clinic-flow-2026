/**
 * `/app/clinic/appointments/:id` — one appointment.
 *
 * ## Which route this reads
 *
 * `GET /api/v1/clinic/appointments/{appointmentId}` — `ClinicApi.appointment`, behind
 * `appointment:read`. Note the route takes **no** `projectId`: the controller resolves the
 * row inside the caller's tenant, and a foreign id and a nonexistent one produce the same
 * status, code and message. The screen therefore sends no scope of its own rather than
 * inventing a parameter the route does not accept.
 *
 * ## The status is printed, not interpreted
 *
 * `Appointment.status` is a stored wire value. This screen renders it in `fe-mono` and
 * says, in words, that it does not translate it and does not infer a clinical meaning
 * from it.
 *
 * That restraint is the rule from `AGENTS.md`: appointment status semantics — what counts
 * as a no-show, when a cancellation is late, what an AI-proposed row has actually
 * committed — are clinic decisions, and a client that coloured a status chip red for
 * `NO_SHOW` would be asserting a policy nobody has approved. The clinic screen's own
 * funnel does the same, for the same reason.
 *
 * ## An AI-proposed row says so, and says it is not persisted
 *
 * `Appointment.isAiProposed` marks a row the assistant proposed. The chip is rendered, and
 * the copy beside it says plainly that the row is not yet committed — because an AI
 * confirmation that has not been persisted must not read as a booking that exists.
 */
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ClinicApi, type ClinicAppointment } from '../../core/api/endpoints';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ResourceStore } from '../../core/state/resource';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatusChipComponent } from '../../ui/status-chip.component';
import { ClinicRbacService } from '../../core/auth/clinic-rbac';
import { ClinicPatientApi, type ClinicResourceDetail } from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-appointment-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AsyncStateComponent, FormsModule, PageHeaderComponent, RouterLink, StatusChipComponent, TranslatePipe],
  template: `
    <div class="fe-page" data-testid="clinic-appointment">
      <fe-page-header [title]="'clinic.appointment.title'" [description]="'clinic.appointment.description'">
        <a class="fe-btn fe-btn--ghost" routerLink="/app/clinic/calendar">
          {{ 'clinic.appointment.back' | t }}
        </a>
      </fe-page-header>

      @if (appointmentId() === null) {
        <p class="fe-sub" data-testid="clinic-appointment-no-id">{{ 'clinic.appointment.noId' | t }}</p>
      } @else {
        <fe-async-state
          [loading]="appointment.loading() && appointment.value() === null"
          [error]="appointment.error()"
          [hasValue]="appointment.value() !== null"
          [loadingLabel]="'state.loading' | t"
          [loadingHint]="'state.loadingHint' | t"
          [errorTitle]="'clinic.error.denied.title' | t"
          [errorBody]="'clinic.error.denied.body' | t"
          [requestId]="errorRequestId()"
          [requestIdLabel]="'state.requestId' | t"
          [retryLabel]="'clinic.retry' | t"
          (retry)="reload()"
        />

        @if (actionMessage()) {
          <div class="fe-alert fe-alert--success" data-testid="appointment-action-success">
            {{ actionMessage() }}
          </div>
        }
        @if (actionError()) {
          <div class="fe-alert fe-alert--danger" data-testid="appointment-action-error">
            {{ actionError() }}
          </div>
        }

        @if (appointment.value(); as row) {
          <section class="fe-card" [attr.aria-labelledby]="'clinic-appointment-title'">
            <div class="card-head">
              <h3 [id]="'clinic-appointment-title'">{{ row.title ?? '—' }}</h3>
              <div class="status-box">
                <span class="fe-mono" dir="ltr" data-testid="clinic-appointment-status">
                  {{ row.status ?? '—' }}
                </span>
              </div>
            </div>

            @if (row.isAiProposed === true) {
              <fe-status-chip class="ai-flag" tone="ai">
                {{ 'clinic.schedule.aiProposed' | t }}
              </fe-status-chip>
              <p class="fe-sub" data-testid="clinic-appointment-ai">{{ 'clinic.appointment.aiNote' | t }}</p>
            }

            <dl class="facts">
              <div>
                <dt class="fe-eyebrow">{{ 'clinic.schedule.patient' | t }}</dt>
                <dd>{{ row.patientName ?? '—' }}</dd>
              </div>
              <div>
                <dt class="fe-eyebrow">{{ 'clinic.schedule.clinician' | t }}</dt>
                <dd>{{ row.doctorName ?? '—' }}</dd>
              </div>
              <div>
                <dt class="fe-eyebrow">{{ 'clinic.calendar.col.time' | t }}</dt>
                <dd class="fe-mono">{{ format.dateTime(row.appointmentDate) }}</dd>
              </div>
              <div>
                <dt class="fe-eyebrow">{{ 'clinic.schedule.duration' | t }}</dt>
                <dd class="fe-num">{{ row.durationMinutes ?? '—' }} دقيقة</dd>
              </div>
              <div>
                <dt class="fe-eyebrow">{{ 'clinic.procedures.title' | t }}</dt>
                <dd>{{ row.service ?? '—' }}</dd>
              </div>
              <div>
                <dt class="fe-eyebrow">{{ 'clinic.appointment.ticket' | t }}</dt>
                <dd class="fe-mono" dir="ltr">{{ row.ticketNumber ?? '—' }}</dd>
              </div>
            </dl>

            <div class="appointment-actions" data-testid="appointment-actions-bar">
              <div class="actions-group">
                <span class="fe-eyebrow">إجراءات الحالة:</span>
                @if (row.status !== 'CONFIRMED' && row.status !== 'COMPLETED' && row.status !== 'CANCELLED') {
                  <button type="button" class="fe-btn fe-btn--sm" data-testid="status-confirm-btn" (click)="updateStatus('CONFIRMED')">
                    تأكيد الموعد
                  </button>
                }
                @if (row.status === 'CONFIRMED') {
                  <button type="button" class="fe-btn fe-btn--sm" data-testid="status-checkin-btn" (click)="updateStatus('CHECKED_IN')">
                    تسجيل وصول المريض
                  </button>
                }
                @if (row.status === 'CHECKED_IN') {
                  @if (canStartEncounter()) {
                    <a
                      class="fe-btn fe-btn--primary fe-btn--sm"
                      [routerLink]="['/app/clinic/encounters', row.id]"
                      [queryParams]="{
                        appointmentId: row.id,
                        patientId: row.patientId,
                        patientName: row.patientName,
                        doctorId: row.doctorId,
                        doctorName: row.doctorName,
                        service: row.service,
                        status: row.status
                      }"
                    >
                      بدء الزيارة السريرية (Encounter)
                    </a>
                  }
                  <button type="button" class="fe-btn fe-btn--secondary fe-btn--sm" data-testid="status-complete-btn" (click)="updateStatus('COMPLETED')">
                    إكمال الموعد
                  </button>
                }
                @if (row.status === 'COMPLETED' && canViewEncounter()) {
                  <a
                    class="fe-btn fe-btn--ghost fe-btn--sm"
                    [routerLink]="['/app/clinic/encounters', row.id]"
                    [queryParams]="{
                      appointmentId: row.id,
                      patientId: row.patientId,
                      patientName: row.patientName,
                      doctorId: row.doctorId,
                      doctorName: row.doctorName,
                      service: row.service,
                      status: row.status
                    }"
                  >
                    عرض سجل الزيارة (Encounter Record)
                  </a>
                }
                @if (row.status !== 'CANCELLED' && row.status !== 'COMPLETED') {
                  <button type="button" class="fe-btn fe-btn--danger-ghost fe-btn--sm" data-testid="open-cancel-btn" (click)="openCancelModal()">
                    إلغاء الموعد
                  </button>
                  <button type="button" class="fe-btn fe-btn--ghost fe-btn--sm" data-testid="status-noshow-btn" (click)="updateStatus('NO_SHOW', 'Patient did not arrive')">
                    لم يحضر (No-Show)
                  </button>
                }
              </div>

              <div class="actions-group">
                <button type="button" class="fe-btn fe-btn--ghost fe-btn--sm" data-testid="open-assign-chair-btn" (click)="openAssignChairModal()">
                  تعيين كرسي العيادة
                </button>
              </div>
            </div>
          </section>
        }

        @if (isCancelModalOpen()) {
          <div class="fe-modal-overlay" (click)="isCancelModalOpen.set(false)">
            <div class="fe-modal-card" (click)="$event.stopPropagation()" data-testid="cancel-appointment-modal">
              <div class="modal-header">
                <h3>إلغاء الموعد</h3>
                <button type="button" class="close-btn" (click)="isCancelModalOpen.set(false)">&times;</button>
              </div>
              <div class="modal-body">
                <p>يرجى تحديد سبب رسمي لإلغاء هذا الموعد:</p>
                <div class="form-field">
                  <label class="fe-label">سبب الإلغاء *</label>
                  <textarea
                    class="fe-input"
                    rows="3"
                    data-testid="cancel-reason-input"
                    [(ngModel)]="cancelReason"
                    placeholder="مثال: اعتذار المريض هاتفياً، ظرف طارئ للطبيب..."
                    required
                  ></textarea>
                </div>
              </div>
              <div class="modal-footer">
                <button type="button" class="fe-btn fe-btn--ghost" (click)="isCancelModalOpen.set(false)">تراجع</button>
                <button
                  type="button"
                  class="fe-btn fe-btn--primary"
                  data-testid="confirm-cancel-btn"
                  [disabled]="!cancelReason.trim()"
                  (click)="confirmCancellation()"
                >
                  تأكيد الإلغاء
                </button>
              </div>
            </div>
          </div>
        }

        @if (isAssignChairModalOpen()) {
          <div class="fe-modal-overlay" (click)="isAssignChairModalOpen.set(false)">
            <div class="fe-modal-card" (click)="$event.stopPropagation()" data-testid="assign-chair-modal">
              <div class="modal-header">
                <h3>تعيين كرسي العيادة (Operatory Chair)</h3>
                <button type="button" class="close-btn" (click)="isAssignChairModalOpen.set(false)">&times;</button>
              </div>
              <div class="modal-body">
                <div class="form-field">
                  <label class="fe-label">كرسي العيادة المتاح</label>
                  <select class="fe-input" data-testid="chair-select" [(ngModel)]="selectedChairId">
                    <option value="">بدون كرسي محدد (تحرير الكرسي)</option>
                    @for (chair of chairsList(); track chair.id) {
                      <option [value]="chair.id">{{ chair.name }}</option>
                    }
                  </select>
                </div>
                <div class="form-field">
                  <label class="fe-label">فارق الوقت العازل بعد الموعد (بالدقائق)</label>
                  <input
                    type="number"
                    min="0"
                    step="5"
                    class="fe-input"
                    data-testid="chair-buffer-input"
                    [(ngModel)]="chairBufferMinutes"
                  />
                </div>
              </div>
              <div class="modal-footer">
                <button type="button" class="fe-btn fe-btn--ghost" (click)="isAssignChairModalOpen.set(false)">إلغاء</button>
                <button type="button" class="fe-btn fe-btn--primary" data-testid="confirm-chair-btn" (click)="confirmAssignChair()">
                  حفظ التعيين
                </button>
              </div>
            </div>
          </div>
        }
      }
    </div>
  `,
  styles: [
    `
      .card-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--fe-space-2);
      }
      .card-head h3 {
        margin: 0;
      }
      .status-box {
        font-size: var(--fe-text-base);
        font-weight: 600;
        padding: var(--fe-space-1) var(--fe-space-3);
        background: var(--surf-tint);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
      }
      .facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
        gap: var(--fe-space-4);
        margin-block: var(--fe-space-3);
      }
      .facts dt {
        margin-block-end: var(--fe-space-1);
      }
      .facts dd {
        margin: 0;
        color: var(--ink);
        overflow-wrap: anywhere;
      }
      .ai-flag {
        margin-inline-end: var(--fe-space-2);
      }
      .appointment-actions {
        display: flex;
        justify-content: space-between;
        align-items: center;
        flex-wrap: wrap;
        gap: var(--fe-space-3);
        margin-top: var(--fe-space-4);
        padding-top: var(--fe-space-3);
        border-top: 1px solid var(--line);
      }
      .actions-group {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: var(--fe-space-2);
      }
      .fe-alert--success {
        padding: var(--fe-space-2) var(--fe-space-3);
        background: var(--ok-soft);
        color: var(--ok);
        border-radius: var(--fe-radius);
        margin-bottom: var(--fe-space-3);
      }
      .fe-alert--danger {
        padding: var(--fe-space-2) var(--fe-space-3);
        background: var(--bad-soft);
        color: var(--bad);
        border-radius: var(--fe-radius);
        margin-bottom: var(--fe-space-3);
      }
      .fe-modal-overlay {
        position: fixed;
        inset: 0;
        background: var(--ink);
        opacity: 0.96;
        display: grid;
        place-items: center;
        z-index: 1000;
        padding: var(--fe-space-4);
      }
      .fe-modal-card {
        background: var(--surf);
        border-radius: var(--fe-radius);
        border: 1px solid var(--line);
        width: 100%;
        max-width: 32rem;
        box-shadow: var(--shadow);
        display: flex;
        flex-direction: column;
      }
      .modal-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--fe-space-3) var(--fe-space-4);
        border-bottom: 1px solid var(--line);
      }
      .modal-header h3 {
        margin: 0;
      }
      .close-btn {
        background: transparent;
        border: none;
        color: var(--mute);
        font-size: 1.4rem;
        cursor: pointer;
      }
      .modal-body {
        padding: var(--fe-space-4);
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-3);
      }
      .form-field {
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-1);
      }
      .fe-label {
        font-size: 0.85rem;
        font-weight: 500;
        color: var(--ink);
      }
      .fe-input {
        padding: var(--fe-space-2) var(--fe-space-3);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        background: var(--surf);
        color: var(--ink);
        font-family: inherit;
        font-size: 0.95rem;
      }
      .fe-input:focus {
        outline: 2px solid var(--pri);
        outline-offset: 1px;
        border-color: var(--pri);
      }
      .modal-footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--fe-space-2);
        padding: var(--fe-space-3) var(--fe-space-4);
        border-top: 1px solid var(--line);
      }
    `,
  ],
})
export class ClinicAppointmentDetailComponent {
  private readonly clinic = inject(ClinicApi);
  private readonly patientApi = inject(ClinicPatientApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly rbac = inject(ClinicRbacService);
  protected readonly canStartEncounter = computed(() =>
    this.rbac.hasAnyPermission(['clinical.write_record']) ||
    this.rbac.activeRoles().includes('doctor') ||
    this.rbac.activeRoles().includes('clinic_admin')
  );
  protected readonly canViewEncounter = computed(() =>
    this.rbac.hasAnyPermission(['clinical.view_record', 'clinical.write_record']) ||
    this.rbac.activeRoles().includes('doctor') ||
    this.rbac.activeRoles().includes('clinic_admin')
  );

  protected readonly format = inject(FormatService);
  protected readonly appointment = new ResourceStore<ClinicAppointment>();

  protected readonly actionMessage = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected readonly isCancelModalOpen = signal(false);
  protected cancelReason = '';

  protected readonly isAssignChairModalOpen = signal(false);
  protected selectedChairId = '';
  protected chairBufferMinutes = 10;
  protected readonly chairsList = signal<readonly ClinicResourceDetail[]>([]);

  protected readonly appointmentId = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('id'))),
    { initialValue: null },
  );

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });

    effect(() => {
      const id = this.appointmentId();
      untracked(() => {
        if (id === null) {
          this.appointment.reset();
          return;
        }
        void this.load(id);
      });
    });
  }

  protected async reload(): Promise<void> {
    const id = this.appointmentId();
    if (id !== null) await this.load(id);
  }

  protected errorRequestId(): string | null {
    return this.appointment.error()?.requestId ?? null;
  }

  protected async updateStatus(status: string, reason?: string): Promise<void> {
    const id = this.appointmentId();
    if (!id) return;

    this.actionMessage.set(null);
    this.actionError.set(null);

    try {
      await this.patientApi.updateAppointmentStatus(id, {
        status,
        cancelledReason: reason,
      });
      this.actionMessage.set(`تم تحديث حالة الموعد إلى: ${status}`);
      await this.reload();
    } catch (err: any) {
      this.actionError.set(err?.message || 'تعذر تحديث حالة الموعد.');
    }
  }

  protected openCancelModal(): void {
    this.cancelReason = '';
    this.isCancelModalOpen.set(true);
  }

  protected async confirmCancellation(): Promise<void> {
    if (!this.cancelReason.trim()) return;
    const reason = this.cancelReason.trim();
    this.isCancelModalOpen.set(false);
    await this.updateStatus('CANCELLED', reason);
  }

  protected async openAssignChairModal(): Promise<void> {
    this.selectedChairId = '';
    this.chairBufferMinutes = 10;
    this.isAssignChairModalOpen.set(true);

    try {
      const res = await this.patientApi.resources();
      this.chairsList.set(res ?? []);
    } catch {
      // Ignore resource load failure
    }
  }

  protected async confirmAssignChair(): Promise<void> {
    const id = this.appointmentId();
    if (!id) return;

    this.actionMessage.set(null);
    this.actionError.set(null);
    this.isAssignChairModalOpen.set(false);

    try {
      await this.patientApi.assignResource(id, {
        resourceId: this.selectedChairId || null,
        bufferMinutes: this.chairBufferMinutes,
      });
      this.actionMessage.set('تم تعيين كرسي العيادة وتحديث بيانات الموعد بنجاح.');
      await this.reload();
    } catch (err: any) {
      this.actionError.set(err?.message || 'تعذر تعيين كرسي العيادة. تحقق من عدم وجود حجز متداخل.');
    }
  }

  private async load(appointmentId: string): Promise<void> {
    // No `projectId`: this route does not accept one, and sending a parameter the
    // controller never declared would be the client inventing a scope.
    await this.appointment.load(() => this.clinic.appointment(appointmentId));
  }
}
