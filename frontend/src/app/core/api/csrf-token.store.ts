/**
 * The CSRF token and header name, as the server last reported them.
 *
 * Held in memory only. A CSRF token is not a credential — it authorizes nothing
 * without the session cookie — but there is no reason to persist one, so
 * nothing is written to `localStorage`.
 *
 * `csrfHeaderName` is seeded from configuration and then corrected from the
 * wire: `GET /api/v1/auth/csrf` returns `headerName` and the session response
 * repeats it, because the name is a server-side property
 * (`SecurityProperties.csrfHeaderName`) and a client that hard-codes it works
 * only until someone changes it.
 */
import { Injectable, signal } from '@angular/core';
import { environment } from '../../../environments/environment';

/** A token older than this is refetched proactively before a mutation. */
const TOKEN_MAX_AGE_MS = 30 * 60 * 1000;

@Injectable({ providedIn: 'root' })
export class CsrfTokenStore {
  private readonly tokenSignal = signal<string | null>(null);
  private readonly headerSignal = signal<string>(environment.csrfHeaderName);
  private readonly cookieSignal = signal<string>(environment.csrfCookieName);
  private issuedAt = 0;

  readonly token = this.tokenSignal.asReadonly();
  readonly headerName = this.headerSignal.asReadonly();
  readonly cookieName = this.cookieSignal.asReadonly();

  set(next: { token?: string | null; headerName?: string | null; cookieName?: string | null }): void {
    if (typeof next.token === 'string' && next.token !== '') {
      this.tokenSignal.set(next.token);
      this.issuedAt = Date.now();
    }
    if (typeof next.headerName === 'string' && next.headerName !== '') {
      this.headerSignal.set(next.headerName);
    }
    if (typeof next.cookieName === 'string' && next.cookieName !== '') {
      this.cookieSignal.set(next.cookieName);
    }
  }

  clear(): void {
    this.tokenSignal.set(null);
    this.issuedAt = 0;
  }

  isStale(): boolean {
    return this.issuedAt === 0 || Date.now() - this.issuedAt > TOKEN_MAX_AGE_MS;
  }
}
