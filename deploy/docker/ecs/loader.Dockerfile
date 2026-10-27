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

# The lake loader and maintenance image for Amazon ECS (docs/architecture/AWS_DELTA_LAKE.md): the scripts of deploy/aws/delta-lake
# with what they run, and nothing else: Java 21 and the server's exec jar (Drishti's Delta writer, `lake build`), Python with
# deltalake, pyarrow and pyyaml (`lake publish`, `lake maintain`), the AWS CLI v2, the tools and the shipped packs (layouts, ingest keys).
# Build from the repository root after ./mvnw -q package -DskipTests:
#   docker build -f deploy/docker/ecs/loader.Dockerfile -t drishti-loader-ecs:2.9.2 .
# The task mounts one EFS access point at /opt/drishti/data (scratch lakes, locks, temp, home); the root file system is read-only.
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}
ARG AWSCLI_VERSION=2.17.0
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64-${AWSCLI_VERSION}.zip" -o /tmp/awscli.zip \
 && unzip -q /tmp/awscli.zip -d /tmp && /tmp/aws/install && rm -rf /tmp/aws /tmp/awscli.zip \
 && python3 -m venv /opt/drishti/venv \
 && /opt/drishti/venv/bin/pip install --no-cache-dir "deltalake==1.6.6" "pyarrow==26.0.0" "pyyaml>=6,<7" \
 && useradd --system --uid 10001 drishti
WORKDIR /opt/drishti/app
COPY drishti-server/target/drishti-server-*-exec.jar /opt/drishti/app.jar
COPY tools/ tools/
COPY config/packs/ config/packs/
COPY deploy/aws/ deploy/aws/
COPY deploy/docker/ecs/loader-entrypoint.sh /opt/drishti/bin/entrypoint.sh
# compiled once here, so the read-only root never needs __pycache__ written at run time
RUN /opt/drishti/venv/bin/python -m compileall -q tools \
 && chmod 0555 /opt/drishti/bin/entrypoint.sh && find deploy/aws -name '*.sh' -exec chmod 0555 {} + \
 && mkdir -p /opt/drishti/data && chown drishti:drishti /opt/drishti/data
USER drishti
ENV PATH=/opt/drishti/venv/bin:$PATH \
    DRISHTI_JAR=/opt/drishti/app.jar \
    DRISHTI_DATA_DIR=/opt/drishti/data \
    DRISHTI_PYTHON=/opt/drishti/venv/bin/python \
    DRISHTI_REPO=/opt/drishti/app \
    TMPDIR=/opt/drishti/data/tmp/loader \
    HOME=/opt/drishti/data/home/loader \
    PYTHONDONTWRITEBYTECODE=1 \
    DRISHTI_REQUIRE_EFS=true
ENTRYPOINT ["/opt/drishti/bin/entrypoint.sh"]
CMD ["deploy/aws/delta-lake/load-day.sh", "--help"]
