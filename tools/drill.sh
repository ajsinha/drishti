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
# Nothing is pushed or merged unless the verification passes.
#
#   tools/drill.sh            full drill (or the light one when the commits since origin/develop touch only docs)
#   tools/drill.sh --docs     light drill: licence check, docs/help/link/guide-image console tests, Python tool tests.
#                             Refused when the commits since origin/develop touch anything but docs/**, *.md, help.yaml, tools/docs/**
#   tools/drill.sh --full     the full drill even when only docs changed
#   tools/drill.sh --no-push  verify only: no push, no merge (the timing of each stage is printed either way)
#
# Full drill: licence check; Java 21 full verify (it builds the jar the console tests start); then, at the same time and each
# with its own log under target/drill/: the Java 25 forward-compatibility verify (in a copy of the committed tree, so it
# cannot touch the jar the console tests run), the console tests on pytest-xdist workers, and the Python tool tests (the
# generated-content checks included). Every test that ran before still runs; only the waiting is shared.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"   # production runs Java 21; the console tests start the server on it

MODE=auto; PUSH=1
for a in "$@"; do
  case "$a" in
    --docs) MODE=docs ;;
    --full) MODE=full ;;
    --no-push) PUSH=0 ;;
    *) echo "drill: unknown option $a (use --docs, --full, --no-push)" >&2; exit 2 ;;
  esac
done

[[ "$(git rev-parse --abbrev-ref HEAD)" == "develop" ]] || { echo "drill: must be on develop" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "drill: commit your changes first" >&2; exit 1; }

# the light drill is for commits that touch only documentation
DOCS_ONLY_RE='^(docs/|tools/docs/|.*\.md$|(.*/)?help\.yaml$)'
git fetch -q origin develop 2>/dev/null || echo "drill: could not fetch origin/develop; using the last known one" >&2
changed=$(git diff --name-only origin/develop..HEAD 2>/dev/null || true)
non_docs=$(printf '%s\n' "$changed" | grep -v '^$' | grep -Ev "$DOCS_ONLY_RE" || true)
if [[ "$MODE" == auto ]]; then
  if [[ -n "$changed" && -z "$non_docs" ]]; then MODE=docs; else MODE=full; fi
elif [[ "$MODE" == docs && -n "$non_docs" ]]; then
  echo "drill: --docs refused: these changes since origin/develop are not documentation:" >&2; printf '%s\n' "$non_docs" | head -20 >&2
  exit 1
fi
echo "drill: mode = $MODE$([[ $PUSH == 0 ]] && echo ' (no push)')"

PY=drishti-console/.venv/bin/python
LOGS=target/drill; mkdir -p "$LOGS"
T0=$SECONDS; STAGES=()
stage_done() { STAGES+=("$(printf '%-36s %5ss' "$1" "$(( SECONDS - $2 ))")"); }
cpus=$(nproc 2>/dev/null || echo 4); workers=$(( cpus / 2 )); (( workers > 4 )) && workers=4; (( workers < 1 )) && workers=1

# background jobs: each has its own log; wait_job returns the job's status and prints the log's tail when it failed
declare -A PID
start_job() { local name=$1; shift; "$@" > "$LOGS/$name.log" 2>&1 & PID[$name]=$!; }
wait_job() {
  local name=$1 rc=0
  wait "${PID[$name]}" || rc=$?
  if (( rc != 0 )); then echo "drill: $name FAILED (exit $rc); last lines of $LOGS/$name.log:" >&2; tail -n 40 "$LOGS/$name.log" >&2; fi
  return $rc
}

python_tool_tests() {
  set -e
  for gen in $(grep -l -- "--check" packs/*/tools/make_*.py tools/packgen/*/make*.py 2>/dev/null); do python3 "$gen" --check; done   # generated pack content is current
  python3 -m unittest -q tools/samplegen/test_samplegen.py
  python3 -m unittest -q tools/packreg/test_packreg.py
  python3 -m unittest -q tools/test_sutragen.py
  python3 -m unittest -q tools/test_ingest_jsonl.py
  python3 -m unittest -q tools/test_drishti_cli.py
  python3 -m unittest -q tools/test_pack_bundle.py
  python3 -m unittest -q tools/test_packmake.py
  python3 -m unittest -q tools/test_pack_authoring.py
  python3 -m unittest -q tools/test_cli_extras.py
  python3 -m unittest -q tools/test_pack_deploy.py
  if command -v uv >/dev/null; then                  # the lake writers and maintenance need deltalake; uv provides it without installing
    # a hung test fails the drill (timeout exits 124) instead of blocking it: these take seconds, 10 minutes is a hang
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/samplegen/test_layout.py
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/lake/test_maintain.py 2>&1 | grep -v '^{"at"'
  fi
}

