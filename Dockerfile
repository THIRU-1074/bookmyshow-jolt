# syntax=docker/dockerfile:1
# UI stage: build the React app to static files. Node exists only here, never at runtime.
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
# Cap the heap so the build fits small builders (Render free tier).
RUN NODE_OPTIONS=--max-old-space-size=768 npm run build

# Build stage: compile and collect runtime dependencies (tests run in CI, not here).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# The build generates the API records (dev.thiru.bms.api.generated) from the schema.
COPY api/openapi.yaml ./api/openapi.yaml
# No fat jar: package the app classes, then copy runtime dependencies next to them.
RUN mvn -B -q package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/lib \
    && cp target/bookmyshow-jolt-*.jar /build/lib/app.jar \
    && mkdir -p src/main/resources/static src/main/resources/views

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 bms \
    && useradd --system --uid 10001 --gid bms --home-dir /app bms \
    && mkdir -p /app /data && chown bms:bms /app /data
WORKDIR /app
COPY --from=build /build/lib /app/lib
# The React build (STATIC_DIR) is produced by Node outside the jar; views are copied too, though the jar also carries them on the class path.
COPY --from=web /web/dist /app/static
COPY --from=build /build/src/main/resources/views /app/views
ENV PORT=8080 \
    JOLT_BIND_ADDRESS=0.0.0.0 \
    DB_URL=jdbc:sqlite:/data/bms.db \
    STATIC_DIR=/app/static \
    VIEWS_DIR=/app/views \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER bms
VOLUME /data
EXPOSE 8080
# bash /dev/tcp instead of curl: the JRE base image does not promise curl.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf 'GET /api/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3 && head -n1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["java", "-cp", "/app/lib/*", "dev.thiru.bms.Main"]
