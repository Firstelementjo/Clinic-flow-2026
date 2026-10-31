# Phase 3 — Tenant context, identity, and the Spring Security boundary

## Goal

Establish the security boundary **before** any business feature is migrated: a
server-resolved `TenantContext`, an explicit principal taxonomy, a revocable
cookie-backed session, default-deny request and method security, repository/query
policy helpers with field redaction, and negative integration tests for every scope
and principal type — against real PostgreSQL.

No business module was migrated, no row was written outside a disposable Testcontainers
database, and no legacy Node code was touched.

## Scope (owned by Agent C)

`backend/tenant-security/**`, `backend/identity-access/**`,
`backend/app-bootstrap/build.gradle.kts` (module registration + dependencies only),
root `settings.gradle.kts` (module registration only),
`docs/migration/phases/phase-03.md`, and the append-only outcome section in
`docs/migration/adr/0001-browser-session-model.md`.

Not touched: `backend/shared-kernel/**`, `backend/database/**`, `ops/**`, `apps/**`,
`packages/**`, `backend/app-bootstrap/src/**`.

## Delivered

### 1. `backend/tenant-security` — the boundary (28 production classes)

| Concern | Type | Notes |
| --- | --- | --- |
| Immutable scope | `TenantContext` | organization, server-resolved `projectIds`, capabilities, session. Defensive copies; role and capabilities are mutually exclusive by construction. |
| Thread binding | `TenantContextHolder` | `call`/`run`/`wrap`; `get()` throws rather than returning null so an unscoped read is a loud failure. |
| Principal taxonomy | `PrincipalType`, `Capability` | `USER`/`SERVICE`/`CONNECTOR`/`WORKER`/`AI_AGENT`. Machine principals have **no role** and are authorized only by capability, with a closed `mayBeHeldBy` table that rejects an inconsistent combination at construction. |
| Authorization | `AuthorizationService`, `RolePermissions`, `PolicyResource` | grant → tenant boundary → project boundary → owner boundary, in that order. Every denial is published. |
| Method security | `@RequirePermission`, `@RequireAnyPermission`, `@RequireLiveSession`, `FirstElementPermissionEvaluator` | Templated meta-annotations via `AnnotationTemplateExpressionDefaults`. Evaluator denies any `Authentication` it does not recognise. |
| Session authentication | `BrowserSessionAuthenticationFilter`, `SessionTokenCodec`, `BrowserSessionStore` (port) | Cookie → DB session, re-resolved on every request. |
| Passwords | `PasswordVerifier` | bcrypt cost 12 (verifies legacy `bcryptjs` hashes) plus `burnComparison()` for constant-time failure. |
| Rate limiting | `LoginRateLimiter` | Per address **and** per account (email only, so source rotation cannot reset it). Defaults 10 / 60 s, the legacy `@Throttle` value. |
| Request security | `SecurityConfiguration`, `UnmappedRequestMatcher`, `SecurityProperties` | default deny, CSRF, CORS allowlist, header policy, STATELESS session. |
| Error contract | `PolicyDeniedException`, `AuthenticationFailedException`, `RateLimitedAuthenticationException`, `SecurityErrorRenderer`, `SecurityExceptionAdvice` | Non-disclosing 401/403/404/429 bodies in the Phase-1 `ErrorResponse` shape. |
| Query policy | `TenantQueryGuard`, `TenantScope`, `ScopedWhere` | A client project/tenant id is validated **before** a statement exists. |
| Field redaction | `FieldRedactionPolicy`, `FieldRedactor` | `NEVER_EXPOSED` cannot be configured away, even for `ADMIN`. |
| Job boundary | `TenantScopedExecutor` | Binds the context **and** a `SecurityContext`, so `@RequirePermission` works off-HTTP. Refuses a job with no scope. |
| Realtime | `RealtimeTopicPolicy` | `fe:org:<uuid>` / `fe:project:<org>:<project>`; subscription lists are filtered and every rejection recorded. |
| AI tools | `AiToolDescriptor`, `AiToolExecutionGuard` | Registered+enabled, capability-checked, allow-listed arguments, scope-override arguments rejected outright, high-risk needs human approval. |
| Integrations | `IntegrationJwtAuthenticationConverter`, `OAuth2ResourceServerConfiguration` | Optional, off by default, integration paths only, rejects a `USER` principal type. |
| Audit port | `SecurityEvent`, `SecurityEventType`, `SecurityEventSanitizer`, `SecurityEventPublisher` | 20 event types; sensitive-looking metadata keys are mechanically replaced. |

