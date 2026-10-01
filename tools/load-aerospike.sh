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

# Loads the banking packs' data into Aerospike (a set per data domain, namespace `test` by default), in the layout the
# connector reads (AerospikeLayout: a record per entity per business date, an index record per entity, the pack's
# promoted fields as bins):
#   tools/load-aerospike.sh [hosts] [namespace] [--ttl-days N] [--trades N --days D]
#   tools/load-aerospike.sh localhost:3000 test                          the 1,791 sample documents x 10 business days
#   tools/load-aerospike.sh localhost:3000 test --trades 1000000 --days 3   and a book of a million trades, streamed
# --ttl-days N lets Aerospike expire each day's documents after N days (history retention without a maintenance job).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
HOSTS="localhost:3000"; NS="test"; TTL=(); TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { HOSTS="$1"; shift; }
[[ $# -gt 0 && "$1" != --* ]] && { NS="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ttl-days) TTL=(--ttl-days "$2"); shift 2 ;;
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-aerospike -am
CP="plugins/drishti-plugin-aerospike/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-aerospike -Dmdep.outputFile=/dev/stdout)"
"$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.aerospike.AerospikeLoader data/banking.jsonl "$HOSTS" "$NS" "${TTL[@]}"
if [[ -n "$TRADES" ]]; then
  # the bulk book straight from the generator into the loader: no file of gigabytes in between
  uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl - \
    | "$JAVA_HOME/bin/java" -cp "$CP" com.ash.drishti.plugin.aerospike.AerospikeLoader - "$HOSTS" "$NS" "${TTL[@]}"
fi
