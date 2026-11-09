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

# Starts a Drishti server, alone or as a node of a Drishti*net cluster (Linux and macOS; bin/start-server.ps1 on Windows).
# Every option: --help, bin/README.md and docs/DEPLOYMENT_AND_QA_GUIDE.md section 6.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd -P)/lib/common.sh"

usage() {
  cat <<'EOF'
usage: bin/start-server.sh [options] [-- server-arguments...]

Starts the Drishti server: finds Java 21+, the newest drishti-server-*-exec.jar, sets the temporary folder, the native memory settings
and the logs under the data folder, waits for /actuator/health and prints the address other machines open.

  --port N            the server's port (default DRISHTI_PORT, else 18480)
  --heap SIZE         the heap limit, -Xmx (default 2g; 4g with --demo)
  --jar FILE          the server jar (default the newest drishti-server/target/drishti-server-*-exec.jar, then drishti-server*.jar here)
  --java-home DIR     the JDK (default DRISHTI_JAVA_HOME, then JAVA_HOME, then java on the PATH, then /usr/libexec/java_home -v 21 on macOS)
  --packs LIST        the packs to load (DRISHTI_PACKS; default the server's own, or the demo's ten with --demo)
  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>/data): run/, logs/, tmp/ and dumps/ are under it
  --env-file FILE     KEY=VALUE lines put into the server's environment (secrets stay off the command line; values never printed)
  --demo              the offline demo on the lakes, with DEVELOPMENT secrets and the drishti-dev-admin account: NOT FOR PRODUCTION
  --stop              the same as bin/stop-server.sh (with --node and --data-dir)
  --engine NAME       the Delta engine: native, hadoop or auto (DRISHTI_DELTA_ENGINE; default the server's own)
  --lake-root DIR     the Delta lake root (DRISHTI_DELTA_ROOT)
  --iceberg           serve the stores from Iceberg tables (copies config/connectors.examples/iceberg/*.yaml into config/connectors)
  --iceberg-root DIR  the Iceberg tables' root (DRISHTI_ICEBERG_ROOT, default data/iceberg), with --iceberg
  --java-opt FLAG     one more JVM flag (repeatable)
  --server-arg ARG    one more server argument, e.g. --drishti.x=y (repeatable; or everything after --)
  --workdir DIR       the server's working folder (default <drishti>)
  --background        detach: log in <data>/logs/server[-<node>].log, pid in <data>/run/server[-<node>].pid
  --wait SECONDS      how long to wait for the health answer (default 300)

 A cluster node (docs/guides/CLUSTER_GUIDE.md):
  --node NAME         the node's id; its pid, log and temp folder are named after it, so several run on one machine
  --node-dir DIR      the node's Drishti*net folder (node.yaml, gossip.yaml, peers/); default <data>/nodes/<node>/net, made when missing
  --node-data DIR     the node's own data folder (the server's DRISHTI_DATA_DIR: its identity database, saved members, inbox);
                      default <data>. Several nodes on one machine each need their own: e.g. <data>/nodes/<node>/data
  --seed HOST:PORT    a member to join through (repeatable, or comma-separated): written to <node-dir>/peers/ when not there yet
  --advertise IP      the address the node advertises (default this machine's real LAN address; never 127.0.0.1, localhost, 0.0.0.0)

  -h, --help          this text

Environment: DRISHTI_JAVA_HOME, JAVA_HOME, DRISHTI_PORT, DRISHTI_DATA_DIR, DRISHTI_PACKS, DRISHTI_NET_SECRET (a cluster node),
DRISHTI_TOKEN_SECRET, DRISHTI_PYTHON. Exit codes: 0 up (or, in the foreground, stopped cleanly);
1 failed to start; 2 a wrong option; 3 already running or the port is in use.

examples:
  bin/start-server.sh --demo --background
  bin/start-server.sh --env-file /etc/drishti/server.env --heap 8g --background
  bin/start-server.sh --node drishti-a --port 18480 --env-file cluster.env --background
  bin/start-server.sh --node drishti-b --port 18481 --seed 192.168.1.20:18480 --env-file cluster.env --background
EOF
}

PORT="" HEAP="" JAR="" JAVA_HOME_DIR="" PACKS="" DATA="" ENV_FILE="" DEMO=false ENGINE="" LAKE_ROOT="" ICEBERG=false ICEBERG_ROOT=""
WORKDIR="$DRISHTI_ROOT" BACKGROUND=false WAIT=300 NODE="" NODE_DIR="" NODE_DATA="" SEEDS="" ADVERTISE=""
STOP=false
JAVA_OPTS=()
SERVER_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --port) need "${2:-}" "$1"; PORT="$2"; shift 2 ;;
    --heap) need "${2:-}" "$1"; HEAP="$2"; shift 2 ;;
    --jar) need "${2:-}" "$1"; JAR="$2"; shift 2 ;;
    --java-home) need "${2:-}" "$1"; JAVA_HOME_DIR="$2"; shift 2 ;;
    --packs) need "${2:-}" "$1"; PACKS="$2"; shift 2 ;;
    --data-dir) need "${2:-}" "$1"; DATA="$2"; shift 2 ;;
    --env-file) need "${2:-}" "$1"; ENV_FILE="$2"; shift 2 ;;
    --demo) DEMO=true; shift ;;
    --stop) STOP=true; shift ;;
    --engine) need "${2:-}" "$1"; ENGINE="$2"; shift 2 ;;
    --lake-root) need "${2:-}" "$1"; LAKE_ROOT="$2"; shift 2 ;;
    --iceberg) ICEBERG=true; shift ;;
    --iceberg-root) need "${2:-}" "$1"; ICEBERG_ROOT="$2"; shift 2 ;;
    --java-opt) need "${2:-}" "$1"; JAVA_OPTS+=("$2"); shift 2 ;;
    --server-arg) need "${2:-}" "$1"; SERVER_ARGS+=("$2"); shift 2 ;;
    --workdir) need "${2:-}" "$1"; WORKDIR="$2"; shift 2 ;;
    --background) BACKGROUND=true; shift ;;
    --wait) need "${2:-}" "$1"; WAIT="$2"; shift 2 ;;
    --node) need "${2:-}" "$1"; NODE="$2"; shift 2 ;;
    --node-dir) need "${2:-}" "$1"; NODE_DIR="$2"; shift 2 ;;
    --node-data) need "${2:-}" "$1"; NODE_DATA="$2"; shift 2 ;;
    --seed) need "${2:-}" "$1"; SEEDS="${SEEDS:+$SEEDS,}$2"; shift 2 ;;
    --advertise) need "${2:-}" "$1"; ADVERTISE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; SERVER_ARGS+=("$@"); break ;;
    *) die "unknown option $1 (see --help)" 2 ;;
  esac
done
if $STOP; then
  exec "$(dirname "$0")/stop-server.sh" ${NODE:+--node "$NODE"} ${DATA:+--data-dir "$DATA"} ${ENV_FILE:+--env-file "$ENV_FILE"}
fi
case "$ENGINE" in ''|native|hadoop|auto) ;; *) die "--engine is native, hadoop or auto" 2 ;; esac
if [ -n "$NODE" ]; then
  case "$NODE" in *[!a-z0-9-]*|-*) die "--node $NODE: lower-case letters, digits and dashes" 2 ;; esac
fi
if [ -n "$ADVERTISE" ] && loopback_or_wildcard "$ADVERTISE"; then
  die "--advertise $ADVERTISE is a loopback or wildcard address: members advertise an address other machines can call" 2
fi
[ -z "$NODE_DIR$NODE_DATA$SEEDS$ADVERTISE" ] || [ -n "$NODE" ] || die "--node-dir, --node-data, --seed and --advertise belong to a cluster node: give --node <name> too" 2

[ -z "$ENV_FILE" ] || load_env_file "$ENV_FILE"
DATA="$(data_dir "$DATA")"
SERVER_DATA="$(data_dir "${NODE_DATA:-$DATA}")"
export DRISHTI_DATA_DIR="$SERVER_DATA"
PORT="${PORT:-${DRISHTI_PORT:-$DEFAULT_SERVER_PORT}}"
STEM="server${NODE:+-$NODE}"
PID_FILE="$DATA/run/$STEM.pid"
LOG="$DATA/logs/$STEM.log"
TMP="$DATA/tmp/$STEM"
LABEL="${NODE:-server}"

# already running, or the port taken
pid="$(pid_of "$PID_FILE")"
if alive "$pid" "$SERVER_MARK"; then
  say "$LABEL: already running (pid $pid, port $(field_of "$PID_FILE" port)); bin/stop-server.sh${NODE:+ --node $NODE} stops it"
  exit 3
fi
[ ! -f "$PID_FILE" ] || { rm -f "$PID_FILE"; say "$LABEL: stale $PID_FILE removed (pid ${pid:-?} is not running)"; }
# two servers never share a data folder (its identity database, saved members and inbox are one server's)
for f in "$DATA"/run/server.pid "$DATA"/run/server-*.pid; do
  [ -f "$f" ] && [ "$f" != "$PID_FILE" ] || continue
  if [ "$(field_of "$f" data)" = "$SERVER_DATA" ] && alive "$(pid_of "$f")" "$SERVER_MARK"; then
    die "$(basename "$f" .pid) (pid $(pid_of "$f")) already uses the data folder $SERVER_DATA: give this node its own with --node-data" 3
  fi
done
port_in_use "$PORT" && die "port $PORT is in use by another process: stop it, or pick another with --port" 3

# Java 21 or newer
if [ -n "$JAVA_HOME_DIR" ]; then JAVA="$JAVA_HOME_DIR/bin/java"
elif [ -n "${DRISHTI_JAVA_HOME:-}" ]; then JAVA="$DRISHTI_JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then JAVA="$(command -v java)"
elif [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21+ >/dev/null 2>&1; then JAVA="$(/usr/libexec/java_home -v 21+)/bin/java"
else die "no Java: install a JDK 21 (Temurin) and set DRISHTI_JAVA_HOME or JAVA_HOME, or pass --java-home"
fi
[ -x "$JAVA" ] || die "no java at $JAVA: point --java-home (or DRISHTI_JAVA_HOME) at a JDK 21 folder"
VERSION_LINE="$("$JAVA" -version 2>&1 | head -n 1)"
MAJOR="$(printf '%s' "$VERSION_LINE" | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
[ -n "$MAJOR" ] && [ "$MAJOR" -ge 21 ] || die "Drishti runs on Java 21 or newer; $JAVA says: $VERSION_LINE"

# the jar
if [ -z "$JAR" ]; then
  JAR="$(ls -t "$DRISHTI_ROOT"/drishti-server/target/drishti-server-*-exec.jar "$DRISHTI_ROOT"/drishti-server*.jar 2>/dev/null | head -n 1 || true)"
  [ -n "$JAR" ] || die "no server jar: build it (./mvnw -q package -DskipTests -pl drishti-app -am) or pass --jar"
fi
[ -f "$JAR" ] || die "no jar $JAR"
[ -d "$DRISHTI_ROOT/config/packs" ] || warn "no config/packs/ in $DRISHTI_ROOT: the server starts without domain packs"

# the demo: what the IntelliJ run configuration 'Drishti server (demo lakes)' sets; the development secrets only where none is set
DEV_SECRETS=""
dev_secret() { if [ -z "$(eval "printf '%s' \"\${$1:-}\"")" ]; then export "$1=$2"; DEV_SECRETS="$DEV_SECRETS $1"; fi; }
if $DEMO; then
  PACKS="${PACKS:-${DRISHTI_PACKS:-core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds}}"
  HEAP="${HEAP:-4g}"
  export DRISHTI_DEMO_ENABLED=false DRISHTI_VAR_SYSTEM_BASKETS_SEED=true DRISHTI_OFFLINE=true DRISHTI_SECURITY_ENABLED=true DRISHTI_SEED_ADMIN=true DRISHTI_STUDIO_SAVE=true
  export DRISHTI_LENSES="${DRISHTI_LENSES:-risk,world,market,trading}" DRISHTI_EXTERNAL_LIB="${DRISHTI_EXTERNAL_LIB:-$DRISHTI_ROOT/external_lib}"
  export DRISHTI_SEED_USERNAME="${DRISHTI_SEED_USERNAME:-drishti-dev-admin}"
  dev_secret DRISHTI_SEED_PASSWORD drishti-dev-admin123
  dev_secret DRISHTI_TOKEN_SECRET drishti-dev-only-token-secret-change-me-0123456789
  dev_secret DRISHTI_SESSION_SECRET drishti-dev-only-session-secret-change-me-0123456789
  if [ -n "$NODE" ]; then
    dev_secret DRISHTI_NET_SECRET drishti-dev-only-cluster-key-change-me-0123456789
  else
    export DRISHTI_DATALOAD_ENABLED="${DRISHTI_DATALOAD_ENABLED:-true}" DRISHTI_DATALOAD_OWNER="${DRISHTI_DATALOAD_OWNER:-true}"
  fi
fi
HEAP="${HEAP:-2g}"
[ -z "$PACKS" ] || export DRISHTI_PACKS="$PACKS"
[ -z "$ENGINE" ] || export DRISHTI_DELTA_ENGINE="$ENGINE"
[ -z "$LAKE_ROOT" ] || export DRISHTI_DELTA_ROOT="$LAKE_ROOT"
if $ICEBERG; then
  export DRISHTI_ICEBERG_ENGINE=native DRISHTI_ICEBERG_ROOT="${ICEBERG_ROOT:-${DRISHTI_ICEBERG_ROOT:-$DRISHTI_ROOT/data/iceberg}}"
  mkdir -p "$DRISHTI_ROOT/config/connectors"
  for f in "$DRISHTI_ROOT"/config/connectors.examples/iceberg/*.yaml; do
    [ -e "$DRISHTI_ROOT/config/connectors/$(basename "$f")" ] || { cp "$f" "$DRISHTI_ROOT/config/connectors/"; say "connector file $(basename "$f") copied into config/connectors"; }
  done
fi
export DRISHTI_PORT="$PORT"

# a cluster node: its Drishti*net folder, its seeds, the address it advertises
URL_HOST="$(lan_ip)"
if [ -n "$NODE" ]; then
  NODE_DIR="${NODE_DIR:-$DATA/nodes/$NODE/net}"
  mkdir -p "$NODE_DIR/peers"
  NODE_DIR="$(cd "$NODE_DIR" && pwd -P)"
  if [ ! -f "$NODE_DIR/node.yaml" ]; then
    {
      sed -n '2,14p' "$0"
      printf '# %s, made by bin/start-server.sh (docs/guides/CLUSTER_GUIDE.md). References only: the key and the address come from the environment.\n' "$NODE"
      printf 'enabled: true\nnode-id: %s\n' "$NODE"
      printf 'advertise-url: http://${DRISHTI_ADVERTISE_HOST}:${DRISHTI_PORT}     # this machine'"'"'s real address (bin/start-server sets both)\n'
      printf 'secret: ${DRISHTI_NET_SECRET}\n'
    } > "$NODE_DIR/node.yaml"
    say "$NODE: made $NODE_DIR/node.yaml"
  fi
  if [ -n "$SEEDS" ]; then
    for seed in $(printf '%s' "$SEEDS" | tr ',' ' '); do
      seed="${seed#http://}"; seed="${seed#https://}"; seed="${seed%/}"
      case "$seed" in *:*) ;; *) seed="$seed:$DEFAULT_SERVER_PORT" ;; esac
      loopback_or_wildcard "${seed%:*}" && die "--seed $seed: a seed is named by its real address, never a loopback or wildcard one" 2
      if ! grep -qs "$seed" "$NODE_DIR"/peers/*.yaml; then
        f="$NODE_DIR/peers/seed-$(printf '%s' "$seed" | tr ':.' '--').yaml"
        { sed -n '2,14p' "$0"; printf '# A seed of %s (bin/start-server.sh --seed).\nurl: http://%s\n' "$NODE" "$seed"; } > "$f"
        say "$NODE: seed $seed written to $f"
      fi
    done
  fi
  URL_HOST="$(advertise_host "$ADVERTISE")"
  export DRISHTI_ADVERTISE_HOST="$URL_HOST" DRISHTI_NET_DIR="$NODE_DIR"
  [ -n "${DRISHTI_NET_SECRET:-}" ] || die "a cluster node needs the cluster key DRISHTI_NET_SECRET (32+ bytes, the same on every node): put it in the --env-file"
fi
[ -n "$URL_HOST" ] || URL_HOST=localhost

# the JVM: temp folder, heap, native memory (CLUSTER_GUIDE.md, Resident memory), a heap dump and a rotated GC log (OPERATIONS_GUIDE.md)
mkdir -p "$TMP" "$DATA/logs" "$DATA/run" "$DATA/dumps/$LABEL"
export DRISHTI_TMP_DIR="$TMP"
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"          # glibc: two malloc arenas, not one per busy thread
FLAGS=("-Djava.io.tmpdir=$TMP" "-Xmx$HEAP" "-XX:MaxDirectMemorySize=256m" "-XX:TrimNativeHeapInterval=60000" "-Djdk.nio.maxCachedBufferSize=262144"
       "-XX:NativeMemoryTracking=summary" "-XX:+HeapDumpOnOutOfMemoryError" "-XX:HeapDumpPath=$DATA/dumps/$LABEL"
       "-Xlog:gc*:file=$DATA/logs/gc-$STEM-%p.log:time,uptime:filecount=5,filesize=20m")
[ "$MAJOR" -lt 25 ] || FLAGS=("-XX:+UseCompactObjectHeaders" "${FLAGS[@]}")
# direct memory beyond 256 MB: the in-memory lake's stores and Arrow's budget, from the one place that sizes them (a later flag wins)
PY="$(python_for_tools)"
if [ -n "$PY" ]; then
  EXTRA="$(cd "$WORKDIR" && "$PY" "$DRISHTI_ROOT/tools/lake/memory_caps.py" --extra-only 2>/dev/null || true)"
  ARROW="$(cd "$WORKDIR" && "$PY" "$DRISHTI_ROOT/tools/lake/memory_caps.py" --arrow-only 2>/dev/null || true)"
  case "$EXTRA" in ''|*[!0-9]*) EXTRA=0 ;; esac
  case "$ARROW" in ''|*[!0-9]*) ARROW=0 ;; esac
  [ "$EXTRA" -eq 0 ] || FLAGS+=("-XX:MaxDirectMemorySize=$((256 + EXTRA))m")
  [ "$ARROW" -eq 0 ] || FLAGS+=("-Ddrishti.arrow.max-bytes=$((ARROW * 1048576))")
fi
[ "${#JAVA_OPTS[@]}" -eq 0 ] || FLAGS+=("${JAVA_OPTS[@]}")
ARGS=("--server.port=$PORT")
$BACKGROUND && ARGS+=("--drishti.logging.file=$LOG" "--logging.threshold.console=OFF")
[ "${#SERVER_ARGS[@]}" -eq 0 ] || ARGS+=("${SERVER_ARGS[@]}")

URL="http://$URL_HOST:$PORT"
HEALTH="http://127.0.0.1:$PORT/actuator/health"
say "Drishti ${NODE:+node $NODE }server: $(basename "$JAR") on Java $MAJOR, port $PORT, heap $HEAP, packs ${DRISHTI_PACKS:-the default}, data $SERVER_DATA"
[ -z "$NODE" ] || say "  Drishti*net folder $NODE_DIR, advertising $URL"
if [ -n "$DEV_SECRETS" ]; then
  warn "DEVELOPMENT secrets in use (NOT FOR PRODUCTION):$DEV_SECRETS. Put your own in an --env-file for anything shared."
fi

write_pid() { printf '%s\nport=%s\nurl=%s\nhealth=%s\ndata=%s\n' "$1" "$PORT" "$URL" "$HEALTH" "$SERVER_DATA" > "$PID_FILE"; }
cd "$WORKDIR"
if $BACKGROUND; then
  nohup "$JAVA" "${FLAGS[@]}" -jar "$JAR" "${ARGS[@]}" >> "$DATA/logs/$STEM.out" 2>&1 < /dev/null &
  pid=$!
  write_pid "$pid"
  say "$LABEL: started (pid $pid); log $LOG; waiting up to $WAIT s for $HEALTH"
  if wait_health "$HEALTH" "$WAIT" "$pid"; then
    say "$LABEL: up on $URL"
    exit 0
  fi
  if kill -0 "$pid" 2>/dev/null; then
    say "$LABEL: still starting after $WAIT s (pid $pid): see $LOG; bin/status.sh shows when it answers"
  else
    rm -f "$PID_FILE"
    say "$LABEL: stopped while starting: see $LOG and $DATA/logs/$STEM.out"
  fi
  exit 1
fi
# the foreground: Ctrl+C stops it; the pid file is there while it runs, so bin/status.sh and bin/stop-server.sh see it
"$JAVA" "${FLAGS[@]}" -jar "$JAR" "${ARGS[@]}" &
pid=$!
write_pid "$pid"
trap 'kill -TERM "$pid" 2>/dev/null || true' INT TERM
( wait_health "$HEALTH" "$WAIT" "$pid" && say "$LABEL: up on $URL" ) &
rc=0
wait "$pid" || rc=$?
if kill -0 "$pid" 2>/dev/null; then wait "$pid" || rc=$?; fi
rm -f "$PID_FILE"
exit "$rc"
