# ADR 0021: Clinic roles are configurable, layered over the existing permission vocabulary

- Status: accepted
- Date: 2026-10-12
- Phase: clinic / platform foundation
- Scope: `DEC-014` — per-clinic role composition and least-privilege navigation
- Raised by: product owner
- Decided by: product owner

## Context

The Arabic source document defines seven roles. Five are human jobs: clinic owner/manager,
receptionist, dentist, dental assistant, and accountant/finance officer.

The system has three: `ADMIN`, `EMPLOYEE`, `CLINIC_STAFF`, held in a **fixed three-value PostgreSQL
enum**. Only one of the five document roles maps onto anything today — receptionist, approximated by
`CLINIC_STAFF`, a six-permission subset of `EMPLOYEE`.

The concrete requirement that exposed this: a clinic finance user must see only finance work from
their clinic, and must not see appointments. That role has no home. `CLINIC_STAFF` is the opposite
shape — all clinical reads, no finance.

A fixed richer enum was considered and rejected. A dental clinic that wants a booking-only
receptionist, who can see the calendar but not patient contact details, is a plausible request, and
an enum cannot express a job nobody anticipated.

## Decision

Clinic roles are **composable from capabilities** and defined per clinic.

Critically, this is layered **on top of** the existing permission vocabulary, not in place of it:

- `Permission` and `RolePermissions` are unchanged. `ADMIN`'s 42 grants, `EMPLOYEE`, `CLINIC_STAFF`,
  and every test that depends on them continue to work exactly as they do.
- The fixed enum is **not** migrated. Built-in roles keep their current identity.
- What becomes configurable is which capability bundles a clinic offers and which bundle each person
  holds, per project.

Least privilege is a property of the interface, not only of the server. A finance user's browser must
**never receive appointment data**; the navigation is built from capabilities and the unauthorised
screens are not part of their session at all. Hiding them with CSS would leave the data in the page
and would fail the moment anyone opened the network tab.

Every permission change is audited. A role change revokes live sessions, matching the existing
membership behaviour that already revokes on deactivation and role change.

## Consequences

- Adding a job the clinic needs does not require a schema migration or a release, which is the point
  of choosing configurability over a richer enum.
- The permission vocabulary becomes the stable contract. Adding a capability is an additive change to
  `Permission`; composing them into a role is clinic configuration.
- The interface needs a capability-driven shell rather than a hard-coded menu, which also improves
  the existing product: the current navigation is not built from capabilities.
- Authorization remains server-side and authoritative throughout. Configurability decides *which*
  capabilities a job holds; it never decides whether the server checks them.
- A clinic can grant a capability it does not itself use. That is legitimate and is not an error, but
  it means the platform console must be able to show a clinic's effective capabilities — which is the
  same read model the existing admin permission inspector already provides.