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

# Drishti console image for Amazon ECS with EFS, a separate container from the server (docs/architecture/AWS_DEPLOYMENT.md
# section 6). Build from the repository root:
#   docker build -f deploy/docker/ecs/console.Dockerfile -t drishti-console-ecs:2.6.7 .
# Calc's Python runtime (Pyodide) and DuckDB-Wasm are fetched in the first stage (pinned, SHA-256 checked), so a running
# console needs no internet.
FROM python:3.13-slim AS pyodide
WORKDIR /src
COPY tools/fetch-pyodide.sh tools/fetch-pyodide.sh
COPY tools/fetch-duckdb-wasm.sh tools/fetch-duckdb-wasm.sh
COPY drishti-console/ drishti-console/
RUN bash tools/fetch-pyodide.sh && bash tools/fetch-duckdb-wasm.sh && rm -rf /root/.cache/drishti

FROM python:3.13-slim
RUN useradd --system --uid 10001 drishti
WORKDIR /opt/drishti/console
COPY drishti-console/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY --from=pyodide /src/drishti-console/ .
# the help centre renders the repository's documents; packs as shipped, outside the EFS paths (as in the server image)
COPY docs/ /opt/drishti/docs/
COPY config/packs/ /opt/drishti/shipped/packs/
COPY tools/packbundle.py /opt/drishti/tools/packbundle.py
COPY LICENSE CHANGELOG.md RELEASE_NOTES.md THIRD-PARTY-NOTICES.md /opt/drishti/
COPY deploy/docker/ecs/console-entrypoint.sh /opt/drishti/bin/entrypoint.sh
# compiled once here, so the read-only root never needs __pycache__ written at run time
RUN python -m compileall -q /opt/drishti/console && chmod 0555 /opt/drishti/bin/entrypoint.sh \
 && mkdir -p /opt/drishti/data /opt/drishti/config /opt/drishti/sutras \
 && chown drishti:drishti /opt/drishti/data /opt/drishti/config /opt/drishti/sutras
USER drishti
ENV DRISHTI_DATA_DIR=/opt/drishti/data \
    DRISHTI_PACKS_DIR=/opt/drishti/shipped/packs \
    DRISHTI_AI_DIR=/opt/drishti/config/ai \
    DRISHTI_MCP_DIR=/opt/drishti/config/mcp \
    DRISHTI_CONSOLE_DATA_DIR=/opt/drishti/data/console \
    DRISHTI_CONSOLE_TMP_DIR=/opt/drishti/data/tmp/console \
    TMPDIR=/opt/drishti/data/tmp/console \
    HOME=/opt/drishti/data/home/console \
    PYTHONDONTWRITEBYTECODE=1 \
    DRISHTI_CONSOLE_HOST=0.0.0.0 \
    DRISHTI_CONSOLE_PORT=17480 \
    DRISHTI_REQUIRE_EFS=true
EXPOSE 17480
ENTRYPOINT ["/opt/drishti/bin/entrypoint.sh"]
