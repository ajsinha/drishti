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

# Stops a Drishti server started by bin/start-server.sh, by its pid file only (never by name). bin/stop-server.ps1 on Windows.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd -P)/lib/common.sh"

usage() {
  cat <<'EOF'
usage: bin/stop-server.sh [--node NAME | --all-nodes] [--data-dir DIR] [--env-file FILE] [--timeout SECONDS]

Stops the server whose process id is in <data>/run/server[-<node>].pid: SIGTERM first (the server drains and closes cleanly),
SIGKILL after the timeout; then removes the pid file. A pid file whose process is gone is removed and said so. Never stops by name.

  --node NAME         the cluster node to stop (its pid file server-<node>.pid); without it, the single server (server.pid)
  --all-nodes         every server pid file of the data folder (the single server and every node)
  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>/data)
  --env-file FILE     read DRISHTI_DATA_DIR (and the rest) from this file, as bin/start-server.sh did
  --timeout SECONDS   how long to wait after SIGTERM before SIGKILL (default 60)
  -h, --help          this text

Exit codes: 0 stopped or not running; 2 a wrong option.
EOF
}

NODE="" ALL=false DATA="" ENV_FILE="" TIMEOUT=60
while [ $# -gt 0 ]; do
  case "$1" in
    --node) need "${2:-}" "$1"; NODE="$2"; shift 2 ;;
    --all-nodes) ALL=true; shift ;;
    --data-dir) need "${2:-}" "$1"; DATA="$2"; shift 2 ;;
    --env-file) need "${2:-}" "$1"; ENV_FILE="$2"; shift 2 ;;
    --timeout) need "${2:-}" "$1"; TIMEOUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option $1 (see --help)" 2 ;;
  esac
done
[ -z "$NODE" ] || ! $ALL || die "--node and --all-nodes: one or the other" 2
[ -z "$ENV_FILE" ] || load_env_file "$ENV_FILE" >/dev/null
DATA="$(data_dir "$DATA")"

if $ALL; then
  found=false
  for f in "$DATA"/run/server.pid "$DATA"/run/server-*.pid; do
    [ -f "$f" ] || continue
    found=true
    name="$(basename "$f" .pid)"; name="${name#server-}"
    stop_by_pid_file "$f" "$name" "$SERVER_MARK" "$TIMEOUT"
  done
  $found || say "no server pid file in $DATA/run: nothing to stop"
  exit 0
fi
stop_by_pid_file "$DATA/run/server${NODE:+-$NODE}.pid" "${NODE:-server}" "$SERVER_MARK" "$TIMEOUT"
