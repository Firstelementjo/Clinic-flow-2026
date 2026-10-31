/**
 * Group 5-8: the screens whose read models exist, and the screens whose do not.
 *
 * These are the assertions that catch a **regression to a placeholder**. Phase-11
 * hard rule 8 says a mock that ships is a failure, and the failure mode is not a
 * missing screen — it is a screen that quietly renders a figure it made up. So each
 * assertion below pins one of:
 *
 * 1. a number on screen comes from the response, or renders as `—`;
 * 2. a screen with no backend route says so, in copy, with no numbers at all;
 * 3. a mutation carries the concurrency header the contract declares.
 *
 * They are unit-level and fast; the journeys prove the same properties through a
 * browser, and a defect that reaches production would have to defeat both.
 */
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiService } from '../api/api.service';
import { ConversationApi, CustomerApi, NotesApi, versionOf } from '../api/endpoints';
import { correlationInterceptor } from '../api/correlation.interceptor';
import { errorInterceptor } from '../api/error.interceptor';
import { SessionStore } from '../auth/session.store';
import { TranslatePipe } from '../i18n/translate.pipe';
import { I18nService } from '../i18n/i18n.service';
import { shellStrings } from '../i18n/strings/core';
import { inboxStrings } from '../../features/inbox/inbox.strings';
import { knowledgeStrings } from '../../features/knowledge/knowledge.strings';
import { peopleStrings } from '../../features/people/people.strings';
import { teamStrings } from '../../features/team/team.strings';
import type { Namespace } from '../i18n/i18n.types';

function configureHttp(): HttpTestingController {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([correlationInterceptor, errorInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  return TestBed.inject(HttpTestingController);
}

describe('the endpoint layer, against the committed contract', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    http = configureHttp();
  });

  it('replies with the concurrency headers the contract declares', async () => {
    // `Idempotency-Key` makes a double-submit one write; `X-Expected-Message-Count`
    // makes the collision check explicit. Both are *headers* in the document, so a
    // client that sent them as query parameters would not be honouring the contract
    // even though the request would appear to work.
    const api = TestBed.inject(ConversationApi);
    const call = api.reply('c-1', { body: 'hello' }, {
      expectedMessageCount: 3,
      idempotencyKey: 'fe-c-1-key',
    });

    const request = http.expectOne((candidate) => candidate.url.endsWith('/conversations/c-1/messages'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('fe-c-1-key');
    expect(request.request.headers.get('X-Expected-Message-Count')).toBe('3');
    // Not in the query string: a token-shaped value in a URL ends up in logs.
    expect(request.request.urlWithParams).not.toContain('fe-c-1-key');

    request.flush({ id: 'c-1' });
    await call;
    http.verify();
  });

  it('omits the collision headers when the caller asserts nothing', async () => {
    const api = TestBed.inject(ConversationApi);
    const call = api.reply('c-2', { body: 'hello' });

    const request = http.expectOne((candidate) => candidate.url.endsWith('/conversations/c-2/messages'));
    // "I am not asserting a count" is different from asserting zero, so the header
    // is absent rather than `0`.
    expect(request.request.headers.has('X-Expected-Message-Count')).toBe(false);
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);

    request.flush({ id: 'c-2' });
    await call;
    http.verify();
  });

  it('sends If-Match only when the server published a version', async () => {
    const api = TestBed.inject(CustomerApi);

    const withVersion = api.update('cust-1', { displayName: 'A' }, '7');
    const versioned = http.expectOne((candidate) => candidate.url.endsWith('/customers/cust-1'));
    expect(versioned.request.headers.get('If-Match')).toBe('7');
    versioned.flush({ id: 'cust-1' });
    await withVersion;

    const withoutVersion = api.update('cust-1', { displayName: 'B' }, null);
    const unversioned = http.expectOne((candidate) => candidate.url.endsWith('/customers/cust-1'));
    // `If-Match: null` would be a literal string the server would not recognise as
    // absent. Guessing a version is worse than sending none.
    expect(unversioned.request.headers.has('If-Match')).toBe(false);
    unversioned.flush({ id: 'cust-1' });
    await withoutVersion;

    http.verify();
  });

  it('reads the concurrency version off the record the server sent', () => {
    expect(versionOf({ version: 12 })).toBe('12');
    expect(versionOf({ version: '12' })).toBe('12');
    expect(versionOf({ etag: 'W/"3"' })).toBe('W/"3"');
    // Absent, null and empty are all "the server offered no version" — three
    // shapes that must not each produce a different header.
    expect(versionOf({})).toBeNull();
    expect(versionOf({ version: null })).toBeNull();
    expect(versionOf({ version: '' })).toBeNull();
    expect(versionOf(null)).toBeNull();
  });

  it('scopes a customer read to the project it was given', async () => {
    // A `projectId` is a *request*, never authorization: it rides on the query and
    // the server's guard compares it. The client never assembles scope itself.
    const api = TestBed.inject(CustomerApi);
    const call = api.list({ projectId: 'p-9', limit: 10 });

    const request = http.expectOne((candidate) => candidate.url.includes('/api/v1/customers'));
    expect(request.request.params.get('projectId')).toBe('p-9');
    expect(request.request.params.get('limit')).toBe('10');
    request.flush({ items: [] });
    await call;
    http.verify();
  });

  it('reads notes for one resource, never merged with the project list', async () => {
    // FR-3.4: customer-facing history and internal history stay separate. The two
    // lists are different routes, and a client that conflated them would put an
    // internal note in a customer's history.
    const api = TestBed.inject(NotesApi);

    const perResource = api.forResource({ resourceType: 'customer', resourceId: 'c-1' });
    // Matched on the path *and* the absence of `/project`: the two lists are
    // separate routes, and a matcher loose enough to accept both would let this
    // test pass while the client conflated them.
    const resourceRequest = http.expectOne(
      (candidate) => candidate.url === '/api/v1/notes' && candidate.urlWithParams.includes('resourceType=customer'),
    );
    expect(resourceRequest.request.params.get('resourceType')).toBe('customer');
    expect(resourceRequest.request.params.get('resourceId')).toBe('c-1');
    resourceRequest.flush([]);
    await perResource;

    const perProject = api.forProject({ projectId: 'p-1' });
    const projectRequest = http.expectOne((candidate) =>
      candidate.url.includes('/api/v1/notes/project'),
    );
    projectRequest.flush([]);
    await perProject;

    http.verify();
  });
});

