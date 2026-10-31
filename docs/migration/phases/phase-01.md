# Phase 1 — Spring Boot foundation

## Goal
Start the target runtime without touching production data.

## Delivered
- Gradle 8.14.3 multi-project build (`settings.gradle.kts`) with Spring Boot 3.5.6, Java 21 bytecode on JDK 24, dependency-management BOM, clean-checkout build.
- `backend/app-bootstrap` (API): Spring MVC web on port 4000, validated `FirstElementProperties`, Actuator (health/info/metrics, liveness+readiness probes), OpenAPI at `/api/v1/openapi` + swagger-ui at `/api/v1/docs`, stable `/api/v1` error contract (`code`,`message`,`details`,`requestId`,`retryable`), correlation-id filter, structured JSON logs via `logging.structured.format.console=logstash` on staging/production, graceful shutdown, versioned profiles (local/test/staging/production).
- `backend/worker-runtime`: worker process on port 4100 with same config/validation, Actuator, drain hook (SmartLifecycle), graceful shutdown.
- `backend/shared-kernel`: `ErrorResponse`, `CorrelationIdFilter`, request-id propagation (MDC + X-Correlation-Id header).
- CI debt note: `.github` CI pipeline deferred to the quality agent (Phase 1 gate met by clean `./gradlew build`).

## Verification (commands, all on Windows, Docker desktop running)
- `./gradlew build --no-daemon` → BUILD SUCCESSFUL (20 tasks), includes unit + smoke tests.
- `java -jar backend/app-bootstrap/build/libs/firstelement-api-0.1.0.jar` + `GET /actuator/health/liveness` and `/actuator/health/readiness` → `{"status":"UP"}`; `/api/v1/status` → `{"status":"UP","version":"v1"}`; `/api/v1/openapi` → 200.
- `java -jar backend/worker-runtime/build/libs/firstelement-worker-0.1.0.jar` + `GET :4100/actuator/health/liveness` → `{"status":"UP"}`; worker logs show drain hook on shutdown.
- No secrets in application logs; secrets only from env vars with dev-only defaults; `*.env` read denied.

## Known follow-ups
- Integration profiles use `SPRING_DATASOURCE_URL` (jdbc form). Prisma-style `DATABASE_URL` conversion is a Phase 2 concern.
- Testcontainers integration tests exist in dependencies but deferred to Phase 2/3 (fixtures arrive with Flyway).
- Micrometer tracing bridge present; OTLP export disabled until an endpoint is configured.
- `.github` CI pipeline is owned by the quality agent and remains outstanding for the Release-A gate.
