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

# The scale benchmark for one store and one size: start the store (a container where it needs one), load it with the
# project's own loader, start a Drishti server on it, measure over HTTP (tools/bench/measure.py), then stop and clean
# everything. One JSON line per measure is appended to the output file; tools/bench/report.py turns them into tables.
#   tools/bench/scale.sh <store> <trades-a-day> [--days 3] [--run r1] [--out results.jsonl] [--port 18993]
#   tools/bench/scale.sh delta 10000                       Delta Lake (engine native), 10,000 trades a day x 3 days
#   tools/bench/scale.sh postgres 50000 --run r2           PostgreSQL in a container, a second run of the same size
#   tools/bench/scale.sh redis 1000000 --days 2 --container-memory 12g --heap 16g     on a real server
#   tools/bench/scale.sh postgres 1000000 --container-memory 12g --heap 16g --store-args "-c shared_buffers=3GB"
# Stores: delta, postgres, duckdb, files, mongodb, redis, aerospike, iceberg.
# Scratch (stores' data, logs, the server's own files) goes to $DRISHTI_BENCH_DIR (default /tmp/drishti-bench) and is
# deleted at the end; containers are named drishti-bench-<store> and removed with their volumes. The run refuses to
# start, and stops between steps, when the machine has less than $DRISHTI_BENCH_MIN_FREE_GB (default 20) GB available.
# The server is stopped by its process id only. JDK: $DRISHTI_BENCH_JAVA_HOME (default /usr/lib/jvm/java-25-openjdk-amd64).
set -euo pipefail
cd "$(dirname "$0")/../.."
# JDK 25 for the loaders and the server (compact object headers), whatever JAVA_HOME the shell has
export JAVA_HOME="${DRISHTI_BENCH_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "2[5-9]' || { echo "scale.sh: $JAVA_HOME is not JDK 25 or later" >&2; exit 2; }

STORE="${1:?usage: scale.sh <store> <trades-a-day> [options]}"; TRADES="${2:?trades a day}"; shift 2
DAYS=3; RUN=r1; PORT=18993; HEAP=2g; CMEM=2g; REPS=25; CLIENTS=8; SECONDS_TP=5; KEEP=false; STORE_ARGS=""
BENCH="${DRISHTI_BENCH_DIR:-/tmp/drishti-bench}"; OUT="$BENCH/results.jsonl"; MIN_FREE="${DRISHTI_BENCH_MIN_FREE_GB:-20}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --run) RUN="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --heap) HEAP="$2"; shift 2 ;;
    --container-memory) CMEM="$2"; shift 2 ;;
    --reps) REPS="$2"; shift 2 ;;
    --clients) CLIENTS="$2"; shift 2 ;;
    --seconds) SECONDS_TP="$2"; shift 2 ;;
    --keep) KEEP=true; shift ;;
    --store-args) STORE_ARGS="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
case "$STORE" in delta|postgres|duckdb|files|mongodb|redis|aerospike|iceberg) ;; *) echo "unknown store $STORE" >&2; exit 2 ;; esac

WORK="$BENCH/$STORE"; DATA="$WORK/data"; SRV="$WORK/server"; LOG="$BENCH/logs"
NAME="drishti-bench-$STORE"; VOLUME="drishti-bench-$STORE"; SERVER_PID=""
mkdir -p "$DATA" "$SRV" "$LOG" "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
TAG="$STORE-$TRADES-$RUN"

available_gb() { awk '/MemAvailable/ { printf "%d", $2 / 1048576 }' /proc/meminfo; }
guard() {
  local gb; gb=$(available_gb)
  if (( gb < MIN_FREE )); then echo "scale.sh: only ${gb} GB available (< ${MIN_FREE} GB): stopping" >&2; exit 3; fi
}
note() { echo "[$(date +%H:%M:%S)] $TAG: $*"; }

