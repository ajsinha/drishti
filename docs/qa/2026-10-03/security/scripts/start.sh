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

# starts the scratch server :18961 (security on, review on, studio-save on) in $W; file-binding via $BIND (true/false)
R=/home/ashutosh/IdeaProjects/drishti
W=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/sec
cd $W
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export DRISHTI_SECURITY_ENABLED=true DRISHTI_TOKEN_SECRET=$(cat secret) DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics
export DRISHTI_PACKS_DIR=$R/packs DRISHTI_SUTRAS=$W/sutras DRISHTI_STUDIO_SAVE=true DRISHTI_SUTRA_REVIEW=true DRISHTI_FEEDS=$W/data/feeds
export DRISHTI_SEED_USERNAME=qa-root DRISHTI_SEED_PASSWORD='qa-root-password-123' DRISHTI_PACK_REGISTRY=
nohup $JAVA_HOME/bin/java -XX:+UseCompactObjectHeaders -Xmx2g -jar $R/drishti-server/target/drishti-server-1.13.0-exec.jar --server.port=18961 --drishti.builder.file-binding=${BIND:-false} > server.log 2>&1 &
echo $! > server.pid
