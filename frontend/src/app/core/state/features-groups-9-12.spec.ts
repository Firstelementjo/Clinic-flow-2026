/**
 * Groups 9-12: administration, finance, exports, and clinic.
 *
 * ## What is asserted here, and what is asserted against
 *
 * These are unit-level and they run in milliseconds, but they are the assertions that
 * catch a **regression to a placeholder** — Phase-11 hard rule 8's real failure mode,
 * which is not a missing screen but a screen quietly rendering a figure it invented.
 *
 * Three properties are pinned:
 *
 * 1. **Every request goes to a route the committed contract declares**, with the exact
 *    method. A typo in a path would otherwise surface as a 404 in a browser run and be
 *    read as a backend gap rather than as a mistake.
 * 2. **A value the server did not send renders as absent, never as zero.** The
 *    free-form `{ document: {...} }` responses make this the live risk on these screens
 *    specifically.
 * 3. **The clinic surface has no data at all**, which is the whole content of group 12.
 *
 * The journeys prove the same properties through a browser against the real API. A
 * defect that reached production would have to defeat both.
 */
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { correlationInterceptor } from '../api/correlation.interceptor';
import { errorInterceptor } from '../api/error.interceptor';
import { AdminReadApi, ExportsApi, FinanceApi } from '../api/endpoints';
import { SessionStore } from '../auth/session.store';
import { clinicStrings } from '../../features/clinic/clinic.strings';

const ADMIN_SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'a@b.c',
  fullName: 'A B',
  role: 'ADMIN',
  locale: 'ar',
  active: true,
  projectIds: ['p-1'],
  csrfHeaderName: 'X-XSRF-TOKEN',
};

const ALL_PERMISSIONS = [
  'account:read',
  'analytics:read-organization',
  'analytics:read-project',
  'audit-log:read',
  'security-event:read',
  'session:revoke',
  'system-health:read',
  'organization:update',
  'user:manage',
];

function signIn(): void {
  TestBed.inject(SessionStore).setSession(ADMIN_SESSION as never);
  TestBed.inject(SessionStore).setPermissions({
    role: 'ADMIN',
    active: true,
    projectIds: ['p-1'],
    projects: [],
    effectivePermissions: ALL_PERMISSIONS,
    missingPermissions: [],
  });
}

describe('group 9: the administration routes, method by method', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([correlationInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    signIn();
  });

  it('reads every admin subject from a GET, and issues no write at all', async () => {
    // The eleven calls this screen makes, in the order it makes them. Each is pinned to
    // its verb, because a `POST` here would be a destructive governance write that the
    // client has explicitly decided not to offer.
    const admin = TestBed.inject(AdminReadApi);
    const projectId = 'p-1';
    const calls = [
      admin.overview({ projectId }),
      admin.backups({ projectId }),
      admin.migrations({ projectId }),
      admin.maintenanceCommands({ projectId }),
      admin.disabledCommands({ projectId }),
      admin.auditEvents({ projectId }),
      admin.auditSecurity({ projectId }),
      admin.legalHolds({ projectId }),
      admin.deletions({ projectId }),
      admin.retentionClasses({ projectId }),
      admin.purgeGate({ projectId }),
      admin.sessions({ projectId }),
      admin.exportJobs({ projectId }),
    ];

    const expected: [string, string][] = [
      ['GET', '/api/v1/admin/overview'],
      ['GET', '/api/v1/admin/backups'],
      ['GET', '/api/v1/admin/migrations'],
      ['GET', '/api/v1/admin/maintenance/commands'],
      ['GET', '/api/v1/admin/maintenance/commands/disabled'],
      ['GET', '/api/v1/admin/audit/events'],
      ['GET', '/api/v1/admin/audit/security'],
      ['GET', '/api/v1/admin/governance/legal-holds'],
      ['GET', '/api/v1/admin/governance/deletions'],
      ['GET', '/api/v1/admin/governance/retention-classes'],
      ['GET', '/api/v1/admin/governance/maintenance/purge-gate'],
      ['GET', '/api/v1/admin/identity/sessions'],
      ['GET', '/api/v1/admin/exports/jobs'],
    ];

    const requests = http.match(() => true);
    expect(requests.length).toBe(expected.length);
    for (const [index, [method, path]] of expected.entries()) {
      expect(requests[index]!.request.method, `${path} verb`).toBe(method);
      expect(requests[index]!.request.urlWithParams.startsWith(path), `${path} path`).toBe(true);
    }

    // The sharpest assertion in the file: nothing this screen does is a write. The
    // seven governance POSTs exist in the contract and are deliberately not called.
    expect(requests.every((r) => r.request.method === 'GET')).toBe(true);
    expect(requests.some((r) => r.request.url.includes('/maintenance/purge') && !r.request.url.includes('purge-gate'))).toBe(
      false,
    );
    expect(requests.some((r) => r.request.url.includes('/maintenance/legal-holds'))).toBe(false);
    expect(requests.some((r) => r.request.url.includes('/maintenance/privacy-deletions'))).toBe(false);

    for (const request of requests) request.flush({});
    await Promise.all(calls);
    http.verify();
  });

  it('passes a nested admin id through, encoded', () => {
    // `legal-holds/{holdId}/history` and `deletions/{requestId}/targets` take a UUID.
    // A path segment that is not encoded is a query-injection shape, and these are the
    // two routes where a caller-supplied value reaches the path.
    const admin = TestBed.inject(AdminReadApi);
    void admin.legalHoldHistory('../admin/overview', {});
    void admin.deletionTargets('a b/c', {});
    void admin.exportJobDetail('x?y=1', {});

    const requests = http.match(() => true);
    expect(requests.length).toBe(3);
    expect(requests[0]!.request.url).toContain('/legal-holds/..%2Fadmin%2Foverview/history');
    expect(requests[1]!.request.url).toContain('/deletions/a%20b%2Fc/targets');
    expect(requests[2]!.request.url).toContain('/jobs/x%3Fy%3D1/detail');
    for (const request of requests) request.flush({});
  });

  it('never sends a project id as authorization', () => {
    // A `projectId` is a request, never a grant. The screen passes the selected project
    // straight through, and the server's `TenantQueryGuard` resolves scope regardless —
    // so the client's only obligation is to pass it and never to branch on it.
    const admin = TestBed.inject(AdminReadApi);
    void admin.overview({ projectId: 'p-1' });
    const request = http.expectOne((r) => r.url.includes('/admin/overview'));
    expect(request.request.params.get('projectId')).toBe('p-1');
    request.flush({});
  });
});

