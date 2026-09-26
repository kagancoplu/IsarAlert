# ======================================================
# IsarAlert — Dockerfile
# ======================================================
# Multi-stage build:
#   Stage 1 (builder): compiles the app with Maven
#   Stage 2 (runtime): runs the JAR on a minimal JRE image
# ======================================================

# ---- Stage 1: Build ----
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /build

# Cache Maven dependencies separately from source code
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN ./mvnw package -DskipTests -B

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app

# Create a non-root user for security
RUN addgroup -S isaralert && adduser -S isaralert -G isaralert
USER isaralert

# Copy the fat JAR from the builder stage
COPY --from=builder /build/target/isar-alert-*.jar app.jar

# Expose the Spring Boot port
EXPOSE 8080

# Health check — relies on Spring Actuator
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
