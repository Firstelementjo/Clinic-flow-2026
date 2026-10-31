# ADR 0013 — Phase 11 rulings: clinic analytics, contract defects, and an unreproduced API stall

Date: 2026-10-06
Status: **Accepted** (orchestrator rulings on the Phase 11 group 1-4 findings)

---

## 1. Clinic analytics cards: a backend gap, not a frontend omission

Phase 11 group 4 (dashboard) could not build the funnel, top-procedures or
clinician-workload cards, because **no analytics read model exists in the
contract**. The agent substituted a queue-state funnel derived from real
`totals.byStatus` data rather than porting the legacy client's hard-coded
fallbacks.

That substitution is correct and I am endorsing it explicitly. The legacy cards
rendered **hard-coded numbers**, which means a clinic manager was shown a figure
that was never computed from anything. An honest derived count is worth more than
a confident fake one, and shipping the fake would have preserved a defect that has
been invisible precisely because it looked like a dashboard.

**Confirmed as a real scope gap.** `PROJECT_REQUIREMENTS.md` line 45 records
analytics as one of the nine vertical slices the repository documents as
implemented, and line 41 lists the domain schema behind it. Phase 8 delivered
*finance* analytics (usage, cost, revenue, margin, forecast); **CRM and clinic
analytics were never migrated**.

**Decision:**

1. The dashboard keeps the derived funnel. No hard-coded figure returns, ever.
2. A **CRM/clinic analytics read model** is required before Phase 12: conversation
   funnel by status, top procedures, clinician workload. It belongs in the CRM
   module (it reads conversations, cases, appointments, procedures and employees),
   not in finance — these are operational counts, and per ADR-0007 a mutable
   dashboard count is never finance truth.
3. Every metric must **drill to source rows**, exactly as Phase 8's finance totals
   do. A dashboard number a reader cannot trace is the same class of defect as a
   hard-coded one.
4. When those routes exist, the Angular dashboard gains the real cards and the
   derived funnel stays as the empty-data case, not as a substitute.

## 2. Five contract defects — accepted, each asserted as a failing test

The agent found these in `tests/contracts/openapi.json`. I agree with all five.
They are asserted in the frontend suite, so **a backend fix turns them green** —
which is the correct direction: the test documents the defect and the fix removes
it.

| # | Defect | Why it matters |
| --- | --- | --- |
| 1 | `GET /api/v1/auth/csrf` declares a **required `token` query parameter** typed `CsrfToken` | Uncallable by any conformant client: the caller must already hold the token it is being asked for. Needs `@Parameter(hidden = true)`. |
| 2 | **Zero 4xx/5xx responses documented** across all 142 operations | `ErrorHandlingAdvice` returns seven distinct error codes and the contract describes none of them. A generated client cannot type an error it has never heard of. |
| 3 | **429 sends no `Retry-After`** | `LoginRateLimiter.Decision` computes `retryAfterSeconds` and the response discards it, so a client must guess its own backoff. |
| 4 | **`limit` typed `string`** on three routes, `integer` on six siblings | Same parameter, two types. Generated clients end up with incompatible signatures for what is one concept. |
| 5 | **139 responses use `*/*`**, 33 use `application/json` | A client cannot recognise `text/event-stream` for the SSE route, which is exactly the route where the typing carries information. |

Defects 2, 3 and 5 are the ones that matter most, because they are about *error*
and *streaming* surfaces — the parts a generated client handles worst and the
parts an operator reads under pressure.

**Decision:** these are backend fixes in the owning modules, then the document is
re-promoted with `export-openapi-document.ps1` in the same commit. A contract
defect is fixed in the code, never in the document.

## 3. Generate the client on demand, verify in CI — confirmed

The agent chose generate-on-demand with a CI drift check, over committing a
generated client. Correct: a committed generated client drifts silently, and the
Phase 7 exporter had already written wrong paths into a committed contract file
once. Its note that a naive `git status --porcelain` check passes against a stale
client in a fresh workspace is right too, which is why the check compares
**content hashes**.

## 4. An API stall I could not reproduce — recorded, not dismissed

The agent reported that after roughly two hours of serving, the API on `:4000`
**stopped answering all unsafe methods while GET stayed instant**, with all Tomcat
threads idle and nothing blocked in a thread dump. A fresh instance of the same
jar was fine. It restarted the backend and continued.

