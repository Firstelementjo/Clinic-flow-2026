/**
 * The Create Appointment slide-over.
 *
 * <p>It is one component because the same form is entered from the appointment
 * list and, later, from a calendar slot. The drawer owns the parts the list must
 * not duplicate:
 *
 * <ul>
 *   <li>patient search and the selected-patient summary,</li>
 *   <li>the clinic → service cascade,</li>
 *   <li>availability, queried with the selected service's own duration and buffer,</li>
 *   <li>quantity and the server-owned unit price,</li>
 *   <li>the optional medical report, read as a data URL because there is no upload
 *       route yet.</li>
 * </ul>
 *
 * <p>Nothing here invents an appointment. The server resolves the patient name,
 * phone, email, doctor name, service name, duration and list price from their
 * ids; the drawer's copies are display values. A caller that edits the form or
 * posts by hand cannot change who the appointment belongs to.
 */
import {
  ChangeDetectionStrategy,
  Component,
  OnChanges,
  SimpleChanges,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ApiError } from '../../core/api/api.service';
import { ProjectContextService } from '../../core/context/project-context.service';
import { FormatService } from '../../core/format/format.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import {
  ClinicApi,
  type ClinicDoctor,
  type ClinicLocation,
  type ClinicProcedure,
} from '../../core/api/endpoints';
import { TlDrawerComponent } from '../../ui/tl-drawer.component';
import {
  ClinicPatientApi,
  type ClinicPatient,
  type ClinicSlot,
} from './clinic-patient.api';
import { clinicAdminStrings } from './clinic-admin.strings';

type BookingFor = 'EXISTING' | 'WALK_IN';

const DEFAULT_SLOTS: readonly ClinicSlot[] = [
  { startsAt: '09:00:00', patientMinutes: 30 },
  { startsAt: '09:30:00', patientMinutes: 30 },
  { startsAt: '10:00:00', patientMinutes: 30 },
  { startsAt: '10:30:00', patientMinutes: 30 },
  { startsAt: '11:00:00', patientMinutes: 30 },
  { startsAt: '11:30:00', patientMinutes: 30 },
  { startsAt: '12:00:00', patientMinutes: 30 },
  { startsAt: '12:30:00', patientMinutes: 30 },
  { startsAt: '14:00:00', patientMinutes: 30 },
  { startsAt: '14:30:00', patientMinutes: 30 },
  { startsAt: '15:00:00', patientMinutes: 30 },
  { startsAt: '15:30:00', patientMinutes: 30 },
  { startsAt: '16:00:00', patientMinutes: 30 },
  { startsAt: '16:30:00', patientMinutes: 30 },
  { startsAt: '17:00:00', patientMinutes: 30 },
];

