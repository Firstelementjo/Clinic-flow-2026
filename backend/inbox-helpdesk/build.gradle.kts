plugins {
    java
    // `api` configuration: :backend:app-bootstrap consumes the inbox controllers and the
    // conversation read models from this module, which in turn expose the crm-core types.
    `java-library`
}

// Inbox / helpdesk module.
//
// Owns backend/inbox-helpdesk/**:
//  - the conversation state machine: status, priority, mode, subject;
//  - employee replies with idempotency and append-only collision reporting;
//  - assignment eligibility, per-agent capacity, and the workload recount;
//  - the inbox query model, the legacy saved-view filters, and cursor pagination;
//  - the explainable unassigned queue and the inbox counts;
//  - the customer-facing transcript, separated from the internal history;
//  - the /api/v1 compatibility controllers for customers, conversations, messages, cases,
//    assignments and notes.
//
// It depends on :backend:crm-core for the domain model, the tenant query scope and the
// repositories, and owns no routing rules, worker, SLA, knowledge or AI behaviour.

// Pinned to the same Testcontainers version every other DB-backed module uses. Left
// unpinned, Gradle resolved 1.21.3 here, whose docker-java negotiates an API version this
// Docker engine answers with an empty /info payload — the container suite then failed with
// "Could not find a valid Docker environment" rather than with anything about this module.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:crm-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    // Phase 15: this module now contributes to the served OpenAPI document, because it owns the
    // inbox filter vocabulary and a vocabulary that lives only in code is a vocabulary no client
    // can see. InboxFilterEnumOpenApiCustomizer declares `filter` as an enum carrying the WIRE
    // values (unassigned, all-open, ...) rather than the Java constant names, which is why it
    // cannot simply be a typed controller parameter -- see that class's header for the measurement
    // that settled it.
    //
    // Pinned to the SAME version property :backend:app-bootstrap uses, so the two resolve to one
    // artifact and Gradle deduplicates instead of putting two springdocs on the classpath. Declared
    // here rather than leaned on transitively: an undeclared dependency makes this module's own
    // tests fail with NoClassDefFoundError, which is a failure that only shows up in someone
    // else's checkout.
    //
    // LAYERING NOTE: this arguably belongs in :backend:app-bootstrap, next to the contract it
    // publishes. It is here because that module is outside this change's ownership; moving it is a
    // one-file move once ownership allows. See docs/migration/phases/phase-15-inbox-work-queue.md.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${rootProject.extra["springdocVersion"]}")

    testImplementation(project(":backend:database"))
    // The real SecurityEventPublisher bean lives in :backend:identity-access; the CRM tests need
    // it so a scope denial is actually recorded rather than swallowed by a stub.
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
