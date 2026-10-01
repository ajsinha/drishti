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

# Loads the banking packs' data domains into PostgreSQL in the layout the jdbc connector's table mode reads at scale
# (PostgresLayout: <domain>.entities partitioned by month, the pack's promoted fields as columns, each kind's business
# dates in <domain>.entity_dates):
#   tools/load-postgres.sh [jdbc-url] [--user U] [--password P] [--keep-months N] [--trades N --days D]
#   tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti                     the 1,791 sample documents x 10 business days
#   tools/load-postgres.sh jdbc:postgresql://localhost:5432/drishti --trades 1000000 --days 3   and a book of a million trades, streamed
# The samples replace each domain's table (--recreate); a bulk book replaces the trade days it covers. --keep-months N
# drops the monthly partitions older than the newest N months (history retention: dropping a table, not deleting rows).
# User and password default to DRISHTI_PG_USER / DRISHTI_PG_PASSWORD, else drishti / drishti.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
URL="${DRISHTI_PG_URL:-jdbc:postgresql://localhost:5432/drishti}"; OPTS=(); TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { URL="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --user|--password|--keep-months|--writers) OPTS+=("$1" "$2"); shift 2 ;;
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-jdbc -am
CP="plugins/drishti-plugin-jdbc/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-jdbc -Dmdep.outputFile=/dev/stdout)"
"$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.jdbc.PostgresLoader data/banking.jsonl "$URL" --recreate "${OPTS[@]}"
if [[ -n "$TRADES" ]]; then
  # the bulk book straight from the generator into the loader: no file of gigabytes in between
  uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl - \
    | "$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.jdbc.PostgresLoader - "$URL" "${OPTS[@]}"
fi
