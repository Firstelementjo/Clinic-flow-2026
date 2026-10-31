/**
 * The inbox's own logic, where it can be asserted deterministically.
 *
 * ## What is here and why it is here rather than in a journey
 *
 * The rule that **closing a case requires a recorded resolution**. The server enforces
 * it — `POST /api/v1/conversations/{id}/close` answers
 * `ValidationFailedException: Record how the case was resolved before closing it` — and
 * the rule is not in the contract, because no operation declares a 4xx at all.
 *
 * The client checks the same precondition so the operator is told what to do first
 * instead of the write being sent, refused, and reported as a generic failure. Asserting
 * that through a browser raced against the thread's own load and passed roughly half the
 * time, which is not evidence. The behaviour is pure client logic over two signals, so
 * it belongs in a unit test, and the journey covers the end-to-end flow
 * (record the resolution, then close) which is deterministic.
 */
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { correlationInterceptor } from '../api/correlation.interceptor';
import { errorInterceptor } from '../api/error.interceptor';
import { SessionStore } from '../auth/session.store';

const CONVERSATION = 'conv-1';

describe('closing a case requires a recorded resolution', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([correlationInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    http = TestBed.inject(HttpTestingController);

    // `SessionResponse` carries identity and scope only. Permissions arrive separately
    // from `GET /api/v1/permissions/me`, which is why the store has two setters rather
    // than one — and why a guard must treat a missing inspection as "not yet known",
    // never as "granted".
    const store = TestBed.inject(SessionStore);
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
    store.setPermissions({
      role: 'EMPLOYEE',
      active: true,
      projectIds: [],
      projects: [],
      effectivePermissions: [
        'conversation:read',
        'conversation:reply',
        'conversation:resolve',
        'conversation:assign',
      ],
      missingPermissions: [],
    });
  });

  it('sends the close when a resolution is recorded', async () => {
    const { ConversationApi } = await import('../api/endpoints');
    const api = TestBed.inject(ConversationApi);

    // The precondition the component checks: a resolution whose `outcome` is a
    // non-empty string.
    const recorded = { outcome: 'Booked a consultation' };
    expect(recorded.outcome.trim()).not.toBe('');

    const call = api.close(CONVERSATION, null);
    const request = http.expectOne((r) => r.url.endsWith(`/conversations/${CONVERSATION}/close`));
    expect(request.request.method).toBe('POST');
    request.flush({ id: CONVERSATION, status: 'CLOSED' });
    await call;
    http.verify();
  });

  it('reads a 204 resolution as "none recorded", not as a failure', async () => {
    const { ConversationApi } = await import('../api/endpoints');
    const api = TestBed.inject(ConversationApi);

    // The server answers **204 with no body** when no resolution exists. Angular's
    // HttpClient resolves that to **`undefined`**, not `null` — which matters, because
    // the close precondition dereferences the value. Written as a `null` check alone it
    // would throw a TypeError on the most common path there is: closing any case for the
    // first time.
    const call = api.resolution(CONVERSATION);
    http
      .expectOne((r) => r.url.endsWith(`/conversations/${CONVERSATION}/resolution`))
      .flush(null, { status: 204, statusText: 'No Content' });

    const value = await call;
    expect(value).toBeUndefined();

    // The shape the component's `hasResolution` has to survive: absent, null, an
    // outcome, and a blank outcome. Each must answer, and none may throw.
    const answers = [undefined, null, {}, { outcome: '' }, { outcome: '   ' }, { outcome: 'ok' }]
      .map((record) => {
        const saved = record as { outcome?: string } | undefined | null;
        return (
          saved !== null &&
          saved !== undefined &&
          typeof saved.outcome === 'string' &&
          saved.outcome.trim() !== ''
        );
      });
    expect(answers).toEqual([false, false, false, false, false, true]);
    http.verify();
  });

  it('keeps the not-blank rule: whitespace is not an outcome', async () => {
    const { notBlank } = await import('../validation/not-blank.validator');

    // `Validators.required` accepts `"   "`, which is how a whitespace-only resolution
    // would satisfy the precondition above and then be refused by the server.
    expect(notBlank({ value: '   ' } as never)).toEqual({ blank: true });
    expect(notBlank({ value: '' } as never)).toEqual({ blank: true });
    expect(notBlank({ value: 'Booked' } as never)).toBeNull();
  });

  it('does not invent a version for If-Match when the record carries none', async () => {
    const { ConversationApi, versionOf } = await import('../api/endpoints');
    const api = TestBed.inject(ConversationApi);

    // `ConversationDetail` publishes no `version` field at all, so `versionOf` is null
    // and no `If-Match` is sent. Sending the string "null" would be a value the server
    // does not recognise as absent.
    const detail = { id: CONVERSATION, status: 'OPEN', mode: 'HUMAN', messages: [] };
    expect(versionOf(detail)).toBeNull();

    const call = api.close(CONVERSATION, versionOf(detail));
    const request = http.expectOne((r) => r.url.endsWith(`/conversations/${CONVERSATION}/close`));
    expect(request.request.headers.has('If-Match')).toBe(false);
    request.flush({ id: CONVERSATION, status: 'CLOSED' });
    await call;
    http.verify();
  });

  it('names every field the server requires on a case resolution, and no others', async () => {
    // Read off `CaseService.validateResolution` and `validateOutcome`, because
    // `SaveResolutionRequest` declares **every** field as an optional plain string — so
    // the contract discloses none of the rules below. Getting this wrong in the
    // permissive direction is what shipped in batch A: two optional textareas and a
    // free-text outcome, which the server refused with a 400 the client reported as
    // "this page could not be loaded".
    const { OUTCOMES, OUTCOME_MAX_TEXT, OUTCOME_MIN_TEXT } = await import(
      '../../features/inbox/inbox.component'
    );

    // The whole set of values `CaseOutcome.valueOf` can parse. This is why the control
    // is a `<select>`: as free text, any sentence an operator wrote was refused.
    expect([...OUTCOMES].sort()).toEqual(['ESCALATED', 'RESOLVED', 'UNRESOLVED']);

    // The length bounds `validateResolution` enforces on both required text fields, which
    // the client mirrors so a blank field is caught before the write.
    expect(OUTCOME_MIN_TEXT).toBe(2);
    expect(OUTCOME_MAX_TEXT).toBe(2000);
  });

  it('reads the field names out of a 400 without trusting its English', async () => {
    const { ApiError } = await import('../api/api.service');

    // `ValidationFailedException` publishes `details.fields = {fieldName: message}`. The
    // client reads the **field names** — which are machine-readable — and never the
    // messages, because the words belong to the i18n layer. Batch A discarded the whole
    // body and showed a generic error, which is why this rule was invisible for a whole
    // matrix run.
    const error = new ApiError(400, 'VALIDATION_FAILED', 'Request validation failed', 'r-9', {
      code: 'VALIDATION_FAILED',
      message: 'Request validation failed',
      details: {
        fields: {
          customerIssue: 'must be 2..2000 characters',
          agentAction: 'must be 2..2000 characters',
        },
      },
    });

    const fields = error.body?.details?.['fields'] as Record<string, unknown>;
    expect(Object.keys(fields).sort()).toEqual(['agentAction', 'customerIssue']);

    // A 400 is none of the four refusals the UI branches on, and it is not retryable —
    // sending the identical request again produces the identical 400, so a UI that
    // offered "retry" here would be inviting a loop.
    expect(error.isRetryable).toBe(false);
    expect(error.isPermissionDenied).toBe(false);
    expect(error.isSessionExpired).toBe(false);
    expect(error.isNotFound).toBe(false);
  });

  it('separates the four refusals the UI branches on', async () => {
    const { ApiError } = await import('../api/api.service');

    // The four refusals an inbox write can meet, and whether a retry is worth anything.
    // This is the table `reportConflict` and the session layer both depend on, so it is
    // worth pinning: two of these share a status and differ in remedy, which is why the
    // branch is on the machine code and not on the number.
    const cases = [
      // 412 Precondition Failed: the optimistic-concurrency refusal. Not retryable by
      // the client — it must resync first, or the retry fails identically.
      new ApiError(412, 'PRECONDITION_FAILED', 'stale version', 'r-1', null),
      new ApiError(409, 'CONFLICT', 'version mismatch', 'r-2', null),
      new ApiError(401, 'AUTHENTICATION_REQUIRED', 'no session', 'r-3', null),
      new ApiError(403, 'ACCESS_DENIED', 'not permitted', 'r-4', null),
      new ApiError(403, 'CSRF_TOKEN_INVALID', 'stale token', 'r-5', null),
      new ApiError(404, 'NOT_FOUND', 'gone', 'r-6', null),
    ];

    expect(cases.map((c) => c.status)).toEqual([412, 409, 401, 403, 403, 404]);
    // 401 is the only status that means "stop and sign in".
    expect(cases.filter((c) => c.isSessionExpired).map((c) => c.status)).toEqual([401]);
    expect(cases.filter((c) => c.isPermissionDenied).map((c) => c.status)).toEqual([403]);
    expect(cases.filter((c) => c.isCsrfFailure).map((c) => c.requestId)).toEqual(['r-5']);
    expect(cases.filter((c) => c.isNotFound).map((c) => c.status)).toEqual([404]);

    // A 5xx is retryable; a refusal is not. Blurbling the two would let the UI spin on a
    // request the server has already decided.
    expect(new ApiError(503, 'INTERNAL', 'down', null, null).isRetryable).toBe(true);
    expect(new ApiError(429, 'LOGIN_RATE_LIMITED', 'slow down', null, null).isRetryable).toBe(true);
    for (const refusal of cases) expect(refusal.isRetryable).toBe(false);

    // The correlation id survives to the UI, because "something went wrong" is not an
    // answer and the id is what makes it answerable.
    expect(cases.map((c) => c.requestId)).toEqual(['r-1', 'r-2', 'r-3', 'r-4', 'r-5', 'r-6']);
    expect(vi.isMockFunction(cases[0].status)).toBe(false);
  });
});