cleanup() {
  set +e
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID"
    for _ in $(seq 1 60); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$SERVER_PID" 2>/dev/null && kill -9 "$SERVER_PID"
  fi
  if [[ "$KEEP" != true ]]; then
    docker rm -f -v "$NAME" >/dev/null 2>&1
    docker volume rm "$VOLUME" >/dev/null 2>&1
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

guard
if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  echo "container $NAME already exists: remove it first (docker rm -f -v $NAME)" >&2; trap - EXIT; exit 2
fi
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then echo "port $PORT is in use" >&2; trap - EXIT; exit 2; fi

# ---- the store --------------------------------------------------------------------------------------------------
wait_for() {  # wait_for <seconds> <command...>
  local limit="$1"; shift
  for _ in $(seq 1 "$limit"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  echo "timed out waiting for: $*" >&2; return 1
}
PG_PORT=15432; REDIS_PORT=16379; MONGO_PORT=37017; AS_PORT=13000
ENV=(DRISHTI_PORT="$PORT" DRISHTI_PACKS=market-risk,counterparty-risk DRISHTI_DELTA_ROOT="$DATA/empty-lake"
     DRISHTI_IDENTITY_DB_URL="jdbc:sqlite:$SRV/identity/drishti.db" DRISHTI_USERS_FILE="$SRV/identity/users.json"
     DRISHTI_AUDIT_FILE="$SRV/identity/audit.jsonl" DRISHTI_GOVERNANCE_DIR="$SRV/governance" DRISHTI_REPORTS_DIR="$SRV/reports"
     DRISHTI_PACKS_OVERLAY="$SRV/packs/added.yaml" DRISHTI_PACKS_INSTALLED="$SRV/packs/installed" DRISHTI_FEEDS="$SRV/feeds"
     DRISHTI_REPORTS_ENABLED=false DRISHTI_ACCESS_LOG=false)
LOADER=(); ENGINE=""
case "$STORE" in
  delta)
    mkdir -p "$DATA/lake"; ENGINE=native
    LOADER=(tools/load-delta.sh "$DATA/lake" --trades "$TRADES" --days "$DAYS")
    ENV+=(DRISHTI_DELTA_ROOT="$DATA/lake" DRISHTI_DELTA_ENGINE=native) ;;
  files)
    LOADER=(tools/load-files.sh "$DATA/files" --trades "$TRADES" --days "$DAYS")
    ENV+=(SPRING_PROFILES_ACTIVE=files DRISHTI_FILES_ROOT="$DATA/files") ;;
  duckdb)
    mkdir -p "$DATA/duckdb"
    LOADER=(tools/load-duckdb.sh "$DATA/duckdb/drishti.duckdb" --trades "$TRADES" --days "$DAYS")
    ENV+=(SPRING_PROFILES_ACTIVE=duckdb DRISHTI_DUCKDB_PATH="$DATA/duckdb/drishti.duckdb") ;;
  iceberg)
    mkdir -p "$DATA/iceberg" "$DATA/spill"
    LOADER=(tools/load-iceberg.sh "$DATA/iceberg" --trades "$TRADES" --days "$DAYS" --spill-dir "$DATA/spill")
    ENV+=(SPRING_PROFILES_ACTIVE=iceberg DRISHTI_ICEBERG_ROOT="$DATA/iceberg") ;;
  postgres)
    note "starting postgres:18-alpine (memory $CMEM)"
    docker run -d --name "$NAME" -m "$CMEM" --shm-size 256m -p "$PG_PORT:5432" -e POSTGRES_DB=drishti -e POSTGRES_USER=drishti \
      -e POSTGRES_PASSWORD=drishti -v "$VOLUME:/var/lib/postgresql" postgres:18-alpine $STORE_ARGS >/dev/null
    wait_for 120 docker exec "$NAME" pg_isready -U drishti -d drishti -h 127.0.0.1
    sleep 2
    URL="jdbc:postgresql://localhost:$PG_PORT/drishti"
    LOADER=(tools/load-postgres.sh "$URL" --user drishti --password drishti --trades "$TRADES" --days "$DAYS")
    ENV+=(SPRING_PROFILES_ACTIVE=postgres DRISHTI_PG_URL="$URL" DRISHTI_PG_USER=drishti DRISHTI_PG_PASSWORD=drishti) ;;
  redis)
    note "starting redis:8.2 (memory $CMEM)"
    docker run -d --name "$NAME" -m "$CMEM" -p "$REDIS_PORT:6379" -v "$VOLUME:/data" redis:8.2 \
      --maxmemory "$(( $(numfmt --from=iec "${CMEM^^}") * 3 / 4 ))" --save "" --appendonly no $STORE_ARGS >/dev/null
    wait_for 60 docker exec "$NAME" redis-cli ping
    URI="redis://localhost:$REDIS_PORT"
    LOADER=(tools/load-redis.sh "$URI" --trades "$TRADES" --days "$DAYS")
    ENV+=(SPRING_PROFILES_ACTIVE=redis DRISHTI_REDIS_URI="$URI") ;;
  mongodb)
    note "starting mongo:7 (memory $CMEM)"
    docker run -d --name "$NAME" -m "$CMEM" -p "$MONGO_PORT:27017" -v "$VOLUME:/data/db" mongo:7 ${STORE_ARGS:---wiredTigerCacheSizeGB 0.5} >/dev/null
    wait_for 120 docker exec "$NAME" mongosh --quiet --eval 'db.runCommand({ping: 1}).ok'
    URI="mongodb://localhost:$MONGO_PORT"
    LOADER=(tools/load-mongodb.sh "$URI" drishti --trades "$TRADES" --days "$DAYS")
    ENV+=(SPRING_PROFILES_ACTIVE=mongodb DRISHTI_MONGODB_URI="$URI" DRISHTI_MONGODB_DATABASE=drishti) ;;
  aerospike)
    AS_GB=$(( TRADES * DAYS * 12 / 1000000 )); (( AS_GB < 8 )) && AS_GB=8      # about 7 KB a trade-day, with room
    note "starting aerospike-server:8.1.2.5 (memory $CMEM, namespace test on a file)"
    docker run -d --name "$NAME" -m "$CMEM" --ulimit nofile=15000:15000 -p "$AS_PORT:3000" -e STORAGE_GB="$AS_GB" \
      -e DEFAULT_TTL=0 -v "$VOLUME:/opt/aerospike/data" aerospike/aerospike-server:8.1.2.5 >/dev/null
    wait_for 120 docker exec "$NAME" asinfo -v status
    sleep 3
    HOSTS="localhost:$AS_PORT"
    LOADER=(tools/load-aerospike.sh "$HOSTS" test --trades "$TRADES" --days "$DAYS")
    ENV+=(SPRING_PROFILES_ACTIVE=aerospike DRISHTI_AEROSPIKE_HOSTS="$HOSTS" DRISHTI_AEROSPIKE_NAMESPACE=test) ;;
