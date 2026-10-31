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
  EnrichedAuditEntry,
  PlatformAuditFilter,
  PlatformClinicSummary,
} from '../../../core/api/endpoints';
import { I18nService } from '../../../core/i18n/i18n.service';
import { IconComponent } from '../../../ui/icon.component';
import { StatusChipComponent, StatusTone } from '../../../ui/status-chip.component';

@Component({
  selector: 'fe-platform-audit',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, StatusChipComponent],
  templateUrl: './platform-audit.component.html',
  styleUrl: './platform-audit.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAuditComponent implements OnInit {
  private readonly platformApi = inject(PlatformApi);
  protected readonly i18n = inject(I18nService);

  // Data signals
  protected readonly events = signal<readonly EnrichedAuditEntry[]>([]);
  protected readonly actions = signal<readonly string[]>([]);
  protected readonly clinics = signal<readonly PlatformClinicSummary[]>([]);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);

  // Filter signals
  protected readonly clinicFilter = signal<string>('');
  protected readonly actorFilter = signal<string>('');
  protected readonly actionFilter = signal<string>('');
  protected readonly outcomeFilter = signal<string>('');
  protected readonly dateFromFilter = signal<string>('');
  protected readonly dateToFilter = signal<string>('');
  protected readonly searchQuery = signal<string>('');

  // Selected event modal/drawer
  protected readonly selectedEvent = signal<EnrichedAuditEntry | null>(null);

  // Computeds
  protected readonly filteredEvents = computed<readonly EnrichedAuditEntry[]>(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const list = this.events();

    if (!q) return list;

    return list.filter((e) => {
      return (
        (e.action && e.action.toLowerCase().includes(q)) ||
        (e.operatorName && e.operatorName.toLowerCase().includes(q)) ||
        (e.operatorEmail && e.operatorEmail.toLowerCase().includes(q)) ||
        (e.clinicName && e.clinicName.toLowerCase().includes(q)) ||
        (e.organizationName && e.organizationName.toLowerCase().includes(q)) ||
        (e.resourceType && e.resourceType.toLowerCase().includes(q)) ||
        (e.resourceId && e.resourceId.toLowerCase().includes(q)) ||
        (e.detail && e.detail.toLowerCase().includes(q)) ||
        (e.reason && e.reason.toLowerCase().includes(q)) ||
        (e.ipAddress && e.ipAddress.toLowerCase().includes(q)) ||
        (e.correlationId && e.correlationId.toLowerCase().includes(q))
      );
    });
  });

  protected readonly allowedCount = computed<number>(
    () => this.events().filter((e) => e.outcome === 'ALLOWED').length,
  );

  protected readonly deniedCount = computed<number>(
    () => this.events().filter((e) => e.outcome === 'DENIED').length,
  );

  ngOnInit(): void {
    this.loadFilterOptions();
    this.loadEvents();
  }

  protected async loadFilterOptions(): Promise<void> {
    try {
      const [actionList, clinicList] = await Promise.all([
        this.platformApi.auditActions().catch(() => []),
        this.platformApi.clinics().catch(() => []),
      ]);
      this.actions.set(actionList);
      this.clinics.set(clinicList);
    } catch {
      // Non-blocking for filter dropdowns
    }
  }

  protected async loadEvents(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);

    const filter: PlatformAuditFilter = {
      clinicId: this.clinicFilter() || undefined,
      actor: this.actorFilter() || undefined,
      action: this.actionFilter() || undefined,
      outcome: this.outcomeFilter() || undefined,
      from: this.dateFromFilter() ? new Date(this.dateFromFilter()).toISOString() : undefined,
      to: this.dateToFilter() ? new Date(this.dateToFilter()).toISOString() : undefined,
      limit: 250,
    };

    try {
      const data = await this.platformApi.auditTrail(filter);
      this.events.set(data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load audit trail events.';
      this.error.set(msg);
    } finally {
      this.loading.set(false);
    }
  }

  protected clearFilters(): void {
    this.clinicFilter.set('');
    this.actorFilter.set('');
    this.actionFilter.set('');
    this.outcomeFilter.set('');
    this.dateFromFilter.set('');
    this.dateToFilter.set('');
    this.searchQuery.set('');
    this.loadEvents();
  }

  protected formatDateTime(instantStr: string | null | undefined): string {
    if (!instantStr) return '—';
    try {
      const d = new Date(instantStr);
      return d.toLocaleString(this.i18n.locale() === 'ar' ? 'ar-JO' : 'en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      });
    } catch {
      return instantStr;
    }
  }

  protected outcomeTone(outcome: string): StatusTone {
    switch (outcome?.toUpperCase()) {
      case 'ALLOWED':
        return 'ok';
      case 'DENIED':
      case 'FAILED':
        return 'bad';
      default:
        return 'neutral';
    }
  }

  protected openEventDetails(event: EnrichedAuditEntry): void {
    this.selectedEvent.set(event);
  }

  protected closeEventDetails(): void {
    this.selectedEvent.set(null);
  }

  protected exportCsv(): void {
    const rows = this.filteredEvents();
    if (rows.length === 0) return;

    const headers = ['Timestamp', 'Actor Name', 'Actor Email', 'Action', 'Target Type', 'Target ID', 'Clinic', 'Tenant', 'Outcome', 'IP Address', 'Details', 'Reason', 'Correlation ID'];
    const csvContent = [
      headers.join(','),
      ...rows.map((r) => [
        `"${r.occurredAt || ''}"`,
        `"${(r.operatorName || '').replace(/"/g, '""')}"`,
        `"${(r.operatorEmail || '').replace(/"/g, '""')}"`,
        `"${r.action || ''}"`,
        `"${r.resourceType || ''}"`,
        `"${r.resourceId || ''}"`,
        `"${(r.clinicName || '').replace(/"/g, '""')}"`,
        `"${(r.organizationName || '').replace(/"/g, '""')}"`,
        `"${r.outcome || ''}"`,
        `"${r.ipAddress || ''}"`,
        `"${(r.detail || '').replace(/"/g, '""')}"`,
        `"${(r.reason || '').replace(/"/g, '""')}"`,
        `"${r.correlationId || ''}"`,
      ].join(',')),
    ].join('\n');

    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `platform-audit-trail-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
}