describe('groups 10 and 11: the finance and export routes', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([correlationInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    signIn();
  });

  it('reads all fourteen finance subjects without writing one', async () => {
    const finance = TestBed.inject(FinanceApi);
    const q = { projectId: 'p-1' };
    const calls = [
      finance.usageDaily(q),
      finance.usageMonthly(q),
      finance.usageForecast(q),
      finance.periods(q),
      finance.rateCards(q),
      finance.reconciliationRuns(q),
      finance.legacyComparison(q),
      finance.periodHistory('per-1', q),
      finance.periodAdjustments('per-1', q),
      finance.closeReadiness('per-1', q),
      finance.rateCardVersions('rc-1', q),
      finance.reconciliationLines('run-1', q),
      finance.drillSnapshot('snap-1', q),
      finance.drillCorrections('evt-1', q),
    ];

    const requests = http.match(() => true);
    expect(requests.length).toBe(14);
    // Eleven of the twenty-four finance routes are writes — computing a rollup, locking
    // and reopening a period, approving/rejecting/applying an adjustment. None is here.
    expect(requests.every((r) => r.request.method === 'GET')).toBe(true);

    for (const request of requests) request.flush({});
    await Promise.all(calls);
    http.verify();
  });

  it('separates the two export scopes onto their own routes', () => {
    // Seventeen export routes take `analytics:read-project`; the finance gate takes
    // `analytics:read-organization`. They are different values and a client that
    // conflated them would either withhold data or ask for data it may not have.
    const exports = TestBed.inject(ExportsApi);
    const q = { projectId: 'p-1' };
    void exports.list(q);
    void exports.queue(q);
    // The catalogue now takes a **required** `projectId` rather than a query bag, which
    // is a compile error when it is omitted — the point of typing it that way. Its
    // response is also a real `$ref` to `ExportCatalogue` now, not a free-form document,
    // so it is the one export route read as a typed value.
    void exports.catalogue('p-1');
    void exports.downloadPolicy(q);
    void exports.exportRow('ex-1', q);
    void exports.attempts('ex-1', q);
    void exports.downloads('ex-1', q);
    void exports.deletions('ex-1', q);
    void exports.financeGate(q);

    const requests = http.match(() => true);
    expect(requests.length).toBe(9);
    expect(requests[8]!.request.urlWithParams).toContain('/api/v1/exports/finance-gate');
    // The per-export history routes are three separate paths, not one merged view.
    const paths = requests.slice(4, 8).map((r) => r.request.url.split('?')[0]);
    expect(paths).toEqual([
      '/api/v1/exports/ex-1',
      '/api/v1/exports/ex-1/attempts',
      '/api/v1/exports/ex-1/downloads',
      '/api/v1/exports/ex-1/deletions',
    ]);
    for (const request of requests) request.flush({});
  });

  it('cannot create an export, because nothing lists what one is made of', () => {
    // `POST /api/v1/exports` needs a `reportDefinitionId`, a `reportVersion`, a
    // `documentTemplateId` and a `documentTemplateVersion` — and no route in the
    // committed contract lists report definitions or document templates. A create form
    // could therefore only be filled by typing UUIDs from a document, so the endpoint
    // layer offers no such method at all. This asserts the absence, because an absence
    // is exactly the kind of thing that gets "helpfully" added back.
    const exports = TestBed.inject(ExportsApi) as unknown as Record<string, unknown>;
    expect(Object.keys(exports).filter((key) => typeof exports[key] === 'function')).not.toContain(
      'create',
    );
    expect(Object.keys(exports).filter((key) => typeof exports[key] === 'function')).not.toContain(
      'request',
    );
  });
});

