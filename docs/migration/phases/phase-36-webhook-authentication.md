# Phase 36 — webhook authentication

**Status: PASS.** Scope: `backend/tenant-security/**` (security configuration only),
`backend/channels/**` (webhook controller + tests). The state of `firstelement` is
**unchanged** — no DDL, no DML, no stamp. Testcontainers only.

---

## 1. The question

The contract agent, re-declaring response codes, found this and correctly refused to
touch it:

> `SecurityConfiguration.PUBLIC_PATHS` does not include `/webhooks/**`, and
> `anyRequest().authenticated()` therefore applies — so an unauthenticated provider
> POST would get `401`, which contradicts `ChannelWebhookController`'s own javadoc
> ("these are the only unauthenticated write paths in the API").

Two readings were available and the code supported neither on its face, so the
question was settled by measurement rather than by reading the configuration.

---

## 2. The fact, measured

An unauthenticated `POST /webhooks/meta/{connectionId}`, carrying a **correctly
computed** `X-Hub-Signature-256` over a realistic WhatsApp payload, answered:

| Request | Status | Code |
| --- | --- | --- |
| valid signature, no cookies (what Meta actually sends) | **403** | `CSRF_TOKEN_INVALID` |
| valid signature **plus** a CSRF cookie and header | **401** | `AUTHENTICATION_REQUIRED` |
| `GET /webhooks/meta/{id}` handshake, correct verify token | **401** | `AUTHENTICATION_REQUIRED` |
| unsigned POST (tampered body / wrong secret / no header) | **403** | `CSRF_TOKEN_INVALID` |
| `GET /api/v1/channels/capabilities`, no session | 401 | `AUTHENTICATION_REQUIRED` |

**No unauthenticated provider request could ever succeed, at any status.** This is
the third option the orchestrator anticipated — it depends on something in between:

1. `CsrfFilter` runs before authorization, so a real provider POST was answered
   **403 CSRF_TOKEN_INVALID** and never reached the controller.
2. Satisfying CSRF was not enough: `anyRequest().authenticated()` then answered
   **401 AUTHENTICATION_REQUIRED**.

So the outage was real and total, and the contract's derived `401` was *truthful but
incomplete* — it described the outcome for a request that had already cleared CSRF,
which no real provider ever does. In production the inbox received no inbound
traffic from Meta, Telegram or the mock connector.

**The configuration was wrong. The javadoc was right.** They disagreed because the
javadoc documented the design and the configuration implemented a superset of the
default-deny rule that nothing had ever exercised: no HTTP test had performed an
unauthenticated provider POST, so neither the outage nor the intent was proven.

---

## 3. The ruling

Two changes, both scoped to the webhook prefix, in
`SecurityConfiguration`:

1. **`/webhooks/**` added to `PUBLIC_PATHS`.** A provider has no FirstElement
   session, so a session requirement is not a control here — it is a rule that
   rejects every caller.
2. **`CSRF_EXEMPT_PATHS = { "/webhooks/**" }`**, wired through
   `csrf.ignoringRequestMatchers`. CSRF defends against a third-party site
   inducing a *browser* to attach ambient cookie authority. A provider carries no
   cookie; requiring a token would leave the permit entry unreachable in production.

Point 2 is the part that reading the configuration alone would have missed. A
`permitAll` on its own moves the failure from 401 to 403 and fixes nothing.

`PUBLIC_PATHS` is read by reflection from `RefusalRules` in
`backend/app-bootstrap`, so the `401` declaration on `/webhooks/**` **disappears on
the next OpenAPI export with no document edit**. That is the intended mechanism: the
contract derives the fact from the filter chain rather than restating it.

### Why the exemption is not "permit anything"

The control on these paths is **provider-signature verification**, which is unchanged
and now actually reachable:

| Case | Answer | Proven to be the verifier's, because |
| --- | --- | --- |
| valid signature | 200 `{"received":true}`, queued | `outbox_events` +1, `lastError` cleared |
| signature absent | 404 `NOT_FOUND` | `channel_connections."lastError"` = `Webhook verification failed` |
| tampered body, well-formed header | 404 `NOT_FOUND` | same |
| wrong secret | 404 `NOT_FOUND` | same |
| malformed header (`sha1=`, `nothex`, no `=`) | 404 `NOT_FOUND` | same |
| Telegram missing/wrong shared secret | 404 `NOT_FOUND` | same |
| disabled connection, wrong family, unknown id | 404 `NOT_FOUND` | `lastError` **absent** — refused before the verifier |

A 404 alone proves nothing, because a nonexistent connection produces one too. The
tests distinguish the two refusal paths by a second independent observation: the
`lastError` row, which only `InboundWebhookIntakeService` writes. The HTTP answer is
byte-identical in both cases, which is what makes the surface non-enumerable.

