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

# stop.sh <name> [signal]   stops a scratch server by its recorded PID only
S=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data
p=$(cat $S/run/$1/pid)
sig=${2:-TERM}
if ps -o args= -p $p | grep -q "$S/run\|drishti-server-1.13.0-exec.jar"; then
  # double-check cwd is our run dir
  if [[ "$(readlink /proc/$p/cwd)" == "$S/run/$1" ]]; then kill -$sig $p; echo "sent $sig to $p"; else echo "pid $p cwd mismatch, not killing"; fi
else echo "pid $p not ours / not running"; fi
for i in $(seq 1 30); do kill -0 $p 2>/dev/null || { echo stopped; exit 0; }; sleep 1; done
echo "still running"
