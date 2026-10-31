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

# Refresh the us-equities pack (SEC EDGAR, Nasdaq Trader, GLEIF, Wikidata; prices internal only) from its public sources: the shell form of tools/publicdata/us_equities.py.
#   tools/publicdata/us-equities.sh status
#   tools/publicdata/us-equities.sh refresh --dry-run
#   tools/publicdata/us-equities.sh refresh [SOURCE ...] [--years N | --since YEAR] [--scope A,B] [--out DIR] [--into-pack] [--offline]
#   tools/publicdata/us-equities.sh refresh prices --server URL --scope AAPL   (internal only: a price snapshot in the data folder)
# Uses the console's Python environment when it exists (drishti-console/.venv), else python3. Guide: docs/guides/DATA_GUIDE.md
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/publicdata/us_equities.py "$@"
