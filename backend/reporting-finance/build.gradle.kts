plugins {
    java
    // `java-library`: another module (and the app bootstrap leaf) consumes the UsageEventWriter so
    // that the module which actually causes a message / AI run / export can emit usage inside its own
    // transaction without reimplementing the taxonomy.
    `java-library`
}

// Reporting-finance module — "what did this cost, what may we bill, and can you prove it?"
//
// Owns backend/reporting-finance/**:
//  - the finance metric catalogue and the source-event taxonomy, and the code that enforces them
//    (FinanceMetricCatalogue, UsageEventFactory): a usage event may only be written for a declared
//    dimension, with that dimension's canonical unit, from a declared source event type;
//  - UsageEventWriter, the only writer of usage_events. MANDATORY propagation, so a usage event can
//    only land in the same transaction as the domain change that caused it, and a tenant-scoped
//    duplicate-suppression key so a retried job cannot double-emit;
//  - the rate-card authoring and effective-dating surface, and RateCardResolver, which answers
//    "which markup applied on this date" and is pinned onto the rollup snapshot at computation time;
//  - the daily and monthly read models: usage, cost, derived revenue, margin, unpriced usage,
//    no-data, and a forecast that is explicitly an estimate and never an input;
//  - the period lifecycle (review -> lock -> reopen), approved+audited adjustments, and
//    reconciliation against an independent recomputation of the ledger;
//  - the finance role policy and the redacted drill-through;
//  - the legacy-versus-new analytics comparison, in which every variance carries an explanation code.
//
// ADR 0007 Part 1 is the specification and is not reinterpreted here. Cost is stored on the immutable
// event; revenue is derived on the rollup snapshot against a pinned rate-card version and markup value;
// margin is derived; unpriced, zero and no-data are three distinct states.
//
// It depends on :backend:administration for the Admin audit stream writer (a period lock is an
// administrative action and ADR-0005 §5 gives it a physical stream) and :backend:crm-core for the
// jsonb codec. It depends on :backend:tenant-security for TenantQueryGuard / AuthorizationService,
// which is where "authorization before aggregation" is structurally enforced.
//
// The database module is NOT a main-scope dependency: the packaged Flyway chain is already on the
// test classpath through :backend:database, and no production source here executes DDL.

// Pinned to the same Testcontainers version every other DB-backed module uses. Unpinned, Gradle
// resolves 1.21.3, whose docker-java cannot negotiate an API version with Docker Desktop 29.x on
// this host. A skipped DB fixture is not a pass.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:tenant-security"))
    // AdminAuditStreamWriter / AuditWrite / AuditAttribution: the admin-stream copy of every finance
    // action, so a close/lock/reopen is visible to an administrator and not only inside finance.
    api(project(":backend:administration"))
    // JsonCodec for the usage_events.metadata jsonb column.
    api(project(":backend:crm-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
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
    // These suites run the real Flyway chain against a throwaway pgvector container, so a column
    // name that does not exist is a test failure here rather than a surprise in production. That is
    // the reason every statement in this module was transcribed from V20261005080000 and
    // V20261005090000 rather than from a mental model of them.
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
