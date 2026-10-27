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

# Intraday batches of amended, new and cancelled trades as JSONL (each its own BATCH_ID, RUN_KIND INTRADAY): the shell form of tools/pvgen/intraday.py.
#   tools/pvgen/intraday.sh --batches 20 --trades-per-batch 15
#   tools/pvgen/intraday.sh --batches 5 --dry-run
#   tools/pvgen/intraday.sh --date 2026-10-27 --lake data/delta --server http://127.0.0.1:18480 --every 30s
#   tools/pvgen/intraday.sh --help
# Uses the console's Python environment when it exists (drishti-console/.venv), else python3. Guide: docs/guides/VAR_GUIDE.md section 15.12
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/pvgen/intraday.py "$@"
