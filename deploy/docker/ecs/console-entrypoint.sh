#!/bin/sh
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

# The Drishti console on ECS (docs/admin/CLUSTER_DEPLOYMENT.md › Drishti on AWS: ECS, EFS and S3, section 6.2). It mounts the same EFS access points, at the
# same paths, as the server of its node, checks them as the server does, and reaches that server by the task's real address.
# In a console service of its own (deploy/aws/ecs/templates/console-task.json) DRISHTI_BACKEND_URL names servers by their
# Cloud Map names instead.
set -eu
D=/opt/drishti
fail() { echo "drishti-console: $*" >&2; exit 78; }

for p in "$D/data" "$D/config" "$D/sutras"; do
  [ -d "$p" ] && [ -w "$p" ] || fail "$p is not mounted writable (each writable path is an EFS access point at the same path)"
  if [ "${DRISHTI_REQUIRE_EFS:-true}" = true ]; then
    t=$(stat -f -c %T "$p")
    case "$t" in nfs*) ;; *) fail "$p is on '$t', not EFS; DRISHTI_REQUIRE_EFS=false is only for a local check of the image" ;; esac
  fi
done
mkdir -p "$TMPDIR" "$HOME" "$DRISHTI_CONSOLE_DATA_DIR"

ip="${DRISHTI_NODE_IP:-$(hostname -I 2>/dev/null | awk '{print $1}')}"
case "$ip" in ''|127.*|0.0.0.0|::1|localhost) fail "no real IPv4 address for this task (found '$ip'); set DRISHTI_NODE_IP" ;; esac
export DRISHTI_NODE_IP="$ip"
# the server of the same task, by the task's real address; the console learns the cluster's other members from it
export DRISHTI_BACKEND_URL="${DRISHTI_BACKEND_URL:-http://$ip:${DRISHTI_PORT:-18480}}"

cd "$D/console"
exec python run_drishti_web.py
