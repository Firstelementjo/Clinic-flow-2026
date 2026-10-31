/**
 * The session signal store.
 *
 * Separate from `SessionService` for one reason: the error interceptor needs to
 * record that a session died, and an interceptor that injected the session
 * service would create a cycle (service → HttpClient → interceptor → service).
 * The store is the state; the service is the behaviour.
 *
 * What is *not* here is any token. The session lives in an HttpOnly cookie the
 * browser attaches on its own, so there is nothing in JavaScript for an XSS
 * payload to steal and nothing to persist. The only client-held session facts
 * are the display facts the server returned (`fullName`, `role`) and the
 * server-resolved project scope.
 */
import { Injectable, computed, signal } from '@angular/core';
import type { PermissionInspection, SessionResponse } from '../api/generated';

export type SessionStatus = 'unknown' | 'resolving' | 'authenticated' | 'anonymous';

/** Why the session ended, so the sign-in screen can say something true. */
export type SessionEndReason = 'expired' | 'signed-out' | 'denied' | 'startup';

@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly sessionSignal = signal<SessionResponse | null>(null);
  private readonly statusSignal = signal<SessionStatus>('unknown');
  private readonly endReasonSignal = signal<SessionEndReason | null>(null);
  private readonly permissionsSignal = signal<PermissionInspection | null>(null);

  readonly session = this.sessionSignal.asReadonly();
  readonly status = this.statusSignal.asReadonly();
  readonly endReason = this.endReasonSignal.asReadonly();
  readonly permissions = this.permissionsSignal.asReadonly();

  readonly isAuthenticated = computed(() => this.statusSignal() === 'authenticated');
  readonly isResolving = computed(() => this.statusSignal() === 'resolving');
  readonly userId = computed(() => this.sessionSignal()?.userId ?? null);
  readonly fullName = computed(() => this.sessionSignal()?.fullName ?? '');
  readonly role = computed(() => this.sessionSignal()?.role ?? null);
  readonly organizationId = computed(() => this.sessionSignal()?.organizationId ?? null);
  /**
   * The server-resolved project scope. Never assembled from a URL, a stored
   * preference, or anything the client chose: `SessionResponse.projectIds` is
   * what the session filter computed on this very request, so a membership
   * revoked a second ago is already gone.
   */
  readonly projectIds = computed<readonly string[]>(
    () => this.sessionSignal()?.projectIds ?? [],
  );
  readonly effectivePermissions = computed<ReadonlySet<string>>(
    () => new Set(this.permissionsSignal()?.effectivePermissions ?? []),
  );
  readonly missingPermissions = computed<readonly string[]>(
    () => this.permissionsSignal()?.missingPermissions ?? [],
  );

  beginResolving(): void {
    this.statusSignal.set('resolving');
  }

  setSession(session: SessionResponse): void {
    this.sessionSignal.set(session);
    this.statusSignal.set('authenticated');
    this.endReasonSignal.set(null);
  }

  setPermissions(inspection: PermissionInspection | null): void {
    this.permissionsSignal.set(inspection);
  }

  /**
   * Ends the session locally. Idempotent, and always safe to call: the worst
   * case for a revoke that never reached the server is that a server session
   * outlives the browser's, which the TTL closes. The opposite failure — a UI
   * that believes it is still signed in while every request 401s — is the one
   * that matters.
   */
  clear(reason: SessionEndReason): void {
    this.sessionSignal.set(null);
    this.permissionsSignal.set(null);
    this.statusSignal.set('anonymous');
    this.endReasonSignal.set(reason);
  }

  /** Two-letter monogram for the account avatar. Never a colour, never an id. */
  initials(fullName: string): string {
    const parts = fullName
      .split(/\s+/)
      .filter((part) => part.length > 0)
      .slice(0, 2)
      .map((part) => [...part][0] ?? '');
    return parts.join('');
  }
}
