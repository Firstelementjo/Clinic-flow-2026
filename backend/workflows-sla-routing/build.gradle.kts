plugins {
    java
    // `java-library`: :backend:app-bootstrap consumes the Phase-6 services and their read models.
    `java-library`
}

// Workflow / SLA / scoring / escalation / routing module.
//
// Owns backend/workflows-sla-routing/**:
//  - availability, working hours, holidays, skills, workload, capacity and the deterministic
//    routing pipeline (ported from packages/routing/src/*);
//  - conversation scoring and the escalation rule engine, with the atomic AI -> HUMAN transition
//    and the handover summary (ported from packages/ai/src/scoring/* and apps/api/src/escalation/*);
//  - versioned workflow definitions and state machines with execution history and audited
//    rollback, over V20261005010000 / V20261005020000;
//  - SLA calendars with independent first-response, next-response and resolution clocks, over
//    V20261005030000;
//  - the routing-explanation read model (derived, never a table) and the unassigned blocking stage,
//    reusing inbox-helpdesk's UnassignedQueueRepository.UNASSIGNED_ACTIVE.
//
// It depends on :backend:inbox-helpdesk for exactly two things: the CONVERSATION_TAKE_OVER /
// CONVERSATION_ASSIGN guarded state-machine writes (so the AI <-> HUMAN transition has ONE
// definition) and UNASSIGNED_ACTIVE, which ADR 0006 section 5 requires be reused rather than
// re-derived. It depends on :backend:worker-runtime for OutboxEnqueuer, so every intent this
// module publishes is written in the same transaction as the domain change.
//
// The database module is NOT a main-scope dependency: the schema the packaged Flyway chain
// creates is already on the test classpath through :backend:database, and no production source
// here executes DDL.

// Pinned to the same Testcontainers version every other DB-backed module uses. Unpinned, Gradle
// resolves 1.21.3, whose docker-java cannot negotiate an API version with Docker Desktop 29.x on
// this host and fails with "Could not find a valid Docker environment" -- which reads as a Docker
// problem and is actually an unpinned dependency. A skipped DB fixture is not a pass.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:crm-core"))
    api(project(":backend:inbox-helpdesk"))
    // OutboxEnqueuer/OutboxIntent/QueueName are on the compile classpath of every producer module.
    api(project(":backend:worker-runtime"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":backend:database"))
    // The real SecurityEventPublisher bean lives in :backend:identity-access; these suites need
    // it so an UNSCOPED_JOB_REJECTED or a scope denial is actually recorded, not swallowed.
    testImplementation(project(":backend:identity-access"))
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
    // The DB-backed negatives run the real Flyway chain against a throwaway pgvector container,
    // so Flyway must not be disabled here.
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}