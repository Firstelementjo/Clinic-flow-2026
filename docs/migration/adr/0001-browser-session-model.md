# ADR-0001 — Browser Session Model

Status: PROPOSED

## Context

Today the API issues an opaque session token stored as a SHA-256 hash in the
`sessions` table and delivered as an `HttpOnly` cookie (`SESSION_COOKIE_NAME`,
Secure in production, SameSite, TTL from `SESSION_TTL_HOURS`). The React app
carries no bearer token in localStorage; every request rides the cookie.
`PR.md` requires no long-lived tokens in browser storage and server-side
revocation.

## Decision

Target: preserve the cookie-backed server-side session contract in Spring
Security. Browser client authenticates via the same HttpOnly/SameSite/Secure
cookie; session invalidation and the session store remain server-side.
OAuth2 resource-server JWT auth is a separate, explicit path for
service-to-service calls only.

## Consequences

- Angular HttpClient must send credentials (`withCredentials`) and the
  Spring CORS allowlist stays an explicit origin.
- CSRF protection must be added for cookie-authenticated mutations
  (token endpoint or SameSite=strict + custom-header check).
- Session-revocation tests must carry over; current unit tests in
  `packages/auth/src/session-token.test.ts` define the expected cookie
  flags and hash storage.
- Old and new session tables must be reconciled so existing logins survive
  cutover, or a forced re-login window must be approved.

---

## Phase-3 outcome (2026-10-04, appended — not a status change above)

**Status after this phase: ACCEPTED as implemented.** The prose above is left as
written; this section records what was built, what was verified, and the two
places the implementation is deliberately stricter than the Decision section.

### Implemented as written

The browser still authenticates with an opaque token in an `HttpOnly` cookie,
and the session store and invalidation remain server-side. Concretely:

| Decision element | Implementation |
| --- | --- |
| Opaque token, not a JWT | `SessionTokenCodec` — 32 random bytes, base64url, no padding; only the lowercase hex SHA-256 is stored in `sessions."tokenHash"` |
| Server-side session and revocation | `DatabaseBrowserSessionStore` over the same `sessions` table; `revoke()` and `revokeAllForUser()` take effect on the **next** request because the row is re-read per request |
| OAuth2 resource server is a separate path | `OAuth2ResourceServerConfiguration`, order `1` (ahead of the browser chain at `-100`), restricted to `/api/v1/integrations/**`, **disabled by default**, and `IntegrationJwtAuthenticationConverter` rejects a `USER` principal type outright |

No forced re-login window is needed. `SessionTokenCodec.hash` is asserted
byte-identical to the legacy `createHash('sha256').digest('hex')`, the cookie name
is still `fe_session`, and the flag set (`HttpOnly`, `SameSite=Lax`, `Path=/`,
`Secure` in production) is unchanged — so a cookie minted before the cutover
resolves after it.

### Deliberately stricter than the Decision section

1. **CSRF is required, on login as well as on mutations.** The Decision listed CSRF
   as a consequence; the inventory had recorded that the legacy runtime had *no*
   CSRF token on cookie-authenticated mutations at all, so requiring it is a
   strengthening, not a port. `GET /api/v1/auth/csrf` exists so a client can obtain
   a token before its first mutation; a CSRF token is not a credential and
   authorizes nothing without the session cookie.
2. **`SessionCreationPolicy.STATELESS`.** The Decision says the session store stays
   server-side; this goes one step further and makes a JVM-local `HttpSession`
   impossible. A second servlet-local copy of the session is exactly what
   `revoke()` could not reach, and is the usual cause of "logout did not log out"
   after a horizontal scale-out.

### One consequence of tightening

`/api/v1/openapi` and `/api/v1/docs` were public in Phase 1 and are now
authenticated. An operator opening Swagger UI from the SPA origin will see an
empty spec, because `fetch` does not attach the session cookie cross-origin.
The fix is one property, `springdoc.api-docs.use-management-port: true`, which
routes the docs page's fetch through the same authenticated chain. It belongs to
the application module's `OpenApiConfig`/`application*.yml`, so it is reported
to the orchestrator rather than applied here.

See `docs/migration/phases/phase-03.md` for the full boundary, the exact
verification commands, and the negative-test evidence against real PostgreSQL.