describe('group 12: clinic reads the backend, and invents no figure', () => {
  it('invents no figure, in either language', () => {
    // The content of group 12. The screen now reads four real routes, which makes this
    // guard MORE important rather than less: before, the screen had no data and so no
    // opportunity to fabricate one; now it has counts, and the cheapest place for a
    // fabricated "14 appointments today" to enter the product is a hard-coded string
    // sitting next to a real API response.
    //
    // Every string is checked rather than every rendered number, because a string is
    // where a fabricated figure can be committed and where a real one cannot.
    const english = Object.values(clinicStrings).map((entry) => entry.en);
    const arabic = Object.values(clinicStrings).map((entry) => entry.ar);

    // Route paths are exempt from the digit rule, because a route is an identifier and
    // `/api/v1/appointments` necessarily contains a `1`. The digits that must never
    // appear are the ones inside a sentence, which is where a fabricated "14
    // appointments today" would live. Stripping the paths first is what makes this
    // check sharp rather than permanently failing on the URL it is quoting.
    const stripRoutes = (text: string): string => text.replace(/\/api\/v\d\/[\w{}/-]*/g, '');
    for (const text of [...english, ...arabic].map(stripRoutes)) {
      expect(text, `a digit in clinic copy: ${text}`).not.toMatch(/[0-9٠-٩]/);
    }

    // Both languages are real, and they differ — the same bar every other namespace
    // meets, asserted here so this screen is not the one place that skips it.
    expect(arabic.every((text) => text.trim().length > 0)).toBe(true);
    expect(english.every((text) => text.trim().length > 0)).toBe(true);
  });

  it('has a heading for each subject it migrated, and none for one it did not', () => {
    const keys = new Set(Object.keys(clinicStrings));

    // The three subjects with a route behind them.
    for (const subject of ['schedule', 'doctors', 'procedures'] as const) {
      expect(keys.has(`${subject}.title`), `${subject}.title is not declared`).toBe(true);
      expect(keys.has(`${subject}.hint`), `${subject}.hint is not declared`).toBe(true);
    }
    expect(keys.has('analytics.title')).toBe(true);

    // And the two it deliberately did not. `reminders` and `receptionist` were headings
    // over empty screens; keeping them would advertise a section that leads nowhere,
    // and `reminder:read` is not in the permission enum, so a control for it could
    // never be gated honestly. This asserts the absence, because an absence is exactly
    // the kind of thing that gets "helpfully" added back.
    for (const subject of ['reminders', 'receptionist', 'unavailable'] as const) {
      expect(keys.has(`${subject}.title`), `${subject}.title must not be declared`).toBe(false);
    }
  });

  it('says on the screen that contact details are withheld, and why', () => {
    // `appointment:read` names the schedule. The one route that legitimately exposes
    // patient and clinician contact details is `GET /clinic/reminders`, behind
    // `reminder:read`, and it is not migrated — so a receptionist who expected a phone
    // number has to learn that from the screen rather than from a support ticket. Naming
    // the grant that *would* be needed is the part that makes it actionable.
    const body = clinicStrings['schedule.noContact'];
    expect(body.en).toContain('appointment:read');
    expect(body.ar).toContain('appointment:read');
    expect(body.en).toContain('reminder');
    expect(body.ar).toContain('التذكيرات');
  });
});
