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
#
# tools/dev/junit_light.sh: compile a few source files and their JUnit 5 tests with plain javac and run them, no Maven (a light check
# while a release drill holds the machine). The classes need nothing beyond the JDK, JUnit and AssertJ.
#
#   tools/dev/junit_light.sh --test com.ash.drishti.compute.ccr.FvaMathTest \
#       drishti-compute/src/main/java/com/ash/drishti/compute/ccr/FvaMath.java \
#       drishti-compute/src/test/java/com/ash/drishti/compute/ccr/FvaMathTest.java
#
# Extra classpath entries: JUNIT_LIGHT_CP. Output goes to a temporary folder, removed on exit.
set -euo pipefail
tests=()
srcs=()
while [ $# -gt 0 ]; do
  case "$1" in
    --test) tests+=("$2"); shift 2 ;;
    *) srcs+=("$1"); shift ;;
  esac
done
[ ${#tests[@]} -gt 0 ] || { echo "give --test <class> and the source files" >&2; exit 2; }
M2="${M2_REPO:-$HOME/.m2/repository}"
JV="${JUNIT_VERSION:-5.12.2}"
PV="${JUNIT_PLATFORM_VERSION:-1.12.2}"
CP="$M2/org/junit/jupiter/junit-jupiter-api/$JV/junit-jupiter-api-$JV.jar"
CP="$CP:$M2/org/junit/jupiter/junit-jupiter-engine/$JV/junit-jupiter-engine-$JV.jar"
CP="$CP:$M2/org/junit/platform/junit-platform-launcher/$PV/junit-platform-launcher-$PV.jar"
CP="$CP:$M2/org/junit/platform/junit-platform-engine/$PV/junit-platform-engine-$PV.jar"
CP="$CP:$M2/org/junit/platform/junit-platform-commons/$PV/junit-platform-commons-$PV.jar"
CP="$CP:$M2/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar"
CP="$CP:$M2/org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar"
CP="$CP:$M2/org/assertj/assertj-core/3.27.7/assertj-core-3.27.7.jar"
CP="$CP:$M2/net/bytebuddy/byte-buddy/1.17.8/byte-buddy-1.17.8.jar"
[ -n "${JUNIT_LIGHT_CP:-}" ] && CP="$CP:$JUNIT_LIGHT_CP"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/junit-light-XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
here="$(cd "$(dirname "$0")" && pwd)"
javac -J-Xmx768m -nowarn -d "$OUT" -cp "$CP" "${srcs[@]}" "$here/JUnitLight.java"
java -Xmx768m -cp "$OUT:$CP" JUnitLight "${tests[@]}"
