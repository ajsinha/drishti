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

# The one wrapper of every data feed (Linux, macOS): `python3 tools/drishti.py feed ...` with the console's Python environment when it
# exists (drishti-console/.venv), else python3. What cron and the systemd units run (drishti.py feed schedule prints them).
#   tools/feeds/feed.sh list
#   tools/feeds/feed.sh run gleif
#   tools/feeds/feed.sh run --all --load inbox
#   tools/feeds/feed.sh status
# The exit code is the command's: 0 ok, 1 failed, 2 refused, 3 locked, 4 partial. Guide: docs/guides/EXTERNAL_DATA.md
set -euo pipefail
cd "$(dirname "$0")/../.."
PY=drishti-console/.venv/bin/python
[[ -x "$PY" ]] || PY=python3
exec "$PY" tools/drishti.py feed "$@"
