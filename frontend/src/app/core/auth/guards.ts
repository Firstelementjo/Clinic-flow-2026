/**
 * Route guards — **UX only**.
 *
 * `plan.md §5`, ADR-0004 and phase-11 hard rule 1 all say the same thing: the
 * server is the authorization boundary, and a guard must never be the only thing
 * preventing an action. Every route below is enforced again by Spring method
 * security, and every permission-gated read answers `403 ACCESS_DENIED` (or the
 * non-disclosing `404`) whatever the client did.
 *
 * So what is a guard *for*? Three things, all of them about the user's time:
 *
 * 1. **Send an unauthenticated visitor to the sign-in screen** instead of
 *    rendering a shell that would fill with 401s.
 * 2. **Resolve the session once per navigation**, so no screen races the session.
 * 3. **Say "the server decided you cannot see this"** in the user's language,
 *    rather than a wall of failed requests.
 *
 * What a guard deliberately does *not* do: it does not fetch data on the user's
 * behalf to decide, it does not cache an authorization verdict beyond the
 * navigation, and it never presents itself as protection.
 */
import { inject } from '@angular/core';
import { Router, RouterStateSnapshot, UrlTree, type CanActivateFn } from '@angular/router';
import { SessionService } from './session.service';
import { SessionStore } from './session.store';

/**
 * Resolves the session, then redirects.
 *
 * `returnUrl` is preserved so signing in lands where the user was going. It is
 * validated to be a path *inside this application* before it is used: `//host`
 * and `https://host` are both valid-looking strings that a naive
 * `navigateByUrl` would treat as somewhere else.
 */
export const sessionGuard: CanActivateFn = async (
  _route: unknown,
  state: RouterStateSnapshot,
): Promise<boolean | UrlTree> => {
  const sessions = inject(SessionService);
  const store = inject(SessionStore);
  const router = inject(Router);

  if (store.status() === 'unknown') {
    await sessions.bootstrap().catch(() => false);
  }

  if (store.isAuthenticated()) return true;

  const params: Record<string, string> = {};
  const returnUrl = safeReturnUrl(state.url);
  if (returnUrl) params['returnUrl'] = returnUrl;
  // Carry the reason so the sign-in screen can say "your session ended" instead of
  // presenting itself as though the user had never been here. This is a usability
  // statement about what happened, not a security signal: the guard is UX only.
  if (store.endReason() === 'expired') params['reason'] = 'expired';

  return router.createUrlTree(['/login'], { queryParams: params });
};

/** The inverse: keeps a signed-in user off the sign-in screen. */
export const anonymousGuard: CanActivateFn = async (): Promise<boolean | UrlTree> => {
  const sessions = inject(SessionService);
  const store = inject(SessionStore);
  const router = inject(Router);

  if (store.status() === 'unknown') {
    await sessions.bootstrap().catch(() => false);
  }
  return store.isAuthenticated() ? router.createUrlTree(['/app/dashboard']) : true;
};

/**
 * Hides a route the effective permission set says will be refused.
 *
 * The route data declares the permission: `data: { permission: 'customer:read' }`. The
 * verdict comes from `GET /api/v1/permissions/me`, which is the server's own answer. A
 * `false` here produces the "the server decided" screen — never a redirect that pretends
 * the route does not exist, and never a cached decision that outlives a permission
 * change.
 *
 * The permission is typed as `string`, not `PermissionName`, because route `data` is
 * untyped data read from a configuration table. That is deliberate: this guard must be
 * able to receive *any* value, including a wrong one, so that a wrong one is refused
 * rather than refused at compile time in a file nobody opens. The literals themselves
 * live in `permissions.ts`, and the Team screen's journey asserts every one of them
 * appears in the server's granted set.
 */
export const permissionGuard: CanActivateFn = async (route: {
  data: Record<string, unknown>;
}): Promise<boolean | UrlTree> => {
  const sessions = inject(SessionService);
  const store = inject(SessionStore);
  const router = inject(Router);

  const required = route.data['permission'];
  if (typeof required !== 'string' || required === '') return true;

  if (store.permissions() === null) {
    await sessions.loadPermissions();
  }
  if (sessions.can()(required)) return true;
  return router.createUrlTree(['/app/denied'], {
    queryParams: { permission: required },
  });
};

/**
 * The `landingGuard` that used to live here is gone.
 *
 * It redirected CLINIC_STAFF and EMPLOYEE away from `/app/dashboard` so that the
 * front desk landed on the calendar. That made the Dashboard link in the sidebar
 * a control that silently navigated somewhere else -- for a dentist it looked
 * broken, and for a receptionist the link was not offered at all because its
 * permission was `conversation:read`. The dashboard is now gated on `doctor:read`,
 * a grant every clinic role holds, and `/app` redirects to it for everyone.
 *
 * The role-adaptive *welcome* was a reasonable idea implemented in the wrong
 * place: a route guard cannot tell "the user asked for the dashboard" apart from
 * "the user signed in and got the default". A destination the user explicitly
 * chose must be the destination they get; a default belongs in the route that is
 * the default, not in a guard that hijacks a real one.
 */

/**
 * A redirect target must be a path inside this application.
 *
 * Anything that is not a single-slash-relative path is discarded: `//evil.example`
 * and `https://evil.example` are both strings a naive `navigateByUrl` would
 * happily treat as somewhere else.
 */
export function safeReturnUrl(url: string): string | null {
  if (!url) return null;
  if (!url.startsWith('/')) return null;
  if (url.startsWith('//')) return null;
  if (url.startsWith('/login')) return null;
  return url;
}
