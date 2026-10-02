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

# dv.sh <table> <date> <id>...   deletes ids with deletion vectors (one commit each)
set -euo pipefail
W=/home/ashutosh/IdeaProjects/drishti/.claude/worktrees/agent-a3a597410e145c292
S=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data
cd $W
f=$S/cp-delta-test.txt
if [[ ! -s $f ]]; then
  ./mvnw -q -o dependency:build-classpath -pl plugins/drishti-plugin-delta -Dmdep.includeScope=test -Dmdep.outputFile=$f >/dev/null
fi
CP="$W/plugins/drishti-plugin-delta/target/test-classes:$W/plugins/drishti-plugin-delta/target/classes:$(cat $f)"
table=$1; date=$2; shift 2
for id in "$@"; do
  /usr/lib/jvm/java-25-openjdk-amd64/bin/java -cp "$CP" $S/scripts/DvDelete.java "$table" "$date" "$id"
done