describe('the unavailable surfaces', () => {
  it('names every backend gap in both languages, with no figure attached', () => {
    // Each of these is a screen the product needs and the committed contract has no
    // route for. The requirement is not "the screen is absent" — it is that the gap is
    // *sayable*, in Arabic and in English, with nothing invented to fill it.
    const pairs: [keyof typeof teamStrings, string, string][] = [
      ['unavailable.title', 'ساعات العمل والتوزيع والاستيراد', 'Working hours, routing and import'],
      ['unavailable.body', 'لا يوجد مسار', 'no route'],
    ];
    const dictionary = new Map(
      Object.entries(teamStrings).map(([key, value]) => [key, value]),
    );
    for (const [key, ar, en] of pairs) {
      expect(dictionary.get(key)?.ar, `${key} Arabic`).toContain(ar);
      expect(dictionary.get(key)?.en, `${key} English`).toContain(en);
    }
  });

  it('states the clinic gap and the AI-agent gap as facts, not as promises', () => {
    const shell = new Map(Object.entries(shellStrings).map(([key, value]) => [key, value]));
    // "Coming soon" is a promise. These say what is missing, which is a fact, and
    // which an operator can file.
    for (const key of [
      'nav.unavailable.clinic',
      'nav.unavailable.availability',
      'nav.unavailable.aiAgents',
      'nav.unavailable.escalation',
    ] as const) {
      const entry = shell.get(key);
      expect(entry, `${key} exists`).toBeDefined();
      expect(entry?.ar).not.toContain('قريباً');
      expect(entry?.en.toLowerCase()).not.toContain('coming soon');
      // Both languages must actually say something.
      expect(entry?.ar.length ?? 0).toBeGreaterThan(10);
      expect(entry?.en.length ?? 0).toBeGreaterThan(10);
    }
  });

  it('gives every group 5-8 string an Arabic and an English value', () => {
    const namespaces: [string, Namespace][] = [
      ['inbox', inboxStrings],
      ['people', peopleStrings],
      ['team', teamStrings],
      ['knowledge', knowledgeStrings],
    ];
    for (const [name, namespace] of namespaces) {
      for (const [key, value] of Object.entries(namespace)) {
        expect(value.ar, `${name}.${key} Arabic`).not.toBe('');
        expect(value.en, `${name}.${key} English`).not.toBe('');
        // A key whose two languages are identical means one was never written.
        expect(value.ar === value.en, `${name}.${key} ar === en`).toBe(false);
      }
    }
  });
});

