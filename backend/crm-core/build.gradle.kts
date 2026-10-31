plugins {
    java
    // `api` configuration: :backend:inbox-helpdesk consumes the domain enums, the
    // timeline recorder and the tenant query scope from this module.
    `java-library`
}

// CRM core module.
//
// Owns backend/crm-core/**:
//  - the CRM/helpdesk domain vocabulary (conversation status/mode/priority, message
//    direction/author/delivery, channel, case outcome, inbox filter, locale);
//  - the tenant/project query scope used by every CRM statement, built on
//    com.firstelement.security.query.TenantQueryGuard + TenantContext;
//  - customers + customer identities (the framework-neutral identity resolver);
//  - internal notes, case classification and case resolution;
//  - the append-only conversation timeline (conversation_events);
//  - the cursor/keyset pagination model and the pure inbox filter definition;
//  - the CRM audit stream writer over the legacy `audit_logs` table.
//
// It depends on :backend:tenant-security for the boundary primitives and owns no
// inbox routing, worker, finance, reporting or AI behaviour.

extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:tenant-security"))
    api(project(":backend:shared-kernel"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

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