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

# Virtual-thread pinning and Java 21 / Java 25 comparison under a concurrent read load.
#
#   tools/bench/pinning.sh data                    build the small data sets (<= 10,000 trades a day) in $DRISHTI_PIN_DIR
#   tools/bench/pinning.sh rabbit-up | rabbit-down start / remove the RabbitMQ container (2 GB, ports 25672/25673)
#   tools/bench/pinning.sh run <config> <21|25> [seconds]
#        config: delta-hadoop | delta-native | duckdb | files
#        starts the exec jar on that JDK (Java 21 with -Djdk.tracePinnedThreads=full) on port $DRISHTI_PIN_PORT
#        (18993) with the config's store, a RabbitMQ message source and the public ECB feed, drives 200 concurrent
#        clients (tools/bench/pinload.py), then stops the server by its process id and prints the pinned stacks.
#
# Everything is written under $DRISHTI_PIN_DIR (default /tmp/drishti-pin); nothing under the repository's data/.
# Needs the exec jar (./mvnw -q -o install -DskipTests), uv (data) and docker (the message source).
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$PWD"
D="${DRISHTI_PIN_DIR:-/tmp/drishti-pin}"; PORT="${DRISHTI_PIN_PORT:-18993}"; TRADES="${DRISHTI_PIN_TRADES:-10000}"
MQ=drishti-pin-rabbit; MQ_AMQP=25672; MQ_HTTP=25673; MQ_QUEUE=pin.orders; MQ_IDS=2000
JDK21=/usr/lib/jvm/java-21-openjdk-amd64; JDK25=/usr/lib/jvm/java-25-openjdk-amd64
WITH=(--with deltalake --with pyarrow --with pyyaml)
mkdir -p "$D"

classpath() { JAVA_HOME=$JDK25 ./mvnw -q -o dependency:build-classpath -pl "$1" -Dmdep.outputFile=/dev/stdout; }

cmd_data() {
  [[ -d "$D/lake" ]] || {
    uv run -q "${WITH[@]}" python tools/packgen/banking/make_data.py --lake "$D/lake"
    uv run -q "${WITH[@]}" python tools/samplegen/bulk_trades.py --root "$D/lake" --trades "$TRADES" --days 3
  }
  uv run -q --with pyyaml python tools/packgen/banking/make_data.py --jsonl "$D/banking.jsonl"
  local fcp dcp
  fcp="plugins/drishti-plugin-file/target/classes:$(classpath plugins/drishti-plugin-file)"
  dcp="plugins/drishti-plugin-duckdb/target/classes:$(classpath plugins/drishti-plugin-duckdb)"
  rm -rf "$D/files" "$D/drishti.duckdb"
  "$JDK25/bin/java" -cp "$fcp" com.ash.drishti.plugin.file.JsonlLoader "$D/banking.jsonl" "$D/files"
  uv run -q "${WITH[@]}" python tools/samplegen/bulk_trades.py --trades "$TRADES" --days 3 --jsonl - \
    | "$JDK25/bin/java" -cp "$fcp" com.ash.drishti.plugin.file.JsonlLoader - "$D/files"
  "$JDK25/bin/java" -cp "$dcp" com.ash.drishti.plugin.duckdb.DuckDbLoader "$D/banking.jsonl" "$D/drishti.duckdb" --recreate
  uv run -q "${WITH[@]}" python tools/samplegen/bulk_trades.py --trades "$TRADES" --days 3 --jsonl - \
    | "$JDK25/bin/java" -cp "$dcp" com.ash.drishti.plugin.duckdb.DuckDbLoader - "$D/drishti.duckdb"
  du -sh "$D"/lake "$D"/files "$D"/drishti.duckdb
}

cmd_rabbit_up() {
  docker rm -f -v "$MQ" >/dev/null 2>&1 || true
  docker run -d --name "$MQ" -m 2g -p "$MQ_AMQP:5672" -p "$MQ_HTTP:15672" -e RABBITMQ_DEFAULT_USER=pin -e RABBITMQ_DEFAULT_PASS=pin \
    rabbitmq:4.1-management-alpine >/dev/null
  for _ in $(seq 1 90); do docker exec -u rabbitmq "$MQ" rabbitmq-diagnostics -q ping >/dev/null 2>&1 && break; sleep 1; done
  for _ in $(seq 1 60); do curl -sf -u pin:pin "http://localhost:$MQ_HTTP/api/overview" >/dev/null 2>&1 && break; sleep 1; done
  echo "rabbitmq up on :$MQ_AMQP (management :$MQ_HTTP)"
}

cmd_rabbit_down() { docker rm -f -v "$MQ" >/dev/null 2>&1 || true; echo "rabbitmq removed"; }

