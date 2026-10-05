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

# Loads the banking packs' data domains into one DuckDB file in the layout the duckdb connector reads at scale
# (DuckDbLayout: a schema per domain, <domain>.entities written in (kind, business_date, id) order with the pack's
# promoted fields as columns, each kind's business dates in <domain>.entity_dates):
#   tools/load-duckdb.sh [database] [--keep-days N] [--memory-limit 2GB] [--trades N --days D]
#   tools/load-duckdb.sh data/duckdb/drishti.duckdb                         the 1,791 sample documents x 10 business days
#   tools/load-duckdb.sh data/duckdb/drishti.duckdb --trades 10000 --days 3  and a generated book of 10,000 trades, streamed
# The samples replace each domain they reach (--recreate); a bulk book replaces the trade days it covers. Each load
# writes <database>.loading and renames it over <database>, so a running Drishti server keeps reading the old file
# until it reopens the new one. --keep-days N drops the business dates more than N calendar days before today.
# Dates are guarded (LoadGuard): a row dated after tomorrow in the business zone is not loaded (--future-days N, --zone Z)
# and the load ends with an error naming it; retention counts back from --as-of (today), never from the newest date
# loaded, and dropping more than --max-drop-share (0.5) of a table needs --force-drop. The database defaults to DRISHTI_DUCKDB_PATH, else data/duckdb/drishti.duckdb.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
DB="${DRISHTI_DUCKDB_PATH:-data/duckdb/drishti.duckdb}"; OPTS=(); TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { DB="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-days|--memory-limit|--parsers|--threads) OPTS+=("$1" "$2"); shift 2 ;;
    --as-of|--future-days|--zone|--max-drop-share) OPTS+=("$1" "$2"); shift 2 ;;
    --force-drop) OPTS+=("$1"); shift ;;
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-duckdb -am
CP="plugins/drishti-plugin-duckdb/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-duckdb -Dmdep.outputFile=/dev/stdout)"
"$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.duckdb.DuckDbLoader data/banking.jsonl "$DB" --recreate "${OPTS[@]}"
if [[ -n "$TRADES" ]]; then
  # the bulk book straight from the generator into the loader: no file of gigabytes in between
  uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl - \
    | "$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.duckdb.DuckDbLoader - "$DB" "${OPTS[@]}"
fi
