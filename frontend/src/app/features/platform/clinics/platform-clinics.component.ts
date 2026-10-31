import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PlatformApi,
  PlatformClinicSummary,
  CreateClinicWizardInput,
  ClinicWizardResult,
  ClinicLimitsInput,
  EditClinicInput,
  AddClinicAdminInput,
} from '../../../core/api/endpoints';
import { I18nService } from '../../../core/i18n/i18n.service';
import { IconComponent } from '../../../ui/icon.component';
import { StatusChipComponent, StatusTone } from '../../../ui/status-chip.component';
import { TlDrawerComponent } from '../../../ui/tl-drawer.component';

type ClinicFilterStatus = 'ALL' | 'ACTIVE' | 'TRIAL' | 'GRACE' | 'SUSPENDED' | 'EXPIRED';

@Component({
  selector: 'fe-platform-clinics',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, StatusChipComponent, TlDrawerComponent],
  templateUrl: './platform-clinics.component.html',
  styleUrl: './platform-clinics.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformClinicsComponent implements OnInit {
  private readonly platformApi = inject(PlatformApi);
  protected readonly i18n = inject(I18nService);

  // Data signals
  protected readonly clinics = signal<readonly PlatformClinicSummary[]>([]);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);

  // Filter & search signals
  protected readonly searchQuery = signal<string>('');
  protected readonly statusFilter = signal<ClinicFilterStatus>('ALL');

  // Wizard modal state
  protected readonly wizardOpen = signal<boolean>(false);
  protected readonly wizardSubmitting = signal<boolean>(false);
  protected readonly wizardStep = signal<1 | 2 | 3 | 4>(1);
  protected readonly wizardError = signal<string | null>(null);
  protected readonly wizardResult = signal<ClinicWizardResult | null>(null);

  protected readonly showAdminPassword = signal<boolean>(false);
  protected readonly directPasswordMode = signal<boolean>(true);
  protected readonly credentialsCopied = signal<boolean>(false);

  protected wizardForm: CreateClinicWizardInput = {
    clinicName: '',
    city: 'Amman',
    contactNumber: '',
    timezone: 'Asia/Amman',
    language: 'ar',
    planCode: 'GROWTH',
    startType: 'TRIAL',
    adminName: '',
    adminEmail: '',
    adminPassword: '',
    adminPhone: '',
    reason: 'New tenant onboarding',
  };

  // Action modals
  protected readonly actionModalOpen = signal<boolean>(false);
  protected readonly actionType = signal<'EXTEND' | 'LIMITS' | 'SUSPEND' | 'REACTIVATE' | 'EDIT' | 'INVITE_LINK' | 'ADD_ADMIN' | null>(null);
  protected readonly selectedClinic = signal<PlatformClinicSummary | null>(null);
  protected readonly actionSubmitting = signal<boolean>(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionSuccessMessage = signal<string | null>(null);

  // Extend form
  protected extendDays = 30;
  protected extendReason = 'Customer subscription renewal';

  // Limits form
  protected limitsForm: ClinicLimitsInput = {
    maxUsers: 5,
    maxBranches: 1,
    storageQuotaMb: 5120,
    monthlyAiQuota: 500,
    reason: 'Standard plan limits update',
  };

  // Edit form
  protected editForm: EditClinicInput = {
    clinicName: '',
    city: 'Amman',
    contactNumber: '',
    timezone: 'Asia/Amman',
    language: 'ar',
    reason: 'Administrative update',
  };

  // Add Clinic Admin form
  protected addAdminForm: AddClinicAdminInput = {
    adminName: '',
    adminEmail: '',
    adminPhone: '',
    adminPassword: '',
    reason: 'Assign additional clinic administrator',
  };

  // Active Invite details for copy
  protected activeInviteLink = signal<string | null>(null);
  protected copyNotification = signal<boolean>(false);

  protected readonly actionDrawerTitle = computed(() => {
    const c = this.selectedClinic();
    const name = c?.clinicName ?? '';
    const isAr = this.i18n.locale() === 'ar';
    switch (this.actionType()) {
      case 'EXTEND': return isAr ? `تمديد اشتراك العيادة • ${name}` : `Extend Subscription • ${name}`;
      case 'LIMITS': return isAr ? `تعديل سعة وحدود العيادة (FDO) • ${name}` : `Capacity & Staff Limits • ${name}`;
      case 'EDIT': return isAr ? `تعديل بيانات العيادة • ${name}` : `Edit Clinic Profile • ${name}`;
      case 'SUSPEND': return isAr ? `إيقاف مؤقت للعيادة • ${name}` : `Suspend Clinic Tenant • ${name}`;
      case 'REACTIVATE': return isAr ? `إعادة تنشيط العيادة • ${name}` : `Reactivate Clinic Tenant • ${name}`;
      case 'INVITE_LINK': return isAr ? `رابط الدعوة المباشر • ${name}` : `Single-Use Invite Link • ${name}`;
      case 'ADD_ADMIN': return isAr ? `إضافة مدير عيادة جديد (Clinic Admin) • ${name}` : `Add Clinic Admin • ${name}`;
      default: return name;
    }
  });

  protected readonly actionDrawerSubtitle = computed(() => {
    const isAr = this.i18n.locale() === 'ar';
    switch (this.actionType()) {
      case 'EXTEND': return isAr ? 'إضافة أيام اشتراك إضافية للعيادة' : 'Grant additional subscription days to this tenant';
      case 'LIMITS': return isAr ? 'تحديد العدد الأقصى لموظفي العيادة (FDO / الأطباء / الاستقبال / المحاسبين) وحصة الذكاء الاصطناعي' : 'Set max FDO / staff accounts and monthly AI allowance for this clinic';
      case 'EDIT': return isAr ? 'تحديث الاسم، الهاتف، المدينة وإعدادات التوقيت' : 'Update basic organizational details and timezone';
      case 'SUSPEND': return isAr ? 'تعليق وصول العيادة مؤقتاً بسبب الفواتير أو السياسات' : 'Temporarily revoke clinic access';
      case 'REACTIVATE': return isAr ? 'استعادة نشاط العيادة وتفعيل الحساب فوراً' : 'Restore full clinic access immediately';
      case 'INVITE_LINK': return isAr ? 'إعادة إنشاء رابط دعوة صالح لمرة واحدة للمشرف' : 'Regenerate one-time onboarding link';
      case 'ADD_ADMIN': return isAr ? 'منح صلاحية مدير عيادة (clinic_admin) وتنسيبه مباشرة لهذه العيادة (حصري لمدير المنصة)' : 'Provision and assign a new clinic_admin account to this clinic';
      default: return null;
    }
  });

  // Computed filtered clinics
  protected readonly filteredClinics = computed(() => {
    const list = this.clinics();
    const query = this.searchQuery().trim().toLowerCase();
    const status = this.statusFilter();

    return list.filter((item) => {
      // Status match
      if (status !== 'ALL') {
        const itemStatus = (item.status || item.subscriptionStatus || '').toUpperCase();
        if (itemStatus !== status) {
          return false;
        }
      }

      // Search match
      if (!query) return true;

      const clinicName = (item.clinicName || '').toLowerCase();
      const slug = (item.slug || '').toLowerCase();
      const city = (item.city || '').toLowerCase();
      const adminName = (item.adminName || '').toLowerCase();
      const adminEmail = (item.adminEmail || '').toLowerCase();
      const plan = (item.planName || item.planCode || '').toLowerCase();

      return (
        clinicName.includes(query) ||
        slug.includes(query) ||
        city.includes(query) ||
        adminName.includes(query) ||
        adminEmail.includes(query) ||
        plan.includes(query)
      );
    });
  });

  ngOnInit(): void {
    void this.loadClinics();
  }

  async loadClinics(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const data = await this.platformApi.clinics();
      this.clinics.set(data);
    } catch (err) {
      this.error.set('Failed to load clinic tenants. Please verify connectivity.');
    } finally {
      this.loading.set(false);
    }
  }

  // --------------------------------------------------------------------------
  // Wizard Modal
  // --------------------------------------------------------------------------

  openWizard(): void {
    this.wizardForm = {
      clinicName: '',
      city: 'Amman',
      contactNumber: '',
      timezone: 'Asia/Amman',
      language: 'ar',
      planCode: 'GROWTH',
      startType: 'TRIAL',
      adminName: '',
      adminEmail: '',
      adminPassword: '',
      adminPhone: '',
      reason: 'New tenant onboarding',
    };
    this.directPasswordMode.set(true);
    this.generateAdminPassword();
    this.showAdminPassword.set(false);
    this.credentialsCopied.set(false);
    this.wizardStep.set(1);
    this.wizardError.set(null);
    this.wizardResult.set(null);
    this.wizardOpen.set(true);
  }

  closeWizard(): void {
    this.wizardOpen.set(false);
    this.wizardResult.set(null);
  }

  generateAdminPassword(): void {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*';
    let pwd = '';
    for (let i = 0; i < 14; i++) {
      pwd += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    this.wizardForm.adminPassword = pwd;
  }

  togglePasswordVisibility(): void {
    this.showAdminPassword.update((v) => !v);
  }

  copyCredentials(): void {
    const res = this.wizardResult();
    const clinicName = res?.clinicName || this.wizardForm.clinicName;
    const adminName = res?.administratorName || this.wizardForm.adminName;
    const adminEmail = res?.administratorEmail || this.wizardForm.adminEmail;
    const loginUrl = `${window.location.origin}/auth/login`;

    const text = [
      `مرحباً دكتور ${adminName}،`,
      `تم إعداد وتفعيل حساب عيادتكم (${clinicName}) في نظام Clinic Flow بنجاح.`,
      '',
      `رابط تسجيل الدخول: ${loginUrl}`,
      `البريد الإلكتروني: ${adminEmail}`,
      this.directPasswordMode() && this.wizardForm.adminPassword
        ? `كلمة المرور: ${this.wizardForm.adminPassword}`
        : `رابط تفعيل الحساب وتعيين كلمة المرور: ${window.location.origin}${res?.inviteLink || ''}`,
      `الخطة المعتمدة: ${res?.planCode || this.wizardForm.planCode}`,
      '',
      'يرجى تسجيل الدخول وإكمال إعداد العيادة. فريق الدعم الفني في خدمتكم دائماً.'
    ].join('\n');

    navigator.clipboard.writeText(text).then(() => {
      this.credentialsCopied.set(true);
      setTimeout(() => this.credentialsCopied.set(false), 3000);
    });
  }

  nextWizardStep(): void {
    this.wizardError.set(null);
    const step = this.wizardStep();

    if (step === 1) {
      if (!this.wizardForm.clinicName.trim()) {
        this.wizardError.set('Clinic name is required.');
        return;
      }
      this.wizardStep.set(2);
    } else if (step === 2) {
      this.wizardStep.set(3);
    } else if (step === 3) {
      if (!this.wizardForm.adminName.trim()) {
        this.wizardError.set('Administrator name is required.');
        return;
      }
      if (!this.wizardForm.adminEmail.trim() || !this.wizardForm.adminEmail.includes('@')) {
        this.wizardError.set('A valid administrator email is required.');
        return;
      }
      if (this.directPasswordMode() && (!this.wizardForm.adminPassword || this.wizardForm.adminPassword.length < 8)) {
        this.wizardError.set('Administrator password must be at least 8 characters.');
        return;
      }
      void this.submitWizard();
    }
  }

  prevWizardStep(): void {
    const step = this.wizardStep();
    if (step > 1 && step < 4) {
      this.wizardStep.set((step - 1) as 1 | 2 | 3);
    }
  }

  async submitWizard(): Promise<void> {
    this.wizardSubmitting.set(true);
    this.wizardError.set(null);
    try {
      const payload: CreateClinicWizardInput = {
        ...this.wizardForm,
        adminPassword: this.directPasswordMode() ? this.wizardForm.adminPassword : undefined,
      };
      const res = await this.platformApi.createClinicWizard(payload);
      this.wizardResult.set(res);
      this.wizardStep.set(4);
      void this.loadClinics();
    } catch (err: any) {
      this.wizardError.set(err?.message || 'Failed to create clinic. Check email uniqueness.');
    } finally {
      this.wizardSubmitting.set(false);
    }
  }

  // --------------------------------------------------------------------------
  // Action Modals
  // --------------------------------------------------------------------------

  openExtend(clinic: PlatformClinicSummary): void {
    this.selectedClinic.set(clinic);
    this.extendDays = 30;
    this.extendReason = 'Customer subscription renewal';
    this.actionType.set('EXTEND');
    this.actionError.set(null);
    this.actionSuccessMessage.set(null);
    this.actionModalOpen.set(true);
  }

  openLimits(clinic: PlatformClinicSummary): void {
    this.selectedClinic.set(clinic);
    this.limitsForm = {
      maxUsers: clinic.maxUsers || 5,
      maxBranches: clinic.maxBranches || 1,
      storageQuotaMb: clinic.storageQuotaMb || 5120,
      monthlyAiQuota: clinic.monthlyAiQuota || 500,
      reason: 'Capacity limits update',
    };
    this.actionType.set('LIMITS');
    this.actionError.set(null);
    this.actionSuccessMessage.set(null);
    this.actionModalOpen.set(true);
  }

  openEdit(clinic: PlatformClinicSummary): void {
    this.selectedClinic.set(clinic);
    this.editForm = {
      clinicName: clinic.clinicName,
      city: clinic.city || 'Amman',
      contactNumber: clinic.contactNumber || '',
      timezone: clinic.timezone || 'Asia/Amman',
      language: clinic.defaultLocale || 'ar',
      reason: 'Clinic profile update',
    };
    this.actionType.set('EDIT');
    this.actionError.set(null);
    this.actionSuccessMessage.set(null);
    this.actionModalOpen.set(true);
  }

  openSuspendPrompt(clinic: PlatformClinicSummary): void {
    this.selectedClinic.set(clinic);
    this.actionType.set('SUSPEND');
    this.actionError.set(null);
    this.actionSuccessMessage.set(null);
    this.actionModalOpen.set(true);
  }

  openReactivatePrompt(clinic: PlatformClinicSummary): void {
    this.selectedClinic.set(clinic);
    this.actionType.set('REACTIVATE');
    this.actionError.set(null);
    this.actionSuccessMessage.set(null);
    this.actionModalOpen.set(true);
  }

  openAddAdmin(clinic: PlatformClinicSummary): void {
    this.selectedClinic.set(clinic);
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*';
    let pwd = '';
    for (let i = 0; i < 12; i++) {
      pwd += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    this.addAdminForm = {
      adminName: '',
      adminEmail: '',
      adminPhone: '',
      adminPassword: pwd,
      reason: 'Assign additional clinic administrator',
    };
    this.actionType.set('ADD_ADMIN');
    this.actionError.set(null);
    this.actionSuccessMessage.set(null);
    this.actionModalOpen.set(true);
  }

  closeActionModal(): void {
    this.actionModalOpen.set(false);
    this.actionType.set(null);
    this.selectedClinic.set(null);
  }

  async executeAddAdmin(): Promise<void> {
    const c = this.selectedClinic();
    if (!c) return;
    if (!this.addAdminForm.adminName.trim() || !this.addAdminForm.adminEmail.trim()) {
      this.actionError.set(this.i18n.locale() === 'ar' ? 'يرجى إدخال اسم وبريد مدير العيادة.' : 'Admin name and email are required.');
      return;
    }
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.platformApi.addClinicAdmin(c.projectId, this.addAdminForm);
      this.actionSuccessMessage.set(this.i18n.locale() === 'ar' ? 'تم إنشاء وتنسيب مدير العيادة الجديد بنجاح.' : 'Clinic administrator created and assigned successfully.');
      await this.loadClinics();
      setTimeout(() => this.closeActionModal(), 1400);
    } catch (err: any) {
      this.actionError.set(err?.message || 'Failed to add clinic administrator.');
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  async executeExtend(): Promise<void> {
    const c = this.selectedClinic();
    if (!c) return;
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.platformApi.extendSubscription(c.projectId, this.extendDays, this.extendReason);
      this.actionSuccessMessage.set('Subscription extended successfully.');
      await this.loadClinics();
      setTimeout(() => this.closeActionModal(), 1200);
    } catch (err: any) {
      this.actionError.set(err?.message || 'Failed to extend subscription.');
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  async executeLimits(): Promise<void> {
    const c = this.selectedClinic();
    if (!c) return;
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.platformApi.updateLimits(c.projectId, this.limitsForm);
      this.actionSuccessMessage.set('Limits updated successfully.');
      await this.loadClinics();
      setTimeout(() => this.closeActionModal(), 1200);
    } catch (err: any) {
      this.actionError.set(err?.message || 'Failed to update limits.');
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  async executeEdit(): Promise<void> {
    const c = this.selectedClinic();
    if (!c) return;
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.platformApi.editClinic(c.projectId, this.editForm);
      this.actionSuccessMessage.set('Clinic updated successfully.');
      await this.loadClinics();
      setTimeout(() => this.closeActionModal(), 1200);
    } catch (err: any) {
      this.actionError.set(err?.message || 'Failed to update clinic.');
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  async executeSuspend(): Promise<void> {
    const c = this.selectedClinic();
    if (!c) return;
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.platformApi.suspendClinic(c.projectId, 'Administrative suspension: overdue or requested.');
      this.actionSuccessMessage.set('Clinic suspended. Data intact under DEC-007 retention.');
      await this.loadClinics();
      setTimeout(() => this.closeActionModal(), 1200);
    } catch (err: any) {
      this.actionError.set(err?.message || 'Failed to suspend clinic.');
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  async executeReactivate(): Promise<void> {
    const c = this.selectedClinic();
    if (!c) return;
    this.actionSubmitting.set(true);
    this.actionError.set(null);
    try {
      await this.platformApi.reactivateClinic(c.projectId, 'Administrative reactivation.');
      this.actionSuccessMessage.set('Clinic reactivated successfully.');
      await this.loadClinics();
      setTimeout(() => this.closeActionModal(), 1200);
    } catch (err: any) {
      this.actionError.set(err?.message || 'Failed to reactivate clinic.');
    } finally {
      this.actionSubmitting.set(false);
    }
  }

  async resendInvite(clinic: PlatformClinicSummary): Promise<void> {
    try {
      const res = await this.platformApi.resendInvite(clinic.projectId);
      const fullUrl = window.location.origin + res.link;
      this.activeInviteLink.set(fullUrl);
      this.selectedClinic.set(clinic);
      this.actionType.set('INVITE_LINK');
      this.actionModalOpen.set(true);
      await this.loadClinics();
    } catch (err) {
      alert('Failed to regenerate invite. Please retry.');
    }
  }

  async resetMfa(clinic: PlatformClinicSummary): Promise<void> {
    if (!confirm(`Reset MFA enrollment for administrator ${clinic.adminName || clinic.adminEmail}? They will re-enroll upon their next login.`)) {
      return;
    }
    try {
      await this.platformApi.resetAdminMfa(clinic.projectId);
      alert('MFA enrollment reset successfully.');
      await this.loadClinics();
    } catch (err) {
      alert('Failed to reset MFA.');
    }
  }

  async toggleAdminActive(clinic: PlatformClinicSummary): Promise<void> {
    const currentlyActive = clinic.adminIsActive;
    const action = currentlyActive ? 'disable' : 'enable';
    if (!confirm(`Are you sure you want to ${action} account ${clinic.adminEmail}?`)) {
      return;
    }
    try {
      if (currentlyActive) {
        await this.platformApi.disableAdmin(clinic.projectId);
      } else {
        await this.platformApi.enableAdmin(clinic.projectId);
      }
      await this.loadClinics();
    } catch (err) {
      alert(`Failed to ${action} account.`);
    }
  }

  copyLink(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.copyNotification.set(true);
      setTimeout(() => this.copyNotification.set(false), 2500);
    });
  }

  exportCsv(): void {
    const rows = this.filteredClinics();
    if (rows.length === 0) return;

    const headers = ['Clinic Name', 'Slug', 'City', 'Plan', 'Status', 'Paid Until', 'Admin Name', 'Admin Email', 'Admin Phone', 'Created At'];
    const csvContent = [
      headers.join(','),
      ...rows.map((r) =>
        [
          `"${r.clinicName}"`,
          `"${r.slug}"`,
          `"${r.city || ''}"`,
          `"${r.planCode || ''}"`,
          `"${r.status || r.subscriptionStatus || ''}"`,
          `"${r.paidUntil || ''}"`,
          `"${r.adminName || ''}"`,
          `"${r.adminEmail || ''}"`,
          `"${r.adminPhone || ''}"`,
          `"${r.createdAt}"`,
        ].join(','),
      ),
    ].join('\n');

    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `clinic-tenants-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  statusTone(status: string | undefined): StatusTone {
    switch ((status || '').toUpperCase()) {
      case 'ACTIVE':
        return 'ok';
      case 'TRIAL':
        return 'warn';
      case 'GRACE':
        return 'escalated';
      case 'SUSPENDED':
        return 'bad';
      case 'EXPIRED':
        return 'neutral';
      default:
        return 'neutral';
    }
  }

  formatDate(val: string | undefined): string {
    if (!val) return '—';
    try {
      return new Date(val).toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      });
    } catch {
      return val;
    }
  }
}
