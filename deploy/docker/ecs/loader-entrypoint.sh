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

# The lake loader and maintenance tasks on ECS. Like the server's entry point: the one writable path must be an EFS mount at
# the same path (the rule of AWS_DEPLOYMENT.md), the root file system is read-only; then the task's command runs
# (deploy/aws/delta-lake/load-day.sh, backfill.sh, maintain.sh or report.sh with their arguments).
set -eu
D=/opt/drishti
fail() { echo "drishti-loader: $*" >&2; exit 78; }

p="$D/data"
[ -d "$p" ] && [ -w "$p" ] || fail "$p is not mounted writable (the task's EFS access point at the same path)"
if [ "${DRISHTI_REQUIRE_EFS:-true}" = true ]; then
  t=$(stat -f -c %T "$p")
  case "$t" in nfs*) ;; *) fail "$p is on '$t', not EFS; DRISHTI_REQUIRE_EFS=false is only for a local check of the image" ;; esac
fi
mkdir -p "$TMPDIR" "$HOME"
[ "$#" -gt 0 ] || fail "no command: give the script to run (deploy/aws/delta-lake/load-day.sh ...)"
cd "$D/app"
exec "$@"
