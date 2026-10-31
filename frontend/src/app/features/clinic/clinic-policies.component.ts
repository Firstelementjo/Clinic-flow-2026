/**
 * `/app/clinic/settings/policies` — Dental Clinic Policies & Settings (USR-09, BIL-07).
 */

import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectContextService } from '../../core/context/project-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import type { Namespace } from '../../core/i18n/i18n.types';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { AsyncStateComponent } from '../../ui/async-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import {
  ClinicPatientApi,
  type ClinicPolicyRow,
} from './clinic-patient.api';
import { clinicStrings } from './clinic.strings';

@Component({
  selector: 'fe-clinic-policies-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AsyncStateComponent,
    CommonModule,
    FormsModule,
    PageHeaderComponent,
    TranslatePipe,
  ],
  template: `
    <div class="fe-page" data-testid="clinic-policies">
      <fe-page-header
        [title]="'clinic.policies.title'"
        [description]="'clinic.policies.description'"
      />

      <fe-async-state
        [loading]="loading()"
        [error]="error()"
        [hasValue]="!loading() && !error()"
        [loadingLabel]="'state.loading' | t"
        [loadingHint]="'state.loadingHint' | t"
        [errorTitle]="'clinic.error.title' | t"
        [errorBody]="'clinic.error.body' | t"
        [retryLabel]="'clinic.retry' | t"
        (retry)="loadPolicies()"
      />

      @if (!loading() && !error()) {
        @if (saveSuccess()) {
          <div class="alert alert-success mb-4">
            {{ 'clinic.policies.saveSuccess' | t }}
          </div>
        }

        <div class="policies-card">
          <!-- Policy 1: Doctor Patient Visibility (USR-09) -->
          <div class="policy-item">
            <div class="policy-info">
              <h4 class="policy-title">{{ 'clinic.policies.doctorVisibility.title' | t }}</h4>
              <p class="policy-desc">{{ 'clinic.policies.doctorVisibility.desc' | t }}</p>
            </div>
            <div class="policy-control">
              <label class="switch-label">
                <input
                  type="checkbox"
                  [(ngModel)]="form.doctorSeesAllPatients"
                  class="toggle-checkbox"
                />
                <span class="toggle-text">{{ 'clinic.policies.doctorVisibility.toggle' | t }}</span>
              </label>
            </div>
          </div>

          <hr class="policy-divider" />

          <!-- Policy 2: Primary Currency (BIL-07) -->
          <div class="policy-item">
            <div class="policy-info">
              <h4 class="policy-title">{{ 'clinic.policies.currency.title' | t }}</h4>
              <p class="policy-desc">{{ 'clinic.policies.currency.desc' | t }}</p>
            </div>
            <div class="policy-control">
              <select [(ngModel)]="form.primaryCurrency" class="fe-input">
                <option value="JOD">JOD - الدينار الأردني (الأساس)</option>
                <option value="USD">USD - الدولار الأمريكي</option>
              </select>
            </div>
          </div>

          <hr class="policy-divider" />

          <!-- Policy 3: Default Slot Duration -->
          <div class="policy-item">
            <div class="policy-info">
              <h4 class="policy-title">{{ 'clinic.policies.slotDuration.title' | t }}</h4>
              <p class="policy-desc">{{ 'clinic.policies.slotDuration.desc' | t }}</p>
            </div>
            <div class="policy-control">
              <select [(ngModel)]="form.defaultSlotDurationMinutes" class="fe-input">
                <option [ngValue]="15">15 {{ 'clinic.policies.slotDuration.minutes' | t }}</option>
                <option [ngValue]="20">20 {{ 'clinic.policies.slotDuration.minutes' | t }}</option>
                <option [ngValue]="30">30 {{ 'clinic.policies.slotDuration.minutes' | t }} (الافتراضي)</option>
                <option [ngValue]="45">45 {{ 'clinic.policies.slotDuration.minutes' | t }}</option>
                <option [ngValue]="60">60 {{ 'clinic.policies.slotDuration.minutes' | t }}</option>
              </select>
            </div>
          </div>

          <hr class="policy-divider" />

          <!-- Policy 4: Conflict & Double Booking Prevention (APT-07) -->
          <div class="policy-item">
            <div class="policy-info">
              <h4 class="policy-title">{{ 'clinic.policies.doubleBooking.title' | t }}</h4>
              <p class="policy-desc">{{ 'clinic.policies.doubleBooking.desc' | t }}</p>
            </div>
            <div class="policy-control">
              <span class="badge-enforced">
                {{ 'clinic.policies.doubleBooking.enabled' | t }}
              </span>
            </div>
          </div>

          <hr class="policy-divider" />

          <!-- Policy 5: Tooth Notation System (CLN-03) -->
          <div class="policy-item">
            <div class="policy-info">
              <h4 class="policy-title">{{ 'clinic.policies.toothNotation.title' | t }}</h4>
              <p class="policy-desc">{{ 'clinic.policies.toothNotation.desc' | t }}</p>
            </div>
            <div class="policy-control">
              <span class="badge-enforced">
                FDI Two-Digit Dental System (ISO 3950)
              </span>
            </div>
          </div>

          <!-- Save Action -->
          <div class="save-bar">
            <button
              class="fe-btn fe-btn--primary"
              [disabled]="saving()"
              (click)="savePolicies()"
            >
              {{ 'clinic.policies.saveBtn' | t }}
            </button>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .fe-page { padding: 12px 18px; max-width: 900px; margin: 0 auto; }
    .policies-card { background: var(--surf); border: 1px solid var(--line); border-radius: 0.75rem; padding: 14px 18px; }
    .policy-item { display: flex; justify-content: space-between; align-items: flex-start; gap: 1.5rem; padding: 0.75rem 0; }
    .policy-info { flex: 1; }
    .policy-title { font-size: 1rem; font-weight: 600; margin: 0 0 0.25rem 0; color: var(--ink); }
    .policy-desc { font-size: 0.85rem; color: var(--mute); margin: 0; line-height: 1.5; }
    .policy-control { min-width: 200px; display: flex; justify-content: flex-end; }
    .policy-divider { margin: 1rem 0; border: none; border-top: 1px solid var(--line); }
    .switch-label { display: flex; align-items: center; gap: 0.5rem; cursor: pointer; font-size: 0.875rem; font-weight: 500; }
    .toggle-checkbox { width: 1.25rem; height: 1.25rem; accent-color: var(--pri); cursor: pointer; }
    .fe-input { padding: 0.5rem 0.75rem; border: 1px solid var(--line); border-radius: 0.375rem; font-size: 0.875rem; width: 100%; }
    .badge-enforced { background: var(--ok-soft); color: var(--ok); padding: 0.35rem 0.75rem; border-radius: 0.375rem; font-size: 0.8rem; font-weight: 600; border: 1px solid var(--ok); }
    .save-bar { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid var(--line); display: flex; justify-content: flex-end; }
    .alert { padding: 0.75rem 1rem; border-radius: 0.375rem; font-size: 0.875rem; }
    .alert-success { background: var(--ok-soft); color: var(--ok); border: 1px solid var(--ok); }
  `],
})
export class ClinicPoliciesComponent implements OnInit {
  private readonly api = inject(ClinicPatientApi);
  private readonly projectContext = inject(ProjectContextService);

