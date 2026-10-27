#!/usr/bin/env bash
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

# Intraday updates published to the PV topic, each message JSONL text of several records (needs kafka-python): the shell form of tools/pvgen/publish_kafka.py.
#   tools/pvgen/publish-kafka.sh --bootstrap localhost:9092 --rate 5 --seconds 60
#   tools/pvgen/publish-kafka.sh --dry-run --seconds 5
#   tools/pvgen/publish-kafka.sh --per-message 10 --save data/pvgen/sent.jsonl
#   tools/pvgen/publish-kafka.sh --help
# Uses the console's Python environment when it exists (drishti-console/.venv), else python3. Guide: docs/guides/VAR_GUIDE.md section 15.12
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/pvgen/publish_kafka.py "$@"
