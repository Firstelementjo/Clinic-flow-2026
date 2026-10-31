# ADR 0018: A clinic is a project, and onboarding belongs to the platform

- Status: accepted
- Date: 2026-10-12
- Phase: clinic / platform foundation
- Scope: `DEC-008` — the product model for the dental clinic vertical
- Raised by: product owner
- Decided by: product owner

## Context

The dental clinic feature arrived as a control-plane package with a fourteen-phase plan and an
Arabic source document. Two things had to be settled before any of it could be built: whether a
"clinic" is a new entity in the tenant model, and who creates one.

The existing model has two levels — `Organization` is the tenant boundary, `Project` is a workspace
inside it. The clinic requirements speak only of "clinic", which could mean either level, and getting
it wrong would have put a parallel hierarchy beside the tenancy model rather than inside it.

Separately, nothing in the current system creates tenants or projects. The admin surface is entirely
read-only and every principal is scoped to a single organization.

## Decision

**A clinic is a project.** No `ClinicLocation` entity is introduced.

`Project` already carries `organizationId` and is already the tenant-scoped workspace boundary used by
CRM scoping, reporting, permissions and exports. Mapping a clinic onto it means all of that machinery
keeps working; introducing a clinic entity would have required a second scoping axis through every
query, export and job.

A clinic is therefore:

```
Organization (tenant)
└── Project = clinic
    ├── its own accounts (users, granted access per project)
    ├── patients · doctors · appointments · clinical records
    └── its own clinic administration
```

Authorization must still map organization / project / clinic **explicitly**, because a request may
name one, another, or a resource belonging to a third.

**Onboarding belongs to the platform.** Creating a clinic creates the project and its first
administrator account in one action; further accounts are created separately, and access to a project
is granted per user. No customer-facing surface creates a clinic.

## Consequences

- The clinical scope owned by one project cannot leak into another through a shared clinic concept,
  because a clinic *is* the scope.
- Every per-clinic setting — seats, features, roles — is stored on the project row rather than in a
  parallel clinic table.
- The platform operator is the only principal that creates organizations and projects, which makes it
  a cross-tenant principal. That is recorded separately in ADR 0020.
- Where existing code says "project" but the domain says "clinic", the column stays `projectId` and
  the domain word is used in the UI and the documentation. Renaming the column would buy nothing and
  would touch every tenant-scoped query in the system.