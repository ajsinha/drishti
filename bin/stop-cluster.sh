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

# Stops a cluster's console and nodes by their pid files (never by name), or only the nodes named.
# The cluster file is deploy/stack/stack.yaml by default (bin/README.md; bin/stop-cluster.ps1 on Windows).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd -P)/lib/common.sh"

case "${1:-}" in
  -h|--help)
    cat <<'EOF'
usage: bin/stop-cluster.sh [--cluster FILE] [--data-dir DIR] [node ...]

  --cluster FILE   the cluster file (default deploy/stack/stack.yaml); its folder holds nodes/ and secrets.env
  --data-dir DIR   the data folder (default DRISHTI_DATA_DIR, else <drishti>/data)
  node ...         only these nodes; "console" for the console
  -h, --help       this text
EOF
    exit 0 ;;
esac
PY="$(python_for_tools)"
[ -n "$PY" ] || die "no Python found: make the console's environment first (bin/start-console.sh does it)" 1
exec "$PY" "$DRISHTI_ROOT/bin/lib/cluster.py" stop "$@"
