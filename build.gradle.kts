plugins {
    java
    id("org.springframework.boot") version "3.5.6" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

extra["springdocVersion"] = "2.8.13"

allprojects {
    group = "com.firstelement"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Test workers are forked JVMs and do NOT inherit org.gradle.jvmargs. Without this flag they
        // fall back to the host's broken IPv6 socket handling and every Testcontainers-backed
        // integration test fails with "BindException: Cannot assign requested address: getsockopt"
        // when JDBC reaches its own Postgres container — which reads as "Docker is down" and is not.
        // Set here because this is the one block every module's test task inherits.
        // See ops/quality/RUNBOOK-local-build-failures.md.
        jvmArgs("-Djava.net.preferIPv4Stack=true")
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.6")
    }
}
