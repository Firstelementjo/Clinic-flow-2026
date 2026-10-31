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
import { RouterLink } from '@angular/router';
import {
  PlatformApi,
  OperatorDashboardOverview,
  DashboardKpis,
  NeedsAttentionItem,
  RevenueByMonth,
  ClinicsByStatus,
  ClinicAiUsageQuota,
  PlatformAuditEvent,
} from '../../../core/api/endpoints';
import { I18nService } from '../../../core/i18n/i18n.service';
import { IconComponent } from '../../../ui/icon.component';
import { StatusChipComponent, StatusTone } from '../../../ui/status-chip.component';

export type AttentionFilterType = 'ALL' | 'CRITICAL' | 'WARNING' | 'SUSPENDED' | 'EXPIRING' | 'ADMIN';

@Component({
  selector: 'fe-platform-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, StatusChipComponent],
  templateUrl: './platform-dashboard.component.html',
  styleUrl: './platform-dashboard.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformDashboardComponent implements OnInit {
  private readonly platformApi = inject(PlatformApi);
  protected readonly i18n = inject(I18nService);

  // Core state
  protected readonly overview = signal<OperatorDashboardOverview | null>(null);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);

  // Filters & Search
  protected readonly attentionFilter = signal<AttentionFilterType>('ALL');
  protected readonly searchQuery = signal<string>('');

  // Computeds
  protected readonly kpis = computed<DashboardKpis | null>(() => this.overview()?.kpis ?? null);

  protected readonly rawNeedsAttention = computed<readonly NeedsAttentionItem[]>(
    () => this.overview()?.needsAttention ?? [],
  );

  protected readonly criticalCount = computed<number>(
    () => this.rawNeedsAttention().filter((item) => item.severity === 'CRITICAL').length,
  );

  protected readonly warningCount = computed<number>(
    () => this.rawNeedsAttention().filter((item) => item.severity === 'WARNING').length,
  );

  protected readonly filteredAttention = computed<readonly NeedsAttentionItem[]>(() => {
    const query = this.searchQuery().trim().toLowerCase();
    const filter = this.attentionFilter();
    let items = this.rawNeedsAttention();

    if (filter === 'CRITICAL') {
      items = items.filter((i) => i.severity === 'CRITICAL');
    } else if (filter === 'WARNING') {
      items = items.filter((i) => i.severity === 'WARNING');
    } else if (filter === 'SUSPENDED') {
      items = items.filter((i) => i.attentionType === 'SUSPENDED' || i.subscriptionStatus === 'SUSPENDED');
    } else if (filter === 'EXPIRING') {
      items = items.filter((i) => i.attentionType === 'EXPIRING_SOON' || i.attentionType === 'IN_GRACE' || i.attentionType === 'OVERDUE');
    } else if (filter === 'ADMIN') {
      items = items.filter((i) => i.attentionType === 'NO_ACTIVE_ADMIN');
    }

    if (!query) return items;

    return items.filter(
      (i) =>
        i.clinicName.toLowerCase().includes(query) ||
        i.organizationName.toLowerCase().includes(query) ||
        i.attentionReason.toLowerCase().includes(query) ||
        (i.adminEmail && i.adminEmail.toLowerCase().includes(query)),
    );
  });

  protected readonly revenueByMonth = computed<readonly RevenueByMonth[]>(
    () => this.overview()?.revenueByMonth ?? [],
  );

  protected readonly maxRevenueAmount = computed<number>(() => {
    const list = this.revenueByMonth();
    if (list.length === 0) return 1;
    const max = Math.max(...list.map((r) => Number(r.amount) || 0));
    return max > 0 ? max : 1;
  });

  protected readonly clinicsByStatus = computed<readonly ClinicsByStatus[]>(
    () => this.overview()?.clinicsByStatus ?? [],
  );

  protected readonly aiUsage = computed<readonly ClinicAiUsageQuota[]>(
    () => this.overview()?.aiUsage ?? [],
  );

  protected readonly recentAuditEvents = computed<readonly PlatformAuditEvent[]>(
    () => this.overview()?.recentAuditEvents ?? [],
  );

  ngOnInit(): void {
    this.loadDashboard();
  }

  protected async loadDashboard(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const data = await this.platformApi.dashboardOverview();
      this.overview.set(data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load operator dashboard overview.';
      this.error.set(msg);
    } finally {
      this.loading.set(false);
    }
  }

  protected formatJod(amount: number | string | undefined | null): string {
    const num = Number(amount) || 0;
    return `${num.toFixed(3)} JOD`;
  }

  protected formatDate(instantStr: string | null | undefined): string {
    if (!instantStr) return '—';
    try {
      const d = new Date(instantStr);
      return d.toLocaleDateString(this.i18n.locale() === 'ar' ? 'ar-JO' : 'en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      });
    } catch {
      return instantStr;
    }
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
      });
    } catch {
      return instantStr;
    }
  }

  protected statusTone(status: string): StatusTone {
    switch (status?.toUpperCase()) {
      case 'ACTIVE':
        return 'ok';
      case 'TRIAL':
        return 'human';
      case 'GRACE':
        return 'warn';
      case 'SUSPENDED':
      case 'EXPIRED':
        return 'bad';
      default:
        return 'neutral';
    }
  }

  protected severityTone(severity: string): StatusTone {
    switch (severity?.toUpperCase()) {
      case 'CRITICAL':
        return 'bad';
      case 'WARNING':
        return 'warn';
      case 'INFO':
      default:
        return 'neutral';
    }
  }

  protected outcomeTone(outcome: string): StatusTone {
    return outcome?.toUpperCase() === 'ALLOWED' ? 'ok' : 'bad';
  }

  protected getRevenueBarHeight(amount: number): number {
    const max = this.maxRevenueAmount();
    if (max <= 0) return 4;
    const pct = Math.round(((Number(amount) || 0) / max) * 100);
    return Math.max(pct, 6);
  }

  protected getAiQuotaProgressTone(percent: number): StatusTone {
    if (percent >= 90) return 'bad';
    if (percent >= 75) return 'warn';
    return 'ok';
  }
}
