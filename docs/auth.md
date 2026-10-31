# Authentication and authorization

## Sessions

Opaque tokens in an httpOnly cookie, not JWTs. Revocation has to be
immediate — an admin deactivating an employee mid-shift, a user signing out
on a shared machine — and a stateless token cannot give that without a
denylist, which is a session table with extra steps.

- 32 random bytes, base64url, returned once and set as `fe_session`
  (`httpOnly`, `sameSite=lax`, `secure` in production).
- Only the SHA-256 of the token is stored. A database dump does not grant
  sessions.
- The frontend never holds a token, so there is nothing in JS for an XSS
  payload to exfiltrate.
- `SessionService.resolve` re-derives the accessible project list on every
  request. Membership changes take effect on the next call, not at token
  expiry.

Login is rate-limited to 10 attempts per minute per IP, and the failure
path performs a dummy bcrypt comparison so "no such account" and "wrong
password" take the same time — otherwise the endpoint is a user-enumeration
oracle.

## Roles and permissions

Two roles, ADMIN and EMPLOYEE, resolved against a named capability list in
`packages/auth/src/policy.ts`. Routes declare what they need:

```ts
@Get(':projectId')
@RequirePermissions(Permission.PROJECT_READ)
getOne(@Tenant() ctx: TenantContext, @Param('projectId') projectId: string) { … }
```

`PermissionsGuard` checks the capability *and* the `:projectId` route param
against the caller's membership before the handler runs, so a handler
cannot forget to scope itself.

Role is only half the decision; membership is the other half. An ADMIN's
membership happens to cover their whole organization, which is a property
of how it resolves — not a branch in the code.

Guards run in order: authenticate (`AuthGuard`), authorize
(`PermissionsGuard`), rate-limit (`ThrottlerGuard`). Authentication is
default-on: routes opt out with `@Public()`, because opt-in protection
makes a forgotten decorator a silent leak.

## Why access failures return 404

Answering 403 for a project that exists in another tenant confirms it
exists. `HttpExceptionFilter` converts `TenantAccessError` and
`PermissionDeniedError` into a 404 with a generic body and logs the real
reason with the request id.

## Frontend

Route protection in `apps/web` is UX only. Editing the URL gets you an
empty screen, not someone else's data — the server is the boundary. On
sign-out the entire query cache is cleared, not just the session, so the
next user on the machine sees nothing of the previous one.

## Not yet implemented

Password reset, MFA, SSO, and session listing/revocation UI. When they
land they belong in `AuthModule` alongside the existing primitives.