esac
mkdir -p "$DATA/empty-lake"

# ---- load -------------------------------------------------------------------------------------------------------
guard
note "loading: ${LOADER[*]}"
T0=$(date +%s.%N)
"${LOADER[@]}" > "$LOG/$TAG-load.log" 2>&1 || { note "load failed (see $LOG/$TAG-load.log)"; tail -20 "$LOG/$TAG-load.log" >&2; exit 4; }
LOAD_S=$(echo "$(date +%s.%N) - $T0" | bc)
note "loaded in ${LOAD_S} s"

store_bytes() {
  case "$STORE" in
    delta) du -sb "$DATA/lake" | cut -f1 ;;
    files) du -sb "$DATA/files" | cut -f1 ;;
    duckdb) du -sb "$DATA/duckdb" | cut -f1 ;;
    iceberg) du -sb "$DATA/iceberg" | cut -f1 ;;
    postgres) docker exec "$NAME" psql -U drishti -d drishti -tAc "select pg_database_size('drishti')" ;;
    redis) docker exec "$NAME" redis-cli info memory | awk -F: '/^used_memory:/ { gsub("\r", "", $2); print $2 }' ;;
    mongodb) docker exec "$NAME" mongosh --quiet --eval 'const s = db.getSiblingDB("drishti").stats(); print(s.storageSize + s.indexSize)' ;;
    aerospike) docker exec "$NAME" asinfo -v namespace/test | tr ';' '\n' | awk -F= '/^data_used_bytes=/ { print $2 }' ;;
  esac
}
SIZE=$(store_bytes || echo 0); SIZE="${SIZE:-0}"
SIZE_KIND=disk; [[ "$STORE" == redis ]] && SIZE_KIND=memory
note "store size ${SIZE} bytes ($SIZE_KIND)"

# ---- the server -------------------------------------------------------------------------------------------------
guard
JAR=$(ls -t drishti-server/target/drishti-server-*-exec.jar 2>/dev/null | head -1 || true)
if [[ -z "$JAR" ]]; then ./mvnw -q -o install -DskipTests -pl drishti-server -am; JAR=$(ls -t drishti-server/target/drishti-server-*-exec.jar | head -1); fi
mkdir -p "$SRV/identity"
note "starting the server on :$PORT (heap $HEAP, $JAR)"
T0=$(date +%s.%N)
env "${ENV[@]}" "$JAVA_HOME/bin/java" -Xmx"$HEAP" -XX:+UseCompactObjectHeaders -jar "$JAR" > "$LOG/$TAG-server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 600); do
  kill -0 "$SERVER_PID" 2>/dev/null || { note "server exited (see $LOG/$TAG-server.log)"; tail -30 "$LOG/$TAG-server.log" >&2; exit 5; }
  curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break
  sleep 0.5
done
START_S=$(echo "$(date +%s.%N) - $T0" | bc)
note "server up in ${START_S} s (pid $SERVER_PID)"

# ---- measure ----------------------------------------------------------------------------------------------------
guard
python3 tools/bench/measure.py --base "http://localhost:$PORT" --store "$STORE" --engine "$ENGINE" --trades "$TRADES" --days "$DAYS" \
  --run "$RUN" --out "$OUT" --pid "$SERVER_PID" --reps "$REPS" --clients "$CLIENTS" --seconds "$SECONDS_TP" \
  --meta "load_s=$LOAD_S" --meta "store_bytes=$SIZE" --meta "store_size_kind=$SIZE_KIND" --meta "start_s=$START_S" \
  --meta "available_gb_before=$(available_gb)" --meta "server_heap_max=$HEAP" --meta "container_memory=$CMEM"
note "done; results appended to $OUT"