### 2. `backend/identity-access` — identity data (10 production classes)

`UserRepository`, `ProjectMembershipRepository`, `DatabaseBrowserSessionStore`,
`AuditLogSecurityEventPublisher`, `LoginService`, `MembershipAdminService`,
`EffectivePermissionService`, `AuthenticationController`,
`PermissionInspectorController`, `IdentityAdminController`.

Every statement maps **explicitly** to the verified physical schema: quoted camelCase
identifiers written by hand, `LocalDateTime` for every timestamp, explicit
`cast(:role as "UserRole")` for the enum columns. No naming strategy exists anywhere.

### 3. Endpoints

| Method | Path | Policy |
| --- | --- | --- |
| POST | `/api/v1/auth/login` | public + CSRF-protected + rate-limited |
| POST | `/api/v1/auth/logout` | live session + CSRF; revokes the row |
| GET | `/api/v1/auth/me` | live session |
| GET | `/api/v1/auth/csrf` | public (a token is not a credential) |
| GET | `/api/v1/permissions/me` | live session |
| GET | `/api/v1/permissions/{userId}` | `@RequirePermission(PERMISSION_INSPECTOR_READ)` |
| PATCH | `/api/v1/admin/identity/users/{userId}/role` | `USER_MANAGE`, audited, revokes target sessions |
| DELETE | `/api/v1/admin/identity/users/{userId}` | `USER_MANAGE`, deactivate + revoke |
| POST/DELETE | `/api/v1/admin/identity/users/{userId}/projects/{projectId}` | `MEMBERSHIP_MANAGE`, audited, revoke |
| DELETE | `/api/v1/admin/identity/sessions/{sessionId}` | `SESSION_REVOKE`, audited |

No endpoint accepts SQL, a column name, a shell command, or a secret.

## Key decisions

| Decision | Rationale |
| --- | --- |
| Scope denial → **404**, role denial → **403** | A cross-tenant id and a nonexistent id must be indistinguishable, or the status code is an existence oracle (FR-2.7). Two denial types make that structural. |
| `SessionCreationPolicy.STATELESS` | The session lives in PostgreSQL. A servlet `HttpSession` would be a second, JVM-local copy that `revoke()` cannot reach — the usual cause of "logout did not log out" after scale-out. |
| CSRF on **login** too | Login-CSRF is real; `GET /api/v1/auth/csrf` lets a client fetch a token first. The inventory records that the legacy runtime had **no** CSRF at all, so this is a strengthening. |
| Non-XOR `CsrfTokenRequestAttributeHandler` + `CookieCsrfTokenRepository.withHttpOnlyFalse()` | The documented Angular pairing: the raw token is in a JS-readable cookie and echoed verbatim in `X-XSRF-TOKEN`. BREACH-style XOR would not match. |
| CORS from `firstelement.api.web-origin`, comma-separated, wildcard rejected at startup | `allowCredentials=true` plus a wildcard is not a thing that can be made safe; failing to boot beats booting open. |
| An unmapped path answers 404, not 401 | `anyRequest().authenticated()` stays the catch-all, narrowed only by a handler-mapping-aware matcher that **fails closed**. Keeps default-deny for everything that exists while preserving a non-disclosing error contract. |
| Capability grants for machine principals, role grants for humans, never mixed | PROJECT_REQUIREMENTS §3.4. Enforced by the `TenantContext` constructor, so "promote the workflow job to administrator" has no representation to express it in. |
| Membership, role, and activation changes revoke every session of the target | Without it, an employee demoted to `EMPLOYEE` keeps their `ADMIN` reach until the cookie expires — immediate revocation failing in the most damaging direction. |
| Self-demotion refused | Losing the last administrator of a tenant is a one-way door, and an authenticated request is the only cheap place to notice. |
| `EMPLOYEE_PERMISSIONS` unchanged | The inventory records the clinic module shipping with no permission decorators. Fixing that is a per-route decision by the clinic/CRM owner, not a blanket widening here. |
| Audit write is `REQUIRES_NEW` and never throws | An audit trail that vanishes with the rolled-back operation it describes is not a trail; and a failing audit insert must not fail legitimate traffic. |
| Login limiter is per-process | Recorded as a follow-up; moving counters to Redis is additive. The control's behaviour is unchanged and it fails closed. |

