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
#   tools/load-iceberg.sh [root] [--engine native|library|auto] [--catalog rest --uri URI --warehouse W --credential C] [--keep-days N]
#                         [--trades N --days D] [--spill-dir DIR] [--set key=value]
#   tools/load-iceberg.sh ./data/iceberg                                   the 1,791 sample documents x 10 business days
#   tools/load-iceberg.sh ./data/iceberg --trades 1000000 --days 3         and a book of a million trades, streamed
#   tools/load-iceberg.sh s3a://risk-lake/iceberg --set s3.endpoint=http://localhost:9000 --set s3.access-key=K ...
# --keep-days N removes each loaded table's business days older than its newest N on or before today (and expires
# week-old snapshots).
# Dates are guarded (LoadGuard): a row dated after tomorrow in the business zone is not loaded (--future-days N, --zone Z)
# and the load ends with an error naming it; retention counts back from --as-of (today), never from the newest date
# loaded, and dropping more than --max-drop-share (0.5) of a table needs --force-drop.
# The loader sorts each day by id with an external sort: its run files go to --spill-dir (default: the system temp
# folder) and take about the size of the loaded JSON; put them on a disk, not a memory-backed /tmp, for a large book.
# The samples and the book go to the loader as one stream, so each business day of a table is written once and holds both (a second
# load of the same days would replace them: QA2-05). The loader's heap is DRISHTI_LOADER_HEAP (default 4g); its input file is
# written under the data folder (DRISHTI_DATA_DIR), never in the repository.
set -euo pipefail
cd "$(dirname "$0")/.."
# the loader's JVM temp folder: native libraries (DuckDB, RocksDB, zstd) unpack there, never in /tmp; the server sweeps it at start
DATA="${DRISHTI_DATA_DIR:-data}"
JTMP="${DRISHTI_TMP_DIR:-$DATA/tmp/server}"; mkdir -p "$JTMP"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
ROOT="$DATA/iceberg"; OPTS=(); TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { ROOT="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    --engine|--catalog|--uri|--warehouse|--credential|--token|--keep-days|--spill-dir|--set|--threads|--buffer-mb|--file-rows|--row-group-mb)
      OPTS+=("$1" "$2"); shift 2 ;;
    --as-of|--future-days|--zone|--max-drop-share) OPTS+=("$1" "$2"); shift 2 ;;
    --force-drop) OPTS+=("$1"); shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl "$DATA/banking.jsonl"
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-iceberg -am
CP="plugins/drishti-plugin-iceberg/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-iceberg -Dmdep.outputFile=/dev/stdout)"
# a failed load ends with the loader's one line (which bucket, which endpoint, what to check: QA3B-12), never a stack trace
if ! {
  cat "$DATA/banking.jsonl"
  if [[ -n "$TRADES" ]]; then
    # the bulk book straight from the generator: no file of gigabytes in between
    uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl -
  fi
} | "$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -Xmx"${DRISHTI_LOADER_HEAP:-4g}" -Djdk.nio.maxCachedBufferSize=262144 -cp "$CP" com.ash.drishti.plugin.iceberg.IcebergLoader - "$ROOT" "${OPTS[@]}"; then
  rm -f "$DATA/banking.jsonl"
  echo "load-iceberg: the load did not finish: see the line above" >&2
  exit 1
fi
rm -f "$DATA/banking.jsonl"
