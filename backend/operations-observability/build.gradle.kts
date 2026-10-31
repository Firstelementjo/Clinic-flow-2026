plugins {
    java
    `java-library`
}

// Operations-observability module — "is it operating?"
//
// Owns backend/operations-observability/**:
//  - the job read model an operator uses to diagnose a failed message, webhook, export or vector
//    ingestion without a database console: one query that answers "what happened to this outbox
//    event" out of outbox_events + job_attempts + job_dead_letters + job_integration_events;
//  - connector and webhook health, including signature failures and last inbound/outbound;
//  - realtime connection and resync health;
//  - search / vector freshness, pending index work, deletion propagation and knowledge gaps;
//  - workflow versions, activation and rollback state, dry-run counts and failed executions;
//  - the operator-facing routes for all of the above, and the replay/dead-letter routes, which call
//    :backend:administration's MaintenanceCommandService so there is exactly one allow-list, one
//    permission decision and one audit writer in the system.
//
// It depends on :backend:administration rather than duplicating it: the audit streams, the legal-hold
// gate and the maintenance allow-list are all owned there, and a second copy of any of them would be
// a second answer to a question an operator would trust.
//
// It depends on :backend:channels for the connector read model, :backend:notifications-realtime for
// the realtime counters, :backend:workflows-sla-routing for the workflow history read models, and
// :backend:worker-runtime for the outbox/attempt/dead-letter repositories and the replay service.
//
// The database module is NOT a main-scope dependency: the packaged Flyway chain is already on the
// test classpath through :backend:database, and no production source here executes DDL.

// Pinned to the same Testcontainers version every other DB-backed module uses.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:administration"))
    // ChannelConnectionRepository: connector state with no credential.
    api(project(":backend:channels"))
    // RealtimeStreamService.health(): connection and resync counters.
    api(project(":backend:notifications-realtime"))
    // WorkflowCatalogRepository / WorkflowHistoryRepository: versions, activation, failures.
    api(project(":backend:workflows-sla-routing"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // RealtimeStreamService implements RedisMessageListenerContainer's MessageListener, so the Redis
    // types are on this module's compile classpath even though nothing here publishes to the bridge.
    // The module never connects: the bridge stays conditional in :backend:notifications-realtime.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":backend:database"))
    // The observed-status table the OpenAPI contract customiser publishes from. Test-only, and
    // declared rather than leaned on transitively: an HTTP assertion here compares the status it
    // observed on the socket against the status the contract declares, which is what stops the
    // ADR-0016 gate being circular. Production code in this module does not read it.
    testImplementation(project(":backend:shared-kernel"))
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
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}