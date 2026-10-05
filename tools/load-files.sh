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

# Writes the banking packs' data domains as the file connector's JSON-lines files, one per kind per business day
# (<root>/<domain>/<yyyy-MM-dd>/<kind>.jsonl), the simplest store Drishti reads:
#   tools/load-files.sh [root] [--trades N --days D]
#   tools/load-files.sh                                the 1,791 sample documents x 10 business days into data/files
#   tools/load-files.sh --trades 50000                 and a book of 50,000 trades a day for 3 days
#   tools/load-files.sh --trades 1000000 --days 3      a million trades a day (about 7 GB of JSON a day)
# Each file the stream reaches is replaced whole (written beside it, then moved into place), so a running server
# never reads half a day; it picks the new files up on its next rescan.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
ROOT="data/files"; TRADES=""; DAYS="3"; OPTS=()
[[ $# -gt 0 && "$1" != --* ]] && { ROOT="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    --future-days|--zone) OPTS+=("$1" "$2"); shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-file -am
CP="plugins/drishti-plugin-file/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-file -Dmdep.outputFile=/dev/stdout)"
"$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.file.JsonlLoader data/banking.jsonl "$ROOT" "${OPTS[@]}"
if [[ -n "$TRADES" ]]; then
  # the bulk book straight from the generator into the loader: no intermediate file
  uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl - \
    | "$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.file.JsonlLoader - "$ROOT" "${OPTS[@]}"
fi
