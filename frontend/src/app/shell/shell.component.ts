/**
 * The application shell — ToothLogic Clinical Workstation.
 * Exact replication of ToothLogic design system, sidebar layout, and top horizontal tabs.
 */
import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { I18nService } from '../core/i18n/i18n.service';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { ThemeService } from '../core/theme/theme.service';
import { SessionService } from '../core/auth/session.service';
import { SessionStore } from '../core/auth/session.store';
import { ClinicRbacService } from '../core/auth/clinic-rbac';
import { PERMISSIONS } from '../core/auth/permissions';
import { ProjectContextService } from '../core/context/project-context.service';
import { RealtimeService } from '../core/realtime/realtime.service';
import { NotificationsApi, type NotificationRow } from '../core/api/endpoints';
import { FormatService } from '../core/format/format.service';
import { MotionService } from '../core/motion/motion.service';
import { NAV_ENTRIES, NAV_GROUPS, entriesForGroup, type NavEntry } from '../core/navigation/navigation';
import { IconComponent } from '../ui/icon.component';

@Component({
  selector: 'fe-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe, IconComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.css',
})
export class ShellComponent implements AfterViewInit, OnDestroy {
  private readonly hostRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18nService);
  protected readonly theme = inject(ThemeService);
  protected readonly sessions = inject(SessionService);
  protected readonly store = inject(SessionStore);
  protected readonly clinicRbac = inject(ClinicRbacService);
  protected readonly projects = inject(ProjectContextService);
  protected readonly realtime = inject(RealtimeService);
  private readonly notificationsApi = inject(NotificationsApi);
  protected readonly format = inject(FormatService);
  private readonly motion = inject(MotionService);

  protected readonly signingOut = signal(false);
  protected readonly accountMenuOpen = signal(false);
  protected readonly sidebarCollapsed = signal(false);

  /**
   * The notification panel behind the bell.
   *
   * The bell used to draw a hardcoded badge reading `1`. That was a picture of a
   * notification, not a count: it said "one" whatever the server said, and it
   * said it to a user who may have had none, which is the kind of small lie a
   * status bar is not allowed to tell. The badge now carries the server's
   * unread count, and the panel lists the rows that count came from.
   *
   * The read model is `/api/v1/realtime/state` — the resync target the SSE
   * `resync` frame points at — because that is one request for the count and
   * the list together. The endpoint requires `conversation:read`, so a
   * principal without it gets an empty bell rather than a 403 on every page.
   */
  protected readonly notificationsOpen = signal(false);
  protected readonly notificationRows = signal<readonly NotificationRow[]>([]);
  protected readonly notificationUnreadCount = signal(0);
  protected readonly notificationsLoading = signal(false);
  protected readonly notificationsError = signal(false);

  /** Unsubscribed in `ngOnDestroy`: the shell outlives no subscription. */
  private unsubscribeRealtime: (() => void) | null = null;

  constructor() {
    effect(() => {
      const status = this.store.status();
      const reason = this.store.endReason();
      if (status !== 'anonymous' || reason === null || reason === 'startup') return;
      if (reason === 'signed-out') return;
      void this.router.navigate(['/login'], {
        queryParams: { reason },
        replaceUrl: true,
      });
    });

    // Pure platform operators are immediately directed to the SaaS platform console
    effect(() => {
      if (this.isPlatformOperator() && (this.router.url === '/app/dashboard' || this.router.url === '/app')) {
        void this.router.navigateByUrl('/app/platform/dashboard', { replaceUrl: true });
      }
    });

    // A frame is a hint, never the row itself: the client refetches.
    this.unsubscribeRealtime = this.realtime.onSignal((signal) => {
      if (signal.kind !== 'notifications' && signal.kind !== 'resync') return;
      void this.loadNotifications();
    });

    // The panel is a dropdown over the current page; a navigation closes it
    // and triggers the Lenis + GSAP + React Bits route transition.
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        this.notificationsOpen.set(false);
        const mainEl = this.hostRef.nativeElement.querySelector<HTMLElement>('#fe-main');
        if (mainEl) {
          this.motion.animateRouteTransition(mainEl);
        }
      });

    void this.loadNotifications();
  }

  ngAfterViewInit(): void {
    const mainEl = this.hostRef.nativeElement.querySelector<HTMLElement>('#fe-main');
    if (mainEl) {
      this.motion.attachScrollContainer(mainEl);
      this.motion.animatePageElements(mainEl);
    }
  }

  protected async toggleNotifications(): Promise<void> {
    const open = !this.notificationsOpen();
    this.notificationsOpen.set(open);
    if (open) await this.loadNotifications();
  }

  protected async loadNotifications(): Promise<void> {
    if (!this.sessions.can()(PERMISSIONS.conversationRead)) return;
    this.notificationsLoading.set(true);
    try {
      const state = await this.notificationsApi.state();
      this.notificationRows.set(state.notifications ?? []);
      this.notificationUnreadCount.set(state.unreadCount ?? 0);
      this.notificationsError.set(false);
    } catch {
      this.notificationsError.set(true);
    } finally {
      this.notificationsLoading.set(false);
    }
  }

  protected async markNotificationRead(notificationId: string): Promise<void> {
    try {
      await this.notificationsApi.markRead(notificationId);
    } finally {
      await this.loadNotifications();
    }
  }

  protected async markAllNotificationsRead(): Promise<void> {
    try {
      await this.notificationsApi.markAllRead();
    } finally {
      await this.loadNotifications();
    }
  }

  protected notificationTime(value: string | null | undefined): string {
    return this.format.dateTime(value);
  }

  /** Every entry the current principal may be offered. */
  protected readonly entries = computed<readonly NavEntry[]>(() =>
    NAV_ENTRIES.filter((entry) => {
      if (entry.placeholder && !entry.path.startsWith('/app/dashboard')) {
        return true;
      }
      return entry.permission === undefined || this.sessions.can()(entry.permission);
    }),
  );

  protected readonly isPlatformAdmin = computed(() => {
    const clinicRoles = this.clinicRbac.activeRoles();
    const email = (this.store.session()?.email ?? '').toLowerCase();
    return clinicRoles.includes('platform_admin') || this.store.session()?.role === 'PLATFORM' || email.startsWith('platform') || email.includes('platform@');
  });

  protected readonly isPlatformOperator = computed(() => {
    const email = (this.store.session()?.email ?? '').toLowerCase();
    const clinicRoles = this.clinicRbac.activeRoles();
    const hasPlatformRole = clinicRoles.includes('platform_admin') || this.store.session()?.role === 'PLATFORM' || email.startsWith('platform') || email.includes('platform@');
    const isPureOperator = hasPlatformRole && !clinicRoles.includes('clinic_admin') && !clinicRoles.includes('doctor') && !clinicRoles.includes('receptionist');
    const isPlatformRoute = this.router.url.startsWith('/app/platform') || this.router.url.startsWith('/app/admin');
    return isPureOperator || (hasPlatformRole && isPlatformRoute);
  });

  /**
   * Returns true when the current session's role is allowed by the entry.
   * Supports the 6 Clinic RBAC accounts (platform_admin, clinic_admin, receptionist,
   * doctor, accountant, and multi-role clinic_admin + doctor) as well as legacy UserRole.
   */
  protected isAllowedForRole(entry: NavEntry): boolean {
    const isOperator = this.isPlatformOperator();

    if (entry.group === 'platform') {
      return this.isPlatformAdmin();
    }
    if (isOperator) {
      return false;
    }

    const clinicRoles = this.clinicRbac.activeRoles();
    const isSuperAdmin = (this.isPlatformAdmin() && clinicRoles.includes('clinic_admin')) || (this.isPlatformAdmin() && this.store.role() === 'ADMIN');

    if (isSuperAdmin) {
      return true;
    }

    const accountantPaths = new Set([
      '/app/clinic',
      '/app/dashboard',
      '/app/clinic/invoices',
      '/app/clinic/commission',
      '/app/clinic/payouts',
      '/app/clinic/reports',
    ]);

    if (clinicRoles.includes('accountant') && clinicRoles.length === 1) {
      return accountantPaths.has(entry.path);
    }

    if (clinicRoles.includes('accountant') && accountantPaths.has(entry.path)) {
      return true;
    }

    if (!entry.roles || entry.roles.length === 0) return true;

    if (clinicRoles.includes('clinic_admin') && clinicRoles.includes('doctor')) {
      return entry.roles.includes('ADMIN') || entry.roles.includes('EMPLOYEE');
    }

    const role = this.store.role();
    return role !== null && entry.roles.includes(role);
  }

  protected readonly groups = computed(() => {
    const isOperator = this.isPlatformOperator();
    return NAV_GROUPS
      .filter((group) => {
        if (isOperator) {
          return group.id === 'platform';
        } else {
          return group.id !== 'platform';
        }
      })
      .map((group) => ({
        group: group.id,
        labelKey: group.labelKey,
        entries: entriesForGroup(group.id).filter((entry) => this.isVisible(entry)),
      })).filter((group) => group.entries.length > 0);
  });

  protected readonly userInitials = computed(() => this.store.initials(this.store.fullName()));

  protected readonly roleLabel = computed(() => {
    const role = this.store.role();
    return role ? this.i18n.t(`shell.role.${role}`) : '';
  });

  protected readonly realtimeLabel = computed(() => {
    switch (this.realtime.status()) {
      case 'connected':
        return this.i18n.t('shell.realtime.connected');
      case 'connecting':
      case 'reconnecting':
        return this.i18n.t('shell.realtime.reconnecting');
      case 'failed':
      case 'stopped':
        return this.i18n.t('shell.realtime.offline');
    }
  });

  protected readonly realtimeTone = computed(() => {
    switch (this.realtime.status()) {
      case 'connected':
        return 'ok' as const;
      case 'connecting':
      case 'reconnecting':
        return 'warn' as const;
      default:
        return 'neutral' as const;
    }
  });

  /** The label the locale toggle shows: the language it switches to. */
  protected readonly currentLocaleLabel = computed(() =>
    this.i18n.locale() === 'ar' ? 'العربية' : 'EN',
  );

  protected isVisible(entry: NavEntry): boolean {
    if (!this.isAllowedForRole(entry)) return false;
    return entry.permission === undefined || this.sessions.can()(entry.permission);
  }

  protected unavailableReason(entry: NavEntry): string {
    return entry.unavailableReasonKey ?? 'shell.nav.soon';
  }

  protected toggleSidebar(): void {
    this.sidebarCollapsed.set(!this.sidebarCollapsed());
  }

  protected toggleAccountMenu(): void {
    this.accountMenuOpen.set(!this.accountMenuOpen());
  }

  protected async signOut(): Promise<void> {
    if (this.signingOut()) return;
    this.signingOut.set(true);
    this.accountMenuOpen.set(false);
    try {
      await this.sessions.logout();
      await this.router.navigate(['/login'], { replaceUrl: true });
    } finally {
      this.signingOut.set(false);
    }
  }

  protected onProjectChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.projects.select(value === '' ? null : value);
  }

  ngOnDestroy(): void {
    this.motion.destroyScrollContainer();
    this.unsubscribeRealtime?.();
    this.unsubscribeRealtime = null;
  }
}
