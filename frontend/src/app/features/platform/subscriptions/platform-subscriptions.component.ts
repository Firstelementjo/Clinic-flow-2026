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
  PlatformSubscriptionOverview,
  PlatformPaymentRecord,
  RecordPaymentPayload,
  PlatformTransitionJobResult,
} from '../../../core/api/endpoints';
import { I18nService } from '../../../core/i18n/i18n.service';
import { IconComponent } from '../../../ui/icon.component';
import { StatusChipComponent, StatusTone } from '../../../ui/status-chip.component';

type SubscriptionFilterStatus = 'ALL' | 'ACTIVE' | 'TRIAL' | 'GRACE' | 'SUSPENDED' | 'EXPIRED';

@Component({
  selector: 'fe-platform-subscriptions',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, StatusChipComponent],
  templateUrl: './platform-subscriptions.component.html',
  styleUrl: './platform-subscriptions.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformSubscriptionsComponent implements OnInit {
  private readonly platformApi = inject(PlatformApi);
  protected readonly i18n = inject(I18nService);

  // Data signals
  protected readonly subscriptions = signal<readonly PlatformSubscriptionOverview[]>([]);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);

  // Search & filter
  protected readonly searchQuery = signal<string>('');
  protected readonly statusFilter = signal<SubscriptionFilterStatus>('ALL');

  // KPI Metrics
  protected readonly totalClinics = computed(() => this.subscriptions().length);
  protected readonly activeCount = computed(() => this.subscriptions().filter(s => s.status === 'ACTIVE').length);
  protected readonly graceCount = computed(() => this.subscriptions().filter(s => s.status === 'GRACE').length);
  protected readonly suspendedCount = computed(() => this.subscriptions().filter(s => s.status === 'SUSPENDED').length);
  protected readonly expiredCount = computed(() => this.subscriptions().filter(s => s.status === 'EXPIRED').length);
  protected readonly totalRevenueJod = computed(() =>
    this.subscriptions().reduce((sum, s) => sum + (Number(s.totalPaidAmount) || 0), 0).toFixed(3)
  );

  // Filtered list
  protected readonly filteredSubscriptions = computed(() => {
    const query = this.searchQuery().trim().toLowerCase();
    const filter = this.statusFilter();

    return this.subscriptions().filter((sub) => {
      if (filter !== 'ALL' && sub.status !== filter) {
        return false;
      }
      if (!query) return true;
      return (
        sub.clinicName.toLowerCase().includes(query) ||
        sub.clinicSlug.toLowerCase().includes(query) ||
        sub.city.toLowerCase().includes(query) ||
        sub.planCode.toLowerCase().includes(query) ||
        sub.planName.toLowerCase().includes(query)
      );
    });
  });

  // Record Payment Modal State
  protected readonly paymentModalOpen = signal<boolean>(false);
  protected readonly selectedSubscriptionForPayment = signal<PlatformSubscriptionOverview | null>(null);
  protected readonly paymentSubmitting = signal<boolean>(false);
  protected readonly paymentError = signal<string | null>(null);
  protected paymentAmount = 120;
  protected paymentMethod: 'CASH' | 'BANK_TRANSFER' | 'CLIQ' | 'OTHER' = 'CLIQ';
  protected paymentRef = '';
  protected paymentMonths = 1;
  protected paymentNotes = '';
  protected paymentIdempotencyKey = '';

  // Ledger Modal State
  protected readonly ledgerModalOpen = signal<boolean>(false);
  protected readonly selectedSubscriptionForLedger = signal<PlatformSubscriptionOverview | null>(null);
  protected readonly ledgerRecords = signal<readonly PlatformPaymentRecord[]>([]);
  protected readonly ledgerLoading = signal<boolean>(false);
  protected readonly ledgerError = signal<string | null>(null);

  // Reversal Modal State
  protected readonly reversalModalOpen = signal<boolean>(false);
  protected readonly selectedPaymentForReversal = signal<PlatformPaymentRecord | null>(null);
  protected readonly reversalSubmitting = signal<boolean>(false);
  protected readonly reversalError = signal<string | null>(null);
  protected reversalReason = '';

  // Extend Modal State
  protected readonly extendModalOpen = signal<boolean>(false);
  protected readonly selectedSubscriptionForExtend = signal<PlatformSubscriptionOverview | null>(null);
  protected readonly extendSubmitting = signal<boolean>(false);
  protected readonly extendError = signal<string | null>(null);
  protected extendDays = 30;
  protected extendReason = 'Customer subscription renewal';

  // Transition Job State
  protected readonly transitionJobRunning = signal<boolean>(false);
  protected readonly transitionJobResult = signal<PlatformTransitionJobResult | null>(null);

  ngOnInit(): void {
    this.loadSubscriptions();
  }

  async loadSubscriptions(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const data = await this.platformApi.subscriptions();
      this.subscriptions.set(data);
    } catch (err: unknown) {
      this.error.set(err instanceof Error ? err.message : 'Failed to load clinic subscriptions.');
    } finally {
      this.loading.set(false);
    }
  }

  // Record Payment
  openRecordPayment(sub: PlatformSubscriptionOverview): void {
    this.selectedSubscriptionForPayment.set(sub);
    this.paymentAmount = sub.planCode === 'STARTER' ? 50 : sub.planCode === 'GROWTH' ? 120 : 250;
    this.paymentMethod = 'CLIQ';
    this.paymentRef = '';
    this.paymentMonths = 1;
    this.paymentNotes = '';
    this.paymentIdempotencyKey = 'PAY-' + Math.random().toString(36).substring(2, 10).toUpperCase();
    this.paymentError.set(null);
    this.paymentModalOpen.set(true);
  }

  closePaymentModal(): void {
    this.paymentModalOpen.set(false);
    this.selectedSubscriptionForPayment.set(null);
  }

  regenerateIdempotencyKey(): void {
    this.paymentIdempotencyKey = 'PAY-' + Math.random().toString(36).substring(2, 10).toUpperCase();
  }

  async submitPayment(): Promise<void> {
    const sub = this.selectedSubscriptionForPayment();
    if (!sub) return;

    if (!this.paymentIdempotencyKey.trim()) {
      this.paymentError.set('Idempotency key is required.');
      return;
    }
    if (this.paymentAmount <= 0) {
      this.paymentError.set('Payment amount must be greater than zero.');
      return;
    }

    this.paymentSubmitting.set(true);
    this.paymentError.set(null);

    try {
      const payload: RecordPaymentPayload = {
        idempotencyKey: this.paymentIdempotencyKey.trim(),
        amount: this.paymentAmount,
        currency: 'JOD',
        paymentMethod: this.paymentMethod,
        referenceNumber: this.paymentRef.trim() || 'N/A',
        periodMonths: this.paymentMonths,
        notes: this.paymentNotes.trim() || undefined,
      };

      await this.platformApi.recordPayment(sub.projectId, payload);
      this.closePaymentModal();
      await this.loadSubscriptions();
    } catch (err: unknown) {
      this.paymentError.set(err instanceof Error ? err.message : 'Failed to record payment.');
    } finally {
      this.paymentSubmitting.set(false);
    }
  }

  // Ledger
  async openLedger(sub: PlatformSubscriptionOverview): Promise<void> {
    this.selectedSubscriptionForLedger.set(sub);
    this.ledgerModalOpen.set(true);
    this.ledgerLoading.set(true);
    this.ledgerError.set(null);
    try {
      const records = await this.platformApi.clinicPayments(sub.projectId);
      this.ledgerRecords.set(records);
    } catch (err: unknown) {
      this.ledgerError.set(err instanceof Error ? err.message : 'Failed to load ledger records.');
    } finally {
      this.ledgerLoading.set(false);
    }
  }

  closeLedgerModal(): void {
    this.ledgerModalOpen.set(false);
    this.selectedSubscriptionForLedger.set(null);
  }

  // Reversal
  openReversal(payment: PlatformPaymentRecord): void {
    this.selectedPaymentForReversal.set(payment);
    this.reversalReason = '';
    this.reversalError.set(null);
    this.reversalModalOpen.set(true);
  }

  closeReversalModal(): void {
    this.reversalModalOpen.set(false);
    this.selectedPaymentForReversal.set(null);
  }

  async submitReversal(): Promise<void> {
    const payment = this.selectedPaymentForReversal();
    const sub = this.selectedSubscriptionForLedger();
    if (!payment || !sub) return;

    if (!this.reversalReason.trim()) {
      this.reversalError.set('A reversal reason is required.');
      return;
    }

    this.reversalSubmitting.set(true);
    this.reversalError.set(null);

    try {
      await this.platformApi.reversePayment(sub.projectId, payment.id, this.reversalReason.trim());
      this.closeReversalModal();
      // Reload ledger
      const records = await this.platformApi.clinicPayments(sub.projectId);
      this.ledgerRecords.set(records);
      await this.loadSubscriptions();
    } catch (err: unknown) {
      this.reversalError.set(err instanceof Error ? err.message : 'Failed to reverse payment.');
    } finally {
      this.reversalSubmitting.set(false);
    }
  }

  // Extend
  openExtend(sub: PlatformSubscriptionOverview): void {
    this.selectedSubscriptionForExtend.set(sub);
    this.extendDays = 30;
    this.extendReason = 'Subscription manual extension';
    this.extendError.set(null);
    this.extendModalOpen.set(true);
  }

  closeExtendModal(): void {
    this.extendModalOpen.set(false);
    this.selectedSubscriptionForExtend.set(null);
  }

  async submitExtend(): Promise<void> {
    const sub = this.selectedSubscriptionForExtend();
    if (!sub) return;

    if (this.extendDays <= 0) {
      this.extendError.set('Extension days must be greater than zero.');
      return;
    }
    if (!this.extendReason.trim()) {
      this.extendError.set('Reason is required.');
      return;
    }

    this.extendSubmitting.set(true);
    this.extendError.set(null);

    try {
      await this.platformApi.extendSubscription(sub.projectId, this.extendDays, this.extendReason.trim());
      this.closeExtendModal();
      await this.loadSubscriptions();
    } catch (err: unknown) {
      this.extendError.set(err instanceof Error ? err.message : 'Failed to extend subscription.');
    } finally {
      this.extendSubmitting.set(false);
    }
  }

  // Transition Job
  async triggerTransitionJob(): Promise<void> {
    this.transitionJobRunning.set(true);
    this.transitionJobResult.set(null);
    try {
      const res = await this.platformApi.runTransitionJob();
      this.transitionJobResult.set(res);
      await this.loadSubscriptions();
    } catch (err: unknown) {
      this.error.set(err instanceof Error ? err.message : 'Failed to run transition job.');
    } finally {
      this.transitionJobRunning.set(false);
    }
  }

  closeJobResultModal(): void {
    this.transitionJobResult.set(null);
  }

  // Status Chip Helpers
  statusTone(status: string): StatusTone {
    switch (status) {
      case 'ACTIVE':
        return 'ok';
      case 'TRIAL':
        return 'neutral';
      case 'GRACE':
        return 'warn';
      case 'SUSPENDED':
      case 'EXPIRED':
        return 'bad';
      default:
        return 'neutral';
    }
  }

  statusLabel(status: string): string {
    const ar = this.i18n.locale() === 'ar';
    switch (status) {
      case 'ACTIVE':
        return ar ? 'نشط' : 'Active';
      case 'TRIAL':
        return ar ? 'تجريبي' : 'Trial';
      case 'GRACE':
        return ar ? 'فترة سماح' : 'Grace Period';
      case 'SUSPENDED':
        return ar ? 'موقوف' : 'Suspended';
      case 'EXPIRED':
        return ar ? 'منتهي' : 'Expired';
      default:
        return status;
    }
  }

  formatDate(dateStr: string | null): string {
    if (!dateStr) return '—';
    try {
      const d = new Date(dateStr);
      return d.toLocaleDateString(this.i18n.intlLocale(), {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
      });
    } catch {
      return dateStr;
    }
  }
}
