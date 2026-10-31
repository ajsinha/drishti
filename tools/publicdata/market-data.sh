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

# Refresh official market data for the market-data pack (ECB FX, US Treasury curve, SOFR, ESTR) from its public sources: the shell form of tools/publicdata/official_market_data.py.
#   tools/publicdata/market-data.sh status
#   tools/publicdata/market-data.sh refresh --dry-run
#   tools/publicdata/market-data.sh refresh [SOURCE ...] [--years N | --since YEAR] [--scope A,B] [--out DIR] [--internal-ok]
# Uses the console's Python environment when it exists (drishti-console/.venv), else python3. Guide: docs/guides/DATA_GUIDE.md
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/publicdata/official_market_data.py "$@"