I took measurements on the process that had actually been serving, hours later,
after the 150-journey matrix:

| probe | result |
| --- | --- |
| `GET /api/v1/status` | **200 in 0 ms** |
| `POST /api/v1/auth/login` without CSRF | **403** — answering, not hanging |
| `POST /api/v1/auth/login` with CSRF, wrong credentials | **401 in 654 ms** — a real DB-backed write |
| postgres connections | 10 `idle` on the e2e database, `max_connections` 100 |
| JVM | 70 threads, 401 MB, healthy |

So the symptom did **not** reproduce, and the instance was healthy on every probe
including a genuine write. I am recording this as an **unreproduced intermittent**,
not as a fixed defect and not as a non-issue.

**Why it is not dismissed:** a mutation path that intermittently stops answering is
the worst possible failure mode at cutover — GET-only monitoring looks healthy while
every write in the product silently hangs.

**Why it is not escalated into a blocker:** there is no evidence of a defect, and
guessing at a cause would be worse than admitting I do not have one. The one
plausible mechanism I can name is the login rate limiter (10 attempts / 60 s per
address, with an account bucket) filling up across a 150-journey matrix — but that
would produce a **refusal**, not a stall, so I am not asserting it.

**Required before cutover**, in this order:

1. If it recurs, capture **at the time of failure**: a thread dump
   (`jcmd <pid> Thread.print`), the Hikari pool metrics from the actuator
   management port, and `pg_stat_activity` with `wait_event_type`.
2. Run it under the **Phase 12 staging rehearsal**, which is the first sustained
   real load — a stall that only appears after two hours will not appear in a
   five-minute gate, and the rehearsal is long enough to see it.
3. Treat "all writes hang, GETs fine" as a **cutover stop condition** until
   explained.

This is recorded rather than closed precisely because an unexplained intermittent
in the write path is not something to mark done.
## 5. The one failing browser journey: a security control working, not a defect

I ran the full matrix myself rather than accept the agent result. Verdict:

```
149 passed (20.2m)   1 failed
```

The single failure is `en-tablet > rejects a wrong password without saying which
field was wrong`, and it failed for an instructive reason:

```
Expected substring: "The email or password does not match our records..."
Received string:    "Too many attempts in a short time. Wait a little, then try again."
```

**The login rate limiter refused the journey.** The suite is 25 journeys across six
locale/width combinations, most of which sign in; the limiter allows 10 attempts per
60 s per address. By the time this journey ran, the window was full.

**This is the control working.** A limiter that a test suite can exhaust is a limiter
doing its job. The mistake would be to loosen it, so:

1. **The production limit is unchanged and is not a defect.** 10 per 60 s per address
   stays.
2. **The journey is not skipped and its assertion is not weakened.** It still asserts
   that a wrong password is refused with a message that does **not** say which field was
   wrong — that non-disclosure is the point of the journey.
3. **The determinism fix is in the harness, not the control.** The limiter's threshold
   must become configurable with the production default unchanged, and the E2E
   environment may raise it — while a dedicated test asserts the *production* values, so
   the default can never drift unnoticed. That is the same shape as the `TEST` profile
   the finance and admin modules already use.

Why this is worth stating plainly: an agent reported 150 passed and I measured 149. The
difference is not flakiness in the product — it is that the agent's run happened to land
outside a limiter window. **A security control interacting with a load-generating test is
a scheduling coincidence, and a green run that depends on one is not evidence.** The
harness fix removes the coincidence; the control stays.

## 6. A defect in the agent's own tooling

`npm run e2e:teardown` does not clean up after `npm run e2e:seed`. Seed generates a
**random** organization id per run while teardown removes a **fixed** id, so the
`organizations.slug = 'e2e-tenant'` row survives and every subsequent seed fails with a
duplicate-key error against a database that looks empty.

I hit this three times before I diagnosed it, and each time the symptom looked like "the
seed is broken" rather than "the cleanup is incomplete". A teardown that cannot reliably
undo its own seed is worse than no teardown, because it makes a scratch database
unusable and the failure points at the wrong tool. Fixing it is part of the follow-ups:
seed and teardown must share one id derivation.
