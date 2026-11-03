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

# A light check of the pivot engine (drishti-bicompute) without Maven, for when a drill or benchmark holds the machine: javac and the
# JUnit launcher with -Xmx768m, against the jars of the last installed release in ~/.m2 (drishti-api, drishti-common) and this checkout's
# ErrorCode. Never a substitute for the module's Maven build; a quick compile and the named test classes only.
#
#   tools/bicompute-unit.sh                       compile the module's main and test sources
#   tools/bicompute-unit.sh LivePivotTest ...     and run these test classes (simple names, package com.ash.drishti.bicompute)
#
# Environment: DRISHTI_M2_VERSION (default: the newest installed drishti-common), JAVA_HOME (default: the PATH's java). Its temporary
# folder is removed on exit.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
m2="${HOME}/.m2/repository"
ver="${DRISHTI_M2_VERSION:-$(ls "$m2/com/ash/drishti/drishti-common" | grep -E '^[0-9]' | sort -V | tail -1)}"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
jar() { ls "$m2/$1"/*.jar | grep -v -- '-sources\|-javadoc\|-tests' | head -1; }
cp="$(jar com/ash/drishti/drishti-api/$ver):$(jar com/ash/drishti/drishti-common/$ver):$(jar org/duckdb/duckdb_jdbc/1.5.6.0)"
cp="$cp:$(jar org/slf4j/slf4j-api/2.0.17):$(jar com/fasterxml/jackson/core/jackson-databind/2.21.4):$(jar com/fasterxml/jackson/core/jackson-core/2.21.4)"
cp="$cp:$(ls "$m2"/com/fasterxml/jackson/core/jackson-annotations/*/jackson-annotations-*.jar | grep -v sources | sort -V | tail -1)"
cp="$cp:$(jar com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.21.4):$(ls "$m2"/org/yaml/snakeyaml/*/snakeyaml-*.jar | grep -v sources | sort -V | tail -1)"
tcp="$(jar org/junit/jupiter/junit-jupiter-api/5.14.4):$(jar org/junit/jupiter/junit-jupiter-engine/5.14.4):$(jar org/junit/jupiter/junit-jupiter-params/5.14.4)"
tcp="$tcp:$(jar org/junit/platform/junit-platform-commons/1.14.4):$(jar org/junit/platform/junit-platform-engine/1.14.4)"
tcp="$tcp:$(jar org/junit/platform/junit-platform-launcher/1.14.4):$(jar org/opentest4j/opentest4j/1.3.0):$(jar org/apiguardian/apiguardian-api/1.1.2)"
tcp="$tcp:$(jar org/assertj/assertj-core/3.27.7):$(ls "$m2"/net/bytebuddy/byte-buddy/*/byte-buddy-*.jar | grep -v sources | sort -V | tail -1)"
work="$(mktemp -d "${TMPDIR:-/tmp}/drishti-bicompute-unit.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/main" "$work/test"
"${java_bin}javac" -J-Xmx768m -nowarn -encoding UTF-8 --release 21 -d "$work/main" -cp "$cp" \
  "$here/drishti-common/src/main/java/com/ash/drishti/common/ErrorCode.java" \
  $(find "$here/drishti-bicompute/src/main/java" -name '*.java')
echo "main: compiled"
"${java_bin}javac" -J-Xmx768m -nowarn -encoding UTF-8 --release 21 -d "$work/test" -cp "$work/main:$cp:$tcp" \
  $(find "$here/drishti-bicompute/src/test/java" -name '*.java')
echo "test: compiled"
[ $# -eq 0 ] && exit 0
cat > "$work/Run.java" <<'EOF'
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public class Run {
    public static void main(String[] a) throws Exception {
        var b = LauncherDiscoveryRequestBuilder.request();
        for (String c : a) b.selectors(selectClass(Class.forName("com.ash.drishti.bicompute." + c)));
        var l = LauncherFactory.create();
        var s = new SummaryGeneratingListener();
        l.execute(b.build(), s);
        s.getSummary().printTo(new java.io.PrintWriter(System.out));
        s.getSummary().printFailuresTo(new java.io.PrintWriter(System.out), 30);
        System.exit(s.getSummary().getTotalFailureCount() == 0 ? 0 : 1);
    }
}
EOF
"${java_bin}java" -Xmx768m -cp "$work/main:$work/test:$here/drishti-bicompute/src/test/resources:$cp:$tcp" "$work/Run.java" "$@"
