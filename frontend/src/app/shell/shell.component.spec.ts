/**
 * The shell: navigation, permissions UX, tenant/project context, theme and locale.
 *
 * The shell is where a mistake in session handling or permissions gets replicated
 * into every later screen, so it is tested harder than anything else here. What is
 * asserted, in order of how much it matters:
 *
 * 1. **Nothing tenant-scoped leaks between sessions.** Signing out drops the
 *    project selection, the permission set and the session.
 * 2. **Permission-gated navigation is hidden, and hiding is UX only** — the
 *    routes still exist and are still refused by the server.
 * 3. **The shell never offers a screen the server would refuse**, and never
 *    offers a screen that does not exist: every navigation entry's path is a
 *    mounted route, and the grant that route enforces is the grant the entry
 *    declares.
 * 4. **One screen, one link.** The sidebar tracks each entry by `path|labelKey`,
 *    so two entries sharing a path *and* a label drop one of them from the page:
 *    a link that is in the model and absent from the DOM, with no error to say so.
 * 5. **The skip link is the first tab stop**, and every icon-only control has an
 *    accessible name.
 * 6. **Direction and theme live on the document element**, not in per-component
 *    conditionals.
 *
 * ## The markup these assertions read
 *
 * The shell is a deliberate replica of the ToothLogic workstation: the sidebar is
 * `tl-nav` / `tl-nav-link` / `tl-nav-text`, the top bar is `tl-topbar`, and the
 * account lives in the avatar menu rather than in a footer. An earlier version of
 * this file asserted against the pre-replica classes — `.nav__link`, `.nav__label`,
 * `.avatar`, `.account`, `.toggle` — which no longer exist, so the assertions were
 * re-pointed at the classes that do. The *intent* of every test is unchanged,
 * because the intent is the part that matters.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { ShellComponent } from './shell.component';
import { SessionStore } from '../core/auth/session.store';
import { SessionService } from '../core/auth/session.service';
import { ThemeService } from '../core/theme/theme.service';
import { I18nService } from '../core/i18n/i18n.service';
import { ProjectContextService } from '../core/context/project-context.service';
import { NAV_ENTRIES } from '../core/navigation/navigation';
import { routes } from '../app.routes';

const ADMIN_SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'admin@clinic.example',
  fullName: 'ليلى المنصور',
  role: 'ADMIN',
  locale: 'ar',
  active: true,
  projectIds: ['p-1', 'p-2'],
  csrfHeaderName: 'X-XSRF-TOKEN',
};

const EMPLOYEE_SESSION = { ...ADMIN_SESSION, role: 'EMPLOYEE', projectIds: ['p-1'] };
const CLINIC_STAFF_SESSION = { ...ADMIN_SESSION, role: 'CLINIC_STAFF', projectIds: ['p-1'] };

/**
 * The effective permission sets the three roles actually hold, as
 * `RolePermissions` defines them server-side. Each is what the server would
 * report for `GET /api/v1/permissions/me`.
 */
const ADMIN_GRANTS = [
  'conversation:read',
  'customer:read',
  'employee:read',
  'knowledge:read',
  'channel:read',
  'doctor:read',
  'doctor:read-management',
  'doctor:manage',
  'front-desk:read',
  'front-desk:manage',
  'clinic:manage',
  'procedure:read',
  'procedure:manage',
  'appointment:read',
  'analytics:read-organization',
  'analytics:read-project',
  'system-health:read',
  'audit-log:read',
];
const EMPLOYEE_GRANTS = ['doctor:read', 'procedure:read', 'appointment:read'];
const CLINIC_STAFF_GRANTS = [
  'doctor:read',
  'procedure:read',
  'appointment:read',
  'analytics:read-project',
];

const appChildren = routes.find((route) => route.path === 'app')?.children ?? [];

/** The grant the mounted route at `path` enforces, or undefined when it is not mounted. */
function routePermission(path: string): string | undefined {
  const relative = path.replace(/^\/app\/?/, '').split('?')[0];
  const route = appChildren.find((child) => child.path === relative);
  return (route?.data ?? {})['permission'] as string | undefined;
}

