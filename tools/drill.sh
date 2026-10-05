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

# "Drill it": verify everything, push develop, merge develop into main, push main.
# Nothing is pushed or merged unless the full Java build and the console tests pass.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"

[[ "$(git rev-parse --abbrev-ref HEAD)" == "develop" ]] || { echo "drill: must be on develop" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "drill: commit your changes first" >&2; exit 1; }

python3 tools/license_headers.py
for gen in $(grep -l -- "--check" packs/*/tools/make_*.py tools/packgen/*/make*.py 2>/dev/null); do python3 "$gen" --check; done   # generated pack content is current
python3 -m unittest -q tools/samplegen/test_samplegen.py
python3 -m unittest -q tools/packreg/test_packreg.py
if command -v uv >/dev/null; then                  # the lake writers and maintenance need deltalake; uv provides it without installing
  # a hung test fails the drill (timeout exits 124) instead of blocking it: these take seconds, 10 minutes is a hang
  timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/samplegen/test_layout.py
  timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/lake/test_maintain.py 2>&1 | grep -v '^{"at"'
fi
./mvnw -q -o verify
# Java 21 is supported too (the jar is Java 21 bytecode): the same tests again on a JDK 21, so a call that exists only on
# 25 or a behaviour that differs on 21 (virtual-thread pinning, no compact object headers) fails the drill, not a user.
# The whole suite is run, not a subset: about 1,800 tests, 3.5 minutes (measured), well inside the drill budget; a failure here exits the script through `set -e`.
JAVA21_HOME="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
[[ -x "$JAVA21_HOME/bin/java" ]] || { echo "drill: no JDK 21 at $JAVA21_HOME (set JAVA21_HOME)" >&2; exit 1; }
JAVA_HOME="$JAVA21_HOME" ./mvnw -q -o verify
# The console suite. A browser test that fails is run once more on its own: about one run in 700 a workbench page has
# loaded without its script starting (diagnosed in console/tests/test_workbench_browser.py: wait() reports the page state,
# failed requests and bad responses). A test that passes the second time is a flake: printed and kept in
# target/drill-flakes.log so it is looked at, not hidden; a test that fails twice fails the drill.
if ! console/.venv/bin/python -m pytest -q console/tests; then
  mkdir -p target
  failed=$(console/.venv/bin/python -m pytest -q --co --last-failed console/tests 2>/dev/null | grep '::' || true)
  [[ -n "$failed" ]] || { echo "drill: the console suite failed with no failed test to re-run (a collection error?)" >&2; exit 1; }
  echo "drill: re-running the failed console tests once:" >&2; echo "$failed" >&2
  console/.venv/bin/python -m pytest -q --last-failed --last-failed-no-failures none console/tests
  { echo "$(date -Iseconds) $(git log --oneline -1)"; echo "$failed"; } >> target/drill-flakes.log
  echo "drill: FLAKY (failed once, passed again), kept in target/drill-flakes.log" >&2
fi

git push -q origin develop
git checkout -q main
git merge -q --ff-only develop
git push -q origin main
git checkout -q develop
echo "drilled: $(git log --oneline -1)"
