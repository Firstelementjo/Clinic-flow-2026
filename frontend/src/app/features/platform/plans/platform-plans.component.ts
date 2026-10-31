import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PlatformApi,
  PlatformPlanSummary,
  PlatformPlanDetail,
  ClinicEffectiveLimits,
  UpdatePlanPayload,
  UpdateClinicLimitsPayload,
} from '../../../core/api/endpoints';
import { I18nService } from '../../../core/i18n/i18n.service';
import { IconComponent } from '../../../ui/icon.component';
import { StatusChipComponent, StatusTone } from '../../../ui/status-chip.component';

@Component({
  selector: 'fe-platform-plans',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, StatusChipComponent],
  templateUrl: './platform-plans.component.html',
  styleUrl: './platform-plans.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformPlansComponent implements OnInit {
  private readonly platformApi = inject(PlatformApi);
  protected readonly i18n = inject(I18nService);

  // Plans data
  protected readonly plans = signal<readonly PlatformPlanSummary[]>([]);
  protected readonly loadingPlans = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);

  // Selected plan for editing
  protected readonly selectedPlan = signal<PlatformPlanDetail | null>(null);
  protected readonly editingPlan = signal<boolean>(false);
  protected readonly savingPlan = signal<boolean>(false);
  protected readonly planForm = signal<UpdatePlanPayload>({
    name: '',
    description: '',
    maxUsers: 5,
    maxBranches: 1,
    storageQuotaMb: 1024,
    monthlyAiQuota: 100,
    entitlements: {},
    reason: '',
  });

  // Clinic overrides
  protected readonly clinicSearchId = signal<string>('');
  protected readonly searchingClinic = signal<boolean>(false);
  protected readonly clinicLimits = signal<ClinicEffectiveLimits | null>(null);
  protected readonly clinicError = signal<string | null>(null);
  protected readonly savingClinicLimits = signal<boolean>(false);

  // Clinic overrides form
  protected readonly overrideForm = signal<{
    maxUsers: number | null;
    maxBranches: number | null;
    storageQuotaMb: number | null;
    monthlyAiQuota: number | null;
    reason: string;
  }>({
    maxUsers: null,
    maxBranches: null,
    storageQuotaMb: null,
    monthlyAiQuota: null,
    reason: '',
  });

  readonly availableEntitlements = [
    { key: 'CLINIC_AI', label: 'Clinic Governed AI Assistant', desc: 'Allows drafting treatment suggestions & dental AI analysis' },
    { key: 'CHANNEL_WHATSAPP', label: 'WhatsApp Integration', desc: 'Patient reminders and direct messaging via WhatsApp' },
    { key: 'CHANNEL_TELEGRAM', label: 'Telegram Notifications', desc: 'Staff alerts and patient communications via Telegram' },
    { key: 'CHANNEL_INSTAGRAM', label: 'Instagram Direct', desc: 'Direct message booking inquiries integration' },
  ];

  ngOnInit(): void {
    void this.loadPlans();
  }

  async loadPlans(): Promise<void> {
    this.loadingPlans.set(true);
    this.error.set(null);
    try {
      const items = await this.platformApi.plans();
      this.plans.set(items);
    } catch (err: unknown) {
      const msg = (err as { message?: string })?.message || 'Failed to load platform subscription plans.';
      this.error.set(msg);
    } finally {
      this.loadingPlans.set(false);
    }
  }

  async openEditPlan(planId: string): Promise<void> {
    this.savingPlan.set(false);
    this.error.set(null);
    try {
      const plan = await this.platformApi.plan(planId);
      this.selectedPlan.set(plan);
      this.planForm.set({
        name: plan.name,
        description: plan.description || '',
        maxUsers: plan.maxUsers,
        maxBranches: plan.maxBranches,
        storageQuotaMb: plan.storageQuotaMb,
        monthlyAiQuota: plan.monthlyAiQuota,
        entitlements: { ...plan.entitlements },
        reason: 'Adjusting plan baseline configuration',
      });
      this.editingPlan.set(true);
    } catch (err: unknown) {
      const msg = (err as { message?: string })?.message || 'Failed to retrieve plan details.';
      this.error.set(msg);
    }
  }

  closeEditPlan(): void {
    this.editingPlan.set(false);
    this.selectedPlan.set(null);
  }

  togglePlanEntitlement(key: string): void {
    const current = { ...(this.planForm().entitlements || {}) };
    current[key] = !current[key];
    this.planForm.set({ ...this.planForm(), entitlements: current });
  }

  hasPlanEntitlement(key: string): boolean {
    return !!this.planForm().entitlements?.[key];
  }

  async submitPlanUpdate(): Promise<void> {
    const plan = this.selectedPlan();
    if (!plan) return;

    this.savingPlan.set(true);
    this.error.set(null);
    try {
      await this.platformApi.updatePlan(plan.id, this.planForm());
      this.savingPlan.set(false);
      this.editingPlan.set(false);
      this.selectedPlan.set(null);
      await this.loadPlans();
    } catch (err: unknown) {
      this.savingPlan.set(false);
      const msg = (err as { message?: string })?.message || 'Failed to update plan limits.';
      this.error.set(msg);
    }
  }

  async searchClinic(): Promise<void> {
    const id = this.clinicSearchId().trim();
    if (!id) return;

    this.searchingClinic.set(true);
    this.clinicError.set(null);
    this.clinicLimits.set(null);

    try {
      const res = await this.platformApi.clinicEffectiveLimits(id);
      this.clinicLimits.set(res);
      this.overrideForm.set({
        maxUsers: res.maxUsersOverride,
        maxBranches: res.maxBranchesOverride,
        storageQuotaMb: res.storageQuotaMbOverride,
        monthlyAiQuota: res.monthlyAiQuotaOverride,
        reason: 'Operator manual override adjustment',
      });
    } catch (err: unknown) {
      const msg = (err as { message?: string })?.message || 'Clinic not found or failed to load effective limits.';
      this.clinicError.set(msg);
    } finally {
      this.searchingClinic.set(false);
    }
  }

  async saveClinicOverrides(): Promise<void> {
    const limits = this.clinicLimits();
    if (!limits) return;

    this.savingClinicLimits.set(true);
    this.clinicError.set(null);

    const payload: UpdateClinicLimitsPayload = {
      maxUsers: this.overrideForm().maxUsers,
      maxBranches: this.overrideForm().maxBranches,
      storageQuotaMb: this.overrideForm().storageQuotaMb,
      monthlyAiQuota: this.overrideForm().monthlyAiQuota,
      reason: this.overrideForm().reason || 'Operator per-clinic limits override',
    };

    try {
      const res = await this.platformApi.updateClinicLimitsOverrides(limits.projectId, payload);
      this.clinicLimits.set(res);
      this.overrideForm.set({
        maxUsers: res.maxUsersOverride,
        maxBranches: res.maxBranchesOverride,
        storageQuotaMb: res.storageQuotaMbOverride,
        monthlyAiQuota: res.monthlyAiQuotaOverride,
        reason: payload.reason,
      });
    } catch (err: unknown) {
      const msg = (err as { message?: string })?.message || 'Failed to save clinic limit overrides.';
      this.clinicError.set(msg);
    } finally {
      this.savingClinicLimits.set(false);
    }
  }

  async toggleClinicEntitlementOverride(featureKey: string, currentlyEnabled: boolean): Promise<void> {
    const limits = this.clinicLimits();
    if (!limits) return;

    this.savingClinicLimits.set(true);
    this.clinicError.set(null);

    try {
      await this.platformApi.overrideClinicEntitlement(
        limits.projectId,
        featureKey,
        !currentlyEnabled,
        `Toggling ${featureKey} to ${!currentlyEnabled}`,
      );
      // Re-fetch limits
      const res = await this.platformApi.clinicEffectiveLimits(limits.projectId);
      this.clinicLimits.set(res);
    } catch (err: unknown) {
      const msg = (err as { message?: string })?.message || `Failed to toggle entitlement for ${featureKey}.`;
      this.clinicError.set(msg);
    } finally {
      this.savingClinicLimits.set(false);
    }
  }

  calculateUsagePercent(current: number, max: number): number {
    if (max <= 0) return 0;
    const pct = Math.round((current / max) * 100);
    return Math.min(pct, 100);
  }

  getUsageTone(current: number, max: number): StatusTone {
    if (max <= 0) return 'neutral';
    const ratio = current / max;
    if (ratio >= 1.0) return 'bad';
    if (ratio >= 0.8) return 'warn';
    return 'ok';
  }
}
