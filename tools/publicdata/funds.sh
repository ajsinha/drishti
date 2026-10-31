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

# Refresh the funds pack (SEC N-PORT, N-CEN and risk/return summary data sets, ticker files; GLEIF): the shell form of tools/publicdata/funds.py.
#   tools/publicdata/funds.sh status
#   tools/publicdata/funds.sh refresh --dry-run
#   tools/publicdata/funds.sh refresh [SOURCE ...] [--years N | --since YEAR] [--scope FUNDS:300,HOLDINGS:1000] [--into-pack] [--offline]
#   tools/publicdata/funds.sh refresh ncen rr --since 2025   (more quarters of the census and the prospectus summaries)
# Uses the console's Python environment when it exists (drishti-console/.venv), else python3. Guide: docs/guides/DATA_GUIDE.md
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/publicdata/funds.py "$@"
