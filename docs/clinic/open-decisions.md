# Dental Clinic / Platform — Open Decision Register

**Status:** Active. Nine decisions answered, eight still open.
**Scope:** Covers the clinic vertical (from `source/متطلبات_نظام_إدارة_عيادات_الأسنان.docx`) and the platform
control plane that sits above it.
**Rule:** An open decision keeps its dependent capability **disabled or feature-flagged**. No agent may
convert an unanswered question into a default policy.

---

## 1. Answered — decided by the product owner

| ID | Area | Decision | Decided | ADRs |
|---|---|---|---|---|
| **DEC-008** | Product model | **Multi-clinic.** A clinic *is* a project. Each clinic has its own accounts. FirstElement owns onboarding. | 2026-10-12 | 0018 |
| **DEC-011** | Platform console | **Ours alone**, operated by a small named team. Global status **with actions**, database control, clinic creation, account creation, access granting. Reachable only from FirstElement, never from the customer product. | 2026-10-12 | 0020 |
| **DEC-012** | Seats / entitlements | The platform owner sets a **seat count per clinic**. Enforced at account creation and at sign-in. | 2026-10-12 | 0019 |
| **DEC-013** | Feature flags / AI | Feature availability is **chosen per clinic**. AI is **asleep until enabled and paid for**. Fail-closed. | 2026-10-12 | 0019 |
| **DEC-014** | Roles | **Configurable per clinic**, layered over the existing permission vocabulary. | 2026-10-12 | 0021 |
| **DEC-015** | Data boundary | `Patient` is protected clinical data, **not** a rename of CRM `Customer`. | 2026-10-12 | 0022 |

---

## 2. What DEC-008 through DEC-015 actually mean

These are recorded in full because they are the constraints every later phase inherits.

### A clinic is a project

`ClinicLocation` is **not** introduced. A clinic maps onto the existing `Project` row, which already
carries `organizationId` and is already the tenant-scoped workspace boundary. Authorization must map
organization / project / clinic explicitly rather than assuming any one of them is the clinic.

Per-clinic configuration therefore lands on the project, not on a parallel hierarchy.

### The platform console is a principal above the organization

Every principal that exists today — `ADMIN`, `EMPLOYEE`, `CLINIC_STAFF`, `AI_AGENT` — is scoped to a
single organization. The platform operator is the **only** principal in the system that legitimately
crosses tenant boundaries, which makes it the highest-value target in the product.

Required properties, all of which are new work:

- separate authentication from customer sign-in, with MFA;
- just-in-time elevation rather than a standing super-admin;
- its own immutable audit of every cross-tenant read and every write, with justification;
- **no shared authentication path with the customer application**;
- database control expressed only as validated, audited, dry-run-capable jobs —
  **no arbitrary SQL, no shell, no secret display**.

Database operations in scope: create/drop a per-clinic database, apply and verify migrations,
backup/restore/verify, tenant offboarding and data deletion, quota and size management.

### Onboarding is one action

Creating a clinic creates the project **and** its first administrator account together. Additional
accounts are created separately from the same console. Access to a specific project is granted per
user.

### Seats are enforced twice

A seat limit that is only enforced in the UI is a billing failure. Enforcement is required at
account creation **and** at sign-in, and both paths must be covered by tests.

### Feature availability is per clinic, and defaults to off

Each clinic is configured with the features it has bought. Two clinics in the same deployment may
differ. **AI is the worked example and it is asleep until paid for:**

- an asleep clinic enqueues **no** AI turn — the gate sits in the trigger, before a run row exists
  and before any provider is called;
- no AI tool may execute;
- no spend is incurred;
- the draft panel and AI route are disabled in the UI with an upgrade message;
- **missing entitlement means asleep, always.** A failed lookup, an unrecognised plan, or absent
  configuration must never resolve to awake.

> The reason this is written as a hard rule: `appointments.isAiProposed` currently carries
> `DEFAULT true`. A default-on flag in this schema would quietly run AI for a clinic that never paid.

**The AI agent and the AI knowledge base are separate flags.** A clinic may hold the agent without the
knowledge base; in that case the agent answers from structured facts and tools only and performs no
free-text retrieval. The knowledge-base flag must be checked in retrieval itself, not only at the AI
gate, or "AI without KB" silently becomes "AI with every clinic's knowledge".

### Roles are configurable per clinic

Clinic roles are composed from the **existing** `Permission` vocabulary. `Permission` and
`RolePermissions` are **not** replaced — ADMIN's 42 grants, EMPLOYEE, CLINIC_STAFF and every test
that depends on them continue to work. What becomes configurable is which capability bundles a clinic
offers and which bundle each person holds, per project.

