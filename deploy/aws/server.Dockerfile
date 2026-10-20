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

# Drishti server image for Amazon ECS with EFS (docs/architecture/AWS_DEPLOYMENT.md). Build from the repository root
# after ./mvnw -q package -DskipTests:
#   docker build -f deploy/aws/server.Dockerfile -t drishti-server-ecs:2.5.0 .
# Java 21 by default; Java 25: --build-arg BASE_IMAGE=eclipse-temurin:25-jre
#
# The image holds only what never changes while it runs (the jar, the packs it ships, the entry point). Everything the
# server writes is under /opt/drishti/data, /opt/drishti/config and /opt/drishti/sutras, each an EFS access point
# mounted at that same path; the task runs with a read-only root file system, so no write can land anywhere else.
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti
COPY drishti-server/target/drishti-server-*-exec.jar app.jar
# shipped packs live outside the EFS paths, so a mount never hides them; installed packs go to data/packs/installed (EFS)
COPY config/packs/ shipped/packs/
COPY deploy/aws/server-entrypoint.sh bin/entrypoint.sh
# the mount points, owned by the user the EFS access points enforce (uid/gid 10001)
RUN chmod 0555 bin/entrypoint.sh && mkdir -p data config sutras && chown drishti:drishti data config sutras
USER drishti
ENV DRISHTI_DATA_DIR=/opt/drishti/data \
    DRISHTI_PACKS_DIR=/opt/drishti/shipped/packs \
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
    DRISHTI_HEAP=8g
EXPOSE 18480
# ECS uses the task definition's health check; this one serves `docker run` checks of the image
HEALTHCHECK --interval=15s --timeout=3s CMD wget -qO- "http://$(hostname -I | awk '{print $1}'):18480/actuator/health/readiness" || exit 1
ENTRYPOINT ["/opt/drishti/bin/entrypoint.sh"]
