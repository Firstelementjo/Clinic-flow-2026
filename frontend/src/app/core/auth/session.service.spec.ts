/**
 * Session behaviour.
 *
 * The behaviours under test are the ones carried over from the frozen client
 * because they were correct, plus the one that is specific to the target stack:
 * a 401 anywhere resolves to "signed out" in exactly one place.
 *
 * The `tick()` calls are not noise. The service chains promises
 * (`csrf.ensure()` before the login mutation, `applySession()` before the
 * permission read), so the *next* request is only issued after a microtask has
 * run. Without an explicit drain, `expectOne` looks at the queue too early and
 * reports a failure that is an artefact of the test rather than of the code.
 */
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SessionService } from './session.service';
import { SessionStore } from './session.store';
import { correlationInterceptor } from '../api/correlation.interceptor';
import { csrfInterceptor } from '../api/csrf.interceptor';
import { errorInterceptor } from '../api/error.interceptor';
import { ApiService } from '../api/api.service';

const SESSION = {
  userId: '11111111-1111-1111-1111-111111111111',
  organizationId: '22222222-2222-2222-2222-222222222222',
  email: 'lead@clinic.example',
  fullName: 'ليلى المنصور',
  role: 'ADMIN',
  locale: 'ar',
  active: true,
  projectIds: ['33333333-3333-3333-3333-333333333333'],
  csrfHeaderName: 'X-XSRF-TOKEN',
};

const PERMISSIONS = {
  subject: { id: SESSION.userId },
  role: 'ADMIN',
  active: true,
  projectIds: SESSION.projectIds,
  projects: [],
  effectivePermissions: ['conversation:read', 'system-health:read'],
  missingPermissions: ['finance:aggregate'],
};

