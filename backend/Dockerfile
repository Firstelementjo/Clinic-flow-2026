# ==============================================================================
# Clinic Flow Backend - Production Multi-Stage Dockerfile
# Java 21 LTS + Spring Boot 3 + Alpine Linux
# Engineered for Portability (x86_64 & ARM64), Fast Startup & Low Footprint
# ==============================================================================

# Stage 1: Build Executable Fat Jar with Gradle
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

RUN apk add --no-cache bash

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

RUN ./gradlew dependencies --no-daemon || true

COPY backend ./backend

RUN ./gradlew :backend:app-bootstrap:bootJar -x test --no-daemon

# Stage 2: Minimalist, Hardened Production JRE Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S clinicflow && adduser -S clinicflow -G clinicflow

WORKDIR /app

COPY --from=builder /workspace/backend/app-bootstrap/build/libs/app-bootstrap-*.jar /app/clinic-flow-api.jar

RUN chown -R clinicflow:clinicflow /app

USER clinicflow

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"

EXPOSE 4000 4101

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:4101/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/clinic-flow-api.jar"]