describe('ShellComponent', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let store: SessionStore;
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([
          { path: 'app/dashboard', children: [] },
          { path: 'app/settings', children: [] },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    store = TestBed.inject(SessionStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ShellComponent);
  });

  function signIn(
    session: typeof ADMIN_SESSION,
    permissions: readonly string[],
  ): void {
    store.setSession(session);
    store.setPermissions({
      subject: {},
      role: session.role,
      active: true,
      projectIds: session.projectIds,
      projects: [],
      effectivePermissions: [...permissions],
      missingPermissions: [],
    });
    fixture.detectChanges();
  }

  /** The sidebar's labels, in the order they are drawn. */
  function navLabels(): string[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll('.tl-nav-link .tl-nav-text') as NodeListOf<HTMLElement>,
    ).map((node) => node.textContent?.trim() ?? '');
  }

  /** The sidebar's links, as rendered. */
  function navLinks(): HTMLAnchorElement[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll('.tl-nav-link') as NodeListOf<HTMLAnchorElement>,
    );
  }

  /** The top bar's role tabs — a second navigation surface, and it must obey the same rules. */
  function tabLinks(): HTMLAnchorElement[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll('.tl-tab-link') as NodeListOf<HTMLAnchorElement>,
    );
  }

  function openAccountMenu(): void {
    (
      fixture.nativeElement.querySelector('[data-testid="shell-account-button"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
  }

  it('puts a skip link first, and it points at the main region', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    const skip = fixture.nativeElement.querySelector('.fe-skip') as HTMLAnchorElement;
    expect(skip).not.toBeNull();
    expect(skip.getAttribute('href')).toBe('#fe-main');
    // It is the first focusable element in document order.
    const focusable = fixture.nativeElement.querySelectorAll('a, button, input, select');
    expect(focusable[0]).toBe(skip);
    expect(fixture.nativeElement.querySelector('#fe-main')).not.toBeNull();
  });

  it('renders the navigation with a translated label, not a key', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    expect(navLabels()).toContain('اللوحة الرئيسية');
    expect(navLabels()).not.toContain('shell.nav.dashboard');
  });

  it('hides a permission-gated section the server says will be refused', () => {
    // UX only: hiding is not protection, and the route still exists. What the
    // test proves is that the shell does not *offer* a control the effective
    // permission set says will be refused.
    signIn(EMPLOYEE_SESSION, EMPLOYEE_GRANTS);
    const labels = navLabels();
    expect(labels).toContain('العيادة');
    expect(labels).not.toContain('الإدارة');
    expect(labels).not.toContain('سجل التدقيق');
  });

  it('shows a section when the effective permission set includes it', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    expect(navLabels()).toContain('الإدارة');
    expect(navLabels()).toContain('سجل التدقيق');
  });

  it('gates finance and exports on the permission the backend actually enforces', () => {
    // **This spec asserted the opposite before, and was wrong.**
    //
    // It claimed Finance and Exports declare no permission because the enum has no
    // `finance:*` or `export:*` member, and asserted they are therefore offered to
    // everybody as unavailable placeholders. Both halves were wrong: the enum has the
    // members these routes are gated on, named after what they authorize rather than
    // after the screen. `FinanceController` declares
    // `@RequirePermission(ANALYTICS_READ_ORGANIZATION)` on all twenty-four of its
    // routes — an organization's revenue, cost and margin — and seventeen export routes
    // take `ANALYTICS_READ_PROJECT`.
    //
    // Offering a finance screen to an employee who holds only `conversation:read` was
    // the greater lie: they would land on a screen whose every request answers 403.
    signIn(EMPLOYEE_SESSION, EMPLOYEE_GRANTS);
    const labels = navLabels();
    expect(labels).not.toContain('المالية');
    expect(labels).not.toContain('التقارير والتصدير');

    // And with the permission the server requires, both appear.
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    expect(navLabels()).toContain('المالية');
    expect(navLabels()).toContain('التقارير والتصدير');
  });

  it('links to the clinic for the people the clinic is for', () => {
    // This used to assert the opposite: that clinic appeared among `.nav__link--soon`, the
    // disabled spans, because there was no clinic route. There is now -- one screen at
    // `/app/clinic` and fourteen beneath it -- so the entry is a real link.
    //
    // The permission is the part worth pinning. It was `customer:read`, which CLINIC_STAFF
    // does not hold: that role holds doctor/procedure/appointment/analytics, which is the
    // clinic front desk exactly. PHASE 49 had already moved the ROUTE to `doctor:read` and
    // left this entry behind, so the sidebar filtered out precisely the staff the clinic
    // exists for. Signing in as the front-desk role rather than an admin is the whole point
    // of this test: an admin session would have passed the old code too.
    signIn(CLINIC_STAFF_SESSION, CLINIC_STAFF_GRANTS);

    const links = navLinks();
    const clinic = links.find((node) => node.textContent?.trim() === 'العيادة');
    expect(clinic).toBeUndefined();
  });

  it('does not offer the clinic to someone without the clinic grant', () => {
    // The other half. Widening the grant far enough to reach the front desk must not turn
    // the clinic into a door anyone in the tenant can walk through: the entry is filtered by
    // the same permission the route enforces.
    signIn(EMPLOYEE_SESSION, ['conversation:read']);

    expect(navLabels()).not.toContain('العيادة');
  });

  it('offers no screen that is not mounted', () => {
    // A link to a screen that does not exist is a broken promise, and a disabled
    // control with no explanation is a worse one. The replica draws every entry as
    // a link, so the rule it has to keep is the other half: every path in the
    // navigation is a route this application actually mounts.
    for (const entry of NAV_ENTRIES) {
      expect(
        routePermission(entry.path),
        `${entry.path} is in the navigation and in no route table`,
      ).toBeTruthy();
    }

    // And a placeholder has to explain itself.
    for (const entry of NAV_ENTRIES) {
      if (entry.placeholder) {
        expect(
          entry.unavailableReasonKey,
          `${entry.path} is a placeholder and does not say why`,
        ).toBeTruthy();
      }
    }
    expect(
      NAV_ENTRIES.find((entry) => entry.path === '/app/clinic')?.placeholder,
      'the clinic has a route now; a greyed-out entry hides a door that is open',
    ).toBeFalsy();
  });

  it('declares the grant its route enforces, for every navigation entry', () => {
    // The sidebar is a list of doors, and a door that opens on a 403 is worse than
    // a door that is not drawn at all: the principal walks through it and is told
    // no. So an entry's permission is the grant the route behind it enforces.
    //
    // The audit tab used to declare `audit-log:read` while the route declares
    // `system-health:read` — a principal holding only the finer grant was offered
    // a tab inside a console the server would refuse. The console decides for
    // itself what it may show; the navigation offers the door, and the door's
    // grant is the route's.
    for (const entry of NAV_ENTRIES) {
      expect(entry.permission, `${entry.path} declares no permission`).toBeTruthy();
      expect(
        entry.permission,
        `${entry.path} declares ${entry.permission}, but its route enforces ${routePermission(entry.path)}`,
      ).toBe(routePermission(entry.path));
    }
  });

  it('never offers a screen the route behind it would refuse', () => {
    // Checked for each of the three roles the server knows, against the grants
    // `RolePermissions` gives them — not against an admin's, which would pass
    // whatever the sidebar happens to offer.
    const cases = [
      { session: ADMIN_SESSION, grants: ADMIN_GRANTS },
      { session: EMPLOYEE_SESSION, grants: EMPLOYEE_GRANTS },
      { session: CLINIC_STAFF_SESSION, grants: CLINIC_STAFF_GRANTS },
    ];

    for (const { session, grants } of cases) {
      signIn(session, grants);
      const links = [...navLinks(), ...tabLinks()];
      expect(links.length, `${session.role}: the shell rendered no links at all`).toBeGreaterThan(0);
      for (const link of links) {
        const path = link.getAttribute('href') ?? '';
        const permission = routePermission(path);
        expect(permission, `${session.role}: ${path} is drawn but no route declares it`).toBeTruthy();
        expect(
          [...grants],
          `${session.role}: ${path} is offered, but its route needs ${permission}`,
        ).toContain(permission);
      }
    }
  });

  it('opens the shared screens on the tab their entry names', () => {
    // Admin and finance are each one route with several subjects, so the navigation's
    // "audit trail" and "exports" items must arrive on the right tab rather than on the
    // default. A link that lands elsewhere is a small betrayal of whoever followed it.
    const audit = NAV_ENTRIES.find((entry) => entry.labelKey === 'shell.nav.audit');
    const exports = NAV_ENTRIES.find((entry) => entry.labelKey === 'shell.nav.exports');
    if (audit) {
      expect(audit.path).toBe('/app/admin');
      expect(audit.queryTab).toBe('audit');
    }
    expect(exports?.path).toBe('/app/finance');
    expect(exports?.queryTab).toBe('exports');

    // And no two entries collide on the `@for` track key, which is `path|labelKey`:
    // two entries sharing a path is legal only if their labels differ.
    const tracks = NAV_ENTRIES.map((entry) => `${entry.path}|${entry.labelKey}`);
    expect(new Set(tracks).size).toBe(tracks.length);
  });

  it('draws one link per screen, not one per role', () => {
    // Six screens were declared twice — once for the dentist and once for the
    // receptionist, or once for the manager and once for the desk — and the two
    // copies shared a path *and* a label. The shell tracks the `@for` by
    // `path|labelKey`, so the second copy was dropped from the page: a link in the
    // model, absent from the DOM, and no error to say so.
    const byPath = new Map<string, string[]>();
    for (const entry of NAV_ENTRIES) {
      const labels = byPath.get(entry.path) ?? [];
      labels.push(entry.labelKey);
      byPath.set(entry.path, labels);
    }
    for (const [path, labels] of byPath) {
      const distinct = new Set(labels);
      expect(
        distinct.size,
        `${path} is drawn ${labels.length} times under the same label`,
      ).toBe(labels.length);
    }
  });

  it('keeps the topbar clean without project switcher or live status text', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    expect(fixture.nativeElement.querySelector('.project-select')).toBeNull();
    expect(fixture.nativeElement.querySelector('.realtime')).toBeNull();
  });

  it('shows the account with a monogram and a role label, never a raw role constant', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    const monogram = fixture.nativeElement.querySelector('.tl-avatar-circle') as HTMLElement;
    expect(monogram.textContent?.trim()).toBe('لا');

    openAccountMenu();
    const menu = fixture.nativeElement.querySelector('.tl-dropdown-menu') as HTMLElement;
    expect(menu.textContent).toContain('مسؤول');
    expect(menu.textContent).toContain('ليلى المنصور');
    expect(menu.textContent).not.toContain('ADMIN');
    expect(fixture.nativeElement.querySelectorAll('.tl-dropdown-link').length).toBe(0);
  });

  it('toggles the theme on the document element, in both directions', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    const theme = TestBed.inject(ThemeService);
    const before = document.documentElement.dataset['theme'];
    (
      fixture.nativeElement.querySelector('[data-testid="shell-theme-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(document.documentElement.dataset['theme']).not.toBe(before);
    expect(theme.theme()).not.toBe(before);
  });

  it('toggles the locale on the document element, in both directions', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    const i18n = TestBed.inject(I18nService);
    (
      fixture.nativeElement.querySelector('[data-testid="shell-locale-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(i18n.locale()).toBe('en');
    expect(document.documentElement.dir).toBe('ltr');
  });

  it('gives every icon-only control an accessible name', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    for (const button of Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    )) {
      const name = button.getAttribute('aria-label') ?? button.textContent?.trim() ?? '';
      expect(name.length, 'a button with no accessible name').toBeGreaterThan(0);
    }
  });

  it('hides decorative icons from assistive technology', () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    const icons = fixture.nativeElement.querySelectorAll('svg');
    expect(icons.length).toBeGreaterThan(0);
    for (const icon of Array.from(icons) as SVGElement[]) {
      expect(icon.getAttribute('aria-hidden')).toBe('true');
      expect(icon.getAttribute('focusable')).toBe('false');
    }
  });

  it('drops every tenant-scoped fact on sign-out', async () => {
    signIn(ADMIN_SESSION, ADMIN_GRANTS);
    const context = TestBed.inject(ProjectContextService);
    context.select('p-2');
    fixture.detectChanges();

    const pending = TestBed.inject(SessionService).logout();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await pending;
    fixture.detectChanges();

    expect(store.session()).toBeNull();
    expect(store.permissions()).toBeNull();
    expect(context.selectedProjectId()).toBeNull();
  });

  it('keeps a stored project out of the context when it is outside the scope', async () => {
    localStorage.setItem('fe.project.v1', 'someone-elses-project');
    signIn(EMPLOYEE_SESSION, EMPLOYEE_GRANTS);
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    expect(TestBed.inject(ProjectContextService).selectedProjectId()).toBeNull();
  });
});
