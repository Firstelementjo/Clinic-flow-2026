plugins {
    java
    // `java-library`: :backend:operations-observability consumes the Admin audit writers and the
    // legal-hold gate, so the types are part of this module's published surface.
    `java-library`
}

// Administration module — "is the system controlled and secure?"
//
// Owns backend/administration/**:
//  - the Admin read models: identity (users, memberships, sessions, machine principals, effective
//    permissions), the audit + security + job-integration streams, retention classes, legal holds,
//    privacy-deletion requests and targets, AI policy/governance/budgets/tool policy/blocked calls/
//    redaction, export activity, and the migration + backup/restore evidence surfaces;
//  - `AdminAuditWriter` and friends, the writers that make the *physical* stream split of
//    ADR-0005 §5 real: admin actions land in admin_audit_events, security decisions in
//    security_events, and worker outcomes in job_integration_events;
//  - `LegalHoldGate`, the one place that answers "may this tenant be purged?", which ADR-0008 rule 5
//    makes a precondition of every purge;
//  - `MaintenanceCommandService`, the allow-listed typed commands. There is no other write path in
//    this module: no SQL parameter, no table name, no free-form row.
//
// It depends on :backend:identity-access because effective permissions and the typed identity
// commands already exist there and Phase 7 must not grow a second definition of either.
//
// It depends on :backend:worker-runtime for the outbox/job read models an operator needs next to the
// audit trail, and :backend:channels for the connector read model.
//
// The database module is NOT a main-scope dependency: the packaged Flyway chain is already on the
// test classpath through :backend:database, and no production source here executes DDL.

// Pinned to the same Testcontainers version every other DB-backed module uses. Unpinned, Gradle
// resolves 1.21.3, whose docker-java cannot negotiate an API version with Docker Desktop 29.x on
// this host. A skipped DB fixture is not a pass.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:tenant-security"))
    api(project(":backend:identity-access"))
    // OutboxReplayService / QueueHealthService / DeadLetterRepository / JobAttemptRepository.
    api(project(":backend:worker-runtime"))
    // ChannelConnectionRepository: connector state without a credential.
    api(project(":backend:channels"))
    // JsonCodec for the jsonb columns the Admin read models assemble.
    api(project(":backend:crm-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":backend:database"))
    // The real SecurityEventPublisher bean lives in :backend:identity-access.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic")
}

tasks.test {
    // These suites run the real Flyway chain against a throwaway pgvector container, so a column
    // name that does not exist is a test failure here rather than a surprise in production.
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}