---

## 4. A second defect this found, which the fix would otherwise have created

`ChannelWebhookController#mock` **never consulted `firstelement.channels.mock-webhook-enabled`.**
The flag defaults to `false`, `ChannelsProperties#assertSafeFor` refuses to boot a
production profile with it on, and `InboundWebhookIntakeService#mockWebhookAllowed()`
existed and was called by nobody. Measured after adding the permit entry:

```
POST /webhooks/mock/{connectionId}, unsigned, no session  ->  200, queued
```

That was masked before this change by the very session requirement being fixed. Adding
`permitAll` alone would have **created an unauthenticated, unsigned write into every
tenant's inbox** on any non-production deployment — staging, a demo, a laptop — which
is exactly where real connections are often copied. This is the "permit a path and
accidentally permit anything" failure in its literal form.

Fixed by gating the route on `intake.mockWebhookAllowed()` per request, answering the
same 404. `assertSafeFor` is a startup check; a default-off flag nothing reads is not
a control.

---

## 5. The boundary did not widen

Asserted over HTTP, unauthenticated:

- `GET /api/v1/channels/capabilities`, `/api/v1/channels/connections`,
  `/api/v1/inbox/unassigned` → **401 `AUTHENTICATION_REQUIRED`**
- the same reads with `X-Organization-Id` / `X-Tenant-Id` headers → **401**. A
  client-supplied tenant id is a request, not authorization.
- `POST /api/v1/dev/mock/incoming-message` → **403 CSRF_TOKEN_INVALID** without a
  token, **401 AUTHENTICATION_REQUIRED** with one
- a *validly signed webhook payload* presented as a header to an `/api/v1` read →
  **401**. A signature is not a credential outside `/webhooks/**`.
- `PUBLIC_PATHS` contains `/webhooks/**` and does **not** contain `/**`, `/api/v1/**`,
  `/api/**`, `/*` or `/webhooks`; the other eight entries are unchanged and the set is
  asserted at size 9.

---

## 6. Enumeration: a new route cannot ship closed or open

`WebhookAuthenticationBoundaryHttpIntegrationTest` reads the routes from the running
`RequestMappingHandlerMapping`, not from a list of controllers, so a webhook added
next month in any module is covered whether or not the test file is updated. For each
mapped `/webhooks/**` route it asserts:

- the route is covered by `SecurityConfiguration.PUBLIC_PATHS` (else it ships closed),
- the route is covered by `SecurityConfiguration.CSRF_EXEMPT_PATHS` (else the
  exemption is unreachable in production),
- `CSRF_EXEMPT_PATHS` contains **exactly** `/webhooks/**` and nothing else.

Today: `GET|POST /webhooks/meta/{connectionId}`, `POST /webhooks/telegram/{connectionId}`,
`POST /webhooks/mock/{connectionId}` — enumerated, not restated.

---

## 7. Red to green

| State | Result |
| --- | --- |
| Before any change | **24 tests, 20 failed** — every webhook route 403/401, never 200 |
| With both configuration changes | **24 tests, 0 failed** |
| `git checkout` the configuration only | **24 tests, 19 failed** |
| Configuration restored | **24 tests, 0 failed** |

---

## 8. Environment

```
$env:JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
.\gradlew.bat :backend:channels:test --tests "com.firstelement.channels.WebhookAuthenticationBoundaryHttpIntegrationTest"
.\gradlew.bat :backend:channels:test :backend:tenant-security:test
.\gradlew.bat :backend:identity-access:test :backend:app-bootstrap:test
```

Real PostgreSQL 16 (`pgvector/pgvector:pg16`) via Testcontainers for every
integration assertion. No test skipped, no test disabled. `gradlew --tests X` with no
task name was not used.

**Encoding note.** The channels module compiles with `options.encoding = "UTF-8"`, but
a payload whose Arabic literal was written in a non-UTF-8 file becomes `?????` — still
valid JSON, so it silently becomes a *different, shorter* payload rather than failing.
The test payloads therefore use `\uXXXX` escapes. Related: the existing
`WebhookVerificationTest.BODY` fixture is **malformed JSON** (one `}` short of a
`change` object). It is harmless there because that suite only tests the verifier,
which never parses — but it is not a "realistic payload" and should not be copied.

---

## 9. Not done here

- `tests/contracts/openapi.json` is untouched; the orchestrator promotes it. The `401`
  declarations on `/webhooks/**` will drop on the next export.
- `docs/migration/migration-ledger.md` untouched.
- The unauthenticated **mock** endpoint remains available when a developer explicitly
  sets `firstelement.channels.mock-webhook-enabled=true` in a non-production profile.
  That is a deliberate development affordance, now actually gated.