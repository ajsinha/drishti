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

# Run the public-sector feeds (World Bank WDI, FRED and the Bank of Canada, Statistics Canada, OECD, official press releases, GDELT):
# the shell form of tools/feeds/publicsector/run.py. Every argument is passed on.
#   tools/feeds/publicsector/run.sh list
#   tools/feeds/publicsector/run.sh run --all --inbox             (daily, from cron: the server's data inbox loads what it writes)
#   tools/feeds/publicsector/run.sh run central-banks --load      (FRED needs DRISHTI_FRED_API_KEY)
#   tools/feeds/publicsector/run.sh run oecd --dry-run
# Exit codes: 0 written or nothing new, 2 a pull failed, 3 a key is missing. Guide: docs/guides/PUBLIC_SECTOR_GUIDE.md
set -euo pipefail
cd "$(dirname "$0")/../../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/feeds/publicsector/run.py "$@"