### Legacy behaviour preserved exactly

- `SESSION_COOKIE_NAME = fe_session`; SHA-256-hex `tokenHash`; only the hash stored.
- `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` in production.
- bcrypt cost 12; the same `DUMMY_PASSWORD_HASH` constant, so the failed-login cost matches.
- Login limit 10 / 60 s.
- Project scope recomputed from `project_memberships` on **every** request.
- An `ADMIN` reaches every project of their own organization, archived included.
- No forced re-login window: an existing legacy cookie resolves unchanged.
- `audit_logs.projectId` stays null for security events, as every one of the 43 live rows does.

## Verification (exact commands, Windows, Docker Desktop 29.8.1, JDK 24.0.1)

Full build, every task re-executed, no skipped test:

```
.\gradlew.bat build --rerun-tasks --no-build-cache --no-daemon --console=plain
```

→ `BUILD SUCCESSFUL in 3m 30s`. **149 tests, 0 failures, 0 skipped.**

| Suite | Tests |
| --- | --- |
| `TenantIsolationQueryIntegrationTest` (real pgvector/PostgreSQL 16) | 34 |
| `SecurityBoundaryHttpIntegrationTest` (MockMvc + real PostgreSQL) | 20 |
| `AuthorizationServiceTest` | 15 |
| `TenantQueryGuardTest` | 12 |
| `SessionTokenCodecTest` | 10 |
| `LoginRateLimiterTest` | 9 |
| `TenantContextTest` | 9 |
| `FieldRedactorTest` | 7 |
| `LoginRateLimitIntegrationTest` (MockMvc + real PostgreSQL) | 5 |
| `NonHttpBoundaryTest` (jobs / realtime / AI tools) | 18 |
| `ApiApplicationSmokeTest`, `FlywayBaselineAcceptanceTest`, `CorrelationIdFilterTest`, `WorkerApplicationSmokeTest` | 10 |

> `--rerun-tasks --no-build-cache` is required. A plain `build` restores the Gradle cache
> and reports up-to-date without executing anything.

Module-scoped, for iteration:

```
.\gradlew.bat :backend:tenant-security:test  --no-daemon --console=plain
.\gradlew.bat :backend:identity-access:test  --no-daemon --console=plain
```

### Live boot evidence (a real jar, not a test context)

```
.\gradlew.bat :backend:app-bootstrap:bootJar --no-daemon --console=plain
$env:SPRING_FLYWAY_ENABLED='false'
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/firstelement_phase3_smoke'
$env:MANAGEMENT_HEALTH_DB_ENABLED='false'
$env:MANAGEMENT_HEALTH_REDIS_ENABLED='false'
$env:API_PORT='4999'
java -jar backend\app-bootstrap\build\libs\firstelement-api-0.1.0.jar
```

| Probe | Result |
| --- | --- |
| `GET /api/v1/status` | `200` |
| `GET /actuator/health` | `200` |
| `GET /api/v1/auth/csrf` | `200`, `XSRF-TOKEN=…; Path=/; SameSite=Lax` |
| `GET /api/v1/auth/me` | `401 AUTHENTICATION_REQUIRED` |
| `GET /api/v1/permissions/me` | `401 AUTHENTICATION_REQUIRED` |
| `GET /api/v1/openapi` | `401` |
| `GET /api/v1/docs` | `401` |
| `GET /api/v1/does-not-exist` | `404` `{"code":"NOT_FOUND",…,"requestId":"…"}` |
| `OPTIONS /api/v1/auth/login`, Origin `http://localhost:5173` | `200`, `Access-Control-Allow-Origin` echoed, `Allow-Credentials: true` |
| `OPTIONS /api/v1/auth/login`, Origin `https://evil.example` | `403` |
| headers on every response | `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, `Referrer-Policy: no-referrer`, `Permissions-Policy: geolocation=(), camera=(), …`, `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` |
| boot log grep for a generated Spring Security password | **0 matches** |

`Strict-Transport-Security` is intentionally absent on a plain-HTTP request and is
asserted over a TLS request in `SecurityBoundaryHttpIntegrationTest`.

Post-boot, the development database was re-checked and is **untouched**:

```
docker exec firstelement-postgres psql -U firstelement -d firstelement -t -A `
  -c "select count(*) from pg_tables where schemaname='public' and tablename='flyway_schema_history';"
# 0
docker exec firstelement-postgres psql -U firstelement -d postgres -Atc `
  "select datname from pg_database where datistemplate=false order by 1;"
