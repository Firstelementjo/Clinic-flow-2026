# Clinic Flow — Operations, Standards & Deployment Runbook
## 1. Scope & Guarantee of the Core Triad

This document governs the implementation of the three non-functional architectural guarantees for **Clinic Flow**:
1. **Installability (القابلية للتثبيت)**: Effortless single-command deployment across both on-premise clinic environments and cloud hosting.
2. **Portability (القابلية للنقل والتشغيل)**: Cross-platform, vendor-neutral execution across Linux, Windows Server, macOS, x86_64, and ARM64.
3. **Upgradeability (القابلية للترقية والتطوير الآمن)**: Zero-loss, forward-only schema evolution with automated pre-upgrade snapshots and automatic rollback safeguards.

---

## 2. Installability

### 2.1 Multi-Stage Container Architecture
Clinic Flow packages its entire footprint into standardized OCI containers:
* **Backend (`Dockerfile.backend`)**:
  * Build stage compiles the Spring Boot application (`:backend:app-bootstrap:bootJar`) using JDK 21.
  * Runtime stage uses `eclipse-temurin:21-jre-alpine` running as a non-privileged user (`clinicflow`), exposing ports `4000` (API) and `4101` (Actuator/Management).
* **Frontend (`frontend/Dockerfile`)**:
  * Build stage compiles Angular 19 into production browser distribution (`dist/frontend/browser`).
  * Runtime stage serves static assets via `nginx:1.27-alpine` with Gzip, aggressive immutable asset caching, and an internal reverse proxy forwarding `/api/` to the backend service.
* **Database & Cache**:
  * PostgreSQL 16 with `pgvector` extension for semantic search / AI capabilities.
  * Redis 7 (Alpine) for distributed locking, rate limiting, and caching.

### 2.2 Single-Command Deployment
* **Production Compose**:
  ```bash
  docker compose -f docker-compose.prod.yml up -d
  ```
* **Automated One-Click Installer**:
  * **Windows Server / Clinic Edge**:
    ```powershell
    .\ops\install\install.ps1 -WebPort 80
    ```
  * **Linux VPS / Cloud Server**:
    ```bash
    ./ops/install/install.sh 80
    ```
  * The installer automatically:
    1. Verifies Docker runtime.
    2. Auto-generates cryptographically strong random secrets (`SESSION_SECRET`, `ENCRYPTION_KEY`, DB passwords) in `.env` if not present.
    3. Launches all services and waits for the readiness healthcheck (`/actuator/health/readiness`).

---

## 3. Portability

### 3.1 Operating System & Hardware Neutrality
* **OS Independence**: The entire stack is decoupled from host-specific dependencies. It runs identically on Ubuntu/Debian/RHEL, Windows 10/11/Server, and macOS.
* **Processor Architecture**: All base images (`eclipse-temurin:21-jre-alpine`, `nginx:1.27-alpine`, `pgvector/pgvector:pg16`, `redis:7-alpine`) provide official multi-architecture support for `linux/amd64` (Intel/AMD) and `linux/arm64` (Apple Silicon, AWS Graviton).

### 3.2 Vendor-Neutral Infrastructure
* **No Cloud Lock-in**:
  * Authentication: Self-contained HMAC signed session tokens & JWT.
  * Persistence: Standard open-source PostgreSQL & Flyway.
  * Vector Engine: Standard `pgvector` inside PostgreSQL (no proprietary vector databases required).
  * AI Layer: Abstracted Spring AI provider port supporting Gemini, OpenAI, Claude, or pure Local Offline models (Ollama/vLLM).

---

## 4. Upgradeability & Zero-Downtime Safeguards

### 4.1 Schema Evolution (Flyway)
* Every schema change is codified in sequential forward-only migrations:
  `backend/database/src/main/resources/db/migration/V{timestamp}__{description}.sql`
* Applied transactionally during application boot. If any migration fails, the transaction rolls back cleanly without leaving the schema corrupted.

### 4.2 Automated Pre-Upgrade Snapshot & Rollback
To upgrade Clinic Flow safely without risking clinic data:
* **Windows**:
  ```powershell
  .\ops\upgrade\upgrade.ps1 -TargetVersion "1.3.0"
  ```
* **Linux**:
  ```bash
  ./ops/upgrade/upgrade.sh "1.3.0"
  ```

**Upgrade Pipeline Execution Flow:**
```
[Start Upgrade]
       │
       ▼
[Pre-Upgrade Automated Dump] ──► Generates custom .dump + .sql + SHA-256 manifest
       │
       ▼
[Pull/Build New Release]
       │
       ▼
[Container Restart & Flyway Migration]
       │
       ▼
[Healthcheck Polling (Liveness & Readiness)]
       │
  ┌────┴──────────────┐
  ▼                   ▼
[Healthy (200 OK)]  [Failed / Timeout]
  │                   │
  │                   ▼
  │         [AUTOMATIC ROLLBACK TRIGGERED]
  │         1. Reverts to previous image tag
  │         2. Restores service availability
  │         3. Logs failure for audit
  │
  ▼
[Release Confirmed]
```
