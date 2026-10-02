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

# server.sh <name> <port> [profile] [extra --key=value ...]
# starts a scratch Drishti server in $S/run/<name> (its own ./data), writes its PID to $S/run/<name>/pid
# environment passed through: DRISHTI_* set by the caller (e.g. DRISHTI_DELTA_ROOT)
set -euo pipefail
W=/home/ashutosh/IdeaProjects/drishti/.claude/worktrees/agent-a3a597410e145c292
S=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data
name=$1; port=$2; profile=${3:-}; shift 3 || shift $#
R=$S/run/$name
mkdir -p $R
cd $R
export DRISHTI_PORT=$port
export DRISHTI_PACKS_DIR=$W/packs
export DRISHTI_SUTRAS=$W/sutras
export DRISHTI_PACKS=${DRISHTI_PACKS:-market-risk,counterparty-risk}
export DRISHTI_FEEDS=$R/data/feeds
[[ -n "$profile" ]] && export SPRING_PROFILES_ACTIVE=$profile
nohup /usr/lib/jvm/java-25-openjdk-amd64/bin/java -Xmx2g -jar $W/drishti-server/target/drishti-server-1.13.0-exec.jar "$@" > $R/server.log 2>&1 &
echo $! > $R/pid
echo "started $name pid $(cat $R/pid) port $port profile '$profile'"
for i in $(seq 1 120); do
  if curl -sf -o /dev/null http://localhost:$port/actuator/health; then echo "up after ${i}s"; exit 0; fi
  if ! kill -0 $(cat $R/pid) 2>/dev/null; then echo "DIED"; tail -30 $R/server.log; exit 1; fi
  sleep 1
done
echo "not up after 120s"; tail -20 $R/server.log
