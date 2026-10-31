/**
 * Where a principal lands, and whether the clinic can be reached at all.
 *
 * PHASE 50. Every test here exists because of a bug a PERSON found by signing in.
 *
 * The clinic screen was gated on `customer:read`, which CLINIC_STAFF does not hold, under a
 * comment saying "there is no clinic permission, because there is no clinic route" -- true when
 * written, false since Phase 37 added four clinic routes, and never updated. On top of that,
 * `/app` redirected to `dashboard`, which was gated on `conversation:read`, which CLINIC_STAFF also
 * does not hold. So the one role whose entire purpose is the clinic front desk was refused the
 * moment it signed in, and then refused again if it navigated to the screen it existed for.
 *
 * PHASE 51. The fix for that was a `landingGuard` that redirected clinic roles away from the
 * dashboard to the calendar. It cured the refusal and created a worse bug: the Dashboard link in
 * the sidebar was offered to a dentist (who holds `conversation:read`) and did nothing when
 * clicked, because the guard sent them back to the calendar. The receptionist never saw the link,
 * because the navigation entry still demanded `conversation:read`.
 *
 * The dashboard is now a normal permission-gated route: `doctor:read`, which all three clinic
 * roles hold, and the navigation entry carries the same grant. These tests assert that
 * relationship against the shipped route table and navigation model rather than restating either.
 */
import { TestBed } from '@angular/core/testing';
import {
  provideRouter,
  type CanActivateFn,
  type Route,
  type RouterStateSnapshot,
  type UrlTree,
} from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { permissionGuard } from './guards';
import { SessionStore } from './session.store';
import { routes } from '../../app.routes';
import { NAV_ENTRIES } from '../navigation/navigation';
import type { PermissionInspection } from '../api/generated';

/**
 * CLINIC_STAFF's grants, copied from `RolePermissions.CLINIC_STAFF` in
 * `backend/tenant-security`. A strict subset of EMPLOYEE on purpose.
 */
const CLINIC_STAFF_GRANTS = [
  'organization:read',
  'project:read',
  'doctor:read',
  'procedure:read',
  'appointment:read',
  'analytics:read-project',
] as const;

/** What EMPLOYEE holds that the clinic role does not. */
const EMPLOYEE_ONLY = ['conversation:read', 'customer:read', 'employee:read'] as const;

/** The `app` shell's children -- the real ones, read from the shipped configuration. */
function children(): Route[] {
  const app = routes.find((r) => r.path === 'app');
  if (!app?.children) throw new Error('the app route has no children');
  return app.children;
}

function child(path: string): Route {
  const found = children().find((c) => c.path === path);
  if (!found) throw new Error(`no route for ${path}`);
  return found;
}

function grant(permissions: readonly string[]): void {
  TestBed.inject(SessionStore).setPermissions({
    effectivePermissions: permissions,
    missingPermissions: [],
  } as unknown as PermissionInspection);
}

describe('the clinic screen is reachable by the role it exists for', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('is gated on a permission CLINIC_STAFF actually holds', () => {
    const required = child('clinic').data?.['permission'];
    expect(CLINIC_STAFF_GRANTS).toContain(required as never);
  });

  it('is NOT gated on a permission only EMPLOYEE holds -- the bug this file exists for', () => {
    const required = child('clinic').data?.['permission'];
    for (const permission of EMPLOYEE_ONLY) {
      expect(required).not.toBe(permission);
    }
  });

  it('lets a clinic clerk through and still refuses a principal without the grant', async () => {
    const route = child('clinic');
    grant(CLINIC_STAFF_GRANTS);
    expect(await runGuard(permissionGuard, route)).toBe(true);

    grant([]);
    const refused = await runGuard(permissionGuard, route);
    expect(refused).not.toBe(true);
  });
});

describe('the dashboard is reachable by every clinic role', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('is gated on a permission the front desk holds, so clicking it does not bounce', () => {
    const required = child('dashboard').data?.['permission'];
    expect(CLINIC_STAFF_GRANTS).toContain(required as never);
  });

  it('does not carry a redirecting landing guard on the chosen destination', () => {
    // A guard that redirects a route the user explicitly chose is how "click Dashboard
    // and nothing happens" reached a human tester. The route may only be gated.
    expect(child('dashboard').canActivate).toEqual([permissionGuard]);
  });

  it('lets a clinic clerk AND a dentist through, and refuses a principal with no grant', async () => {
    const route = child('dashboard');

    grant(CLINIC_STAFF_GRANTS);
    expect(await runGuard(permissionGuard, route)).toBe(true);

    grant([...CLINIC_STAFF_GRANTS, ...EMPLOYEE_ONLY]);
    expect(await runGuard(permissionGuard, route)).toBe(true);

    grant([]);
    expect(await runGuard(permissionGuard, route)).not.toBe(true);
  });

  it('is offered in the sidebar to exactly the principals the route admits', () => {
    const entry = NAV_ENTRIES.find((candidate) => candidate.path === '/app/dashboard');
    expect(entry).toBeDefined();
    expect(entry?.permission).toBe(child('dashboard').data?.['permission']);
    expect(CLINIC_STAFF_GRANTS).toContain(entry?.permission as never);
  });

  it('is the shell index, so signing in lands on a screen the user can open', () => {
    const index = children().find((c) => c.path === '');
    expect(index?.redirectTo).toBe('dashboard');
  });
});

/**
 * Runs a guard outside Angular's router, which is what `CanActivateFn` is: it receives the route
 * snapshot and returns `true` or a `UrlTree`.
 *
 * The second argument is a fabricated `RouterStateSnapshot` because the guards under test never read
 * it -- only `sessionGuard` does, and it is not one of them. Passing a real one would mean running a
 * navigation, and the whole point here is to exercise the decision the guard makes, not the router's
 * ability to perform it.
 */
async function runGuard(guard: CanActivateFn, route: Route): Promise<boolean | UrlTree> {
  const state = { url: `/app/${route.path ?? ''}` } as RouterStateSnapshot;
  const result = await TestBed.runInInjectionContext(() => guard(route as never, state));
  return result as boolean | UrlTree;
}