Required properties:

- least privilege is visible in the interface, not just enforced on the server: a clinic finance user
  must **never receive appointment data in the browser**, rather than having it hidden by CSS;
- every permission change is audited;
- a role change revokes live sessions, matching the existing membership behaviour.

---

## 3. Still open — blocks dependent behaviour

| ID | Area | Question | Owner | Blocks | Status |
|---|---|---|---|---|---|
| DEC-001 | Appointment duration | How is service duration determined? Per dentist? Multi-session? Buffers? | Clinic | Scheduling | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-002 | Resources | Rooms/chairs count? Fixed to a dentist or shared? Imaging/sterilization requirements? | Clinic | Scheduling, appointments | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-003 | Cancellation / no-show | Notice window, fees, repeated no-show action, staff-approval triggers? | Clinic | Appointments, notifications | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-004 | Billing / payment | Invoice timing, deposits, installments, currencies, methods, tax, official numbering? | Clinic + finance | Billing | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-005 | Communication | Approved channels, response languages, reminder timings, permitted sending hours? | Clinic | Notifications | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-006 | AI policy | Actions allowed without a human, approved prices and information, concerning-symptom protocol, escalation? | Clinic + clinical lead | Governed AI | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-007 | Privacy / retention | Local law, medical-record retention, export/delete authority, legal hold? | Clinic + legal | Security, retention | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-009 | Odontogram | Tooth numbering, surfaces, finding vocabulary, snapshot/history policy? | Clinical lead | Clinical records | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-010 | Prescriptions / consent | Format, attachment limits, e-signature, co-sign rules, expiry/refusal? | Clinical lead | Clinical records, treatment | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-016 | Clinic modelling | Extend `procedures`/`doctors`, or create `service_catalog_items`/`doctor_profiles` beside them? Three clinic tables already exist, and `appointments` stores patient identity as free text. | Product + engineering | Phase 2, Phase 4 | **ANSWERED 2026-10-13** - see [answers.md](answers.md) |
| DEC-017 | Appointment status | DEC-003 defines seven statuses. The only deployed booking path writes an eighth (`APPROVED`, `clinic-scheduling.ts:588`). Is the path removed, gated, or is `APPROVED` accepted? Also: is `cancelledBy` a user id or a free-text actor? | Product + owner of the legacy AI booking path | The `appointments.status` CHECK; the `cancelledBy` column shape | **Open** � raised 2026-10-13 |

---

## 4. Decision protocol

For each row, record: the proposed option, alternatives considered, operational and legal effect,
migration effect, test changes, owner, the clinic representative who approved it, the approval date,
and whether the decision is reversible.

Until a blocking decision is approved, the dependent capability stays disabled or feature-flagged.

---

## 5. Known conflicts with the existing system

Recorded so they are not rediscovered later.

1. **Invoicing is currently excluded by ADR-0007 rule 7** ("no payment execution and no external
   billing"). DEC-004 must either supply a clinic-billing requirement that amends that rule or
   confirm billing stays out of scope.
2. **Roles are currently a fixed three-value PostgreSQL enum.** DEC-014 requires a project-scoped role
   definition layered above it, not a migration of the enum.
3. **AI is currently a deployment-global flag** (`firstelement.ai.enabled`). DEC-013 requires a
   per-project runtime lookup; the global flag cannot express per-clinic state and must not be
   treated as satisfying it.
4. **The clinic module is read-only today** — seven `GET` handlers, no write path. Appointment
   creation, cancellation, rescheduling and reminders exist in the legacy NestJS controller and are a
   **port**, not a new build.
5. **The Arabic source orders clinical records as second priority** (§8). Any decision to park the
   clinical scope is a scope reduction taken downstream of the source document, not a clinic request.
---

## 6. Stage 1b outcome (2026-10-13)

The platform console now exists on the wire and is reachable only by an authenticated operator.
Three decisions were settled by implementation rather than by a clinic-facing choice, and are
recorded here so they are not relitigated as product questions:

- **The console is opt-in and off by default** (PLATFORM_CONSOLE_ENABLED=false). Off means
  /api/v1/platform/** has no handler: absent, not merely guarded. A deployment that never opts in
  publishes no cross-tenant surface. See ADR 0023.
- **A customer session on a platform route answers 401, not 403.** The customer filter chain does not
  run on platform paths at all, so the cookie is never read. A 403 would have meant the session was
  recognised and then refused.
- **The platform login steps are CSRF-protected**, unlike the webhook path. Step two acts on the
  cookie step one just set, so a cross-site induction could otherwise ride it.

None of these change what a clinic sees. They change what a deployment must configure before the
console exists at all, which is a deliberate gate rather than an accident of wiring.