  readonly policy = signal<ClinicPolicyRow | null>(null);
  readonly loading = signal<boolean>(true);
  readonly error = signal<boolean>(false);
  readonly saving = signal<boolean>(false);
  readonly saveSuccess = signal<boolean>(false);

  form = {
    doctorSeesAllPatients: false,
    defaultSlotDurationMinutes: 30,
    allowDoubleBooking: false,
    primaryCurrency: 'JOD' as 'JOD' | 'USD',
    toothNotationSystem: 'FDI' as 'FDI' | 'UNIVERSAL',
  };

  constructor() {
    inject(I18nService).register({ clinic: clinicStrings as Namespace });
  }

  ngOnInit(): void {
    this.loadPolicies();
  }

  async loadPolicies(): Promise<void> {
    this.loading.set(true);
    this.error.set(false);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const data = await this.api.policies(proj ? { projectId: proj } : {});
      this.policy.set(data);
      this.form = {
        doctorSeesAllPatients: data.doctorSeesAllPatients,
        defaultSlotDurationMinutes: data.defaultSlotDurationMinutes,
        allowDoubleBooking: data.allowDoubleBooking,
        primaryCurrency: data.primaryCurrency,
        toothNotationSystem: data.toothNotationSystem,
      };
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  async savePolicies(): Promise<void> {
    this.saving.set(true);
    this.saveSuccess.set(false);
    try {
      const proj = this.projectContext.selectedProjectId() || undefined;
      const updated = await this.api.updatePolicies(
        {
          doctorSeesAllPatients: this.form.doctorSeesAllPatients,
          defaultSlotDurationMinutes: Number(this.form.defaultSlotDurationMinutes),
          allowDoubleBooking: this.form.allowDoubleBooking,
          primaryCurrency: this.form.primaryCurrency,
          toothNotationSystem: this.form.toothNotationSystem,
        },
        proj ? { projectId: proj } : {},
      );

      this.policy.set(updated);
      this.saveSuccess.set(true);
      setTimeout(() => this.saveSuccess.set(false), 4000);
    } catch (err) {
      alert('تعذر حفظ السياسات: ' + (err as Error).message);
    } finally {
      this.saving.set(false);
    }
  }
}
