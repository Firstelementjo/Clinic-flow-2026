plugins {
    java
    // `api` configuration: FirstElementProperties (shared-kernel) and the
    // tenant-security types are part of this module's public contract, so
    // :backend:identity-access and :backend:app-bootstrap compile against them.
    `java-library`
}

// Tenant / principal / Spring Security boundary module.
//
// Owns backend/tenant-security/**:
//  - TenantContext (immutable) + thread-bound TenantContextHolder;
//  - the principal taxonomy (USER / SERVICE / CONNECTOR / WORKER / AI_AGENT)
//    and its deny-by-default capability policy;
//  - the cookie -> revocable server-side session authentication filter and the
//    port it depends on (BrowserSessionStore), implemented in :backend:identity-access;
//  - default-deny request authorization, @EnableMethodSecurity, and the
//    explicit permission meta-annotations;
//  - repository/query policy helpers (TenantQueryGuard, TenantScope,
//    ScopedWhere) and field redaction;
//  - non-HTTP enforcement boundaries for jobs, realtime topics and AI tools;
//  - security-event publishing port + non-disclosing error translation.
//
// It owns NO business module and NO entity/repository for CRM, inbox, finance,
// reporting or AI. Anything that would become one is a policy primitive here
// and a consumer in the owning module (plan.md §1.3).
//
// ADR-0002 applies to every persistence touch from here on: quoted camelCase
// identifiers are written explicitly and the 98 `timestamp without time zone`
// columns are bound as LocalDateTime, never Instant.

extra["testcontainers.version"] = "1.21.4"

dependencies {
    // `api` because FirstElementProperties is part of this module's public contract.
    api(project(":backend:shared-kernel"))

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Optional OAuth2 resource-server path for trusted integrations. Gated by
    // firstelement.security.oauth2.enabled + a configured JwtDecoder; a browser
    // cookie session never goes through it.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:testcontainers")
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