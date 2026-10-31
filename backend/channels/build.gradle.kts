plugins {
    java
    // `java-library`: :backend:notifications-realtime does not depend on channels, but
    // :backend:app-bootstrap consumes both, and the connector port is the type the webhook
    // controllers and the outbound delivery service expose.
    `java-library`
}

// Channels / connectors module.
//
// Owns backend/channels/**:
//  - the provider-neutral connector port, a faithful port of packages/connectors;
//  - Mock / WhatsApp / Instagram / Messenger / Telegram adapters with explicit capability
//    declarations;
//  - raw-body Meta signature verification and constant-time Telegram secret comparison;
//  - provider-event deduplication (idempotency_records, scope PROVIDER_EVENT) plus the
//    messages-projectId-channel-externalMessageId unique index as the correctness guarantee;
//  - persist-outbound-before-delivery with delivery status tracking;
//  - the webhook HTTP surface and the connector health read models.
//
// It depends on :backend:worker-runtime for the JobHandler SPI and the OutboxEnqueuer: an
// outbound intent must be written in the SAME transaction as the message row, and a handler needs
// the durable queue contract. The dependency arrow points channels -> worker-runtime and never
// back, so the runtime has no domain dependency.

extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:worker-runtime"))
    api(project(":backend:crm-core"))
    // api, not implementation: the webhook controller and the outbound handler both need the
    // normalized inbound contract that inbox-helpdesk declares.
    api(project(":backend:inbox-helpdesk"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The real SecurityEventPublisher bean lives in :backend:identity-access; the channels tests
    // need it so a refused webhook signature is recorded rather than swallowed by a stub.
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

tasks.test {
    systemProperty("org.testcontainers.logging", "slf4j")
    listOf("DOCKER_HOST", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> providers.environmentVariable(name).orNull?.let { environment(name, it) } }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}