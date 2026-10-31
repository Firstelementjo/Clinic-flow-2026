plugins {
    java
    // `api` configuration: :backend:app-bootstrap consumes the auth/session/permission
    // read models from this module, which in turn expose the tenant-security types.
    `java-library`
}

// Identity & access module.
//
// Owns backend/identity-access/**:
//  - users / organization / project-membership reads mapped EXPLICITLY to the
//    verified legacy physical schema (quoted camelCase, LocalDateTime);
//  - the revocable server-side browser session store (implements
//    com.firstelement.security.session.BrowserSessionStore);
//  - login, logout, immediate session revocation, and rate-limited login with
//    constant-time password failure behaviour;
//  - the security/audit event writer over the legacy `audit_logs` stream;
//  - role/membership change commands (audited, revocation-aware);
//  - the Admin effective-permission inspector read model.
//
// It depends on :backend:tenant-security for the boundary primitives and owns
// no CRM, inbox, finance, reporting or AI behaviour.

extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:tenant-security"))
    api(project(":backend:shared-kernel"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // :backend:tenant-security declares the starter as `implementation`, so the Spring
    // Security types this module uses directly (AccessDeniedException, CsrfToken,
    // @RequirePermission's PreAuthorize) must be declared here too.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":backend:database"))
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
    // pgvector container, so Flyway must not be disabled here (unlike the
    // app-bootstrap smoke test, which must not need a database at all).
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}