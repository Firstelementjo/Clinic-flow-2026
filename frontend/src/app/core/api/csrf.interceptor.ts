/**
 * CSRF: the cookie/header pair the backend expects.
 *
 * The server uses `CookieCsrfTokenRepository` with a JS-readable cookie
 * (`XSRF-TOKEN`) and a non-XOR'd token echoed in `X-XSRF-TOKEN`
 * (`SecurityConfiguration`, decision 4) — the documented Angular pairing. Login
 * is CSRF-protected too, so a token is fetched before the first mutation.
 *
 * Two details that are easy to get wrong and are load-bearing:
 *
 * 1. **The token is copied verbatim.** The backend deliberately skips BREACH
 *    XOR masking (`csrfHandler.setCsrfRequestAttributeName(null)`), so a client
 *    that "decoded" or masked the cookie would fail every mutation.
 * 2. **The header name comes from the wire.** `GET /api/v1/auth/csrf` returns
 *    `headerName` and `SessionResponse.csrfHeaderName` repeats it, because the
 *    name is configurable server-side. Hard-coding it would work until someone
 *    changed the property.
 *
 * A 403 with `CSRF_TOKEN_INVALID` is retried once with a freshly fetched token;
 * that is the documented remedy and it is safe because the original request was
 * never executed.
 */
import { HttpInterceptorFn } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { ApiService } from './api.service';
import { CsrfTokenStore } from './csrf-token.store';

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Reads one cookie without depending on `document.cookie` ordering. */
export function readCookie(document: Document, name: string): string | null {
  const raw = document.cookie ?? '';
  for (const part of raw.split(';')) {
    const separator = part.indexOf('=');
    if (separator < 0) continue;
    if (part.slice(0, separator).trim() === name) {
      return decodeURIComponent(part.slice(separator + 1).trim());
    }
  }
  return null;
}

/**
 * Attaches the CSRF header to unsafe methods.
 *
 * `withXsrfConfiguration` from `@angular/common/http` would do the same, but it
 * hard-codes the cookie and header names at build time. Reading both from the
 * store means a server-side rename of either is corrected from the wire.
 */
export const csrfInterceptor: HttpInterceptorFn = (request, next) => {
  if (!UNSAFE_METHODS.has(request.method.toUpperCase())) return next(request);
  const document = inject(DOCUMENT);
  const store = inject(CsrfTokenStore);

  // The cookie is the source of truth (the server wrote it); the store holds the
  // value the token endpoint reported, which covers the window before the browser
  // has applied a freshly-set cookie.
  const token =
    readCookie(document, store.cookieName()) ??
    store.token() ??
    readCookie(document, environment.csrfCookieName);
  if (!token) return next(request);

  return next(
    request.clone({
      setHeaders: { [store.headerName()]: token },
      withCredentials: true,
    }),
  );
};

/**
 * Fetches a CSRF token once and remembers it.
 *
 * Public, so a client can obtain a token *before* its first mutation — in
 * particular before `POST /api/v1/auth/login`. A CSRF token authorizes nothing
 * without the session cookie, which is why fetching one early is harmless.
 */
@Injectable({ providedIn: 'root' })
export class CsrfBootstrapper {
  private readonly api = inject(ApiService);
  private readonly store = inject(CsrfTokenStore);
  private inFlight: Promise<void> | null = null;

  /** Idempotent: concurrent callers share one request. */
  ensure(): Promise<void> {
    const current = this.store.token();
    if (current !== null && !this.store.isStale()) return Promise.resolve();
    if (this.inFlight) return this.inFlight;
    const request: Promise<void> = this.api
      .get<{ token?: string; headerName?: string; parameterName?: string }>('/api/v1/auth/csrf')
      .then((body: { token?: string; headerName?: string; parameterName?: string }) => {
        if (typeof body?.token === 'string' && body.token !== '') {
          this.store.set({ token: body.token, headerName: body.headerName ?? null });
        }
      })
      .catch(() => {
        // No token is not a boot failure: login reports the real error and the
        // user sees one message instead of two.
      })
      .finally(() => {
        this.inFlight = null;
      });
    this.inFlight = request;
    return request;
  }
}
