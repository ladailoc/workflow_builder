# ==============================================================================
# Workflow Platform Backend — Production Multi-Stage Dockerfile
# Base: Eclipse Temurin Java 21 (Alpine)
# ==============================================================================

# --- Stage 1: Build Application ---
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Build packaged application
COPY src ./src
RUN mvn clean package -DskipTests -B

# --- Stage 2: Production Runtime ---
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Non-root user and directories
RUN addgroup -g 10001 -S workflow && \
    adduser -u 10001 -S workflow -G workflow && \
    mkdir -p /app/storage /tmp/workflow-platform-files && \
    chown -R workflow:workflow /app /tmp/workflow-platform-files

# Install curl and tzdata for health checks and UTC time
RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/UTC /etc/localtime && \
    echo "UTC" > /etc/timezone && \
    apk del tzdata

# Copy jar artifact from builder
COPY --from=builder --chown=workflow:workflow /workspace/target/*.jar app.jar

USER workflow:workflow

ENV TZ=UTC \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# Production container healthcheck probing actuator readiness/liveness
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=25s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Exec form for graceful shutdown signal propagation (SIGTERM)
ENTRYPOINT ["java", "-jar", "app.jar"]
