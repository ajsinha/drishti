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

# The morning run of a stand-in pricing system: a full day's scenario PVs, P&L vectors and scenario FX as JSONL: the shell form of tools/pvgen/morning.py.
#   tools/pvgen/morning.sh --date 2026-10-27 --gzip
#   tools/pvgen/morning.sh --all-vectors --dry-run
#   tools/pvgen/morning.sh --lake data/delta --server http://127.0.0.1:18480
#   tools/pvgen/morning.sh --help
# Uses the console's Python environment when it exists (drishti-console/.venv), else python3. Guide: docs/guides/VAR_GUIDE.md section 15.12
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/pvgen/morning.py "$@"
