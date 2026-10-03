#!/bin/bash
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

# usage: server.sh <name> <port> [extra spring args...]  ; scratch server in scratchpad/run/<name>
S=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad
W=/home/ashutosh/IdeaProjects/drishti
name=$1; port=$2; shift 2
R=$S/run/$name; mkdir -p $R/sutras; cd $R
export DRISHTI_PORT=$port DRISHTI_PACKS_DIR=$W/packs DRISHTI_SUTRAS=$R/sutras
export DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics
export DRISHTI_SECURITY_ENABLED=true DRISHTI_TOKEN_SECRET=qa-secret-qa-secret-qa-secret-qa-secret-1234 DRISHTI_STUDIO_SAVE=true
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
nohup $JAVA_HOME/bin/java -XX:+UseCompactObjectHeaders -Xmx2g -jar $W/drishti-server/target/drishti-server-1.13.0-exec.jar --server.port=$port "$@" > $R/server.log 2>&1 &
echo $! > $R/pid; echo pid $(cat $R/pid)
for i in $(seq 1 120); do curl -sf -o /dev/null http://localhost:$port/actuator/health && { echo up ${i}s; exit 0; }; kill -0 $(cat $R/pid) 2>/dev/null || { tail -20 $R/server.log; exit 1; }; sleep 1; done
