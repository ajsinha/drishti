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

# loader.sh <plugin-module-dir> <main-class> args...   runs a plugin's loader class with the module's class path
set -euo pipefail
S=/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data
CP=$(bash $S/scripts/cp.sh "$1")
shift
cls=$1; shift
cd $S
exec /usr/lib/jvm/java-25-openjdk-amd64/bin/java -Xmx2g -cp "$CP" "$cls" "$@"
