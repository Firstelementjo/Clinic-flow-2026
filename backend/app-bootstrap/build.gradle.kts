plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":backend:shared-kernel"))
    implementation(project(":backend:database"))
    implementation(project(":backend:tenant-security"))
    implementation(project(":backend:identity-access"))
    implementation(project(":backend:crm-core"))
    implementation(project(":backend:inbox-helpdesk"))
    // Phase 5b: the transactional outbox engine, the connector port with the webhook surface, and
    // the notification/SSE runtime. `implementation` rather than `api` because the API is a leaf
    // deployable and exposes none of these types to another module.
    implementation(project(":backend:worker-runtime"))
    implementation(project(":backend:channels"))
    implementation(project(":backend:notifications-realtime"))
    // Phase 6: versioned workflows, SLA calendars/clocks, scoring, escalation and deterministic
    // routing. `implementation` rather than `api`: the API is a leaf deployable and exposes none of
    // these types to another module.
    implementation(project(":backend:workflows-sla-routing"))
    // Phase 7: the admin control plane (admin read models + typed allow-listed maintenance commands)
    // and the operational read models (jobs/connectors/realtime/migrations/vector freshness). The
    // second depends on the first, so the pair is one line here rather than two.
    implementation(project(":backend:administration"))
    implementation(project(":backend:operations-observability"))
    // Phase 8: the usage ledger, effective-dated rate cards, the daily/monthly read models, the
    // period close/lock/reopen lifecycle, approved adjustments, reconciliation and the redacted
    // drill-through. `implementation` rather than `api`: the API is a leaf deployable and exposes none
    // of these types to another module.
    implementation(project(":backend:reporting-finance"))
    // Phase 9: the versioned report catalogue, policy-before-aggregation export planning, streaming
    // Arabic-safe CSV and controlled DOCX, the ExportJob state machine, signed expiring downloads and
    // legal-hold-gated artefact retention. `implementation` rather than `api`: the API is a leaf
    // deployable and exposes none of these types to another module.
    implementation(project(":backend:reporting-exports"))
    // Phase 10: the AI provider abstraction (Spring AI behind a FirstElement port, with a deterministic
    // mock provider so the CRM is fully usable with NO provider key), tenant-filtered pgvector retrieval,
    // deny-by-default schema-defined tools, budgets, redaction, provenance and the ai_run_* trace.
    // `implementation` rather than `api`: the API is a leaf deployable and exposes none of these types to
    // another module. Including it here is what makes the "no provider key, CRM fully usable" claim a
    // property of the assembled application rather than of this module's own tests -- the beans are on the
    // boot classpath and AiRagModule is component-scanned like every other runtime package.
    implementation(project(":backend:ai-rag"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${rootProject.extra["springdocVersion"]}")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:testcontainers")
}

tasks.bootJar {
    archiveBaseName.set("firstelement-api")
}

tasks.test {
    // Phase 2: keep smoke tests decoupled from a live database. Flyway runs
    // for real in `java -jar` boots; the migration chain itself is proven by
    // the Testcontainers fixture in :backend:database.
    systemProperty("spring.flyway.enabled", "false")
}
