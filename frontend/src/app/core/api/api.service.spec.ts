/**
 * The request layer: error contract, credentials, query hygiene.
 *
 * The interceptor tests live in their own files; this one covers the service that
 * everything else is built on, because a mistake here is invisible everywhere
 * above it.
 */
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiError, ApiErrorCode, ApiService, toApiError, toHttpParams } from './api.service';
import { correlationInterceptor } from './correlation.interceptor';
import { CsrfTokenStore } from './csrf-token.store';
import { SessionStore } from '../auth/session.store';

describe('toHttpParams', () => {
  it('omits absent, empty and nullish values rather than sending them', () => {
    // `?projectId=` is a different request from one without the parameter, and a
    // blank filter would match nothing on the server for a reason no user asked for.
    const params = toHttpParams({ a: 'x', b: undefined, c: null, d: '', e: 0, f: false });
    expect(params.keys().sort()).toEqual(['a', 'e', 'f']);
    expect(params.get('e')).toBe('0');
    expect(params.get('f')).toBe('false');
  });

  it('tolerates no query at all', () => {
    expect(toHttpParams(undefined).keys()).toEqual([]);
  });
});

describe('toApiError', () => {
  it('reads the stable error body the server documents', () => {
    const error = toApiError({
      status: 403,
      error: {
        code: 'ACCESS_DENIED',
        message: 'Access denied',
        requestId: 'corr-1',
        retryable: false,
      },
      headers: { get: () => null },
    });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe(ApiErrorCode.accessDenied);
    expect(error.requestId).toBe('corr-1');
    expect(error.isPermissionDenied).toBe(true);
    expect(error.isRetryable).toBe(false);
  });

  it('falls back to the response correlation header when the body has none', () => {
    const error = toApiError({
      status: 500,
      error: null,
      headers: { get: (name: string) => (name === 'X-Correlation-Id' ? 'hdr-9' : null) },
    });
    expect(error.requestId).toBe('hdr-9');
    expect(error.code).toBe(ApiErrorCode.internal);
    expect(error.isRetryable).toBe(true);
  });

  it('maps a status with no body to a code the UI can branch on', () => {
    expect(toApiError({ status: 401, error: null, headers: { get: () => null } }).isSessionExpired).toBe(true);
    expect(toApiError({ status: 404, error: null, headers: { get: () => null } }).isNotFound).toBe(true);
    expect(toApiError({ status: 429, error: null, headers: { get: () => null } }).isRetryable).toBe(true);
  });

  it('recognises a CSRF refusal specifically, not as a permission denial', () => {
    const error = toApiError({
      status: 403,
      error: { code: 'CSRF_TOKEN_INVALID', message: 'CSRF token missing or invalid' },
      headers: { get: () => null },
    });
    expect(error.isCsrfFailure).toBe(true);
    expect(error.isPermissionDenied).toBe(false);
  });

  it('turns a dead network into a status-0 error rather than a bare exception', () => {
    const error = toApiError(new TypeError('Failed to fetch'));
    expect(error.status).toBe(0);
    expect(error.code).toBe('NETWORK');
    expect(error.isSessionExpired).toBe(false);
  });

  it('passes an ApiError through unchanged', () => {
    const original = new ApiError(404, 'NOT_FOUND', 'x', null, null);
    expect(toApiError(original)).toBe(original);
  });
});