cmd_run() {
  local cfg="${1:?config}" jdk="${2:?21 or 25}" secs="${3:-180}" home opts run
  [[ "$jdk" == 21 ]] && { home=$JDK21; opts=(-Djdk.tracePinnedThreads=full); } || { home=$JDK25; opts=(-XX:+UseCompactObjectHeaders); }
  run="$D/run/$cfg-j$jdk"; rm -rf "$run"; mkdir -p "$run/identity"; cd "$run"
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then echo "port $PORT is in use" >&2; exit 2; fi
  local jar; jar=$(ls -t "$REPO"/drishti-server/target/drishti-server-*-exec.jar | head -1)
  local env=(DRISHTI_PORT="$PORT" DRISHTI_PACKS=market-risk,counterparty-risk DRISHTI_PACKS_DIR="$REPO/packs" DRISHTI_DELTA_ROOT="$D/lake"
    DRISHTI_IDENTITY_DB_URL="jdbc:sqlite:$run/identity/drishti.db" DRISHTI_USERS_FILE="$run/identity/users.json"
    DRISHTI_AUDIT_FILE="$run/identity/audit.jsonl" DRISHTI_GOVERNANCE_DIR="$run/governance" DRISHTI_REPORTS_DIR="$run/reports"
    DRISHTI_PACKS_OVERLAY="$run/packs/added.yaml" DRISHTI_PACKS_INSTALLED="$run/packs/installed" DRISHTI_FEEDS="$run/feeds"
    DRISHTI_REPORTS_ENABLED=false DRISHTI_ACCESS_LOG=false DRISHTI_FEED_ECB_FX=true)
  case "$cfg" in
    delta-hadoop) env+=(DRISHTI_DELTA_ENGINE=hadoop) ;;
    delta-native) env+=(DRISHTI_DELTA_ENGINE=native) ;;
    duckdb) env+=(SPRING_PROFILES_ACTIVE=duckdb DRISHTI_DUCKDB_PATH="$D/drishti.duckdb") ;;
    files) env+=(SPRING_PROFILES_ACTIVE=files DRISHTI_FILES_ROOT="$D/files") ;;
    *) echo "unknown config $cfg" >&2; exit 2 ;;
  esac
  cat > application.local.yaml <<EOF
drishti:
  sources:
    connectors:
      pin-mq:
        plugin: rabbitmq
        kinds: [pin-order]
        settings:
          uri: amqp://pin:pin@localhost:$MQ_AMQP/%2f
          queues: $MQ_QUEUE
          declare: true
          kind.$MQ_QUEUE: pin-order
          id-field.$MQ_QUEUE: callId
          state.max-gb: 1
EOF
  docker exec -u rabbitmq "$MQ" rabbitmqctl -q purge_queue "$MQ_QUEUE" >/dev/null 2>&1 || true
  echo "[$(date +%H:%M:%S)] $cfg on Java $jdk: starting the server on :$PORT"
  env "${env[@]}" "$home/bin/java" -Xmx2g "${opts[@]}" -jar "$jar" > server.log 2>&1 &
  local pid=$! pub=""
  trap '[[ -n "${pub:-}" ]] && kill "$pub" 2>/dev/null; kill "$pid" 2>/dev/null; true' EXIT
  for _ in $(seq 1 300); do
    kill -0 "$pid" 2>/dev/null || { tail -30 server.log >&2; exit 5; }
    curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break
    sleep 1
  done
  python3 "$REPO/tools/bench/mqpublish.py" --api "http://localhost:$MQ_HTTP" --user pin --password pin --queue "$MQ_QUEUE" \
    --prefix MC-LOAD- --count "$MQ_IDS" --rate 20 --seconds $((secs + 120)) > mqpublish.log 2>&1 &
  pub=$!
  python3 "$REPO/tools/bench/pinload.py" --base "http://localhost:$PORT" --trades "$TRADES" --seconds "$secs" --concurrency 200 \
    --mq-ids 500 --label "$cfg-java$jdk" --out "$D/result-$cfg-j$jdk.json"
  kill "$pub" 2>/dev/null || true
  curl -s "http://localhost:$PORT/api/v1/admin/health" | head -c 1500 > health.json || true
  kill "$pid"; for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  trap - EXIT
  echo "--- pinned stacks ($cfg, Java $jdk) ---"
  python3 "$REPO/tools/bench/pinned_summary.py" server.log | tee "$D/pinned-$cfg-j$jdk.txt" | head -20
}

case "${1:-}" in
  data) cmd_data ;;
  rabbit-up) cmd_rabbit_up ;;
  rabbit-down) cmd_rabbit_down ;;
  run) shift; cmd_run "$@" ;;
  *) sed -n 2,13p "$0"; exit 2 ;;
esac
