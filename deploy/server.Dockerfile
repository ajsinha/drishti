# Drishti server image. Build from the repository root after ./mvnw -q package -DskipTests:
#   docker build -f deploy/server.Dockerfile -t drishti-server:1.15.0 .
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti
COPY drishti-server/target/drishti-server-*-exec.jar app.jar
COPY packs/ packs/
USER drishti
EXPOSE 18480
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+UseCompactObjectHeaders"
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- http://localhost:18480/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
