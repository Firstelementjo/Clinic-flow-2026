/**
 * Session behaviour.
 *
 * The session is server state. There is exactly one fetch of `GET /api/v1/auth/me`
 * on boot, and any 401 anywhere in the app resolves to "signed out" without a
 * second source of truth — the `SessionStore` is a cache of that one answer, not
 * an independent opinion about who is signed in.
 *
 * Two details carried over from the frozen client because they are correct:
 *
 * - **Logout tears down on settle, not on success.** The request can be reported
 *   as aborted by the browser even though the server processed it and revoked the
 *   session; keying teardown to success leaves the user staring at a dashboard
 *   whose session is already dead, with every later request 401ing. Signing out
 *   locally is safe unconditionally: a server session that outlives the browser's
 *   is closed by its TTL, while a UI that believes it is still signed in is not.
 * - **Logout clears every tenant-scoped cache**, not just the session, so the
 *   next user on the same machine cannot see the previous one's data. The
 *   realtime client closes and the project context resets for the same reason.
 */
import { Injectable, computed, inject } from '@angular/core';
import { ApiService, ApiError, ApiErrorCode } from '../api/api.service';
import { CsrfTokenStore } from '../api/csrf-token.store';
import { CsrfBootstrapper } from '../api/csrf.interceptor';
import type { PermissionInspection, SessionResponse } from '../api/generated';
import { SessionStore } from './session.store';
import { RealtimeService } from '../realtime/realtime.service';

/** `POST /api/v1/auth/login` — `INVALID_CREDENTIALS` for a wrong password, an unknown email and an inactive account alike. */
export type LoginFailure = 'invalid-credentials' | 'rate-limited' | 'unreachable' | 'unknown';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly api = inject(ApiService);
  private readonly store = inject(SessionStore);
  private readonly csrfStore = inject(CsrfTokenStore);
  private readonly csrf = inject(CsrfBootstrapper);
  private readonly realtime = inject(RealtimeService);

  private bootstrapPromise: Promise<boolean> | null = null;

  readonly status = this.store.status;
  readonly session = this.store.session;
  readonly permissions = this.store.permissions;
  readonly endReason = this.store.endReason;
  readonly isAuthenticated = this.store.isAuthenticated;

  /**
   * The caller's effective permission wire values, e.g. `conversation:read`.
   *
   * Typed against `PermissionName`, so a permission literal this client does not know
   * about is a compile error rather than a control that quietly never renders. Two
   * invented literals (`conversation:write`, `customer:write`) hid the reply composer
   * and the notes editor for every user in a six-combination matrix before this
   * signature existed; see `permissions.ts`.
   *
   * The argument is still a plain string at runtime — the server is what decides — so
   * the check is UX only and never a substitute for method security.
   */
  readonly can = computed(() => {
    const granted = this.store.effectivePermissions();
    return (permission: PermissionName | (string & {})): boolean => granted.has(permission);
  });

  /**
   * Resolves the session once per page load.
   *
   * Idempotent and shared: the router guard, the shell and the realtime client
   * all call it on the same navigation and must not produce three `auth/me`
   * requests.
   */
  bootstrap(): Promise<boolean> {
    if (this.bootstrapPromise) return this.bootstrapPromise;
    this.store.beginResolving();
    this.bootstrapPromise = this.resolve();
    return this.bootstrapPromise;
  }

  /** Re-reads the session and the permission set. Used after a membership change. */
  async refresh(): Promise<boolean> {
    this.bootstrapPromise = null;
    return this.bootstrap();
  }

  /**
   * Signs in.
   *
   * The CSRF token is fetched first because login is CSRF-protected on purpose:
   * a login a third-party site can trigger is login-CSRF, and the token endpoint
   * is public precisely so a client can arm before its first mutation.
   */
  async login(email: string, password: string): Promise<LoginFailure | null> {
    await this.csrf.ensure();
    try {
      const session = await this.api.post<SessionResponse>('/api/v1/auth/login', {
        email: email.trim(),
        password,
      });
      // A login rotates the CSRF token server-side, so the cached one is stale.
      this.applySession(session, { rotateCsrf: true });
      // Open the stream here as well as at boot. The app initializer starts it for
      // a session that already existed when the page loaded; a sign-in is the other
      // way a session comes into existence, and an operator who signs in and then
      // watches a queue that never updates has been handed a broken product.
      this.realtime.start();
      return null;
    } catch (cause) {
      return this.classify(cause);
    }
  }

  async logout(): Promise<void> {
    try {
      await this.api.post<void>('/api/v1/auth/logout');
    } catch {
      // Deliberately swallowed: the local teardown below runs unconditionally.
    } finally {
      this.teardown('signed-out');
    }
  }

  /** Ends the session locally without calling the server (an expiry, say). */
  endLocally(reason: 'expired' | 'denied' | 'startup'): void {
    this.teardown(reason);
  }

  private async resolve(): Promise<boolean> {
    try {
      const session = await this.api.get<SessionResponse>('/api/v1/auth/me');
      // No token rotation on a bootstrap: the cookie the browser already holds is
      // still the one the server expects, and a page refresh must not spend a
      // round trip on a token it may never need.
      this.applySession(session, { rotateCsrf: false });
      return true;
    } catch (cause) {
      if (cause instanceof ApiError && cause.isSessionExpired) {
        this.teardown('startup');
        return false;
      }
      // A 5xx or a dead network is not an anonymous session. Reporting it as one
      // would bounce a signed-in operator to the sign-in screen because a
      // database was briefly unavailable, which is a support incident of its own.
      this.store.beginResolving();
      this.bootstrapPromise = null;
      throw cause;
    }
  }

  private applySession(session: SessionResponse, options: { rotateCsrf: boolean }): void {
    this.store.setSession(session);
    if (session.csrfHeaderName) {
      this.csrfStore.set({ headerName: session.csrfHeaderName });
    }
    if (options.rotateCsrf) {
      this.csrfStore.clear();
      void this.csrf.ensure();
    }
    // The permission set is a UX read model: it decides what a screen offers,
    // never what the server will allow.
    void this.loadPermissions();
  }

  /**
   * `GET /api/v1/permissions/me` — available to any authenticated caller.
   *
   * A failure here is not a session failure: the user is signed in, they just
   * cannot be told what they may do, and the UI degrades to hiding
   * permission-gated affordances rather than locking the whole app.
   */
  async loadPermissions(): Promise<PermissionInspection | null> {
    try {
      const inspection = await this.api.get<PermissionInspection>('/api/v1/permissions/me');
      this.store.setPermissions(inspection);
      return inspection;
    } catch {
      this.store.setPermissions(null);
      return null;
    }
  }

  private teardown(reason: 'expired' | 'signed-out' | 'denied' | 'startup'): void {
    // Order matters: close the stream first so a late frame cannot repopulate a
    // store the next user is about to read.
    this.realtime.stop();
    this.csrfStore.clear();
    this.bootstrapPromise = null;
    this.store.clear(reason);
  }

  private classify(cause: unknown): LoginFailure {
    if (!(cause instanceof ApiError)) return 'unreachable';
    if (cause.code === ApiErrorCode.invalidCredentials) return 'invalid-credentials';
    if (cause.code === ApiErrorCode.loginRateLimited || cause.status === 429) return 'rate-limited';
    if (cause.status === 0) return 'unreachable';
    return 'unknown';
  }
}
