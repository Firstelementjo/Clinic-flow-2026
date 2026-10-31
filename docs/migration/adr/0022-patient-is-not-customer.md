# ADR 0022: Patient is not Customer

- Status: accepted
- Date: 2026-10-12
- Phase: clinic / platform foundation
- Scope: `DEC-015` — the protected clinical data boundary
- Raised by: product owner
- Decided by: product owner

## Context

The clinic requirements carry clinical data — medical history, allergies, current medication,
diagnosis, prescriptions, odontogram, treatment detail, radiographs. None of that exists in the
current system, because there is no clinical system.

What does exist is `Customer`: a CRM contact reached over WhatsApp, Instagram, Messenger or Telegram,
with conversations, messages and history attached. A patient booking a cleaning appointment is very
often the same person who messaged the practice.

Collapsing the two would be the cheaper design and the wrong one. `Customer` is already projected
into CRM search, exports, inbox views, AI retrieval corpora and realtime payloads. A `Customer` that
also carried a diagnosis would put that diagnosis into every one of those paths, and the scope of
each is already carefully bounded — field-level minimisation is enforced in SQL today, not in a
response filter.

## Decision

`Patient` is a separate protected aggregate that is **not** a rename or an alias of `Customer`.

A controlled, optional link connects a patient to a CRM customer where a patient is also reachable
through a channel. The link is explicit and may be absent; a patient may exist with no customer, and a
customer may exist with no patient.

Clinical content must not enter:

- generic CRM search and customer projections;
- ordinary support exports and report aggregates;
- AI prompts, retrieval corpora and tool arguments;
- logs and realtime payloads;

by default. Anything that requires it is an explicit policy decision with its own capability, not a
default.

The existing field-level minimisation pattern is the model to follow: `ClinicRepository` already omits
`doctors.email` and `doctors.phone` from its projection so they cannot leak through a log or a future
field addition, and `customerVisibleTranscript` drops internal content in SQL rather than after the
fact. Clinical projections do the same thing.

## Consequences

- A patient reachable on WhatsApp has two records joined by a link, not one merged record. That is
  the deliberate cost of the boundary.
- Search across both is a clinic-authorised capability, not the default CRM lookup.
- An AI turn triggered by a channel conversation may use the customer's communication context but not
  the patient's clinical record, unless the clinic has explicitly granted that policy. This is the
  same tenant-scoped retrieval filtering the vector path already performs, applied to a new
  dimension.
- Retention and deletion have two subjects with potentially different rules and different legal
  bases. `DEC-007` is open precisely because that difference has not yet been decided, and this ADR
  does not pre-empt it.
- The clinical scope stays parked until `DEC-009` (odontogram) and `DEC-010` (prescriptions and
  consent) are answered. Creating the `Patient` aggregate is not blocked by them; populating clinical
  content is.