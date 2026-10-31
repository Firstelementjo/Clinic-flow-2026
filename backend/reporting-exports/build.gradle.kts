plugins {
    java
    // `java-library`: the worker-runtime JobHandler SPI and the administration audit/hold services
    // are compile-time inputs to this module, so the types have to leave it as `api` rather than as
    // an implementation detail a sibling module cannot name.
    `java-library`
}

// Reporting-exports module — "who may see which row and which field, as bytes they can download".
//
// Owns backend/reporting-exports/**:
//  - the versioned report catalogue (definition + immutable version body) and the DOCX document
//    template catalogue, both resolved through a typed field registry rather than a query language;
//  - policy-before-aggregation query planning: the tenant/project predicate is written into the WHERE
//    clause before any aggregation is assembled, a filter that cannot be pushed down REFUSES the
//    export instead of post-filtering, and a subject not declared in the registry is unreachable;
//  - streaming UTF-8 RFC 4180 CSV and a controlled Apache POI XWPF DOCX renderer with bilingual labels,
//    RTL marking, locale-aware numbers/dates/currency, tables and totals;
//  - the ExportJob state machine (queued/running/completed/failed/cancelled/expired/deleted) with
//    progress, cancel, retry, expiry, checksum, deletion and per-redemption download audit;
//  - HMAC-signed short-lived download links, re-authorized at job start AND at every redemption;
//  - legal-hold-gated retention that removes the artefact and the object together, disabled by default.
//
// It depends on :backend:administration for the audit stream writer, the legal-hold gate and the
// retention-class vocabulary, on :backend:worker-runtime for the durable JobHandler SPI (a large
// export is never generated on a request thread), on :backend:tenant-security for TenantQueryGuard /
// AuthorizationService / FieldRedactionPolicy — which is where "authorization before aggregation" and
// "a redacted field is absent" are structurally enforced — on :backend:crm-core for JsonCodec, and on
// :backend:reporting-finance so a finance export resolves the finance module's OWN metric and period
// state rather than a second copy of it.
//
// The database module is NOT a main-scope dependency: the packaged Flyway chain is already on the test
// classpath through :backend:database, and no production source here executes DDL.

// Pinned to the same Testcontainers version every other DB-backed module uses. Unpinned, Gradle
// resolves 1.21.3, whose docker-java cannot negotiate an API version with Docker Desktop 29.x on
// this host. A skipped DB fixture is not a pass.
extra["testcontainers.version"] = "1.21.4"

// Apache POI is NOT managed by the Spring Boot BOM, so the version is stated here rather than
// resolved transitively. poi-ooxml brings poi + poi-ooxml-lite + xmlbeans; the ooxml-schemas /
// full schemas artefact is deliberately NOT pulled, because the renderer only writes XWPF and a
// template that needs more is a template this module refuses.
val poiVersion = "5.4.1"

dependencies {
    api(project(":backend:tenant-security"))
    // AdminAuditStreamWriter, AuditWrite, LegalHoldService (the ADR-0008 rule 5 purge gate),
    // AuditVocabularies and RetentionClassResolver. Administration owns operator actions; this module
    // reads its governance state and never duplicates it.
    api(project(":backend:administration"))
    // JobHandler / JobEnvelope / QueueName / OutboxEnqueuer: an export is durable work, and the only
    // supported shape of durable work in this system is the worker SPI.
    api(project(":backend:worker-runtime"))
    // JsonCodec for the jsonb columns (filters, columns, authorizationSnapshot, placeholderSchema).
    api(project(":backend:crm-core"))
    // The finance metric vocabulary (UsageDimension) and the finance period state an export of finance
    // data must respect. One owner of those definitions, not a second copy in this module.
    api(project(":backend:reporting-finance"))

    // The DOCX renderer. `implementation` rather than `api`: no other module names a POI type.
    implementation("org.apache.poi:poi-ooxml:$poiVersion")

    // Phase 16: this module now contributes to the served OpenAPI document, because it owns the export
    // request's closed vocabularies (`format`, `locale`) and the catalogue response type. A vocabulary
    // that lives only in code is a vocabulary no generated client can see, and a response typed as
    // Map<String, Object> is typed as `unknown` by every client generator.
    //
    // Pinned to the SAME version property :backend:app-bootstrap and :backend:inbox-helpdesk use, so all
    // three resolve to one artifact and Gradle deduplicates instead of putting two springdocs on the
    // classpath. Declared here rather than leaned on transitively: an undeclared dependency makes this
    // module's own tests fail with NoClassDefFoundError, which is a failure that only shows up in someone
    // else's checkout.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${rootProject.extra["springdocVersion"]}")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
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
    // The integration suites run the real Flyway chain — including
    // V20261005060000__report_definitions and V20261005070000__export_jobs — against a throwaway
    // pgvector container, so a column name transcribed wrongly is a test failure here rather than a
    // production surprise. That is the entire reason every statement in this module was written
    // against those two files column by column.
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}