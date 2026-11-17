# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# No fat jar: compile, then copy runtime dependencies next to the classes.
RUN mvn -B -q -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/lib \
    && mkdir -p src/main/resources/static src/main/resources/views

FROM eclipse-temurin:21-jre
RUN groupadd --system bms && useradd --system --gid bms --home-dir /app bms \
    && mkdir -p /data && chown bms:bms /data
WORKDIR /app
COPY --from=build /build/lib /app/lib
COPY --from=build /build/target/classes /app/classes
# Static UI and views are served from the filesystem until jolt#68 ships.
COPY --from=build /build/src/main/resources/static /app/static
COPY --from=build /build/src/main/resources/views /app/views
ENV PORT=8080 \
    JOLT_BIND_ADDRESS=0.0.0.0 \
    DB_URL=jdbc:sqlite:/data/bms.db \
    STATIC_DIR=/app/static \
    VIEWS_DIR=/app/views
VOLUME /data
EXPOSE 8080
USER bms
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf 'GET /api/health HTTP/1.0\r\n\r\n' >&3 && head -n1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "dev.thiru.bms.Main"]
