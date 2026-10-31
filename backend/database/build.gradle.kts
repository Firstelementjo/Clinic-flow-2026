plugins {
    java
}

// Database / schema-ownership module.
//
// Owns backend/database/** and ops/flyway/**:
//  - the verified Flyway baseline plus additive migrations, packaged on the
//    classpath under db/migration so Spring Boot's Flyway auto-configuration
//    runs them against the Spring datasource;
//  - the baseline strategy (FlywayDatabaseConfiguration);
//  - the Testcontainers pgvector fixture proving a fresh database accepts the
//    chain;
//  - the verification scripts referenced by docs/migration/flyway-runbook.md.
//
// It deliberately owns no entities, repositories, or naming strategy: the
// strangler contract is that Spring persistence maps EXPLICITLY to the
// existing physical (quoted camelCase) schema. See ADR-0002.

// The Spring Boot BOM pins Testcontainers 1.21.3, which cannot negotiate an API
// version with Docker Desktop 29.x on this host and fails with "Could not find a
// valid Docker environment". A skipped migration fixture is not a pass, so this
// module pins the whole Testcontainers stack to a version that can. Setting the
// BOM property keeps every org.testcontainers:* artifact consistent instead of
// mixing (for example) postgresql 1.21.4 with jdbc 1.21.3.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // Logging only. Spring Boot supplies the binding at runtime; the module
    // must not drag in a logging implementation of its own.
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Testcontainers reports Docker problems through SLF4J. Without a binding
    // the diagnostics are swallowed and the failure reads as a bare
    // "Could not find a valid Docker environment".
    testRuntimeOnly("ch.qos.logback:logback-classic")
}

tasks.test {
    // The fixture reads the migration chain straight from ops/flyway so the
    // repository copy and the packaged copy cannot silently diverge.
    systemProperty("opsFlywayDir", rootProject.file("ops/flyway").absolutePath)
    systemProperty("repoRoot", rootProject.projectDir.absolutePath)

    // Testcontainers diagnostics through SLF4J (needs the logback binding above).
    systemProperty("org.testcontainers.logging", "slf4j")

    // Forward only what the caller already set. Testcontainers' built-in
    // Windows strategy resolves the docker_engine named pipe itself; forcing
    // DOCKER_HOST here breaks environment auto-detection.
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}