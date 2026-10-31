# FirstElement Dental Clinic

A standalone dental clinic management system. Primary domain:
- Patient records and appointment scheduling
- Odontogram (tooth notation) and treatment plans
- Billing, invoices, and payments
- Media/X-ray storage and retrieval
- AI assistant for clinical queries

**NOT a generic CRM.** The CRM modules (customers, conversations, inbox)
exist only to support clinic project-scoping.

Primary workflow: patients → appointments → clinical procedures → billing.

## Multi-tenant

Each organization/project can have its own clinic domain. Patient data
is scoped to the project — a patient from Project A cannot be accessed
from Project B without explicit permission.

## Tech Stack

- **Backend:** Spring Boot 3.x, Spring Security, PostgreSQL/pgvector
- **Frontend:** Angular standalone components, signals, reactive forms
- **Design System:** RTL/LTR support, Arabic-first, Tailwind CSS
- **Testing:** JUnit 5, Testcontainers, Playwright browser journeys

## Quick Start

See `plan.md` for the 12-phase migration plan. Each vertical slice
is runnable end-to-end before the next begins.

## Key Directories

- `backend/crm-core/` — clinic domain (patients, appointments, odontograms, treatment plans, billing, encounters, media)
- `frontend/src/app/features/clinic/` — Angular components for all clinic surfaces
- `docs/clinic/` — dental-specific requirements, phases, and decisions