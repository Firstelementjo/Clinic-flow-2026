# e2e/ — browser journeys (Phase 11)

Playwright acceptance journeys for the Angular application: Arabic RTL and
English LTR, at desktop, tablet and mobile widths, covering refresh, session
expiry, realtime reconnect and resync, empty / error / loading states, and
keyboard navigation.

## The stage is RED until Phase 11, on purpose

`ops/quality/gate-stages.json` has a `playwright-journeys` stage whose
`requiresPaths` are `frontend/playwright.config.ts` and
`e2e/playwright.config.ts`. Neither exists. The runner therefore **fails**
that stage, every run, until the Angular workspace lands.

This is the intended behaviour and it is not a bug. A browser suite that
silently does not exist is the single failure mode most likely to survive to
production: nothing goes red, nothing is measured, and the checklist says
"Playwright: passing". `plan.md §10` requires CI to have no hidden skipped
browser tests, and a stage that reports "skipped" is exactly the shape that
requirement forbids.

To stage the gate deliberately — for example to get the rest of the pipeline
green on the Node/Spring run while Angular is still in progress — add an
entry to `ops/quality/waivers/pending-stages.allow.json`:

```json
{
  "stage": "playwright-journeys",
  "reason": "Angular workspace is being built in Phase 11; journeys land with it",
  "ticket": "<tracker item>",
  "expiresOn": "<within 30 days>",
  "approvedBy": "<name>"
}
```

and run with `-AllowPendingStages`. Without a matching, unexpired entry the
flag does nothing. Remove the entry in the same commit that creates
`frontend/playwright.config.ts`.

## What goes here when Phase 11 starts

```
e2e/
  playwright.config.ts         projects: chromium/firefox/webkit x ar-RTL/en-LTR x 3 widths
  fixtures/
    login.ts                   session bootstrap through POST /api/v1/auth/login
    synthetic-tenant.ts        two tenants, two projects, an employee and an admin
  journeys/
    login.spec.ts              login, session expiry, CSRF failure path
    inbox.spec.ts              the acceptance journey from PROJECT_REQUIREMENTS section 8
    customer-360.spec.ts
    admin-overview.spec.ts     an operator diagnoses a failed webhook without DB access
    finance.spec.ts            period approval, lock, adjustment, reconciliation
    exports.spec.ts            asynchronous export, progress, signed download, expiry
    admin-ai-governance.spec.ts a disabled tool is denied and the conversation continues
    tenant-isolation.spec.ts   a second tenant sees nothing of the first, in the UI
    accessibility.spec.ts      keyboard reachability, visible focus, semantic status text
    realtime.spec.ts           reconnect then resync from the API, not from missed events
```

Every spec runs against **synthetic data only**. The fixtures create two
tenants so that the isolation journeys have something to be isolated from;
no customer name, phone, email or message body comes from anywhere real.

## The rule that still applies in the browser

Route guards are UX. The browser suite asserts what the **server** decided:
a cross-project request returns the same 404 as a nonexistent one, a revoked
session dies immediately, and a filtered client-side list never contained the
row in the first place. A green UI journey that only checks rendering proves
that the Angular client drew a list the backend had already scoped — which
is the right thing to assert, provided it is asserted as a server behaviour
rather than as a rendering.

See [`docs/migration/phases/quality-gates.md`](../docs/migration/phases/quality-gates.md) §2, stage 12.