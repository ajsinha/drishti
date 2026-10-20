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

# Loads the banking packs' data into Apache Cassandra (or ScyllaDB, Astra, Keyspaces) in the layout the connector reads
# (CassandraLayout: a keyspace per data domain, a table per kind with a partition per business day, generation and bucket,
# entity_days and kind_days; docs/connectors/CASSANDRA_CONNECTOR.md):
#   tools/load-cassandra.sh [contact-points] [local-datacenter] [options]
#   tools/load-cassandra.sh localhost:9042 datacenter1 --create --create-keyspace 1      a development node: the samples x 10 days
#   tools/load-cassandra.sh localhost:9042 datacenter1 --create --trades 10000 --days 3  and a book of 10,000 trades a day
# Options: --create (create the tables; without it the CQL is printed for an administrator), --create-keyspace RF (a development
# keyspace, SimpleStrategy), --keyspace K (one keyspace for every domain), --ttl-days N, --keep-days N [--force-drop],
# --buckets N | --buckets KIND=N (128), --in-flight N (256), --username U --password P (or DRISHTI_CASSANDRA_USERNAME /
# DRISHTI_CASSANDRA_PASSWORD), --setting key=value (any connector setting, e.g. tls.enabled=true), --future-days N, --zone Z.
# The samples and the book go to the loader as one stream, so each business day is loaded once and appears whole.
# The loader's heap is DRISHTI_LOADER_HEAP (default 4g); its input file is written under the data folder and removed after the load.
set -euo pipefail
cd "$(dirname "$0")/.."
# the loader's JVM temp folder: never /tmp; the server sweeps it at start
DATA="${DRISHTI_DATA_DIR:-data}"
JTMP="${DRISHTI_TMP_DIR:-$DATA/tmp/server}"; mkdir -p "$JTMP"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
POINTS="localhost:9042"; DC="datacenter1"; OPTS=(); TRADES=""; DAYS="3"
[[ $# -gt 0 && "$1" != --* ]] && { POINTS="$1"; shift; }
[[ $# -gt 0 && "$1" != --* ]] && { DC="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --create|--force-drop) OPTS+=("$1"); shift ;;
    --create-keyspace|--keyspace|--ttl-days|--keep-days|--buckets|--in-flight|--batch-rows|--consistency|--username|--password|--setting|--future-days|--zone|--as-of|--max-drop-share)
      OPTS+=("$1" "$2"); shift 2 ;;
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl "$DATA/banking.jsonl"
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-cassandra -am
CP="plugins/drishti-plugin-cassandra/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-cassandra -Dmdep.outputFile=/dev/stdout)"
{
  cat "$DATA/banking.jsonl"
  if [[ -n "$TRADES" ]]; then
    # the bulk book straight from the generator: no file of gigabytes in between
    uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl -
  fi
} | "$JAVA_HOME/bin/java" -Djava.io.tmpdir="$JTMP" -Xmx"${DRISHTI_LOADER_HEAP:-4g}" -cp "$CP" com.ash.drishti.plugin.cassandra.CassandraLoader - "$POINTS" "$DC" "${OPTS[@]}"
rm -f "$DATA/banking.jsonl"
