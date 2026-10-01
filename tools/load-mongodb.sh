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

# Loads the banking packs' data into MongoDB (a collection per data domain, database `drishti` by default), in the
# layout the connector reads (MongoLayout: a document per entity per business date, the pack's promoted fields beside
# it, zstd compression, the day_ids index):
#   tools/load-mongodb.sh [uri] [database] [--keep-days N | --ttl-days N] [--doc-format string|bson] [--trades N --days D]
#   tools/load-mongodb.sh mongodb://localhost:27017 drishti                           the 1,791 sample documents x 10 business days
#   tools/load-mongodb.sh mongodb://localhost:27017 drishti --trades 1000000 --days 3  and a book of a million trades, streamed
# --keep-days N deletes, after loading, every business day of each kind older than its N newest;
# --ttl-days N lets MongoDB delete each day's documents N days after their business date (a TTL index on expireAt).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
URI="mongodb://localhost:27017"; DB="drishti"; OPTS=(); TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { URI="$1"; shift; }
[[ $# -gt 0 && "$1" != --* ]] && { DB="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-days|--ttl-days|--doc-format|--batch|--in-flight) OPTS+=("$1" "$2"); shift 2 ;;
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-mongodb -am
CP="plugins/drishti-plugin-mongodb/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-mongodb -Dmdep.outputFile=/dev/stdout)"
LOADER=("$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.mongodb.MongoLoader)
if [[ -n "$TRADES" ]]; then
  "${LOADER[@]}" data/banking.jsonl "$URI" "$DB" ${OPTS[@]+"${OPTS[@]}"}
  # the bulk book straight from the generator into the loader: no file of gigabytes in between
  uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl - \
    | "${LOADER[@]}" - "$URI" "$DB" ${OPTS[@]+"${OPTS[@]}"}
else
  "${LOADER[@]}" data/banking.jsonl "$URI" "$DB" ${OPTS[@]+"${OPTS[@]}"}
fi