/** Lets the promise chains in the service advance by one turn. */
async function tick(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('SessionService', () => {
  let sessions: SessionService;
  let store: SessionStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([correlationInterceptor, errorInterceptor, csrfInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    });
    sessions = TestBed.inject(SessionService);
    store = TestBed.inject(SessionStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /**
   * Answers the two requests a bootstrap makes: the session, then — once the
   * session has been applied — the permission read. No CSRF fetch: a read is not
   * a mutation, and `bootstrap()` must not spend a round trip on a token it does
   * not use.
   */
  async function answerBootstrap(session: object = SESSION, permissions: object = PERMISSIONS): Promise<void> {
    http.expectOne('/api/v1/auth/me').flush(session);
    await tick();
    http.expectOne('/api/v1/permissions/me').flush(permissions);
  }

  it('resolves the session once per page load, not once per caller', async () => {
    const first = sessions.bootstrap();
    const second = sessions.bootstrap();
    await answerBootstrap();
    await expect(first).resolves.toBe(true);
    await expect(second).resolves.toBe(true);
    expect(store.isAuthenticated()).toBe(true);
  });

  it('treats a 401 on /auth/me as anonymous, not as an error', async () => {
    const pending = sessions.bootstrap();
    http
      .expectOne('/api/v1/auth/me')
      .flush({ code: 'AUTHENTICATION_REQUIRED', message: 'x' }, { status: 401, statusText: 'Unauthorized' });
    await expect(pending).resolves.toBe(false);
    expect(store.status()).toBe('anonymous');
    expect(store.endReason()).toBe('startup');
  });

  it('does not report a 5xx as an anonymous session', async () => {
    // Bouncing a signed-in operator to the sign-in screen because a database was
    // briefly unavailable is a support incident of its own.
    const pending = sessions.bootstrap();
    http
      .expectOne('/api/v1/auth/me')
      .flush({ code: 'INTERNAL', message: 'x' }, { status: 503, statusText: 'Service Unavailable' });
    await expect(pending).rejects.toMatchObject({ status: 503 });
    expect(store.status()).not.toBe('anonymous');
  });

  it('arms a CSRF token before the login mutation', async () => {
    const pending = sessions.login('lead@clinic.example', 'correct horse');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf-1', headerName: 'X-XSRF-TOKEN' });
    await tick();

    const login = http.expectOne('/api/v1/auth/login');
    // Login is CSRF-protected on purpose, so the token must already be here.
    expect(login.request.headers.get('X-XSRF-TOKEN')).toBe('csrf-1');
    login.flush(SESSION);
    await tick();
    http.expectOne('/api/v1/permissions/me').flush(PERMISSIONS);
    await tick();
    // A fresh token is fetched after the session rotates.
    http.expectOne('/api/v1/auth/csrf').flush({ token: 'csrf-2' });

    await expect(pending).resolves.toBeNull();
    expect(store.isAuthenticated()).toBe(true);
  });

  it('classifies a wrong password without telling the user which field was wrong', async () => {
    const pending = sessions.login('lead@clinic.example', 'wrong');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 't' });
    await tick();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'INVALID_CREDENTIALS', message: 'x' }, { status: 401, statusText: 'Unauthorized' });
    await expect(pending).resolves.toBe('invalid-credentials');
    expect(store.status()).not.toBe('authenticated');
  });

  it('classifies the rate limiter separately from a wrong password', async () => {
    const pending = sessions.login('lead@clinic.example', 'x');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 't' });
    await tick();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'LOGIN_RATE_LIMITED', message: 'x' }, { status: 429, statusText: 'Too Many Requests' });
    await expect(pending).resolves.toBe('rate-limited');
  });

  it('reports an unreachable server differently from a rejection', async () => {
    const pending = sessions.login('lead@clinic.example', 'x');
    http.expectOne('/api/v1/auth/csrf').flush({ token: 't' });
    await tick();
    http
      .expectOne('/api/v1/auth/login')
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    await expect(pending).resolves.toBe('unreachable');
  });

  it('tears the session down on logout even when the request never completed', async () => {
    // Preserved from the frozen client: a logout the browser reports as aborted
    // may still have revoked the session server-side, and the opposite failure —
    // a UI that believes it is still signed in — is the one that costs.
    store.setSession(SESSION);
    const pending = sessions.logout();
    http
      .expectOne('/api/v1/auth/logout')
      .error(new ProgressEvent('abort'), { status: 0, statusText: 'Unknown Error' });
    await pending;
    expect(store.status()).toBe('anonymous');
    expect(store.endReason()).toBe('signed-out');
    expect(store.permissions()).toBeNull();
  });

  it('clears the session when any unrelated request 401s', async () => {
    // One place decides a session is dead, so no screen has to remember to.
    const pending = sessions.bootstrap();
    await answerBootstrap();
    await pending;

    const failing = TestBed.inject(ApiService).get('/api/v1/inbox/counts');
    await tick();
    http
      .expectOne('/api/v1/inbox/counts')
      .flush({ code: 'AUTHENTICATION_REQUIRED', message: 'x' }, { status: 401, statusText: 'Unauthorized' });
    await expect(failing).rejects.toMatchObject({ status: 401 });
    expect(store.status()).toBe('anonymous');
    expect(store.endReason()).toBe('expired');
  });

  it('degrades to no permission set rather than failing the sign-in', async () => {
    const pending = sessions.bootstrap();
    http.expectOne('/api/v1/auth/me').flush(SESSION);
    await tick();
    http
      .expectOne('/api/v1/permissions/me')
      .flush({ code: 'ACCESS_DENIED', message: 'x' }, { status: 403, statusText: 'Forbidden' });
    await expect(pending).resolves.toBe(true);
    expect(store.isAuthenticated()).toBe(true);
    expect(store.permissions()).toBeNull();
    expect(sessions.can()('conversation:read')).toBe(false);
  });

  it('reports permissions from the server answer, not from the role name', async () => {
    const pending = sessions.bootstrap();
    await answerBootstrap();
    await pending;
    expect(sessions.can()('conversation:read')).toBe(true);
    expect(sessions.can()('system-health:read')).toBe(true);
    expect(sessions.can()('finance:aggregate')).toBe(false);
    expect(store.missingPermissions()).toEqual(['finance:aggregate']);
  });
});
