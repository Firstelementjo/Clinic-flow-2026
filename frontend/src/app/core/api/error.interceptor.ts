/**
 * The error interceptor.
 *
 * One place that turns a failed request into an `ApiError` and applies the three
 * remedies the backend documents:
 *
 * 1. **401 → the session is over.** Cleared once, centrally, so no screen has to
 *    remember to handle it. A screen that renders a 401 as "you are signed out"
 *    in only one of its five requests is the bug this removes. The auth
 *    endpoints are exempt: their own 401 is a wrong password or a not-yet-signed-in
 *    probe, and reporting that as an expiry would tell the user something false.
 * 2. **403 `CSRF_TOKEN_INVALID` → fetch a fresh token, retry once.** The original
 *    request was never executed, so the retry cannot double-apply a write.
 *    Bounded to one attempt because a second refusal means the pairing is broken
 *    and looping would be a denial-of-service against our own backend.
 * 3. **Everything else → rethrow, with the correlation id recorded** so an error
 *    message can quote it.
 */
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { toApiError } from './api.service';
import { CORRELATION_HEADER, CorrelationService } from './correlation.service';
import { CsrfTokenStore } from './csrf-token.store';
import { CsrfBootstrapper } from './csrf.interceptor';
import { SessionStore } from '../auth/session.store';

const AUTH_ENDPOINTS = ['/api/v1/auth/login', '/api/v1/auth/me', '/api/v1/auth/logout'];
const CSRF_ENDPOINT = '/api/v1/auth/csrf';

function isAuthEndpoint(url: string): boolean {
  return AUTH_ENDPOINTS.some((path) => url.includes(path));
}

export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const correlation = inject(CorrelationService);
  const sessions = inject(SessionStore);
  const csrfStore = inject(CsrfTokenStore);
  const csrfBootstrap = inject(CsrfBootstrapper);

  const traced = request.url.includes(CSRF_ENDPOINT)
    ? request
    : request.clone({ setHeaders: { [CORRELATION_HEADER]: correlation.next() } });

  return next(traced).pipe(
    catchError((cause: unknown) => {
      const error = toApiError(cause);
      correlation.record(error.requestId);

      if (error.isCsrfFailure && !request.url.includes(CSRF_ENDPOINT)) {
        // The token the browser held is no longer the one the server expects.
        // Clear it so the bootstrapper really refetches, then retry once.
        csrfStore.clear();
        return from(csrfBootstrap.ensure()).pipe(
          switchMap(() =>
            next(
              request.clone({
                setHeaders: {
                  [csrfStore.headerName()]: csrfStore.token() ?? '',
                  [CORRELATION_HEADER]: correlation.next(),
                },
              }),
            ),
          ),
          catchError((retryCause: unknown) => {
            const retryError = toApiError(retryCause);
            correlation.record(retryError.requestId);
            return throwError(() => retryError);
          }),
        );
      }

      if (error.isSessionExpired && !isAuthEndpoint(request.url)) {
        // One place decides a session is dead. `expired` rather than
        // `signed-out` so the sign-in screen can say "your session ended"
        // instead of blaming the user for something they did not do.
        sessions.clear('expired');
      }

      return throwError(() => error);
    }),
  );
};
