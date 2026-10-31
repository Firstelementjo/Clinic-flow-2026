plugins {
    java
    // `java-library`: :backend:app-bootstrap consumes the SSE controller and the notification
    // service, which expose these types.
    `java-library`
}

// Notifications and realtime module.
//
// Owns backend/notifications-realtime/**:
//  - the versioned realtime event contract (realtime is a refresh hint, never the source of truth);
//  - persist-first in-app notifications with a realtime push second;
//  - Redis pub/sub fan-out so several API instances serve one tenant's stream;
//  - an SSE endpoint with reconnect and resync-from-API semantics (ADR-0003, not WebSocket).
//
// It depends on :backend:worker-runtime for the JobHandler SPI and the durable queue, because
// notification delivery is a job: an offline employee must find the notification at the start of
// their shift, so the row is the record and the push is an optimization.

extra["testcontainers.version"] = "1.21.4"

dependencies {
    api(project(":backend:worker-runtime"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The real SecurityEventPublisher bean lives in :backend:identity-access; the realtime tests need
    // it so a refused realtime subscription is recorded rather than swallowed by a stub.
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