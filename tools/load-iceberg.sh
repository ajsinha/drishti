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

# Loads the banking packs' data into Apache Iceberg tables (one per kind, <root>/<domain>/<kind>, or a REST catalog's
# namespace per domain), in the layout the connector reads (IcebergLayout: promoted columns beside id and doc, each
# business day sorted by id into files of 250,000 rows, small row groups, no metrics on doc):
#   tools/load-iceberg.sh [root] [--catalog rest --uri URI --warehouse W --credential C] [--keep-days N]
#                         [--trades N --days D] [--spill-dir DIR] [--set key=value]
#   tools/load-iceberg.sh ./data/iceberg                                   the 1,791 sample documents x 10 business days
#   tools/load-iceberg.sh ./data/iceberg --trades 1000000 --days 3         and a book of a million trades, streamed
#   tools/load-iceberg.sh s3a://risk-lake/iceberg --set s3.endpoint=http://localhost:9000 --set s3.access-key=K ...
# --keep-days N removes each loaded table's business days older than its newest N (and expires week-old snapshots).
# The loader sorts each day by id with an external sort: its run files go to --spill-dir (default: the system temp
# folder) and take about the size of the loaded JSON; put them on a disk, not a memory-backed /tmp, for a large book.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
ROOT="./data/iceberg"; OPTS=(); TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { ROOT="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    --catalog|--uri|--warehouse|--credential|--token|--keep-days|--spill-dir|--set|--threads|--buffer-mb|--file-rows|--row-group-mb)
      OPTS+=("$1" "$2"); shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-iceberg -am
CP="plugins/drishti-plugin-iceberg/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-iceberg -Dmdep.outputFile=/dev/stdout)"
JAVA=("$JAVA_HOME/bin/java" -Xmx6g -cp "$CP")
"${JAVA[@]}" com.ash.drishti.plugin.iceberg.IcebergLoader data/banking.jsonl "$ROOT" "${OPTS[@]}"
if [[ -n "$TRADES" ]]; then
  # the bulk book straight from the generator into the loader: no file of gigabytes in between
  uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl - \
    | "${JAVA[@]}" com.ash.drishti.plugin.iceberg.IcebergLoader - "$ROOT" "${OPTS[@]}"
fi
