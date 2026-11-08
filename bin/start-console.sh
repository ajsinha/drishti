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

# Starts the Drishti console (the web front end), pointed at a server or at the entry nodes of a cluster (Linux and macOS;
# bin/start-console.ps1 on Windows). Every option: --help, bin/README.md and docs/DEPLOYMENT_AND_QA_GUIDE.md section 6.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd -P)/lib/common.sh"

usage() {
  cat <<'EOF'
usage: bin/start-console.sh [options]

Starts the console: finds its Python (makes drishti-console/.venv on first use), points it at the server, waits for /healthz and
prints the address people open.

  --server URLS       the server, or a cluster's entry nodes, comma-separated (http://<ip>:18480,...): the console uses the first that
                      answers and learns the other members from the cluster. Default DRISHTI_SERVER_URL (from the environment or the
                      --env-file), else this machine's LAN address :18480 (with a warning)
  --port N            the console's port (default DRISHTI_CONSOLE_PORT, else 17480)
  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>/data): run/console.pid, logs/console.log
  --env-file FILE     KEY=VALUE lines put into the console's environment (the token and session secrets; values never printed)
  --demo              the demo's settings with DEVELOPMENT secrets (the same as bin/start-server.sh --demo): NOT FOR PRODUCTION
  --python FILE       the Python 3.11+ to make the environment with (default python3)
  --reinstall         remake drishti-console/.venv (after drishti-console/requirements.txt changed)
  --background        detach: log in <data>/logs/console.log, pid in <data>/run/console.pid
  --wait SECONDS      how long to wait for the health answer (default 90)
  -h, --help          this text

Environment: DRISHTI_SERVER_URL, DRISHTI_PYTHON (the console's Python; default drishti-console/.venv), DRISHTI_CONSOLE_PORT,
DRISHTI_DATA_DIR, DRISHTI_TOKEN_SECRET and DRISHTI_SESSION_SECRET (the same as the servers'). Exit codes: 0 up; 1 failed to start;
2 a wrong option; 3 already running or the port is in use.

examples:
  bin/start-console.sh --demo --background
  bin/start-console.sh --server http://192.168.1.20:18480 --env-file /etc/drishti/console.env --background
  bin/start-console.sh --server http://192.168.1.20:18480,http://192.168.1.21:18480 --env-file cluster.env --background
EOF
}

SERVERS="" PORT="" DATA="" ENV_FILE="" DEMO=false BASE_PY="python3" REINSTALL=false BACKGROUND=false WAIT=90
while [ $# -gt 0 ]; do
  case "$1" in
    --server) need "${2:-}" "$1"; SERVERS="${SERVERS:+$SERVERS,}$2"; shift 2 ;;
    --port) need "${2:-}" "$1"; PORT="$2"; shift 2 ;;
    --data-dir) need "${2:-}" "$1"; DATA="$2"; shift 2 ;;
    --env-file) need "${2:-}" "$1"; ENV_FILE="$2"; shift 2 ;;
    --demo) DEMO=true; shift ;;
    --python) need "${2:-}" "$1"; BASE_PY="$2"; shift 2 ;;
    --reinstall) REINSTALL=true; shift ;;
    --background) BACKGROUND=true; shift ;;
    --wait) need "${2:-}" "$1"; WAIT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option $1 (see --help)" 2 ;;
  esac
done

[ -z "$ENV_FILE" ] || load_env_file "$ENV_FILE"
DATA="$(data_dir "$DATA")"
export DRISHTI_DATA_DIR="$DATA"
PORT="${PORT:-${DRISHTI_CONSOLE_PORT:-$DEFAULT_CONSOLE_PORT}}"
PID_FILE="$DATA/run/console.pid"
LOG="$DATA/logs/console.log"

pid="$(pid_of "$PID_FILE")"
if alive "$pid" "$CONSOLE_MARK"; then
  say "console: already running (pid $pid, port $(field_of "$PID_FILE" port)); bin/stop-console.sh stops it"
  exit 3
fi
[ ! -f "$PID_FILE" ] || { rm -f "$PID_FILE"; say "console: stale $PID_FILE removed (pid ${pid:-?} is not running)"; }
port_in_use "$PORT" && die "port $PORT is in use by another process: stop it, or pick another with --port" 3

# the console's Python: DRISHTI_PYTHON, else drishti-console/.venv (made on first use, with uv when it is installed)
VENV="$DRISHTI_ROOT/drishti-console/.venv"
if [ -n "${DRISHTI_PYTHON:-}" ]; then
  PY="$DRISHTI_PYTHON"
