plugins {
    java
    // `java-library`: the worker-runtime JobHandler SPI, the administration audit streams, the
    // tenant-security AI-tool guard and the finance rate-card vocabulary are compile-time inputs to
    // this module, so those types have to leave it as `api` rather than as implementation details a
    // sibling module cannot name.
    `java-library`
}

// backend/ai-rag — Spring AI behind a FirstElement policy boundary.
//
// Owns:
//  - the provider abstraction (a Spring AI ChatModel/EmbeddingModel port) with a DETERMINISTIC mock
//    provider so the whole CRM runs with no API key, plus a failover chain that fails closed;
//  - the versioned agent / prompt / model resolution and the policy snapshot it runs under;
//  - deny-by-default policy enforcement: budgets (token, cost, wall-clock) enforced BEFORE the call,
//    rate limits, a model allow-list, and a data-residency check;
//  - tenant- and project-filtered pgvector retrieval whose tenant predicate lives INSIDE the
//    similarity statement, and asynchronous ingestion with deletion propagation;
//  - the explicit tool registry: schema-defined tools with a permission, a capability, a risk class
//    and a dry-run; unknown tools are refused, scope-override arguments are rejected, and a
//    consequential action only ever becomes an approval request;
//  - redaction of prompts AND responses (a redaction failure is a failure), provenance records, and
//    the ai_run_* trace that makes an answer explainable later;
//  - the armed, batched, checkpointed, idempotent backfill of the three ai_run_* child tenant-scope
//    columns from their parent ai_runs row (ADR 0009 §2).
//
// It depends on :backend:tenant-security for TenantQueryGuard / TenantScope / AiToolExecutionGuard,
// on :backend:administration for the append-only audit streams, on :backend:worker-runtime for the
// durable JobHandler SPI (an embedding or a backfill is never run on a request thread), on
// :backend:crm-core for JsonCodec, on :backend:reporting-finance for UsageEventWriter (the AI request
// and token events that make AI spend part of the finance ledger) and RateCardResolver (the only
// source of a pricing decision), and on :backend:inbox-helpdesk for the conversation-mode vocabulary.
//
// The database module is NOT a main-scope dependency: the packaged Flyway chain is already on the
// test classpath through :backend:database, and no production source here executes DDL.

// Pinned to the same Testcontainers version every other DB-backed module uses.
extra["testcontainers.version"] = "1.21.4"

// Spring AI is NOT in the Spring Boot BOM, so the version is stated here rather than resolved
// transitively -- the same discipline reporting-exports applies to Apache POI. 1.1.x is the line that
// targets Spring Boot 3.5.x, which is what this build runs.
//
// ONLY the client/model jars are taken. No `spring-ai-starter-model-*` and no provider starter is
// added, for two reasons that are both load-bearing:
//   * a provider starter reads an API key from configuration or the environment, and this migration
//     never reads a credential from a file in the repository (phase-10 hard rule);
//   * Spring AI's provider auto-configuration would decide, at boot, which provider bean exists.
//     ADR 0007 Part 2 rule 3 says the CRM must not depend on a provider being configured, so which
//     provider is reachable has to be a FirstElement policy decision made per run, not an
//     auto-configured singleton. Spring AI is therefore used as the message/response model and the
//     ChatClient/advisor chain inside our own orchestrator, and the provider itself is ours.
val springAiVersion = "1.1.8"

dependencies {
    api(project(":backend:tenant-security"))
    // AdminAuditStreamWriter / AuditWrite / AuditVocabularies: an AI tool call, a refusal and an
    // approval decision are audit events on the stream a security reviewer reads.
    api(project(":backend:administration"))
    // JobHandler / JobEnvelope / QueueName: ingestion, embedding and the ADR-0009 backfill are
    // durable work, and the only supported shape of durable work in this system is the worker SPI.
    api(project(":backend:worker-runtime"))
    // JsonCodec for the jsonb columns (detail, argumentsRedacted, resultRedacted, schemaDefinition).
    api(project(":backend:crm-core"))
    // UsageEventWriter + UsageEventFactory + RateCardResolver: AI requests and tokens are part of
    // the finance ledger, and cost is decided by the rate card -- never by this module's own guess.
    api(project(":backend:reporting-finance"))

    // The Spring AI client model: Prompt / ChatResponse / ToolCallback and the ChatClient with its
    // advisor chain. Used INSIDE the orchestrator, never as a policy surface.
    implementation("org.springframework.ai:spring-ai-client-chat:$springAiVersion")
    implementation("org.springframework.ai:spring-ai-model:$springAiVersion")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.slf4j:slf4j-api")
    // springdoc, for the /api/v1/ai/drafts read route. The annotations are compile-time only and
    // three artifacts resolve to one, which Gradle deduplicates -- the same statement
    // :backend:inbox-helpdesk and :backend:reporting-exports make. Without it the draft route would
    // be undocumented in the served OpenAPI document, and an undocumented route is a route no client
    // can be generated for.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${rootProject.extra["springdocVersion"]}")
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
    // The integration suites run the real Flyway chain -- including
    // V20261005100000__ai_policy_and_governance, ...5110000 and ...5120000 -- against a throwaway
    // pgvector container, so a column name transcribed wrongly is a test failure here rather than a
    // production surprise. That is the entire reason every statement in this module was written
    // against those files column by column.
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}