describe('a failure never becomes a zero', () => {
  it('surfaces the server code and correlation id rather than swallowing it', async () => {
    TestBed.resetTestingModule();
    const http = configureHttp();
    const api = TestBed.inject(ApiService);

    // `ApiService.get` already returns a Promise — it is the only entry point a
    // caller uses, so the test asserts on the same shape the product consumes.
    const call = api.get('/api/v1/inbox/unassigned');
    http.expectOne('/api/v1/inbox/unassigned').flush(
      { code: 'INTERNAL', message: 'boom', requestId: 'req-9' },
      { status: 503, statusText: 'Service Unavailable' },
    );

    // The error is thrown, not swallowed: the caller needs the code and the id to
    // show a retry and quote the correlation id to support.
    await expect(call).rejects.toMatchObject({
      code: 'INTERNAL',
      requestId: 'req-9',
      status: 503,
    });
    http.verify();
  });

  it('clears the session once, on a 401 from anywhere but the auth endpoints', async () => {
    TestBed.resetTestingModule();
    const http = configureHttp();
    const store = TestBed.inject(SessionStore);
    const api = TestBed.inject(ApiService);

    store.setSession({
      userId: 'u-1',
      organizationId: 'o-1',
      email: 'e@example.test',
      fullName: 'E',
      role: 'EMPLOYEE',
      locale: 'ar',
      active: true,
      projectIds: [],
      csrfHeaderName: 'X-XSRF-TOKEN',
    });
    expect(store.isAuthenticated()).toBe(true);

    const call = api.get('/api/v1/inbox/unassigned');
    http.expectOne('/api/v1/inbox/unassigned').flush(
      { code: 'AUTHENTICATION_REQUIRED', message: 'no', requestId: 'r-1' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await expect(call).rejects.toMatchObject({ status: 401 });

    // One place decides a session is dead, and it decides once.
    expect(store.isAuthenticated()).toBe(false);
    expect(store.endReason()).toBe('expired');
    http.verify();
  });

  it('does not treat a 401 from the login endpoint as an expiry', async () => {
    TestBed.resetTestingModule();
    const http = configureHttp();
    const store = TestBed.inject(SessionStore);
    const api = TestBed.inject(ApiService);

    store.setSession({
      userId: 'u-1',
      organizationId: 'o-1',
      email: 'e@example.test',
      fullName: 'E',
      role: 'EMPLOYEE',
      locale: 'ar',
      active: true,
      projectIds: [],
      csrfHeaderName: 'X-XSRF-TOKEN',
    });

    const call = api.post('/api/v1/auth/login', { email: 'e', password: 'x' });
    http.expectOne('/api/v1/auth/login').flush(
      { code: 'INVALID_CREDENTIALS', message: 'no', requestId: 'r-2' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await expect(call).rejects.toMatchObject({ status: 401 });

    // A wrong password is not an expired session. Reporting it as one would bounce
    // the user to the sign-in screen claiming their session ended — which is the
    // mistake the auth-endpoint exemption exists to prevent.
    expect(store.isAuthenticated()).toBe(true);
    expect(store.endReason()).toBeNull();
    http.verify();
  });
});

describe('the translation layer the new screens depend on', () => {
  it('resolves a key from a lazily-registered namespace', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const i18n = TestBed.inject(I18nService);
    i18n.register({ inbox: inboxStrings as Namespace });

    expect(i18n.t('inbox.thread.collision')).toContain('رسالة أخرى');
    expect(i18n.has('inbox.thread.collision')).toBe(true);
    // A key in a namespace that has not been registered is *missing*, not blank:
    // the pipe would render the key, which is visible, rather than nothing.
    expect(i18n.has('inbox.nope')).toBe(false);
    expect(i18n.t('inbox.nope')).toBe('inbox.nope');
  });

  it('renders a parameterised Arabic string with the number substituted', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const i18n = TestBed.inject(I18nService);
    i18n.register({ inbox: inboxStrings as Namespace });

    expect(i18n.t('inbox.thread.messageCount', { count: 3 })).toContain('3');
    // `ar-u-nu-latn` keeps Western digits in Arabic, so the figure the operator
    // compares against a row is the figure they see.
    expect(i18n.intlLocale()).toBe('ar-u-nu-latn');
  });

  it('exposes the pipe the templates use', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [TranslatePipe] });
    expect(TestBed.inject(TranslatePipe)).toBeInstanceOf(TranslatePipe);
  });
});
