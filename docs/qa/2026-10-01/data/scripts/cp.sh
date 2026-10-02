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

# prints the class path of a plugin module: cp.sh plugins/drishti-plugin-file
set -euo pipefail
W=/home/ashutosh/IdeaProjects/drishti/.claude/worktrees/agent-a3a597410e145c292
S=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data
cd "$W"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
name=$(basename "$1")
f=$S/cp-$name.txt
if [[ ! -s $f ]]; then
  ./mvnw -q -o dependency:build-classpath -pl "$1" -Dmdep.outputFile=$f >/dev/null
fi
echo "$W/$1/target/classes:$(cat $f)"