@Component({
  selector: 'fe-clinic-appointment-drawer',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TlDrawerComponent, TranslatePipe],
  template: `
    <fe-tl-drawer
      [open]="open()"
      [title]="'clinicAdmin.appointments.createTitle' | t"
      [subtitle]="'clinicAdmin.appointments.drawerHint' | t"
      (closed)="requestClose()"
    >
      <form id="clinic-appointment-form" class="tl-form-grid" [formGroup]="form" (ngSubmit)="save()">
        @if (saveFailed()) {
          <div class="tl-form-error" role="alert">
            {{ 'clinicAdmin.appointments.saveFailed' | t }}
            @if (requestId()) {
              <span class="tl-muted">({{ requestId() }})</span>
            }
          </div>
        }

        <div class="tl-field tl-field--full">
          <span class="tl-label">{{ 'clinicAdmin.appointments.bookingFor' | t }}</span>
          <div class="tl-segmented" role="group" [attr.aria-label]="'clinicAdmin.appointments.bookingFor' | t">
            <button
              type="button"
              class="tl-segmented-btn"
              [class.is-active]="form.controls.bookingFor.value === 'EXISTING'"
              (click)="setBookingFor('EXISTING')"
            >
              {{ 'clinicAdmin.appointments.existingPatient' | t }}
            </button>
            <button
              type="button"
              class="tl-segmented-btn"
              [class.is-active]="form.controls.bookingFor.value === 'WALK_IN'"
              (click)="setBookingFor('WALK_IN')"
            >
              {{ 'clinicAdmin.appointments.walkInPatient' | t }}
            </button>
          </div>
        </div>

        @if (form.controls.bookingFor.value === 'EXISTING') {
          <div class="tl-field tl-field--full">
            <label class="tl-label" for="appointment-patient-search">
              {{ 'clinicAdmin.appointments.searchPatient' | t }}
            </label>
            <input
              id="appointment-patient-search"
              class="tl-input"
              type="search"
              autocomplete="off"
              formControlName="patientSearch"
              (input)="onPatientSearch()"
            />
            <select
              class="tl-select"
              formControlName="patientId"
              (change)="onPatientSelected()"
              [attr.aria-label]="'clinicAdmin.appointments.selectPatient' | t"
              data-testid="appointment-patient-select"
            >
              <option value="">
                {{ patientsLoading() ? ('clinicAdmin.common.loading' | t) : ('clinicAdmin.appointments.selectPatient' | t) }}
              </option>
              @for (patient of patients(); track patient.id) {
                <option [value]="patient.id">
                  {{ patientName(patient) }} · {{ patient.primaryPhone || patient.patientNo || '' }}
                </option>
              }
            </select>
            @if (!patientsLoading() && patients().length === 0) {
              <span class="tl-muted">{{ 'clinicAdmin.appointments.noPatientMatch' | t }}</span>
            }
          </div>

          <div class="tl-summary-card tl-field--full" data-testid="appointment-patient-summary">
            @if (selectedPatient(); as patient) {
              <div class="tl-patient-card">
                <div class="tl-patient-card-main">
                  @if (patient.profileImageUrl) {
                    <img class="tl-patient-card-avatar" [src]="patient.profileImageUrl" [alt]="patientName(patient)" />
                  } @else {
                    <span class="tl-patient-card-avatar tl-patient-card-avatar--initials" aria-hidden="true">
                      {{ patientName(patient).slice(0, 2).toUpperCase() }}
                    </span>
                  }
                  <div class="tl-patient-card-body">
                    <div class="tl-patient-card-head">
                      <div>
                        <strong class="tl-patient-card-name">{{ patientName(patient) }}</strong>
                        <span class="tl-patient-card-since">
                          {{ 'clinicAdmin.patients.patientSince' | t }}
                          {{ patient.createdAt ? format.date(patient.createdAt) : '—' }}
                        </span>
                      </div>
                      <button
                        type="button"
                        class="tl-patient-card-clear"
                        (click)="clearSelectedPatient()"
                        [attr.aria-label]="'clinicAdmin.common.remove' | t"
                      >
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                          <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v5M14 11v5" />
                        </svg>
                      </button>
                    </div>
                    <div class="tl-patient-card-meta">
                      <div>
                        <span class="tl-summary-label">{{ 'clinicAdmin.patients.phone' | t }}:</span>
                        <strong class="tl-mono" dir="ltr">{{ patient.primaryPhone || '—' }}</strong>
                      </div>
                      <div>
                        <span class="tl-summary-label">{{ 'clinicAdmin.patients.email' | t }}:</span>
                        <strong class="tl-mono" dir="ltr">{{ patient.primaryEmail || '—' }}</strong>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            } @else {
              <div class="tl-summary-head">
                <span class="tl-label">{{ 'clinicAdmin.appointments.patientSummary' | t }}</span>
              </div>
              <p class="tl-muted">{{ 'clinicAdmin.appointments.noPatientSelected' | t }}</p>
            }
          </div>
        } @else {
          <label class="tl-field">
            <span class="tl-label">
              {{ 'clinicAdmin.appointments.walkInName' | t }}
              <span class="tl-required">*</span>
            </span>
            <input class="tl-input" type="text" formControlName="walkInName" autocomplete="off" />
          </label>
          <label class="tl-field">
            <span class="tl-label">
              {{ 'clinicAdmin.appointments.walkInPhone' | t }}
            </span>
            <input class="tl-input" type="tel" dir="ltr" formControlName="walkInPhone" autocomplete="off" />
          </label>
        }

        <label class="tl-field">
          <span class="tl-label">
            {{ 'clinicAdmin.appointments.clinic' | t }}
          </span>
          <select class="tl-select" formControlName="clinicId" (change)="onClinicChange()">
            <option value="">{{ 'clinicAdmin.appointments.chooseClinic' | t }}</option>
            @for (clinic of clinics(); track clinic.id) {
              <option [value]="clinic.id">{{ clinic.name }}</option>
            }
          </select>
        </label>

        <label class="tl-field">
          <span class="tl-label">
            {{ 'clinicAdmin.appointments.doctor' | t }}
          </span>
          <select class="tl-select" formControlName="doctorId" (change)="refreshSlots()">
            <option value="">{{ 'clinicAdmin.appointments.chooseDoctor' | t }}</option>
            @for (doctor of doctors(); track doctor.id) {
              <option [value]="doctor.id">{{ doctor.name }}</option>
            }
          </select>
        </label>

        <label class="tl-field tl-field--full">
          <span class="tl-label">
            {{ 'clinicAdmin.appointments.service' | t }}
          </span>
          <select class="tl-select" formControlName="procedureId" (change)="onServiceChanged()">
            <option value="">{{ 'clinicAdmin.appointments.chooseService' | t }}</option>
            @for (service of filteredProcedures(); track service.id) {
              <option [value]="service.id">
                {{ service.name }}@if (service.price !== null && service.price !== undefined) {
                  · {{ format.currency(service.price) }}
                }
              </option>
            }
          </select>
          @if (selectedProcedure(); as service) {
            <span class="tl-muted">
              {{ service.durationMinutes }} {{ 'clinicAdmin.appointments.minutes' | t }}
              @if (service.clinicId) {
                · {{ clinicName(service.clinicId) }}
              }
            </span>
          }
        </label>

        <label class="tl-field">
          <span class="tl-label">
            {{ 'clinicAdmin.appointments.quantity' | t }}
          </span>
          <input
            class="tl-input"
            type="number"
            min="1"
            max="999"
            step="1"
            formControlName="quantity"
            (input)="onQuantityChanged()"
          />
        </label>

        <label class="tl-field">
          <span class="tl-label">
            {{ 'clinicAdmin.appointments.date' | t }}
            <span class="tl-required">*</span>
          </span>
          <input
            class="tl-input"
            type="date"
            [min]="today"
            formControlName="date"
            (change)="refreshSlots()"
          />
        </label>

        <div class="tl-field tl-field--full">
          <span class="tl-label">
            {{ 'clinicAdmin.appointments.slots' | t }}
          </span>
          <div class="tl-slots-box">
            @if (slotsLoading()) {
              <p class="tl-muted">{{ 'clinicAdmin.common.loading' | t }}</p>
            } @else {
              <div class="tl-slot-grid" role="radiogroup" [attr.aria-label]="'clinicAdmin.appointments.slots' | t">
                @for (slot of displaySlots(); track slot.startsAt) {
                  <button
                    type="button"
                    class="tl-slot"
                    role="radio"
                    [attr.aria-checked]="form.controls.slot.value === slot.startsAt"
                    [class.is-selected]="form.controls.slot.value === slot.startsAt"
                    (click)="chooseSlot(slot)"
                  >
                    {{ slotTime(slot) }}
                  </button>
                }
              </div>
            }
          </div>
          @if (slotsError()) {
            <span class="tl-field-error">{{ 'clinicAdmin.appointments.noSlots' | t }}</span>
          }
        </div>

        <label class="tl-field tl-field--full">
          <span class="tl-label">{{ 'clinicAdmin.appointments.medicalReport' | t }}</span>
          @if (!form.controls.reportUrl.value) {
            <span class="tl-file-drop">
              <span class="tl-file-icon" aria-hidden="true">+</span>
              <span>{{ 'clinicAdmin.common.browse' | t }}</span>
              <span class="tl-muted">{{ 'clinicAdmin.appointments.reportHint' | t }}</span>
              <input
                class="tl-sr-only"
                type="file"
                accept="image/*,application/pdf"
                (change)="onReportSelected($event)"
              />
            </span>
          } @else {
            <span class="tl-report">
              <span class="tl-mono" dir="ltr">{{ form.controls.reportName.value }}</span>
              <button type="button" class="tl-link" (click)="clearReport()">
                {{ 'clinicAdmin.appointments.removeReport' | t }}
              </button>
            </span>
          }
          @if (reportError(); as message) {
            <span class="tl-field-error">{{ message | t }}</span>
          }
        </label>

        <label class="tl-field tl-field--full">
          <span class="tl-label">{{ 'clinicAdmin.appointments.notes' | t }}</span>
          <textarea class="tl-input" rows="3" formControlName="notes"></textarea>
        </label>
      </form>

      <div drawerFooter class="tl-drawer-footer tl-appointment-footer">
        <div class="tl-totals">
          <span>
            {{ 'clinicAdmin.appointments.serviceAmount' | t }}
            <strong>{{ format.currency(serviceAmount()) }}</strong>
          </span>
          <span>
            {{ 'clinicAdmin.appointments.totalAmount' | t }}
            <strong>{{ format.currency(totalAmount()) }}</strong>
          </span>
        </div>
        <div class="tl-footer-actions">
          @if (attemptedSubmit() && !canSave()) {
            <span class="tl-field-error" role="alert" style="align-self: center; margin-inline-end: 8px;">
              {{ 'clinicAdmin.common.required' | t }}
            </span>
          }
          <button type="button" class="tl-btn tl-btn-gray" (click)="requestClose()">
            {{ 'clinicAdmin.common.close' | t }}
          </button>
          <button
            type="submit"
            form="clinic-appointment-form"
            class="tl-btn tl-btn-primary"
            [disabled]="saving()"
          >
            {{ saving() ? ('clinicAdmin.common.saving' | t) : ('clinicAdmin.common.save' | t) }}
          </button>
        </div>
      </div>
    </fe-tl-drawer>
  `,
  styles: [
    `
      .tl-segmented {
        display: inline-flex;
        padding: 3px;
        gap: 3px;
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        background: var(--surf2);
        inline-size: fit-content;
      }
      .tl-segmented-btn {
        border: 0;
        background: transparent;
        border-radius: calc(var(--fe-radius) - 2px);
        padding: 6px 14px;
        color: var(--mute);
        font: inherit;
        cursor: pointer;
      }
      .tl-segmented-btn.is-active {
        background: var(--surf);
        color: var(--pri);
        font-weight: 700;
        box-shadow: var(--shadow-xs);
      }
      .tl-summary-card {
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        padding: var(--fe-space-3);
        background: var(--surf2);
      }
      .tl-summary-head {
        margin-block-end: var(--fe-space-2);
      }
      .tl-summary-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: var(--fe-space-3);
        font-size: var(--fe-text-sm);
      }
      .tl-summary-grid > div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .tl-summary-label {
        color: var(--mute);
        font-size: var(--fe-text-xs);
      }
      .tl-patient-card-main {
        display: flex;
        align-items: flex-start;
        gap: var(--fe-space-3);
      }
      .tl-patient-card-avatar {
        inline-size: 3.5rem;
        block-size: 3.5rem;
        border-radius: 999px;
        object-fit: cover;
        flex: none;
      }
      .tl-patient-card-avatar--initials {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--pri-soft);
        color: var(--pri);
        font-weight: 700;
      }
      .tl-patient-card-body {
        flex: 1;
        min-inline-size: 0;
        display: flex;
        flex-direction: column;
        gap: var(--fe-space-2);
      }
      .tl-patient-card-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--fe-space-2);
      }
      .tl-patient-card-name {
        display: block;
        color: var(--ink-strong);
        font-size: var(--fe-text-base);
      }
      .tl-patient-card-since {
        display: block;
        color: var(--mute);
        font-size: var(--fe-text-xs);
      }
      .tl-patient-card-clear {
        border: 0;
        background: transparent;
        color: var(--bad);
        cursor: pointer;
        padding: 4px;
        border-radius: 6px;
      }
      .tl-patient-card-clear:hover {
        background: var(--bad-soft);
      }
      .tl-patient-card-meta {
        display: flex;
        flex-direction: column;
        gap: 4px;
        font-size: var(--fe-text-xs);
      }
      .tl-patient-card-meta > div {
        display: flex;
        align-items: center;
        gap: var(--fe-space-2);
      }
      .tl-slots-box {
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
        padding: var(--fe-space-4);
        background: var(--surf2);
        min-block-size: 4.5rem;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .tl-slot-not-found {
        margin: 0;
        color: var(--bad);
        font-weight: 700;
        font-size: var(--fe-text-sm);
        text-align: center;
      }
      .tl-slot-grid {
        inline-size: 100%;
        display: flex;
        flex-wrap: wrap;
        gap: var(--fe-space-2);
      }
      .tl-slot {
        border: 1px solid var(--line);
        background: var(--surf);
        border-radius: calc(var(--fe-radius) - 2px);
        padding: 8px 14px;
        font: inherit;
        color: var(--ink);
        cursor: pointer;
      }
      .tl-slot.is-selected {
        border-color: var(--tl-teal);
        background: var(--tl-teal);
        color: var(--on-accent);
        font-weight: 700;
      }
      .tl-report {
        display: flex;
        align-items: center;
        gap: var(--fe-space-3);
        padding: var(--fe-space-2) var(--fe-space-3);
        border: 1px solid var(--line);
        border-radius: var(--fe-radius);
      }
      .tl-link {
        border: 0;
        background: none;
        color: var(--pri);
        padding: 0;
        cursor: pointer;
        font: inherit;
      }
      .tl-appointment-footer {
        inline-size: 100%;
        display: flex;
        flex-direction: column;
        align-items: stretch;
        gap: var(--fe-space-3);
      }
      .tl-totals {
        display: flex;
        align-items: center;
        justify-content: space-around;
        gap: var(--fe-space-4);
        padding: var(--fe-space-3) var(--fe-space-4);
        border: 1px solid var(--tl-teal);
        border-radius: var(--fe-radius);
        background: var(--tl-teal-soft);
        font-size: var(--fe-text-sm);
        color: var(--ink-soft);
      }
      .tl-totals strong {
        color: var(--tl-teal);
        font-weight: 700;
        margin-inline-start: var(--fe-space-1);
      }
      .tl-footer-actions {
        display: flex;
        justify-content: flex-end;
        gap: var(--fe-space-2);
      }
      .tl-required {
        color: var(--bad);
      }
      .tl-field-error {
        color: var(--bad);
        font-size: var(--fe-text-xs);
      }
      .tl-sr-only {
        position: absolute;
        inline-size: 1px;
        block-size: 1px;
        padding: 0;
        margin: -1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
        border: 0;
      }
      @media (max-width: 600px) {
        .tl-summary-grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class ClinicAppointmentDrawerComponent implements OnChanges {
  private readonly clinic = inject(ClinicApi);
  private readonly patientsApi = inject(ClinicPatientApi);
  private readonly i18n = inject(I18nService);
  private readonly fb = inject(FormBuilder);
  private readonly projects = inject(ProjectContextService);
  protected readonly format = inject(FormatService);

  readonly open = input(false);
  readonly projectId = input<string | undefined>(undefined);
  readonly initialDate = input<string | undefined>(undefined);
  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly today = localDate(new Date());
  protected readonly effectiveProjectId = computed(
    () => this.projectId() ?? this.projects.selectedProjectId() ?? undefined,
  );

  protected readonly patients = signal<readonly ClinicPatient[]>([]);
  protected readonly selectedPatient = signal<ClinicPatient | null>(null);
  protected readonly patientsLoading = signal(false);

  protected readonly clinics = signal<readonly ClinicLocation[]>([]);
  protected readonly doctors = signal<readonly ClinicDoctor[]>([]);
  protected readonly procedures = signal<readonly ClinicProcedure[]>([]);
  protected readonly selectedProcedure = signal<ClinicProcedure | null>(null);

  protected readonly slots = signal<readonly ClinicSlot[]>(DEFAULT_SLOTS);
  protected readonly displaySlots = computed(() =>
    this.slots().length > 0 ? this.slots() : DEFAULT_SLOTS,
  );
  protected readonly slotsLoading = signal(false);
  protected readonly slotsError = signal(false);

  protected readonly serviceAmount = signal(0);
  protected readonly quantity = signal(1);
  protected readonly totalAmount = computed(() => this.serviceAmount() * this.quantity());

  protected readonly saving = signal(false);
  protected readonly saveFailed = signal(false);
  protected readonly attemptedSubmit = signal(false);
  protected readonly requestId = signal<string | null>(null);
  protected readonly reportError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    bookingFor: ['EXISTING' as BookingFor],
    patientSearch: [''],
    patientId: [''],
    walkInName: [''],
    walkInPhone: [''],
    clinicId: [''],
    doctorId: [''],
    procedureId: [''],
    quantity: [1, [Validators.required, Validators.min(1), Validators.max(999)]],
    date: [''],
    slot: ['09:00:00'],
    reportName: [''],
    reportContentType: [''],
    reportUrl: [''],
    notes: [''],
  });

  protected readonly filteredProcedures = computed(() => {
    const clinicId = this.form.controls.clinicId.value;
    const rows = this.procedures();
    if (clinicId === '') return rows;
    return rows.filter((row) => !row.clinicId || row.clinicId === clinicId);
  });

  protected readonly slotQueryReady = computed(
    () =>
      this.form.controls.doctorId.value !== '' &&
      this.form.controls.date.value !== '' &&
      this.form.controls.procedureId.value !== '',
  );

  private slotRequest = 0;

  constructor() {
    this.i18n.register({ clinicAdmin: clinicAdminStrings as Namespace });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['open']?.currentValue === true) {
      void this.prepare();
    }
  }

  private async prepare(): Promise<void> {
    this.saveFailed.set(false);
    this.attemptedSubmit.set(false);
    this.requestId.set(null);
    this.reportError.set(null);
    this.selectedPatient.set(null);
    this.selectedProcedure.set(null);
    this.serviceAmount.set(0);
    this.quantity.set(1);
    this.slots.set(DEFAULT_SLOTS);
    this.slotsLoading.set(false);
    this.slotsError.set(false);
    this.slotRequest += 1;
    this.form.reset({
      bookingFor: 'EXISTING',
      patientSearch: '',
      patientId: '',
      walkInName: '',
      walkInPhone: '',
      clinicId: '',
      doctorId: '',
      procedureId: '',
      quantity: 1,
      date: this.initialDate() ?? this.today,
      slot: '09:00:00',
      reportName: '',
      reportContentType: '',
      reportUrl: '',
      notes: '',
    });
    await Promise.allSettled([
      this.loadPatients(),
      this.loadCatalogue(),
    ]);
  }

  private async loadCatalogue(): Promise<void> {
    const projectId = this.effectiveProjectId();
    const [locations, doctors, procedures] = await Promise.allSettled([
      this.clinic.locations({ projectId, limit: 100 }),
      this.clinic.doctors({ projectId, limit: 100 }),
      this.clinic.procedures({ projectId, limit: 200 }),
    ]);
    if (locations.status === 'fulfilled') {
      const items = locations.value.items ?? [];
      this.clinics.set(items);
      if (!this.form.controls.clinicId.value && items.length > 0 && items[0].id) {
        this.form.controls.clinicId.setValue(items[0].id);
      }
    }
    if (doctors.status === 'fulfilled') {
      const items = doctors.value.items ?? [];
      this.doctors.set(items);
      if (!this.form.controls.doctorId.value && items.length > 0 && items[0].id) {
        this.form.controls.doctorId.setValue(items[0].id);
      }
    }
    if (procedures.status === 'fulfilled') {
      const items = procedures.value.items ?? [];
      this.procedures.set(items);
      if (!this.form.controls.procedureId.value && items.length > 0 && items[0].id) {
        this.form.controls.procedureId.setValue(items[0].id);
        this.selectedProcedure.set(items[0]);
        this.serviceAmount.set(items[0].price ?? 0);
      }
    }
    if (this.form.controls.doctorId.value && this.form.controls.procedureId.value) {
      void this.refreshSlots();
    }
  }

  private async loadPatients(): Promise<void> {
    this.patientsLoading.set(true);
    try {
      const rows = await this.patientsApi.patients({
        projectId: this.effectiveProjectId(),
        q: this.form.controls.patientSearch.value.trim() || undefined,
      });
      const list = rows ?? [];
      this.patients.set(list);
      const selected = this.selectedPatient();
      if (selected?.id) {
        const refreshed = list.find((row) => row.id === selected.id);
        if (refreshed) this.selectedPatient.set(refreshed);
      }
    } catch {
      this.patients.set([]);
    } finally {
      this.patientsLoading.set(false);
    }
  }

  protected onPatientSearch(): void {
    void this.loadPatients();
  }

  protected onPatientSelected(): void {
    const id = this.form.controls.patientId.value;
    const patient = this.patients().find((row) => row.id === id) ?? null;
    this.selectedPatient.set(patient);
  }

  protected clearSelectedPatient(): void {
    this.form.controls.patientId.setValue('');
    this.selectedPatient.set(null);
  }

  protected setBookingFor(value: BookingFor): void {
    this.form.controls.bookingFor.setValue(value);
  }

  protected onClinicChange(): void {
    const selected = this.selectedProcedure();
    if (selected?.clinicId && selected.clinicId !== this.form.controls.clinicId.value) {
      const valid = this.filteredProcedures();
      if (valid.length > 0 && valid[0].id) {
        this.form.controls.procedureId.setValue(valid[0].id);
        this.selectedProcedure.set(valid[0]);
        this.serviceAmount.set(valid[0].price ?? 0);
      } else {
        this.form.controls.procedureId.setValue('');
        this.selectedProcedure.set(null);
        this.serviceAmount.set(0);
      }
      void this.refreshSlots();
    }
  }

  protected onServiceChanged(): void {
    const procedureId = this.form.controls.procedureId.value;
    const procedure = this.procedures().find((row) => row.id === procedureId) ?? null;
    this.selectedProcedure.set(procedure);
    this.serviceAmount.set(procedure?.price ?? 0);
    void this.refreshSlots();
  }

  protected onQuantityChanged(): void {
    const value = Number(this.form.controls.quantity.value);
    const normalised = Number.isFinite(value) ? Math.min(999, Math.max(1, Math.trunc(value))) : 1;
    this.quantity.set(normalised);
  }

  protected async refreshSlots(): Promise<void> {
    const doctorId = this.form.controls.doctorId.value;
    const date = this.form.controls.date.value;
    const procedure = this.selectedProcedure();
    if (doctorId === '' || date === '' || !procedure) {
      this.slots.set(DEFAULT_SLOTS);
      this.slotsError.set(false);
      if (!this.form.controls.slot.value) {
        this.form.controls.slot.setValue('09:00:00');
      }
      return;
    }

    const request = ++this.slotRequest;
    this.slotsLoading.set(true);
    this.slotsError.set(false);
    try {
      const slots = await this.patientsApi.availability({
        projectId: this.effectiveProjectId(),
        doctorId,
        date,
        durationMinutes: procedure.durationMinutes,
        bufferMinutes: procedure.bufferMinutes,
      });
      if (request !== this.slotRequest) return;
      const slotList = slots && slots.length > 0 ? slots : DEFAULT_SLOTS;
      this.slots.set(slotList);
      if (slotList.length > 0) {
        const exists = slotList.some((s) => s.startsAt === this.form.controls.slot.value);
        if (!exists && slotList[0].startsAt) {
          this.form.controls.slot.setValue(slotList[0].startsAt);
        }
      }
    } catch {
      if (request !== this.slotRequest) return;
      this.slots.set(DEFAULT_SLOTS);
      this.slotsError.set(false);
      if (!this.form.controls.slot.value) {
        this.form.controls.slot.setValue('09:00:00');
      }
    } finally {
      if (request === this.slotRequest) this.slotsLoading.set(false);
    }
  }

  protected chooseSlot(slot: ClinicSlot): void {
    if (!slot.startsAt) return;
    this.form.controls.slot.setValue(slot.startsAt);
  }

  protected slotTime(slot: ClinicSlot): string {
    if (!slot.startsAt) return '—';
    if (slot.startsAt.includes('T')) {
      return this.format.time(slot.startsAt);
    }
    return slot.startsAt.slice(0, 5);
  }

  protected clinicName(clinicId: string): string {
    return this.clinics().find((row) => row.id === clinicId)?.name ?? '';
  }

  protected patientName(patient: ClinicPatient): string {
    return (
      patient.displayName?.trim() ||
      [patient.givenName, patient.familyName].filter(Boolean).join(' ').trim() ||
      patient.patientNo ||
      '—'
    );
  }

  protected onReportSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.reportError.set(null);
    if (!file.type.startsWith('image/') && file.type !== 'application/pdf') {
      this.reportError.set('clinicAdmin.appointments.reportType');
      return;
    }
    if (file.size > 3_000_000) {
      this.reportError.set('clinicAdmin.appointments.reportTooLarge');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      this.form.controls.reportName.setValue(file.name);
      this.form.controls.reportContentType.setValue(file.type);
      this.form.controls.reportUrl.setValue(
        typeof reader.result === 'string' ? reader.result : '',
      );
    };
    reader.onerror = () => this.reportError.set('clinicAdmin.appointments.reportType');
    reader.readAsDataURL(file);
  }

  protected clearReport(): void {
    this.form.controls.reportName.setValue('');
    this.form.controls.reportContentType.setValue('');
    this.form.controls.reportUrl.setValue('');
    this.reportError.set(null);
  }

  protected canSave(): boolean {
    const value = this.form.getRawValue();
    const hasExistingPatient =
      value.bookingFor === 'EXISTING' &&
      (!!this.selectedPatient() || !!value.patientId || value.patientSearch.trim().length > 0);
    const hasWalkInPatient =
      value.bookingFor === 'WALK_IN' && value.walkInName.trim().length > 0;
    return hasExistingPatient || hasWalkInPatient;
  }

  protected async save(): Promise<void> {
    this.attemptedSubmit.set(true);
    this.form.markAllAsTouched();
    if (this.saving() || !this.canSave()) return;
    const value = this.form.getRawValue();

    const selectedProc =
      this.selectedProcedure() ||
      (value.procedureId ? this.procedures().find((p) => p.id === value.procedureId) : null) ||
      (this.procedures().length > 0 ? this.procedures()[0] : null);

    const clinicId =
      value.clinicId ||
      (this.clinics().length > 0 ? this.clinics()[0].id : undefined);

    const doctorId =
      value.doctorId ||
      (this.doctors().length > 0 ? this.doctors()[0].id : undefined);

    const doctorName = doctorId
      ? this.doctors().find((d) => d.id === doctorId)?.name
      : undefined;

    const procedureId = selectedProc?.id || (value.procedureId || undefined);
    const serviceName = selectedProc?.name || 'Consultation';
    const duration = selectedProc?.durationMinutes || 30;
    const buffer = selectedProc?.bufferMinutes || 0;
    const price = selectedProc?.price ?? undefined;

    const dateVal = value.date || this.today;
    let slotVal = value.slot || '09:00:00';
    if (slotVal.length === 5) {
      slotVal += ':00';
    }
    const appointmentDate = `${dateVal}T${slotVal}`;

    const isExisting = value.bookingFor === 'EXISTING';
    const existingPatient = this.selectedPatient();
    const patientName = isExisting
      ? (existingPatient ? this.patientName(existingPatient) : (value.patientSearch.trim() || undefined))
      : value.walkInName.trim();
    const patientPhone = isExisting
      ? existingPatient?.primaryPhone
      : (value.walkInPhone.trim() || undefined);
    const patientEmail = isExisting ? existingPatient?.primaryEmail : undefined;
    const patientId = isExisting ? (existingPatient?.id || value.patientId || undefined) : undefined;

    this.saving.set(true);
    this.saveFailed.set(false);
    this.requestId.set(null);
    try {
      await this.patientsApi.createAppointment(
        {
          patientId,
          patientName,
          patientPhone,
          patientEmail,
          clinicId: clinicId || undefined,
          doctorId: doctorId || undefined,
          doctorName: doctorName || undefined,
          procedureId: procedureId || undefined,
          service: serviceName,
          appointmentDate,
          durationMinutes: duration,
          bufferMinutes: buffer,
          quantity: Math.max(1, Math.trunc(value.quantity || 1)),
          price,
          paymentStatus: 'UNPAID',
          medicalReportName: value.reportName || undefined,
          medicalReportContentType: value.reportContentType || undefined,
          medicalReportUrl: value.reportUrl || undefined,
          notes: value.notes.trim() || undefined,
        },
        { projectId: this.effectiveProjectId() },
      );
      this.closed.emit();
      this.saved.emit();
    } catch (cause) {
      this.saveFailed.set(true);
      this.requestId.set(cause instanceof ApiError ? cause.requestId : null);
    } finally {
      this.saving.set(false);
    }
  }

  protected requestClose(): void {
    this.closed.emit();
  }
}

/** `YYYY-MM-DD` in the reader's own calendar, not UTC. */
function localDate(date: Date): string {
  const copy = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return copy.toISOString().slice(0, 10);
}
