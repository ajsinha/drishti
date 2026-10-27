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

# Recomputes the VaR the engine should show from the JSONL files and compares it with the server: the shell form of tools/pvgen/check.py.
#   tools/pvgen/check.sh --morning data/pvgen/morning-2026-10-27 --intraday data/pvgen/intraday-2026-10-27 --server http://127.0.0.1:18480
#   tools/pvgen/check.sh --morning data/pvgen/morning-2026-09-30 --scope BANK
#   tools/pvgen/check.sh --help
# Uses the console's Python environment when it exists (drishti-console/.venv), else python3. Guide: docs/guides/VAR_GUIDE.md section 15.12
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/pvgen/check.py "$@"