else
  PY="$VENV/bin/python"
  if $REINSTALL && [ -d "$VENV" ]; then rm -rf "$VENV"; fi
  if [ ! -x "$PY" ]; then
    say "making the console's Python environment in drishti-console/.venv ..."
    if command -v uv >/dev/null 2>&1; then
      uv venv "$VENV" && uv pip install --python "$PY" -r "$DRISHTI_ROOT/drishti-console/requirements.txt" || die "uv could not make drishti-console/.venv"
    else
      "$BASE_PY" -m venv "$VENV" || die "$BASE_PY -m venv failed: install Python 3.11 or newer (or pass --python)"
      "$PY" -m pip install -q -r "$DRISHTI_ROOT/drishti-console/requirements.txt" || die "installing drishti-console/requirements.txt failed"
    fi
  fi
fi
[ -x "$PY" ] || command -v "$PY" >/dev/null 2>&1 || die "no Python at $PY"

DEV_SECRETS=""
dev_secret() { if [ -z "$(eval "printf '%s' \"\${$1:-}\"")" ]; then export "$1=$2"; DEV_SECRETS="$DEV_SECRETS $1"; fi; }
if $DEMO; then
  export DRISHTI_AUTH_ENABLED=true DRISHTI_SECURE_COOKIE=false
  export DRISHTI_PACKS="${DRISHTI_PACKS:-core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds}"
  dev_secret DRISHTI_TOKEN_SECRET drishti-dev-only-token-secret-change-me-0123456789
  dev_secret DRISHTI_SESSION_SECRET drishti-dev-only-session-secret-change-me-0123456789
fi

# the servers: --server, else DRISHTI_SERVER_URL, else this machine's LAN address
HOST="$(lan_ip)"
[ -n "$HOST" ] || HOST=localhost
SERVERS="${SERVERS:-${DRISHTI_SERVER_URL:-}}"
if [ -z "$SERVERS" ]; then
  SERVERS="http://$HOST:$DEFAULT_SERVER_PORT"
  warn "no --server (or DRISHTI_SERVER_URL): using this machine's $SERVERS"
fi
LIST="" FIRST=""
for s in $(printf '%s' "$SERVERS" | tr ',' ' '); do
  case "$s" in http://*|https://*) ;; *) s="http://$s" ;; esac
  s="${s%/}"
  h="${s#*://}"; h="${h%%[:/]*}"
  if loopback_or_wildcard "$h"; then warn "$s is a loopback address: in a cluster give the entry node's real address, as the members advertise it"; fi
  LIST="${LIST:+$LIST,}$s"
  if [ -z "$FIRST" ] && http_ok "$s/actuator/health"; then FIRST="$s"; fi
done
if [ -z "$FIRST" ]; then
  FIRST="${LIST%%,*}"
  warn "no server answers yet at $LIST: the console starts and keeps trying"
fi
export DRISHTI_BACKEND_URLS="$LIST" DRISHTI_BACKEND_URL="$FIRST" DRISHTI_CONSOLE_PORT="$PORT"

URL="http://$HOST:$PORT"
HEALTH="http://127.0.0.1:$PORT/healthz"
say "Drishti console on port $PORT, given $LIST (using $FIRST first), data $DATA"
[ -z "$DEV_SECRETS" ] || warn "DEVELOPMENT secrets in use (NOT FOR PRODUCTION):$DEV_SECRETS. Put your own in an --env-file for anything shared."
mkdir -p "$DATA/run" "$DATA/logs"
write_pid() { printf '%s\nport=%s\nurl=%s\nhealth=%s\n' "$1" "$PORT" "$URL" "$HEALTH" > "$PID_FILE"; }
cd "$DRISHTI_ROOT"
if $BACKGROUND; then
  nohup "$PY" "$DRISHTI_ROOT/drishti-console/run_drishti_web.py" "--server.port=$PORT" >> "$LOG" 2>&1 < /dev/null &
  pid=$!
  write_pid "$pid"
  say "console: started (pid $pid); log $LOG; waiting up to $WAIT s for $HEALTH"
  if wait_health "$HEALTH" "$WAIT" "$pid"; then
    say "console: up on $URL"
    exit 0
  fi
  kill -0 "$pid" 2>/dev/null || rm -f "$PID_FILE"
  say "console: did not answer in $WAIT s: see $LOG"
  exit 1
fi
"$PY" "$DRISHTI_ROOT/drishti-console/run_drishti_web.py" "--server.port=$PORT" &
pid=$!
write_pid "$pid"
trap 'kill -TERM "$pid" 2>/dev/null || true' INT TERM
( wait_health "$HEALTH" "$WAIT" "$pid" && say "console: up on $URL" ) &
rc=0
wait "$pid" || rc=$?
if kill -0 "$pid" 2>/dev/null; then wait "$pid" || rc=$?; fi
rm -f "$PID_FILE"
exit "$rc"
