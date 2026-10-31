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
  SupportAccessGrant,
  RequestSupportAccessPayload,
} from '../../../core/api/endpoints';
import { I18nService } from '../../../core/i18n/i18n.service';
import { IconComponent } from '../../../ui/icon.component';
import { StatusChipComponent, StatusTone } from '../../../ui/status-chip.component';

@Component({
  selector: 'fe-platform-support-access',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, StatusChipComponent],
  templateUrl: './platform-support-access.component.html',
  styleUrl: './platform-support-access.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformSupportAccessComponent implements OnInit {
  private readonly platformApi = inject(PlatformApi);
  protected readonly i18n = inject(I18nService);

  // Grants state
  protected readonly grants = signal<readonly SupportAccessGrant[]>([]);
  protected readonly clinics = signal<readonly PlatformClinicSummary[]>([]);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);
  protected readonly statusFilter = signal<string>('ALL');
  protected readonly searchQuery = signal<string>('');

  // Request Access Modal
  protected readonly showRequestModal = signal<boolean>(false);
  protected readonly requesting = signal<boolean>(false);
  protected readonly requestForm = signal<RequestSupportAccessPayload>({
    projectId: '',
    reason: '',
    durationMinutes: 60,
    isReadOnly: true,
  });

  // Revoke Modal
  protected readonly showRevokeModal = signal<boolean>(false);
  protected readonly revoking = signal<boolean>(false);
  protected readonly revokingGrant = signal<SupportAccessGrant | null>(null);
  protected readonly revokeReason = signal<string>('');

  // Diagnostic Inspection View
  protected readonly activeInspectionGrant = signal<SupportAccessGrant | null>(null);
  protected readonly inspectionTab = signal<'patients' | 'appointments' | 'audit'>('patients');
  protected readonly inspectionLoading = signal<boolean>(false);
  protected readonly inspectionPatients = signal<readonly any[]>([]);
  protected readonly inspectionAppointments = signal<readonly any[]>([]);
  protected readonly inspectionAuditLogs = signal<readonly any[]>([]);

  // Computed KPI stats
  protected readonly activeGrants = computed(() =>
    this.grants().filter((g) => g.status === 'ACTIVE'),
  );

  protected readonly expiredGrants = computed(() =>
    this.grants().filter((g) => g.status === 'EXPIRED'),
  );

  protected readonly revokedGrants = computed(() =>
    this.grants().filter((g) => g.status === 'REVOKED'),
  );

  protected readonly filteredGrants = computed(() => {
    let list = this.grants();
    const filter = this.statusFilter();
    if (filter !== 'ALL') {
      list = list.filter((g) => g.status === filter);
    }
    const q = this.searchQuery().trim().toLowerCase();
    if (q) {
      list = list.filter(
        (g) =>
          g.clinicName.toLowerCase().includes(q) ||
          g.operatorDisplayName.toLowerCase().includes(q) ||
          g.reason.toLowerCase().includes(q),
      );
    }
    return list;
  });

  ngOnInit(): void {
    void this.loadData();
  }

  async loadData(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [grantsList, clinicsList] = await Promise.all([
        this.platformApi.supportGrants(),
        this.platformApi.clinics(),
      ]);
      this.grants.set(grantsList);
      this.clinics.set(clinicsList);
    } catch (e: any) {
      this.error.set(e?.message || 'Failed to load support access records.');
    } finally {
      this.loading.set(false);
    }
  }

  openRequestModal(): void {
    const firstClinicId = this.clinics().length > 0 ? this.clinics()[0].projectId : '';
    this.requestForm.set({
      projectId: firstClinicId,
      reason: '',
      durationMinutes: 60,
      isReadOnly: true,
    });
    this.error.set(null);
    this.showRequestModal.set(true);
  }

  closeRequestModal(): void {
    this.showRequestModal.set(false);
  }

  async submitRequest(): Promise<void> {
    const form = this.requestForm();
    if (!form.projectId) {
      this.error.set('Please select a target clinic.');
      return;
    }
    if (!form.reason || form.reason.trim().length < 10) {
      this.error.set('A written justification of at least 10 characters is required.');
      return;
    }
    if (form.durationMinutes < 15 || form.durationMinutes > 120) {
      this.error.set('Duration must be between 15 and 120 minutes.');
      return;
    }

    this.requesting.set(true);
    this.error.set(null);
    try {
      await this.platformApi.requestSupportAccess({
        ...form,
        reason: form.reason.trim(),
        isReadOnly: true,
      });
      this.showRequestModal.set(false);
      await this.loadData();
    } catch (e: any) {
      this.error.set(e?.message || 'Failed to request break-glass support access.');
    } finally {
      this.requesting.set(false);
    }
  }

  openRevokeModal(grant: SupportAccessGrant): void {
    this.revokingGrant.set(grant);
    this.revokeReason.set('');
    this.error.set(null);
    this.showRevokeModal.set(true);
  }

  closeRevokeModal(): void {
    this.showRevokeModal.set(false);
    this.revokingGrant.set(null);
  }

  async submitRevoke(): Promise<void> {
    const grant = this.revokingGrant();
    if (!grant) return;
    const reason = this.revokeReason().trim() || 'Investigation completed';

    this.revoking.set(true);
    this.error.set(null);
    try {
      await this.platformApi.revokeSupportAccess(grant.id, reason);
      this.showRevokeModal.set(false);
      if (this.activeInspectionGrant()?.id === grant.id) {
        this.closeInspection();
      }
      await this.loadData();
    } catch (e: any) {
      this.error.set(e?.message || 'Failed to revoke support access grant.');
    } finally {
      this.revoking.set(false);
    }
  }

  async openInspection(grant: SupportAccessGrant): Promise<void> {
    this.activeInspectionGrant.set(grant);
    this.inspectionTab.set('patients');
    await this.loadInspectionData(grant.id);
  }

  closeInspection(): void {
    this.activeInspectionGrant.set(null);
    this.inspectionPatients.set([]);
    this.inspectionAppointments.set([]);
    this.inspectionAuditLogs.set([]);
  }

  setInspectionTab(tab: 'patients' | 'appointments' | 'audit'): void {
    this.inspectionTab.set(tab);
    const grant = this.activeInspectionGrant();
    if (grant) {
      void this.loadInspectionData(grant.id);
    }
  }

  async loadInspectionData(grantId: string): Promise<void> {
    this.inspectionLoading.set(true);
    try {
      const tab = this.inspectionTab();
      if (tab === 'patients') {
        const data = await this.platformApi.supportGrantPatients(grantId);
        this.inspectionPatients.set(data);
      } else if (tab === 'appointments') {
        const data = await this.platformApi.supportGrantAppointments(grantId);
        this.inspectionAppointments.set(data);
      } else if (tab === 'audit') {
        const data = await this.platformApi.supportGrantAudit(grantId);
        this.inspectionAuditLogs.set(data);
      }
    } catch (e: any) {
      this.error.set(e?.message || 'Failed to load diagnostic records.');
    } finally {
      this.inspectionLoading.set(false);
    }
  }

  statusTone(status: string): StatusTone {
    switch (status) {
      case 'ACTIVE':
        return 'ok';
      case 'EXPIRED':
        return 'neutral';
      case 'REVOKED':
        return 'bad';
      default:
        return 'warn';
    }
  }
}
