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

# Stops the Drishti console started by bin/start-console.sh, by its pid file only (never by name). bin/stop-console.ps1 on Windows.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd -P)/lib/common.sh"

usage() {
  cat <<'EOF'
usage: bin/stop-console.sh [--data-dir DIR] [--env-file FILE] [--timeout SECONDS] [--name NAME]

Stops the console whose process id is in <data>/run/console.pid: SIGTERM first, SIGKILL after the timeout; then removes the pid
file. A pid file whose process is gone is removed and said so. Never stops by name.

  --data-dir DIR      the data folder (default DRISHTI_DATA_DIR, else <drishti>/data)
  --env-file FILE     read DRISHTI_DATA_DIR (and the rest) from this file, as bin/start-console.sh did
  --timeout SECONDS   how long to wait after SIGTERM before SIGKILL (default 30)
  --name NAME         the console started with --name (run/console-<name>.pid)
  -h, --help          this text

Exit codes: 0 stopped or not running; 2 a wrong option.
EOF
}

DATA="" ENV_FILE="" TIMEOUT=30 CNAME=""
while [ $# -gt 0 ]; do
  case "$1" in
    --data-dir) need "${2:-}" "$1"; DATA="$2"; shift 2 ;;
    --env-file) need "${2:-}" "$1"; ENV_FILE="$2"; shift 2 ;;
    --timeout) need "${2:-}" "$1"; TIMEOUT="$2"; shift 2 ;;
    --name) need "${2:-}" "$1"; CNAME="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option $1 (see --help)" 2 ;;
  esac
done
[ -z "$ENV_FILE" ] || load_env_file "$ENV_FILE" >/dev/null
DATA="$(data_dir "$DATA")"
stop_by_pid_file "$DATA/run/console${CNAME:+-$CNAME}.pid" "console${CNAME:+-$CNAME}" "$CONSOLE_MARK" "$TIMEOUT"
