# Drishti server image. Build from the repository root after ./mvnw -q package -DskipTests:
#   docker build -f deploy/server.Dockerfile -t drishti-server:1.17.0 .
# Production runs Java 21 (the default); Java 25 is also supported: --build-arg BASE_IMAGE=eclipse-temurin:25-jre
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti
COPY drishti-server/target/drishti-server-*-exec.jar app.jar
COPY packs/ packs/
USER drishti
EXPOSE 18480
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://localhost:18480/actuator/health/readiness || exit 1
# -XX:+UseCompactObjectHeaders exists from Java 25 (Java 21 refuses to start with it), so the entrypoint adds it only there
ENTRYPOINT ["sh", "-c", "v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p'); if [ \"$v\" -ge 25 ]; then set -- -XX:+UseCompactObjectHeaders; else set --; fi; exec java \"$@\" -jar app.jar"]