# The console suite. A test that fails is run once more on its own, serially: about one run in 700 a workbench page has
# loaded without its script starting (wait() in drishti-console/tests/test_workbench_browser.py reports the page state and,
# after net::ERR_NETWORK_CHANGED, reloads once). A test that passes the second time is a flake: printed and kept in
# target/drill-flakes.log so it is looked at, not hidden; a test that fails twice fails the drill.
# The tests run on pytest-xdist workers, one whole file at a time (--dist loadfile) so a browser file keeps its server and
# browser fixtures; every live server picks its own free port and its own scratch directory (wb_live.py, tmp_path_factory).
console_tests() {
  local targets=("$@"); [[ ${#targets[@]} -gt 0 ]] || targets=(drishti-console/tests)
  if ! $PY -m pytest -q -n "$workers" --dist loadfile "${targets[@]}"; then
    mkdir -p target
    local failed
    failed=$($PY -m pytest -q --co --last-failed -p no:xdist "${targets[@]}" 2>/dev/null | grep '::' || true)
    [[ -n "$failed" ]] || { echo "drill: the console suite failed with no failed test to re-run (a collection error?)" >&2; return 1; }
    echo "drill: re-running the failed console tests once:" >&2; echo "$failed" >&2
    $PY -m pytest -q -p no:xdist --last-failed --last-failed-no-failures none "${targets[@]}" || return 1
    { echo "$(date -Iseconds) $(git log --oneline -1)"; echo "$failed"; } >> target/drill-flakes.log
    echo "drill: FLAKY (failed once, passed again), kept in target/drill-flakes.log" >&2
  fi
}

# Java 25 runs on a copy of the committed tree: its own target/ directories, so it can neither clean nor replace the jar the
# console tests run from the working tree. Docker is hidden from this run: the container tests (PostgreSQL, Kafka, S3 ...
# through Testcontainers) ran on 21 and test the brokers, not the JVM.
java25_verify() {
  set -e
  local copy; copy=$(mktemp -d "${TMPDIR:-/tmp}/drishti-java25.XXXXXX")
  trap 'rm -rf "$copy"' EXIT
  git archive HEAD | tar -x -C "$copy"
  cd "$copy"
  DOCKER_HOST=unix:///nonexistent/drill-java25 TESTCONTAINERS_RYUK_DISABLED=true JAVA_HOME="$JAVA25_HOME" ./mvnw -q -o verify
}

t=$SECONDS
python3 tools/license_headers.py
stage_done "licence headers" $t

if [[ "$MODE" == docs ]]; then
  t=$SECONDS
  start_job tools python_tool_tests
  start_job console console_tests drishti-console/tests/test_docs_*.py drishti-console/tests/test_help*.py drishti-console/tests/test_guide_images.py drishti-console/tests/test_examples.py
  rc=0; wait_job console || rc=1; wait_job tools || rc=1
  (( rc == 0 )) || { echo "drill: FAILED (docs mode)" >&2; exit 1; }
  stage_done "docs console tests | tool tests" $t
else
  # Production runs Java 21: the whole build and every test, containers included, run on a JDK 21.
  JAVA21_HOME="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
  [[ -x "$JAVA21_HOME/bin/java" ]] || { echo "drill: no JDK 21 at $JAVA21_HOME (set JAVA21_HOME)" >&2; exit 1; }
  t=$SECONDS
  JAVA_HOME="$JAVA21_HOME" ./mvnw -q -o verify
  stage_done "Java 21 verify" $t
  # Java 21 and newer are supported: the same tests again on Java 25, so a library that breaks on a newer JVM (Hadoop and the
  # removed Security Manager, Unsafe memory access) fails the drill.
  JAVA25_HOME="${JAVA25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
  t=$SECONDS
  if [[ -x "$JAVA25_HOME/bin/java" ]]; then export JAVA25_HOME; start_job java25 java25_verify
  else echo "drill: no JDK 25 at $JAVA25_HOME (set JAVA25_HOME): the forward-compatibility run is skipped" >&2; fi
  start_job console console_tests
  start_job tools python_tool_tests
  rc=0
  wait_job console || rc=1
  wait_job tools || rc=1
  [[ -z "${PID[java25]:-}" ]] || wait_job java25 || rc=1
  (( rc == 0 )) || { echo "drill: FAILED (logs in $LOGS/)" >&2; exit 1; }
  stage_done "Java 25 | console | tool tests" $t
fi

echo "drill: stages ($MODE mode, $workers console workers):"; printf '  %s\n' "${STAGES[@]}"; echo "  total $(( SECONDS - T0 ))s"
if [[ $PUSH == 0 ]]; then echo "drill: verified, not pushed: $(git log --oneline -1)"; exit 0; fi

git push -q origin develop
git checkout -q main
git merge -q --ff-only develop
git push -q origin main
git checkout -q develop
echo "drilled ($MODE): $(git log --oneline -1)"