describe('ApiService over HttpClient', () => {
  let api: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([correlationInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends credentials so the HttpOnly session cookie rides along', async () => {
    const pending = api.get('/api/v1/auth/me');
    const request = http.expectOne('/api/v1/auth/me');
    // The session is a cookie the browser attaches; `withCredentials` is what
    // makes that work against a cross-origin dev base URL and is harmless
    // same-origin.
    expect(request.request.withCredentials).toBe(true);
    request.flush({ userId: 'u' });
    await pending;
  });

  it('stamps a correlation id on every request', async () => {
    const pending = api.get('/api/v1/status');
    const request = http.expectOne('/api/v1/status');
    expect(request.request.headers.has('X-Correlation-Id')).toBe(true);
    request.flush({});
    await pending;
  });

  it('resolves a 204 logout with no body', async () => {
    const pending = api.post<void>('/api/v1/auth/logout');
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await expect(pending).resolves.toBeUndefined();
  });

  it('rejects with an ApiError carrying the server code', async () => {
    const pending = api.get('/api/v1/permissions/me');
    http
      .expectOne('/api/v1/permissions/me')
      .flush({ code: 'ACCESS_DENIED', message: 'nope', requestId: 'c-1' }, { status: 403, statusText: 'Forbidden' });
    await expect(pending).rejects.toMatchObject({ code: 'ACCESS_DENIED', requestId: 'c-1' });
  });

  it('builds a query string without the absent parameters', async () => {
    const pending = api.get('/api/v1/inbox/unassigned', { limit: 5, cursor: undefined });
    const request = http.expectOne((candidate) => candidate.url === '/api/v1/inbox/unassigned');
    expect(request.request.params.get('limit')).toBe('5');
    expect(request.request.params.has('cursor')).toBe(false);
    request.flush({});
    await pending;
  });

  it('never puts a project id in a path when the server takes it as a filter', async () => {
    // A project id is a request, never authorization; the guard validates it
    // server-side against the caller's resolved scope.
    const pending = api.get('/api/v1/inbox/counts', { projectId: 'p-1' });
    const request = http.expectOne((candidate) => candidate.url === '/api/v1/inbox/counts');
    expect(request.request.params.get('projectId')).toBe('p-1');
    request.flush({});
    await pending;
  });
});

describe('CsrfTokenStore', () => {
  it('takes its header name from the wire rather than from a constant', () => {
    const store = TestBed.inject(CsrfTokenStore);
    store.set({ headerName: 'X-Custom-CSRF' });
    expect(store.headerName()).toBe('X-Custom-CSRF');
  });

  it('holds the token in memory and never writes it to storage', () => {
    localStorage.clear();
    const store = TestBed.inject(CsrfTokenStore);
    store.set({ token: 'secret-token' });
    expect(store.token()).toBe('secret-token');
    // A CSRF token is not a credential, but there is no reason to persist one.
    expect(JSON.stringify(localStorage)).not.toContain('secret-token');
    store.clear();
    expect(store.token()).toBeNull();
  });

  it('ignores an empty token rather than replacing a good one with nothing', () => {
    const store = TestBed.inject(CsrfTokenStore);
    store.set({ token: 'good' });
    store.set({ token: '' });
    expect(store.token()).toBe('good');
  });
});

describe('SessionStore', () => {
  it('derives the project scope from the server response and nothing else', () => {
    const store = TestBed.inject(SessionStore);
    store.setSession({
      userId: 'u1',
      organizationId: 'o1',
      email: 'a@b.c',
      fullName: 'A B',
      role: 'EMPLOYEE',
      locale: 'ar',
      active: true,
      projectIds: ['p1', 'p2'],
      csrfHeaderName: 'X-XSRF-TOKEN',
    });
    expect(store.projectIds()).toEqual(['p1', 'p2']);
    expect(store.isAuthenticated()).toBe(true);
    expect(store.effectivePermissions().size).toBe(0);
  });

  it('drops every tenant-scoped fact when the session ends', () => {
    const store = TestBed.inject(SessionStore);
    store.setSession({ userId: 'u1', projectIds: ['p1'], role: 'ADMIN' } as never);
    store.setPermissions({
      subject: {} as never,
      role: 'ADMIN',
      active: true,
      projectIds: ['p1'],
      projects: [],
      effectivePermissions: ['conversation:read'],
      missingPermissions: [],
    });
    store.clear('expired');
    expect(store.session()).toBeNull();
    expect(store.permissions()).toBeNull();
    expect(store.projectIds()).toEqual([]);
    expect(store.status()).toBe('anonymous');
    expect(store.endReason()).toBe('expired');
  });

  it('builds a monogram from at most two letters', () => {
    const store = TestBed.inject(SessionStore);
    expect(store.initials('Layla Al Mansour')).toBe('LA');
    expect(store.initials('')).toBe('');
  });
});
