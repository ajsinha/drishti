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

# One server and one console on this machine, in the background: bin/start-server.sh, then bin/start-console.sh pointed at it
# (bin/start-all.ps1 on Windows). A cluster is started node by node with bin/start-server.sh --node (docs/DEPLOYMENT_AND_QA_GUIDE.md).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd -P)"
. "$HERE/lib/common.sh"

usage() {
  cat <<'EOF'
usage: bin/start-all.sh [--demo] [--env-file FILE] [--data-dir DIR] [--server-port N] [--console-port N] [--heap SIZE]

Starts one server and one console on this machine in the background (the simplest case), the console pointed at the server's LAN
address. The options go to bin/start-server.sh and bin/start-console.sh (see their --help).

  --demo              the demo with DEVELOPMENT secrets: NOT FOR PRODUCTION
  --env-file FILE     the secrets and settings of both (KEY=VALUE lines)
  --data-dir DIR      the data folder of both
  --server-port N     the server's port (default 18480)
  --console-port N    the console's port (default 17480)
  --heap SIZE         the server's heap (-Xmx)
  -h, --help          this text

Exit codes: 0 both up; otherwise the failing script's code. bin/stop-all.sh stops both.
EOF
}

COMMON=()
SERVER=()
CONSOLE=()
SPORT="$DEFAULT_SERVER_PORT"
while [ $# -gt 0 ]; do
  case "$1" in
    --demo) COMMON+=(--demo); shift ;;
    --env-file|--data-dir) need "${2:-}" "$1"; COMMON+=("$1" "$2"); shift 2 ;;
    --server-port) need "${2:-}" "$1"; SPORT="$2"; shift 2 ;;
    --console-port) need "${2:-}" "$1"; CONSOLE+=(--port "$2"); shift 2 ;;
    --heap) need "${2:-}" "$1"; SERVER+=(--heap "$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option $1 (see --help)" 2 ;;
  esac
done
HOST="$(lan_ip)"
[ -n "$HOST" ] || HOST=localhost
"$HERE/start-server.sh" ${COMMON[@]+"${COMMON[@]}"} ${SERVER[@]+"${SERVER[@]}"} --port "$SPORT" --background
"$HERE/start-console.sh" ${COMMON[@]+"${COMMON[@]}"} ${CONSOLE[@]+"${CONSOLE[@]}"} --server "http://$HOST:$SPORT" --background
