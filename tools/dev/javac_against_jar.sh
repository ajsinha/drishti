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
# tools/dev/javac_against_jar.sh: compile a few changed source files against an unpacked server jar (its BOOT-INF/classes and
# BOOT-INF/lib), no Maven: a light check that new code compiles while a release drill holds the machine. The files given win over the
# jar's classes of the same name. Unpack first (once):
#
#   unzip -q server-exec.jar 'BOOT-INF/lib/*' 'BOOT-INF/classes/com/*' -d <dir>
#   tools/dev/javac_against_jar.sh <dir> drishti-server/src/main/java/.../FvaService.java ...
#
# The classes go to a temporary folder, removed on exit; javac's messages are the output.
set -euo pipefail
dir="$1"
shift
[ -d "$dir/BOOT-INF/classes" ] || { echo "no $dir/BOOT-INF/classes: unpack the server's exec jar there first" >&2; exit 2; }
cp="$dir/BOOT-INF/classes"
for j in "$dir"/BOOT-INF/lib/*.jar; do
  cp="$cp:$j"
done
out="$(mktemp -d "${TMPDIR:-/tmp}/javac-check-XXXXXX")"
trap 'rm -rf "$out"' EXIT
javac -J-Xmx768m -nowarn -proc:none -d "$out" -cp "$cp" "$@"
echo "compiled: $# files"
