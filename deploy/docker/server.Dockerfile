# Drishti server image. Build from the repository root after ./mvnw -q package -DskipTests:
#   docker build -f deploy/docker/server.Dockerfile -t drishti-server:2.9.5 .
# Production runs Java 21 (the default); Java 25 is also supported: --build-arg BASE_IMAGE=eclipse-temurin:25-jre
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti
COPY drishti-server/target/drishti-server-*-exec.jar app.jar
COPY config/packs/ config/packs/
COPY config/ui-kit/ config/ui-kit/
# the server's temp base (<data dir>/tmp/server), made here so the JVM's temp folder exists from its first start
RUN mkdir -p /opt/drishti/data/tmp/server && chown -R drishti /opt/drishti/data
USER drishti
EXPOSE 18480
# -Djava.io.tmpdir: DuckDB, RocksDB and CatBoost unpack their native libraries into the JVM's startup temp folder (only the command
# line moves it); the server removes stale copies from its temp base at start, so a killed container leaves nothing in /tmp
# native memory (PERFORMANCE.md, Resident memory): two glibc malloc arenas, direct buffers capped, freed native memory given back
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:MaxDirectMemorySize=256m -XX:TrimNativeHeapInterval=60000 -Djava.io.tmpdir=/opt/drishti/data/tmp/server" \
    MALLOC_ARENA_MAX=2
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://localhost:18480/actuator/health/readiness || exit 1
# -XX:+UseCompactObjectHeaders exists from Java 25 (Java 21 refuses to start with it), so the entrypoint adds it only there
ENTRYPOINT ["sh", "-c", "v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p'); if [ \"$v\" -ge 25 ]; then set -- -XX:+UseCompactObjectHeaders; else set --; fi; exec java \"$@\" -jar app.jar"]
