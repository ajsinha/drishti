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

# Stops what bin/start-all.sh started, the console first, by their pid files (bin/stop-all.ps1 on Windows). With --all-nodes, every
# server node of the data folder too.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd -P)"
. "$HERE/lib/common.sh"

usage() {
  cat <<'EOF'
usage: bin/stop-all.sh [--data-dir DIR] [--env-file FILE] [--all-nodes]

Stops the console, then the server (bin/stop-console.sh, bin/stop-server.sh), by their pid files only.

  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>/data)
  --env-file FILE     read DRISHTI_DATA_DIR from this file
  --all-nodes         every server pid file (the single server and every cluster node), not only server.pid
  -h, --help          this text

Exit codes: 0 stopped or not running; 2 a wrong option.
EOF
}

COMMON=()
NODES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --data-dir|--env-file) need "${2:-}" "$1"; COMMON+=("$1" "$2"); shift 2 ;;
    --all-nodes) NODES=(--all-nodes); shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option $1 (see --help)" 2 ;;
  esac
done
"$HERE/stop-console.sh" ${COMMON[@]+"${COMMON[@]}"}
"$HERE/stop-server.sh" ${COMMON[@]+"${COMMON[@]}"} ${NODES[@]+"${NODES[@]}"}
