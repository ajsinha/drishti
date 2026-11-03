# Drishti server image. Build from the repository root after ./mvnw -q package -DskipTests:
#   docker build -f deploy/docker/server.Dockerfile -t drishti-server:2.9.11.a .
# Production runs Java 21 (the default); Java 25 is also supported: --build-arg BASE_IMAGE=eclipse-temurin:25-jre
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti
COPY drishti-server/target/drishti-server-*-exec.jar app.jar
COPY config/packs/ config/packs/
COPY config/editions/ /opt/drishti/config/editions/
COPY config/ui-kit/ config/ui-kit/
COPY config/alerts/ config/alerts/
COPY --chmod=0555 deploy/bin/drishti-node.sh /opt/drishti/bin/drishti-node.sh
# the server's temp base (<data dir>/tmp/server), made here so the JVM's temp folder exists from its first start
RUN mkdir -p /opt/drishti/data/tmp/server && chown -R drishti /opt/drishti/data
USER drishti
EXPOSE 18480
# -Djava.io.tmpdir: DuckDB, RocksDB and CatBoost unpack their native libraries into the JVM's startup temp folder (only the command
# line moves it); the server removes stale copies from its temp base at start, so a killed container leaves nothing in /tmp
# native memory (PERFORMANCE.md, Resident memory): two glibc malloc arenas, direct buffers capped, the JDK's per-thread temporary direct
# buffers cached up to 256 KB only, freed native memory given back, native memory tracked; a heap dump on OutOfMemoryError in data/dumps
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:MaxDirectMemorySize=256m -XX:TrimNativeHeapInterval=60000 -Djdk.nio.maxCachedBufferSize=262144 -XX:NativeMemoryTracking=summary -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/drishti/data/dumps -Djava.io.tmpdir=/opt/drishti/data/tmp/server" \
    MALLOC_ARENA_MAX=2 \
    DRISHTI_VAR_KEEP_HOT_FILES=true \
    DRISHTI_EXPOSURE_KEEP_HOT_FILES=true
# the hot files are kept across restarts (PO-10, 2.9.7): the disk is bounded (drishti.var.tier.max-disk-gb, the free-space guard)
# the container's health is liveness (a restart decision); readiness (CD1: DOWN while a date opens or a shard builds, OUT_OF_SERVICE while
# drained) is for routing: load balancers and the rolling upgrade runner (CLUSTER_DEPLOYMENT.md section 9.1)
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://localhost:18480/actuator/health/liveness || exit 1
# -XX:+UseCompactObjectHeaders exists from Java 25 (Java 21 refuses to start with it), so the entrypoint adds it only there; the rotated
# GC log (5 x 20 MB) goes to data/logs, made first (the data folder is a volume, and the JVM does not start when the log's folder is missing);
# drishti-node.sh run is the stop hook (CD2): on docker stop the node drains first (give it time: docker stop -t 120), then Java stops.
# The in-memory lake (IN_MEMORY_LAKE.md section 3a): with memory stores, set DRISHTI_LAKE_MEMORY_MB to their max-mb added up: the
# command line then raises the direct-memory limit to 256 MB plus that (it wins over JAVA_TOOL_OPTIONS); Health turns red when short
ENTRYPOINT ["sh", "-c", "mkdir -p /opt/drishti/data/logs /opt/drishti/data/dumps; v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p'); if [ \"$v\" -ge 25 ]; then set -- -XX:+UseCompactObjectHeaders; else set -- -XX:+ZGenerational; fi; if [ \"${DRISHTI_LAKE_MEMORY_MB:-0}\" -gt 0 ] 2>/dev/null; then set -- \"$@\" -XX:MaxDirectMemorySize=$((256 + DRISHTI_LAKE_MEMORY_MB))m; fi; exec /opt/drishti/bin/drishti-node.sh run java \"$@\" -Xlog:gc*:file=/opt/drishti/data/logs/gc-%p.log:time,uptime:filecount=5,filesize=20m -jar app.jar"]
