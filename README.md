# Clinic Flow 2026

<div align="center">

![Clinic Flow](https://img.shields.io/badge/Clinic%20Flow-2026-0d9488?style=for-the-badge)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen?style=for-the-badge&logo=springboot)
![Angular](https://img.shields.io/badge/Angular-18%2B-red?style=for-the-badge&logo=angular)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16%20(pgvector)-336791?style=for-the-badge&logo=postgresql)
![Architecture](https://img.shields.io/badge/Architecture-Modular%20Monolith-orange?style=for-the-badge)

**Enterprise Dental Clinic Management System, Clinical Workstation & Omnichannel Patient CRM**  
*Arabic-first with native RTL support, alongside comprehensive English LTR.*

</div>

---

## Executive Summary

**Clinic Flow 2026** is a modern, Arabic-first dental clinic CRM and clinical workstation powered by Spring Boot and Angular. It delivers end-to-end management for patient journeys, appointments, multi-chair operatories, and clinician schedules. The platform integrates advanced clinical tools including interactive odontograms, DICOM imaging, and 3D dental model viewing, while streamlining clinic finance with automated invoicing, treatment plans, lab orders, and doctor payout tracking.

**باللغة العربية:**
1. **كلينك فلو (Clinic Flow 2026):** نظام متكامل لإدارة عيادات ومراكز طب الأسنان مبني بأحدث تقنيات Spring Boot و Angular بدعم كامل ومثالي للغة العربية.
2. **إدارة سريرية متطورة:** تنظيم المواعيد، وجداول الأطباء، ومتابعة كراسي العيادة (Operatories) في شاشة تفاعلية ذكية وسلسة.
3. **أدوات طبية وتشخيصية:** يدعم مخطط الأسنان التفاعلي (Odontogram)، واستعراض الأشعة بصيغة DICOM، والمجسمات ثلاثية الأبعاد (3D Dental Viewer).
4. **منظومة مالية وإدارية شاملة:** إدارة خطط العلاج، الفواتير، طلبات المعامل والمختبرات، وحساب عمولات ومستحقات الأطباء بدقة وموثوقية.

---

## Key Capabilities & Modules

### 1. Multi-Chair Dental Calendar & Scheduling
- **Multi-Chair Operatory Board:** Visual management of clinic operatories (`Operatory 1` to `Operatory 4` and unassigned chairs) in a unified, non-clipping responsive view.
- **Agenda Feed:** Chronological appointment stream organized by day with quick triage actions (`تأكيد`, `وصل`, `الزيارة السريرية`, `إتمام`).
- **Doctor-Centric View:** Real-time clinician availability, schedules, and active bookings with doctor initials and specialties.
- **BiDi & Typography Optimization:** Tailored typography with zero text overlap, proper Arabic line-heights, and clear separation between titles, descriptions, and counter badges.

### 2. Clinical Workstation & Encounters
- **Clinical Encounter Flow:** Structured consultation workflows capturing chief complaints, clinical examination notes, and diagnostic assessments.
- **SOAP Notes & History:** Standardized clinical documentation linked directly to the patient's ongoing electronic health record (EHR).
- **Interactive FDI Odontogram:** Digital tooth chart (adult 11–48 and pediatric 51–85) tracking restorations, crowns, endodontic treatments, and missing teeth.
- **Diagnostic Imaging & 3D Visualization:** In-browser DICOM medical image parsing and interactive 3D STL/OBJ dental mesh rendering.

### 3. Patient Journey & Engagement
- **Patient 360 Profile:** Centralized patient records, medical alert badges (allergies, systemic conditions), appointment history, and financial balance.
- **Digital Prescriptions:** Electronic prescription generator supporting clinic letterhead printing, dosage rules, and medication catalogs.
- **Dental Lab Tracking:** Digital lab slip dispatch, fabrication stage tracking (Impressions, Framework, Try-in, Fitting), and delivery verification.
- **Patient Feedback System:** Net Promoter Score (NPS) and satisfaction rating collection after completed visits.

### 4. Billing, Financials & Payouts
- **High-Precision Multi-Currency Ledger:** Native support for 3-decimal currencies (such as Jordanian Dinar `JOD 0.000`) and standard 2-decimal currencies.
- **Invoices & Payments:** Real-time generation of invoices from treatment plans, automated balance due tracking, discounts, and payment receipts.
- **Doctor Commission & Payouts:** Automated clinician payout calculator factoring procedure commission percentages, lab expense deductions, and payment status.
- **Revenue Analytics:** Clinic financial reports by service category, doctor productivity, and payment method breakdowns.

### 5. Omnichannel Messaging & Clinical AI
- **Unified Communication Inbox:** Integration with WhatsApp Cloud API, Instagram, Messenger, and Telegram for patient inquiries and reminders.
- **Clinical AI Assistant:** Safe, governed clinical AI companion with strict human-in-the-loop review, patient privacy protection, and immediate kill-switch safety controls.

---

## Architecture & Technology Stack

```
+---------------------------------------------------------------------------------------+
|                               CLINIC FLOW 2026 ARCHITECTURE                           |
+-------------------------------------------+-------------------------------------------+
|          FRONTEND APPLICATION             |             BACKEND SERVICES              |
|  - Angular 18+ (Standalone Components)    |  - Spring Boot 3.3 (Modular Monolith)     |
|  - Angular Signals & Reactive State       |  - Spring Security with Tenant Guards     |
|  - Native RTL/LTR Bilingual Design System |  - Multi-Tenant Row-Level Data Isolation  |
|  - 3D Canvas Mesh & DICOM Renderers       |  - PostgreSQL 16 with pgvector & Flyway   |
|  - High-Efficiency Tabular Numeric Engine |  - Redis 7 (Session & Rate Limiting)      |
+-------------------------------------------+-------------------------------------------+
```

### Backend
- **Framework:** Spring Boot 3.3.x (Java 21)
- **Security:** Session cookie-based authentication, strict CSRF tokens, and tenant-scoped security queries.
- **Persistence:** Spring Data JDBC with explicitly mapped schemas and Flyway versioned migrations.
- **Database:** PostgreSQL 16 with `pgvector` extension for semantic knowledge and clinical search.
- **Caching & Queues:** Redis 7 for distributed session management and rate limiting.

### Frontend
- **Framework:** Angular 18+ utilizing Standalone Components, Signal-based reactivity, and control flow syntax (`@if`, `@for`).
- **Design System:** Custom enterprise clinical theme adhering to rectangular geometry (`border-radius: 2px - 3px`), high-contrast clinical teal palette, and no distracting rounded elements.
- **Typography:** Fully optimized for Arabic typography with `IBM Plex Sans Arabic` and `Plus Jakarta Sans`, featuring `caret-color: transparent` on interactive buttons to eliminate click artifacts.

---

## Directory Structure

```text
.
├── backend/
│   ├── app-bootstrap/          # Spring Boot main application runner & properties
│   ├── crm-core/               # Core clinic domain (Patients, Encounters, Dental, Billing)
│   ├── inbox-helpdesk/         # REST API Controllers (Clinic, Operations, Encounters, AI)
│   ├── tenant-security/        # RBAC, tenant context guards, and security policies
│   ├── ai-rag/                 # Vector embeddings and clinical assistance logic
│   └── database/               # Database migrations and baseline scripts
├── frontend/
│   ├── src/app/features/clinic/# Dental clinic views (Calendar, Encounters, Invoices, etc.)
│   ├── src/app/core/           # Services (Auth, Session, I18n, API, DICOM/3D utils)
│   ├── src/app/ui/             # Reusable UI controls (Page header, Status chip, Icons)
│   └── src/styles/             # Global tokens, base styles, and component themes
├── ops/                        # Flyway migrations, backup scripts, and deployment configs
└── docker-compose.prod.yml     # Production container orchestration
```

---

## Getting Started

### Quick Start (Windows 1-Click Deployment)
Simply double-click or run the automated deployment batch script:

```cmd
deploy.bat
```
- **Option 1:** Complete Docker Production Stack (PostgreSQL, Redis, Spring Boot API, and Angular).
- **Option 2:** Fast Local Dev Mode (Runs databases in Docker, and launches Spring Boot and Angular with hot-reload).
- **Option 3:** Stop and clean all running containers.

### Prerequisites (Manual Setup)
- **Java 21** or higher
- **Node.js 20+** and **npm**
- **Docker** and **Docker Compose**

### 1. Launch Supporting Infrastructure
Start PostgreSQL with `pgvector` and Redis:

```bash
docker compose -f docker-compose.prod.yml up -d postgres redis
```

### 2. Run the Spring Boot Backend
Navigate to the root directory and launch the application:

```bash
# Set required environment variables
export WEB_ORIGIN="http://localhost:4200,http://localhost"
export SESSION_SECRET="a-very-secure-32-character-secret-key"
export ENCRYPTION_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

# Build and start via Gradle
./gradlew :backend:app-bootstrap:bootRun
```
*The API service will be accessible on port `4000`.*

### 3. Run the Angular Frontend
In a separate terminal, install dependencies and launch the dev server:

```bash
cd frontend
npm install
npm start
```
*The application workstation will be available at [http://localhost:4200](http://localhost:4200).*

---

## Default Access & Roles

For local testing and evaluation, the following pre-configured tenant accounts can be used:

| Role | Email | Password | Permissions |
|---|---|---|---|
| **Super Admin** | `admin@clinicflow.io` | `Password123!` | Full clinic configuration, financials, users, and audit logs |
| **Platform Operator** | `platform@clinicflow.io` | `Password123!` | Platform-level monitoring and organization management |

---

## License

This project is licensed under the terms of the [MIT License](LICENSE).  
Copyright © 2026 FirstElement.
