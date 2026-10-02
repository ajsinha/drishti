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

# Loads the banking packs' data into Redis (keys prefixed by each data domain), in the layout the connector reads
# (RedisLayout: a compressed document per entity per business date, each entity's and each kind's days, each day's
# promoted fields column-wise in chunks):
#   tools/load-redis.sh [uri] [--cluster] [--merge] [--ttl-days N] [--trades N --days D] [--codec C] [--publish]
#   tools/load-redis.sh redis://localhost:6379                            the 1,791 sample documents x 10 business days
#   tools/load-redis.sh redis://localhost:6379 --trades 1000000 --days 1  and a book of a million trades, streamed
# Each business day a load reaches is REPLACED (the default since DATA-11): it then holds exactly the loaded entities,
# switched atomically for readers; the replaced day stays readable --keep-replaced-seconds (120). A load that would drop
# more than --max-drop-share (0.5) of a day's entities is refused unless --force-drop. --merge adds to each day instead
# (the behaviour before: loads in parts, intraday corrections with --publish). With --trades the samples are merged and
# the bulk book (which starts with the 750 sample trades) replaces the trade days it covers.
# --ttl-days N lets Redis expire every key N days after it is written (Redis holds the last few days; history lives in
# Delta Lake or Iceberg). --codec zstd-dict (default), zstd, deflate or none. --publish announces every written entity on
# <domain>:changes (for intraday updates to open views). Credentials: in the URI (redis://user:secret@host:6379,
# rediss:// for TLS) or DRISHTI_REDIS_USER / DRISHTI_REDIS_PASSWORD.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
URI="redis://localhost:6379"; OPTS=(); TRADES=""; DAYS="1"
[[ $# -gt 0 && "$1" != --* ]] && { URI="$1"; shift; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ttl-days|--codec|--level|--chunk-rows|--in-flight|--threads|--keep-replaced-seconds) OPTS+=("$1" "$2"); shift 2 ;;
    --cluster|--publish|--retrain|--replace|--merge) OPTS+=("$1"); shift ;;
    --as-of|--future-days|--zone|--max-drop-share) OPTS+=("$1" "$2"); shift 2 ;;
    --force-drop) OPTS+=("$1"); shift ;;
    --trades) TRADES="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl data/banking.jsonl
./mvnw -q -o install -DskipTests -pl plugins/drishti-plugin-redis -am
CP="plugins/drishti-plugin-redis/target/classes:$(./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-redis -Dmdep.outputFile=/dev/stdout)"
# the loader keeps one day's promoted values per kind (about 230 MB per million trades) until the input ends
JAVA=("$JAVA_HOME/bin/java" -Xmx6g -cp "$CP")
SAMPLE_OPTS=("${OPTS[@]}")
if [[ -n "$TRADES" && " ${OPTS[*]} " != *" --replace "* ]]; then
  # the bulk book below replaces the trade days it covers; the samples merge, so a reload over an earlier bulk book is
  # not refused for dropping most of its trades (they are a subset of it)
  SAMPLE_OPTS+=(--merge)
fi
"${JAVA[@]}" com.ash.drishti.plugin.redis.RedisLoader data/banking.jsonl "$URI" "${SAMPLE_OPTS[@]}"
if [[ -n "$TRADES" ]]; then
  # the bulk book straight from the generator into the loader: no file of gigabytes in between
  uv run -q --with deltalake --with pyarrow --with pyyaml python tools/samplegen/bulk_trades.py --trades "$TRADES" --days "$DAYS" --jsonl - \
    | "${JAVA[@]}" com.ash.drishti.plugin.redis.RedisLoader - "$URI" "${OPTS[@]}"
fi
