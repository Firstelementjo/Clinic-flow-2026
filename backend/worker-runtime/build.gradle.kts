plugins {
    java
    // `java-library`: :backend:channels and :backend:notifications-realtime compile against the
    // durable queue SPI (JobHandler, OutboxEnqueuer, the canonical claim statements), so the
    // types have to leave this module as `api`, not as an implementation detail.
    `java-library`
    id("org.springframework.boot")
}

// Durable worker runtime / transactional outbox engine.
//
// Owns backend/worker-runtime/**:
//  - the outbox claim/complete/retry/dead-letter/reap/replay statements, byte-compatible with
//    ops/flyway/reference/outbox-claim.sql so the claim predicate keeps matching
//    outbox_events_claim_idx;
//  - queue classes, retry classification, exponential backoff with jitter, dead letters and the
//    append-only attempt history;
//  - the dispatcher loop, per-tenant fairness/limits, graceful drain, and the worker/queue health
//    metrics exposed on the management port.
//
// It depends on :backend:tenant-security because an off-request job has no Authentication and
// must run through TenantScopedExecutor. It deliberately does NOT depend on :backend:channels
// or :backend:notifications-realtime: the job handlers live in those modules and are discovered
// as JobHandler beans, so the dependency arrow points handler -> runtime and never back.

// The Spring Boot BOM pins Testcontainers 1.21.3, which cannot negotiate an API version with
// Docker Desktop 29.x on this host and fails with "Could not find a valid Docker environment".
// A skipped DB fixture is not a pass, so the whole Testcontainers stack is pinned as the other
// DB-backed modules do.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:shared-kernel"))
    api(project(":backend:tenant-security"))
    // RetryClassifier distinguishes "the job lost its tenant scope" (permanent) from "the provider
    // timed out" (retryable), which means classifying against Spring Security's AccessDenied
    // hierarchy. The boundary stays authoritative; only the type is on the compile classpath.
    api("org.springframework.boot:spring-boot-starter-security")

    // The packaged Flyway chain (db/migration/**) plus flyway-core, so the worker process can boot
    // against an empty database exactly like the API does. Phase 5a put the migrations on the
    // classpath; a worker that cannot migrate is a worker that cannot start against a fresh volume.
    implementation(project(":backend:database"))
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // DispatcherProperties uses @Validated/@Min so a misconfigured lease or batch size fails at
    // boot instead of producing a queue that cannot make progress.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The real SecurityEventPublisher bean lives in :backend:identity-access; the worker tests need
    // it so an UNSCOPED_JOB_REJECTED is recorded rather than swallowed by a stub.
    testImplementation(project(":backend:identity-access"))
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

springBoot {
    // Stated rather than inferred. The launcher deliberately lives outside `com.firstelement`
    // (see firstelement.worker.WorkerApplication for why), so the class-name scan must not be
    // trusted to keep finding it if another `main` is ever added to this module.
    mainClass.set("firstelement.worker.WorkerApplication")
}

tasks.bootJar {
    archiveBaseName.set("firstelement-worker")
}

tasks.test {
    // The DB-backed negatives run the real Flyway chain against a throwaway
    // pgvector container, so Flyway must not be disabled here.
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}