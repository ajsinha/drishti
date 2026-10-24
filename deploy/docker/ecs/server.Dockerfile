# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

# Drishti server image for Amazon ECS with EFS (docs/architecture/AWS_DEPLOYMENT.md section 5). Build from the repository
# root after ./mvnw -q package -DskipTests:
#   docker build -f deploy/docker/ecs/server.Dockerfile -t drishti-server-ecs:2.7.0 .
# Java 21 by default; Java 25: --build-arg BASE_IMAGE=eclipse-temurin:25-jre
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
# curl: the health check and the task metadata endpoint (the task's ARN for task.lock)
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 drishti
WORKDIR /opt/drishti
COPY drishti-server/target/drishti-server-*-exec.jar app.jar
# shipped packs live outside the EFS paths, so a mount never hides them; installed packs go to data/packs/installed (EFS)
COPY config/packs/ shipped/packs/
COPY config/ui-kit/ shipped/ui-kit/
COPY deploy/docker/ecs/server-entrypoint.sh bin/entrypoint.sh
# the mount points, owned by the user the EFS access points enforce (uid/gid 10001)
RUN chmod 0555 bin/entrypoint.sh && mkdir -p data config sutras && chown drishti:drishti data config sutras
USER drishti
ENV DRISHTI_DATA_DIR=/opt/drishti/data \
    DRISHTI_PACKS_DIR=/opt/drishti/shipped/packs \
    DRISHTI_UI_KIT_DIR=/opt/drishti/shipped/ui-kit \
    DRISHTI_CONNECTORS_DIR=/opt/drishti/config/connectors \
    DRISHTI_NET_DIR=/opt/drishti/config/net \
    DRISHTI_AI_DIR=/opt/drishti/config/ai \
    DRISHTI_MCP_DIR=/opt/drishti/config/mcp \
    DRISHTI_BI_DIR=/opt/drishti/config/bi \
    DRISHTI_SUTRAS=/opt/drishti/sutras \
    DRISHTI_PORT=18480 \
    TMPDIR=/opt/drishti/data/tmp/server \
    HOME=/opt/drishti/data/home/server \
    DRISHTI_REQUIRE_EFS=true \
    DRISHTI_HEAP=8g \
    MALLOC_ARENA_MAX=2
EXPOSE 18480
ENTRYPOINT ["/opt/drishti/bin/entrypoint.sh"]
