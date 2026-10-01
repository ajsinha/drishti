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

# Builds the banking packs' Delta Lake, the history the server reads by default, in the layout each pack declares:
#   tools/load-delta.sh [root] [--days D] [--trades N]
#   tools/load-delta.sh                              the 1,791 sample documents x 10 business days into data/delta (a small demo)
#   tools/load-delta.sh --trades 50000               and a trade book of 50,000 trades a day for 3 days (a medium demo)
#   tools/load-delta.sh --trades 1000000 --days 3    a million trades a day (the scale test; about 1.6 GB a day)
# The samples rebuild every domain's tables; --trades replaces the trade table with a book of that size (its first 750
# trades are the samples), --days sets how many business days that book covers (default 3).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="data/delta"; TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { ROOT="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
WITH=(--with deltalake --with pyarrow --with pyyaml)
uv run -q "${WITH[@]}" python tools/packgen/banking/make_data.py --lake "$ROOT"
if [[ -n "$TRADES" ]]; then
  uv run -q "${WITH[@]}" python tools/samplegen/bulk_trades.py --root "$ROOT" --trades "$TRADES" --days "$DAYS"
fi
