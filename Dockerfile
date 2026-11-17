# syntax=docker/dockerfile:1
# Build stage: compile and collect runtime dependencies (tests run in CI, not here).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home /app bms \
    && mkdir -p /app /data && chown bms /app /data
WORKDIR /app
COPY --from=build /src/target/lib ./lib
COPY --from=build /src/target/bookmyshow-jolt-*.jar ./lib/app.jar
# Static files are served from disk until Jolt can serve from the classpath (jolt#68).
COPY --from=build /src/src/main/resources/static ./static
ENV PORT=8080 \
    JOLT_BIND_ADDRESS=0.0.0.0 \
    STATIC_DIR=/app/static \
    DB_URL=jdbc:sqlite:/data/bms.db \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER bms
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD curl -fsS "http://127.0.0.1:${PORT}/api/health" || exit 1
ENTRYPOINT ["java", "-cp", "/app/lib/*", "dev.thiru.bms.Main"]