# firstelement, postgres
```

Flyway was disabled for the boot precisely so the Phase-2 baseline was **not** stamped
against `firstelement`; that remains an orchestrator-owned, backup-gated action.

## Security / tenancy checks

- **No membership ⇒ no project scope.** `nobody@a` has no `project_memberships` row; the
  guard refuses all three project ids, and a tenant-wide read still returns 2 of 3 rows
  (never the other tenant's).
- **Cross-tenant read** → `PolicyDeniedException`; the response body is byte-identical to a
  nonexistent id (asserted over HTTP and at the service layer).
- **Cross-project read/write** → refused for an `EMPLOYEE`; a tenant-scoped `UPDATE` and
  `DELETE` cannot reach the other tenant's row (`0` rows affected, value unchanged).
- **Unauthorized aggregate** → `count(*)`, `count(distinct projectId)` and
  `count(distinct organizationId)` over a tenant-scoped statement; the organization count is
  `1`, never the global total.
- **Field redaction** → a real `customers` row: `primaryPhone`/`notes` replaced with the
  constant placeholder, keys retained, values provably gone; `passwordHash` redacted even
  for `ADMIN` and absent from every `UserView`.
- **Session revocation** → `revoke()` stops resolution on the next call; membership change,
  role change, and deactivation all revoke every live session and the next login reflects
  the new scope.
- **CSRF** → mutation without a token `403 CSRF_TOKEN_INVALID` and no session created; login
  is covered.
- **CORS** → allowlisted origin credentialed; any other origin `403` and no
  `Access-Control-Allow-Origin` header on a normal request.
- **Rate limit** → the limiter runs before the account lookup; flooding one address with an
  unknown account does not lock the legitimate account from another address.
- **Audit on denial** → `Authorization.Denied`, `Access.Denied`, `User.Login.Failed`,
  `Identity.RoleChanged`, `Identity.MembershipChanged`, `Ai.ToolDenied`,
  `Realtime.SubscriptionDenied`, `Job.UnscopedRejected`, `Security.CsrfRejected` all
  asserted on the real `audit_logs` table.
- **Audit sanitization** → a `password`/`token` key a caller tried to record is replaced
  with `[redacted]`; the submitted email and password appear nowhere.
- **Audit failure is survivable** → renaming `audit_logs` mid-test does not change an
  authorization decision.
- **AI tool** → cross-project, disabled tool, missing capability, undeclared argument,
  scope-override argument, and high-risk-without-approval all denied and recorded; the
  record carries argument *names*, never values.
- **Jobs** → no `TenantContext` ⇒ `UnscopedJobException`; a job's scope is its own, not a
  leftover HTTP scope; a worker thread does not inherit the submitting thread's tenant.
- **Realtime** → another tenant's project topic refused and recorded; a client topic list is
  filtered, not failed.
- **No secrets** read or printed. No `.env` read. The boot smoke used an environment variable
  pointing at a non-existent database; no credential was displayed.

## Migration / data impact

- **Nothing was migrated.** No Flyway migration was added or applied. `ops/flyway/**` and
  `backend/database/**` are untouched.
- `firstelement` is unchanged: still 45 application tables, still **0**
  `flyway_schema_history` rows, still only `firstelement` and `postgres` databases.
- All test data lives in disposable Testcontainers `pgvector/pgvector:pg16` instances,
  reclaimed by Ryuk.
- No `db push`, no `prisma migrate`, no DDL against any persistent database.

## Known risks / debt carried forward

1. **The `EMPLOYEE` grant set is the legacy one, so it is incomplete.** The clinic and
   appointments modules ship with no permission decorators (inventory §9). Phase 3 does not
   widen the employee grant; the CRM/clinic owner must decide those per-route permissions.
   Until then, clinic endpoints remain under `anyRequest().authenticated()` plus their own
   service-layer checks.
2. **`OpenApiConfig` must use `useManagementPort`** so Swagger UI can reach the contract
   through the authenticated chain (follow-up below).
3. **The login rate limiter is per-process state.** With N replicas the effective limit is
   N × configured. Redis counters are the fix and are additive.
4. **Audit/security stream separation is by convention.** `SecurityEventType` names which
   constants are security events, but they still land in the single `audit_logs` table
   because Phase-2 migration `V202610040008` is an inert `.sql.todo` this phase may not
   promote. The split moves with the migration.
5. **AI-trace child tables still inherit tenant scope** (Phase-2 debt). `TenantQueryGuard`
   covers application access; PostgreSQL RLS and the child `organizationId` columns remain
   unapplied.
6. **`TenantScopedExecutor` binds a `SecurityContext`.** A worker thread must therefore use
   it (or bind both contexts) rather than only `TenantContextHolder`, or `@RequirePermission`
   denies for the wrong reason.
7. **No OpenAPI contract test** pins the new endpoints' shapes.
8. **Cross-tenant coverage is limited to the tables this phase may read.** CRM, inbox,
   finance, export, and vector paths are not yet written; their negative tests arrive with
   their owning phases.
9. **No ArchUnit rule** yet forbids a `@RestController` from being the only enforcement
   point; that is a quality-agent gate.
10. **Gradle build caching will report `:backend:*:test` as up-to-date** without running it.
    Until CI enforces it, always pass `--rerun-tasks --no-build-cache`.

## Follow-up for orchestrator

1. **`OpenApiConfig` (shared by Phase 1) needs `springdoc.api-docs.use-management-port: true`.**
   `/api/v1/openapi` and `/api/v1/docs` are now authenticated, and Swagger UI loads the
   contract with `fetch`, which does **not** attach the session cookie cross-origin. Without
   this property the docs page renders an empty spec for an operator. One property change in
   `backend/app-bootstrap/src/main/resources/application*.yml`; it needs the Phase-1 owner.
2. **Decide whether the Admin UI may read `/api/v1/permissions/{userId}`** for a user outside
   its own tenant. Today it gets `404`, which is deliberate.
3. **Schedule the first Flyway-enabled boot against `firstelement`** (baseline stamp +
   `V20260814000100`). It was deliberately *not* done in this phase.
4. **Approve Redis-backed login limiter counters** before the first multi-replica deployment.
5. **Decide the `audit_logs` → `SecurityEvent`/`AdminAuditEvent` split** by promoting
   `V202610040008`, or accept the convention-only split.
6. **Security review of the four new Phase-3 permissions** (`security-event:read`,
   `permission-inspector:read`, `session:revoke`, `user:manage`, `membership:manage`),
   all granted to `ADMIN` only.
7. **Quality agent:** wire `./gradlew build --rerun-tasks --no-build-cache` and a Docker
   availability gate into CI so a cached result is never read as a pass.
8. **Confirm the clinic/CRM permission decisions** before those modules are migrated.
## Orchestrator close-out (2026-10-04)

- Blocking follow-up #1 is resolved: the OpenAPI/Swagger surfaces and Actuator now
  serve from the separate operator management port (`MANAGEMENT_PORT`, default 4101)
  and stay behind the authenticated security chain. Verified on a live boot:
  `GET :4999/api/v1/status` 200, `GET :4999/api/v1/openapi` 401 (not on the API port),
  `GET :4998/api/v1/openapi` 401 unauthenticated, `GET :4998/actuator/health` 200.
  Rationale and the remaining open items are recorded in
  `docs/migration/adr/0005-phase2-3-decisions.md`.
- The five new ADMIN-only permissions (`security-event:read`, `permission-inspector:read`,
  `session:revoke`, `user:manage`, `membership:manage`) are accepted for this phase.
  `membership:manage` has no legacy equivalent and is reviewed in the next security pass.
- Independent verification: `./gradlew build --rerun-tasks --no-build-cache --no-daemon`
  ? BUILD SUCCESSFUL; aggregated JUnit XML = **149 tests, 0 failures, 0 skipped**.
  The dev database `firstelement` was re-checked afterwards: still 45 tables, still
  **0** `flyway_schema_history` rows.
