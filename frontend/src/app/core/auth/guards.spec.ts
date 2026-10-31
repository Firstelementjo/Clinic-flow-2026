/**
 * Guards and the project context: the UX half of authorization.
 *
 * The point of these tests is that they assert the guards are *UX only* — that
 * they never become the thing standing between a caller and another tenant's
 * data, and that the redirect target cannot be turned into an open redirect.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { beforeEach, describe, expect, it } from 'vitest';
import { safeReturnUrl } from './guards';
import { SessionStore } from './session.store';
import { ProjectContextService } from '../context/project-context.service';

const SESSION = {
  userId: 'u-1',
  organizationId: 'o-1',
  email: 'a@b.c',
  fullName: 'A B',
  role: 'EMPLOYEE',
  locale: 'ar',
  active: true,
  projectIds: ['p-1', 'p-2'],
  csrfHeaderName: 'X-XSRF-TOKEN',
};

describe('safeReturnUrl', () => {
  it('keeps a path inside the application', () => {
    expect(safeReturnUrl('/app/settings')).toBe('/app/settings');
    expect(safeReturnUrl('/app/inbox?c=123')).toBe('/app/inbox?c=123');
  });

  it('discards anything that could leave the origin', () => {
    // `//evil.example` and `https://evil.example` are both strings a naive
    // navigateByUrl would treat as somewhere else. That is the open-redirect shape.
    expect(safeReturnUrl('//evil.example')).toBeNull();
    expect(safeReturnUrl('https://evil.example')).toBeNull();
    expect(safeReturnUrl('javascript:alert(1)')).toBeNull();
    expect(safeReturnUrl('app/settings')).toBeNull();
    expect(safeReturnUrl('')).toBeNull();
  });

  it('does not bounce the user back to the sign-in screen after signing in', () => {
    expect(safeReturnUrl('/login?returnUrl=%2Fapp')).toBeNull();
  });
});

describe('ProjectContextService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  });

  it('offers only the projects the server resolved for this session', async () => {
    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION as never);
    const context = TestBed.inject(ProjectContextService);
    settle();
    expect(context.allowedProjectIds()).toEqual(['p-1', 'p-2']);
    expect(context.hasChoice()).toBe(true);
  });

  it('refuses a selection outside the resolved scope', async () => {
    // A control that can set an impossible value is a control that lies; the
    // server would refuse it anyway, and the operator would see a 404 for a
    // choice they were offered.
    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION as never);
    const context = TestBed.inject(ProjectContextService);
    settle();
    context.select('someone-elses-project');
    expect(context.selectedProjectId()).toBeNull();
  });

  it('treats "no selection" as the whole server-resolved scope, not a wildcard', async () => {
    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION as never);
    const context = TestBed.inject(ProjectContextService);
    settle();
    context.select('p-2');
    expect(context.asQueryParam()).toEqual({ projectId: 'p-2' });
    context.select(null);
    expect(context.asQueryParam()).toEqual({});
  });

  it('drops a stored selection the current session may no longer see', async () => {
    localStorage.setItem('fe.project.v1', 'p-retired');
    const store = TestBed.inject(SessionStore);
    store.setSession(SESSION as never);
    const context = TestBed.inject(ProjectContextService);
    settle();
    expect(context.selectedProjectId()).toBeNull();
  });

  it('has no project switcher to offer when there is only one project', async () => {
    const store = TestBed.inject(SessionStore);
    store.setSession({ ...SESSION, projectIds: ['p-1'] } as never);
    const context = TestBed.inject(ProjectContextService);
    settle();
    expect(context.hasChoice()).toBe(false);
  });

  it('labels a project from its id, because the contract carries no name', () => {
    // `ProjectSummary` has `projectId` and `archived` and nothing else, and no
    // route lists a project with a name. Inventing one would be inventing data.
    const context = TestBed.inject(ProjectContextService);
    expect(context.shortLabel('33333333-3333-3333-3333-333333333333')).toBe('…33333333');
    expect(context.shortLabel('short')).toBe('short');
  });
});

/**
 * Lets the `effect` in the project context run.
 *
 * `TestBed.flushEffects()` is the purpose-built API for this: it drains the effect queue
 * synchronously, so the assertion afterwards does not depend on **timer scheduling**.
 *
 * It used to be `await new Promise(r => setTimeout(r, 0))`, which passed in isolation
 * and failed with a 5-second timeout whenever the unit suite ran alongside a Playwright
 * matrix — five tests timing out on a zero-millisecond timer. That is the same defect as
 * a browser journey that only passes when the rate-limit window happens to line up: a
 * green run that depends on a coincidence.
 *
 * The macrotask that used to follow it as a fallback is **gone**, and that is the whole
 * fix. Keeping it meant the test still needed a real event-loop turn, so under load —
 * six browsers, a dev server and a dev build all competing for the same cores — the
 * turn took longer than the 5-second test timeout and five tests failed on the clock
 * rather than on any assertion. `flushEffects()` is synchronous and does not need one.
 *
 * The five tests now report in ~3 seconds rather than ~25, which is itself the evidence:
 * there is nothing left to wait for.
 */
function settle(): void {
  TestBed.flushEffects();
}

