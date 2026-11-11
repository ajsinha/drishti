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

# Every Drishti process this machine started with bin/: its pid, port, health answer and LAN address, from the pid files of the data
# folder (bin/status.ps1 on Windows).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd -P)/lib/common.sh"

usage() {
  cat <<'EOF'
usage: bin/status.sh [--data-dir DIR ...] [--env-file FILE] [--cluster FILE]

For each pid file in <data>/run (server.pid, server-<node>.pid, console.pid): the process id, whether it runs, its port, whether
its health URL answers, and the address other machines open.

  --data-dir DIR      the data folder (repeatable; default DRISHTI_DATA_DIR, else <drishti>/data)
  --env-file FILE     read DRISHTI_DATA_DIR from this file
  --cluster FILE      a cluster file: also the members each node sees and the console's servers
  -h, --help          this text

Exit codes: 0 every process found runs and answers (or none was started); 1 one is not running or not answering; 2 a wrong option.
EOF
}

for a in "$@"; do
  if [ "$a" = --cluster ]; then
    PY="$(python_for_tools)"
    [ -n "$PY" ] || die "no Python found for --cluster" 1
    exec "$PY" "$DRISHTI_ROOT/bin/lib/cluster.py" status "$@"
  fi
done

DIRS=("") ENV_FILE="" GIVEN=false
while [ $# -gt 0 ]; do
  case "$1" in
    --data-dir) need "${2:-}" "$1"; if $GIVEN; then DIRS+=("$2"); else DIRS=("$2"); GIVEN=true; fi; shift 2 ;;
    --env-file) need "${2:-}" "$1"; ENV_FILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option $1 (see --help)" 2 ;;
  esac
done
[ -z "$ENV_FILE" ] || load_env_file "$ENV_FILE" >/dev/null

rc=0
any=false
printf '%-22s %8s  %-10s %-6s %-10s %-16s %s\n' process pid state port health distribution address
for d in "${DIRS[@]}"; do
  DATA="$(data_dir "$d")"
  for f in "$DATA"/run/server.pid "$DATA"/run/server-*.pid "$DATA"/run/console.pid "$DATA"/run/console-*.pid; do
    [ -f "$f" ] || continue
    any=true
    name="$(basename "$f" .pid)"
    mark="$SERVER_MARK"
    case "$name" in console*) mark="$CONSOLE_MARK" ;; esac
    dist="$(field_of "$f" distribution)"
    pid="$(pid_of "$f")"
    port="$(field_of "$f" port)"
    url="$(field_of "$f" url)"
    health="$(field_of "$f" health)"
    if alive "$pid" "$mark"; then state=running; else state="stale pid"; rc=1; fi
    answer="-"
    if [ -n "$health" ]; then
      if http_ok "$health"; then answer=UP; else answer="no answer"; rc=1; fi
    fi
    printf '%-22s %8s  %-10s %-6s %-10s %-16s %s\n' "$name" "${pid:--}" "$state" "${port:--}" "$answer" "${dist:-all}" "${url:--}"
  done
done
$any || say "(no Drishti process started with bin/ in this data folder)"
exit "$rc"
