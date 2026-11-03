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
#   tools/drill.sh              full drill (or the light one when the commits since origin/develop touch only docs)
#   tools/drill.sh --docs       light drill: the gate's static and docs checks, the other docs console tests, the Python tool tests.
#                               Refused when the commits since origin/develop touch anything but docs/**, *.md, help.yaml, tools/docs/**
#   tools/drill.sh --full       the full drill even when only docs changed
#   tools/drill.sh --no-push    verify only: no push, no merge (the timing of each stage is printed either way)
#   tools/drill.sh --keep-going run every job to its end even after one has failed (the default stops the rest at the first failure)
#   DRILL_MVN_ARGS="..."        extra Maven arguments for every Maven run (e.g. -Ddrishti.test.forks=1 to run the test classes serially)
#   DRILL_BROWSER_WORKERS, DRILL_OTHER_WORKERS   the console lanes' pytest workers (default from the CPUs and the memory)
#   DRILL_GATE_THREADS          Maven threads of the gate's build (default 1C: one per CPU)
#   DRILL_BROWSER_RERUNS        browser-lane files re-run at a time (default 1); DRILL_LANE_LIMIT a console lane's time limit in seconds
#
# Full drill, in two stages (docs/guides/TOOLS.md, "The release drill"):
# 1. The gate, a few minutes, every job at once; the first failure stops the drill with the list:
#    licence headers; the generated documents and data that must be current (settings catalogue, data contracts and their pages,
#    data samples, the packs' generated content, the Python API reference); the docs and help console tests; the repository rules
#    (no hidden names); and one Java 21 build of the working tree (-P api-docs: Javadoc with doclint; Spotless) that runs only the
#    rule tests: every drishti-it test (architecture, file size, caches, locks, branding, licence, connections, schemas), the
#    token-scope guard and the Arrow answer sample the console reads. That build makes the server jar every later stage uses.
# 2. Everything else at once, each job with its own log under <data>/drill/:
#    - the console suite in two lanes: "browser" (files that drive a browser or start a live stack on the scratch ports; fewer
#      workers, they suffer under load; normal priority) and "other" (the rest; more workers, lower priority), each file ordered
#      longest first from the durations the last runs recorded (<data>/drill/durations.json);
#    - the Python tool tests;
#    - the Java 21 verify (clean verify -P api-docs, every test, containers included) in a copy of the committed tree, so it
#      cannot touch the jar the console runs; then the Java 25 verify, in a copy too, once the lighter lanes have finished.
#    A console test that fails is run once more on its own (file by file, every file, under a time limit of twice the file's
#    recorded time); one that passes then is a flake, kept in <data>/drill/flakes.log; one listed in tools/drill-known.txt is
#    reported as accepted and does not fail the drill. Every test that ran before still runs.
set -euo pipefail
shopt -s nullglob
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"   # production runs Java 21; the console tests start the server on it
# No JVM of the drill unpacks native code (DuckDB 60 MB, RocksDB, CatBoost, jansi) into /tmp, whose per-user quota a killed fork
# would fill: ./mvnw starts Maven with -Djava.io.tmpdir=<repo>/data/tmp/maven (in a copy, the copy's own), the test forks get
# <module>/target/tmp (the parent pom's argLine), and the console and tool tests start java through tools/javacmd.py.

MODE=auto; PUSH=1; KEEP_GOING=0
for a in "$@"; do
  case "$a" in
    --docs) MODE=docs ;;
    --full) MODE=full ;;
    --no-push) PUSH=0 ;;
    --keep-going) KEEP_GOING=1 ;;
    *) echo "drill: unknown option $a (use --docs, --full, --no-push, --keep-going)" >&2; exit 2 ;;
  esac
done

[[ $PUSH == 0 || "$(git rev-parse --abbrev-ref HEAD)" == "develop" ]] || { echo "drill: must be on develop" >&2; exit 1; }
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
echo "drill: mode = $MODE$([[ $PUSH == 0 ]] && echo ' (no push)')$([[ $KEEP_GOING == 1 ]] && echo ' (keep going)')"

PY=drishti-console/.venv/bin/python
# a new worktree has no console environment of its own: stop at once with the remedy, not after the gate
if [[ ! -x "$PY" ]]; then
  echo "drill: $PY not found: create the console environment (python3 -m venv drishti-console/.venv, then pip install -r" >&2
  echo "       drishti-console/requirements.txt), or in a worktree link the main checkout's: ln -s <main>/drishti-console/.venv drishti-console/.venv" >&2
  exit 1
fi
DATA="${DRISHTI_DATA_DIR:-data}"; mkdir -p "$DATA/drill"; LOGS=$(cd "$DATA/drill" && pwd -P)
rm -f "$LOGS"/*.secs "$LOGS"/timings-*.json "$LOGS"/rerun-*.log "$LOGS"/console-*.txt   # this run's own; durations.json and flakes.log stay
# Every temporary file of the drill goes to a folder on disk under the data folder (git-ignored with the rest of data/), never the
# system /tmp: on a machine whose /tmp is a RAM disk, parallel suites would fill it and take memory from the test servers.
# DRISHTI_DRILL_TMP names another folder; it is emptied when the drill ends.
DRILL_TMP="${DRISHTI_DRILL_TMP:-$DATA/tmp/drill}"; mkdir -p "$DRILL_TMP"; DRILL_TMP=$(cd "$DRILL_TMP" && pwd -P)
export TMPDIR="$DRILL_TMP"
DURATIONS="$LOGS/durations.json"; KNOWN=tools/drill-known.txt; PLAN=tools/drillkit/drill_plan.py
T0=$SECONDS; STAGES=()
stage_done() { STAGES+=("$(printf '%-44s %5ss' "$1" "$(( SECONDS - $2 ))")"); echo "drill: ${STAGES[-1]}"; }

# Workers. The browser lane is what load breaks (a page that misses a tick under a busy CPU), so it gets few workers and normal
# priority; the other lane's tests run in-process against a fake backend and take many. Memory decides too: a browser worker
# with its server and Chromium needs about 3 GB, another worker about 1 GB, and the Java runs and the tool tests about 20 GB.
cpus=$(nproc 2>/dev/null || echo 4)
mem_gb=$(awk '/^MemTotal:/ { print int($2 / 1048576) }' /proc/meminfo 2>/dev/null || echo 16)
clamp() { local v=$1; (( v < $2 )) && v=$2; (( v > $3 )) && v=$3; echo "$v"; }
browser_workers=${DRILL_BROWSER_WORKERS:-$(clamp $(( cpus / 6 )) 1 4)}
other_workers=${DRILL_OTHER_WORKERS:-$(clamp $(( cpus / 3 )) 1 8)}
while (( 3 * browser_workers + other_workers + 20 > mem_gb && other_workers > 2 )); do other_workers=$(( other_workers - 1 )); done
while (( 3 * browser_workers + other_workers + 20 > mem_gb && browser_workers > 1 )); do browser_workers=$(( browser_workers - 1 )); done
LANE_LIMIT=${DRILL_LANE_LIMIT:-10800}   # a console lane that runs three hours has hung: it fails the drill instead of blocking it
echo "drill: $cpus CPUs, ${mem_gb} GB: console lanes browser $browser_workers workers, other $other_workers workers"

# ---- jobs --------------------------------------------------------------------------------------------------------------------
# Each job runs in the background with its own log; watch_jobs waits for a set of them and, at the first failure (unless
# --keep-going), stops the others with everything they started (test servers, browsers, Maven forks) and fails the drill.
# The job runs in a subshell of its own with errexit on and its status read afterwards, never as `job || ...`: bash ignores
# errexit inside anything run on the left of || or &&, so there every command but the last could fail unseen.
declare -A PID RC
start_job() {
  local name=$1 began=$SECONDS; shift
  ( set +e; ( set -e; "$@" ); rc=$?; echo $(( SECONDS - began )) > "$LOGS/$name.secs"; exit $rc ) > "$LOGS/$name.log" 2>&1 &
  PID[$name]=$!
}
niced() { renice -n "$1" -p $BASHPID >/dev/null; shift; "$@"; }   # a job at lower priority, with all it starts
kill_tree() {   # signal, pid: the process and every descendant, the children first
  local c
  for c in $(pgrep -P "$2" 2>/dev/null || true); do kill_tree "$1" "$c"; done
  kill "-$1" "$2" 2>/dev/null || true
}
stop_jobs() {
  local n live=0
  for n in "${!PID[@]}"; do [[ -n "${RC[$n]:-}" ]] || live=1; done
  (( live )) || return 0
  for n in "${!PID[@]}"; do [[ -n "${RC[$n]:-}" ]] || { echo "drill: stopping $n" >&2; kill_tree TERM "${PID[$n]}"; }; done
  sleep 5
  for n in "${!PID[@]}"; do [[ -n "${RC[$n]:-}" ]] || kill_tree KILL "${PID[$n]}"; done
  for n in "${!PID[@]}"; do [[ -n "${RC[$n]:-}" ]] || { wait "${PID[$n]}" 2>/dev/null || true; RC[$n]=stopped; }; done
}
job_report() {
  local name=$1 rc=$2
  echo "drill: $name took $(cat "$LOGS/$name.secs" 2>/dev/null || echo '?')s ($([[ $rc == 0 ]] && echo passed || echo FAILED))"
  if [[ $rc != 0 ]]; then echo "drill: $name FAILED (exit $rc); last lines of $LOGS/$name.log:" >&2; tail -n 60 "$LOGS/$name.log" >&2; fi
}
watch_jobs() {   # the jobs to wait for; returns 1 when any failed
  local failed=0 running n rc
  while :; do
    running=0
    for n in "$@"; do
      [[ -n "${RC[$n]:-}" ]] && continue
      if kill -0 "${PID[$n]}" 2>/dev/null; then running=1; continue; fi
      rc=0; wait "${PID[$n]}" || rc=$?; RC[$n]=$rc
      job_report "$n" "$rc"
      if (( rc != 0 )); then
        failed=1
        if (( ! KEEP_GOING )); then echo "drill: $n failed: stopping the other jobs (--keep-going runs them to the end)" >&2; stop_jobs; return 1; fi
      fi
    done
    (( running )) || break
    sleep 5
  done
  return $failed
}
after() {   # seconds at most, then files: returns once every file before the "," exists, or any file after it
  local limit=$1 began=$SECONDS f ok mode=all; shift
  local all=() any=()
  for f in "$@"; do if [[ "$f" == , ]]; then mode=any; elif [[ $mode == all ]]; then all+=("$f"); else any+=("$f"); fi; done
  while (( SECONDS - began < limit )); do
    ok=1; for f in "${all[@]}"; do [[ -e "$f" ]] || ok=0; done
    (( ok )) && return 0
    for f in "${any[@]}"; do [[ -e "$f" ]] && return 0; done
    sleep 10
  done
}

# ---- the gate ----------------------------------------------------------------------------------------------------------------
# The docs and help console tests the gate runs (no browser, no server); the lanes leave them out.
GATE_CONSOLE=(drishti-console/tests/test_docs_*.py drishti-console/tests/test_help.py drishti-console/tests/test_help_links.py
              drishti-console/tests/test_help_configuration.py drishti-console/tests/test_help_developer.py
              drishti-console/tests/test_tools_guide.py)

all_of() {   # runs each argument (a command line) at once and waits for all; fails naming each that failed
  local cmd pids=() cmds=() i bad=0
  for cmd in "$@"; do bash -c "$cmd" & pids+=($!); cmds+=("$cmd"); done
  for i in "${!pids[@]}"; do wait "${pids[$i]}" || { echo "drill: FAILED: ${cmds[$i]}" >&2; bad=1; }; done
  return $bad
}
gate_static() {
  local checks=(
    "python3 tools/license_headers.py"
    "python3 tools/settings_catalogue.py --check"      # CONFIGURATION.md's every setting: each key has a meaning, every variable indexed, current
    "python3 tools/contracts.py check"                 # every command has a data contract that matches its pack and data
    "python3 tools/contracts.py docs --check"          # the contracts' pages are current
    "python3 tools/testdata/sample_docs.py --check"    # the data samples' pages are current
    "python3 tools/api_docs.py check-python"           # every public Python function, class and method has a docstring and type hints
    "python3 -m unittest -q tools/test_no_hidden_names.py")   # again in the tool tests, where the server jar is there for its pack-make case
  local gen
  for gen in $(grep -l -- "--check" config/packs/*/tools/make_*.py tools/packgen/*/make*.py 2>/dev/null); do checks+=("python3 $gen --check"); done   # generated pack content is current
  all_of "${checks[@]}"
}

# the console tests of the gate, and in the light drill the docs ones; a test that fails runs once more (tools/drillkit/drill_plan.py)
console_files() {   # name, workers, failed files re-run at a time, then the files: pytest-xdist workers, one file per worker at a time
  local name=$1 workers=$2 reruns=$3; shift 3
  local out="$LOGS/console-$name.txt" rc=0
  DRILL_TIMINGS_OUT="$LOGS/timings-$name.json" PYTHONPATH="$PWD/tools/drillkit${PYTHONPATH:+:$PYTHONPATH}" \
    timeout --kill-after=60 "$LANE_LIMIT" "$PY" -m pytest -q -rfE -p drill_timings -n "$workers" --dist loadfile --no-loadscope-reorder "$@" \
    > "$out" 2>&1 || rc=$?
  tail -n 5 "$out"
  (( rc == 0 )) && return 0
  if (( rc != 1 )); then echo "drill: console $name stopped with exit $rc (124: the time limit; 2: interrupted; 3: internal error; 4: usage)" >&2; return 1; fi
  python3 "$PLAN" rerun --label "console $name" --report "$out" --durations "$DURATIONS" --known "$KNOWN" --logs "$LOGS" \
    --python "$PY" --parallel "$reruns"
}

# One Java 21 build of the working tree: compiles every module once (Error Prone, Javadoc with doclint, Spotless, the enforcer's
# bytecode rule), packages the jars the console and the tool tests start, and runs only the rule tests. Maven threads build the
# modules side by side; nothing is installed into ~/.m2.
gate_java() {
  set -e
  local rules; rules=$(cd drishti-it/src/test/java && find . -name '*Test.java' -printf '%f\n' | sed 's/\.java$//' | sort | paste -sd, -)
  JAVA_HOME="$JAVA21_HOME" ./mvnw -q -o clean package spotless:check -P api-docs -T "${DRILL_GATE_THREADS:-1C}" \
    -Dtest="$rules,TokenScopeGuardTest,ArrowAnswersTest" -Dsurefire.failIfNoSpecifiedTests=false ${DRILL_MVN_ARGS:-}
  compgen -G 'drishti-server/target/drishti-server-*-exec.jar' >/dev/null || { echo "drill: the gate's build made no server jar" >&2; return 1; }
  # a filter that matched nothing would pass in silence: every rule test must have its report
  local c missing=()
  for c in ${rules//,/ } TokenScopeGuardTest ArrowAnswersTest; do
    compgen -G "*/target/surefire-reports/TEST-*.$c.xml" >/dev/null || missing+=("$c")
  done
  (( ${#missing[@]} == 0 )) || { echo "drill: the gate's build ran no tests of: ${missing[*]}" >&2; return 1; }
}

# ---- stage two ---------------------------------------------------------------------------------------------------------------
python_tool_tests() {
  set -e
  python3 -m unittest -q tools/samplegen/test_samplegen.py
  python3 -m unittest -q tools/packreg/test_packreg.py
  python3 -m unittest -q tools/test_sutragen.py
  python3 -m unittest -q tools/test_ingest_jsonl.py
  python3 -m unittest -q tools/test_convert.py         # drishti.py convert: CSV, JSON arrays and .json folders into JSON Lines
  python3 -m unittest -q tools/test_generators_write_jsonl.py tools/test_exports_offer_jsonl.py   # the data standard: generators write JSONL, exports offer it
  python3 -m unittest -q tools/test_drishti_cli.py
  python3 -m unittest -q tools/test_pack_bundle.py tools/test_no_hidden_names.py
  python3 -m unittest -q tools/test_packmake.py
  python3 -m unittest -q tools/test_pack_authoring.py
  python3 -m unittest -q tools/test_cli_extras.py
  python3 -m unittest -q tools/bench/test_net_soak.py   # the Drishti*net soak's checker (history, kill windows, memory, verdict): no servers
  python3 -m unittest -q tools/test_pack_deploy.py
  python3 -m unittest -q tools/test_deploy_aws.py      # deploy/aws: --help and --dry-run of every script (a fake aws fails if called), templates
  python3 -m unittest -q tools/test_cli_bi.py
  python3 -m unittest -q tools/test_cli_bitransform.py
  python3 -m unittest -q tools/test_cli_connectors.py
  python3 -m unittest -q tools/test_cli_loads.py
  python3 -m unittest -q tools/test_pack_form.py
  python3 -m unittest -q tools/test_schemapack.py
  python3 -m unittest -q tools/test_contracts.py        # the data contract of every command: valid, current with its pack and data, pages current
  python3 tools/testdata/make_testdata.py --check     # the committed test data (data/testdata) is what its generator writes
  python3 -m unittest -q tools/test_api_docs.py        # the API references' checks
  # the API references (Help -> Developer): the gate checked the Python side; in the full drill every public Java type and member of
  # the Javadoc the gate's build made (-P api-docs, doclint on) has a comment
  if [[ "$MODE" != docs ]]; then python3 tools/api_docs.py check-java; fi
  python3 -m unittest -q tools/testdata/test_testdata.py
  python3 -m unittest -q tools/test_sizing_planner.py   # the sizing planner: formulas over named inputs, documented keys, the committed workbook current
  python3 -m unittest -q tools/test_capacitycli.py      # drishti.py admin capacity: the server's planner evaluated here, --set, --live, --xlsx
  if command -v uv >/dev/null; then                  # the lake writers and maintenance need deltalake; uv provides it without installing
    # a hung test fails the drill (timeout exits 124) instead of blocking it: these take seconds, 10 minutes is a hang
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/samplegen/test_layout.py
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/lake/test_maintain.py tools/lake/test_maintain_admin.py 2>&1 | grep -v '^{"at"'
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/lake/test_demo.py
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/lake/test_names.py   # the bank's names
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml python -m unittest -q tools/lake/test_publish.py
    # the lake tools on S3 simulators (Delta on S3, lake publish's conditional-PUT commit): moto, and the MinIO server binary
    # on Linux (DRISHTI_MINIO_BIN or tools/cache/minio; it skips, printing why, when no binary can be had). Each prints which
    # conditional writes its simulator covered. The Java S3Mock and MinIO suites run in the Java verify.
    timeout --kill-after=30 600 uv run -q --with deltalake --with pyarrow --with pyyaml --with "moto[server]==5.2.3" python -m unittest -q tools/lake/test_s3_simulators.py 2>&1 | grep -v '^{"at"'
    # the real-data tools (tools/realdata) are pytest-style functions on recorded fixtures: no network, a few seconds
    timeout --kill-after=30 600 uv run -q --with pytest --with pyyaml python -m pytest -q -p no:cacheprovider tools/realdata/tests
    # the compute worker SDK and its engines: pure Python, no servers
    timeout --kill-after=30 600 uv run -q --with pytest python -m pytest -q -p no:cacheprovider drishti-compute/src/test/python
    # the AWS connectors' tool scripts (S3 Tables, Delta on S3): botocore's Stubber and an in-memory S3, no AWS account
    timeout --kill-after=30 600 uv run -q --with pytest --with boto3 --with pyarrow --with deltalake python -m pytest -q -p no:cacheprovider deploy/aws/s3-tables deploy/aws/delta-s3
  fi
}

# A console lane: its files longest first (the planner's list), then the failed tests once more.
console_lane() {   # name, workers, re-runs at a time
  local name=$1; mapfile -t files < "$LOGS/lane-$1.txt"
  (( ${#files[@]} )) || { echo "drill: console $name: no files"; return 0; }
  console_files "$name" "$2" "$3" "${files[@]}"
}

# A Java verify in a copy of the committed tree: its own target/ directories, so it can neither clean nor replace the jar the
# console tests run from the working tree, and its own Drishti*net test ports. The copy lives outside the repository (beside it;
# DRISHTI_DRILL_COPY_DIR names another folder): inside it, tests that tell a moved table from its original place would see the
# copy's paths start with the original's. A failed test runs once more (surefire's rerunFailingTestsCount: the console lanes
# share the machine); one that passes the second time is listed as a flake here and in flakes.log, not hidden.
REPO=$(pwd -P)
COPY_BASE="${DRISHTI_DRILL_COPY_DIR:-$(cd .. && pwd -P)/$(basename "$REPO")-drill-copies}"
copy_verify() {   # name, JDK, Drishti*net test ports, whether the copy's data/ and config/ are checked; then Maven arguments
  set -e
  local name=$1 jdk=$2 ports=$3 check=$4; shift 4
  mkdir -p "$COPY_BASE"
  local copy; copy=$(mktemp -d "$COPY_BASE/$name.$$.XXXXXX")
  trap "rm -rf '$copy'; rmdir '$COPY_BASE' 2>/dev/null || true" EXIT
  trap "exit 143" TERM
  git archive HEAD | tar -x -C "$copy"
  cd "$copy"
  # the copy is not a repository: git must not find one above it (a worktree's copy sits under the main checkout's ignored .claude/,
  # where git would call every copied file ignored and the repository-wide scans would see none)
  export GIT_CEILING_DIRECTORIES="$COPY_BASE"
  # what the tests may not leave in data/ and config/ (as in the working tree, below): every file there before, by its content
  tree_files() { { find config data \( -path data/tmp/maven -o -path data/drill -o -path data/testdata -o -path data/stack \) -prune \
                     -o -type f -exec md5sum {} + 2>/dev/null || true; } | awk '{ h = $1; sub(/^[^ ]+  /, ""); print $0 "\t" h }' | sort; }
  local before; before=$(tree_files)
  local rc=0
  DRISHTI_NET_IT_PORTS="$ports" JAVA_HOME="$jdk" ./mvnw -q -o clean verify -Dsurefire.rerunFailingTestsCount=1 "$@" ${DRILL_MVN_ARGS:-} || rc=$?
  local flaky
  flaky=$(grep -l -E '<flaky(Failure|Error)' $(find . -path '*/target/surefire-reports/TEST-*.xml' 2>/dev/null) /dev/null 2>/dev/null \
          | sed -E 's|.*/TEST-(.*)\.xml|\1|' | sort -u || true)
  if [[ -n "$flaky" ]]; then
    echo "drill: $name: FLAKY (failed once, passed again):"; printf '  %s\n' $flaky
    { echo "$(date -Iseconds) $name $(git -C "$REPO" log --oneline -1)"; printf '%s\n' $flaky; } >> "$LOGS/flakes.log"
  fi
  (( rc == 0 )) || return $rc
  if [[ "$check" == check ]]; then
    local added; added=$(comm -13 <(printf '%s\n' "$before") <(tree_files) | cut -f1 | grep -v '^$' || true)
    if [[ -n "$added" ]]; then
      echo "drill: FAILED: the $name tests wrote these files under data/ or config/ (a test writes to its own scratch folder):" >&2
      printf '  %s\n' $added >&2; return 1
    fi
  fi
}

# Java 21 in a copy, every test and the containers (Testcontainers), as production runs; its cluster tests on their own ports.
java21_verify() { copy_verify java21 "$JAVA21_HOME" 20783,20784,20785 check -P api-docs; }
# Java 21 and newer are supported: the same tests again on Java 25, so a library that breaks on a newer JVM (Hadoop and the
# removed Security Manager, Unsafe memory access) fails the drill. It starts once the other console lane and the tool tests have
# finished, or Java 21 has (the lighter load the browser lane then shares), at lower priority, with two test forks. Docker is
# hidden from it: the container tests ran on 21 and test the brokers, not the JVM.
java25_verify() {
  after 3600 "$LOGS/console-other.secs" "$LOGS/tools.secs" , "$LOGS/java21.secs"
  echo "drill: java25 starts at $(date +%T)"
  export DOCKER_HOST=unix:///nonexistent/drill-java25 TESTCONTAINERS_RYUK_DISABLED=true
  copy_verify java25 "$JAVA25_HOME" 19783,19784,19785 none -Ddrishti.test.forks=2
}

# ---- clean-up and the working tree's checks ----------------------------------------------------------------------------------
# Every run cleans up after itself, passed or failed: the temporary folders the tests made in its temporary folder during this run
# (drishti-* and pytest's), never older ones, never the console's static cache; and the console files' durations are kept.
DRILL_MARK=$(mktemp "${TMPDIR:-/tmp}/drill-mark.XXXXXX")
drill_cleanup() {
  stop_jobs 2>/dev/null || true
  python3 "$PLAN" merge --durations "$DURATIONS" "$LOGS"/timings-*.json 2>/dev/null || true
  local tmp="${TMPDIR:-/tmp}"
  find "$tmp" -maxdepth 1 -name 'drishti-*' ! -name 'drishti-console-static' -newer "$DRILL_MARK" -exec rm -rf {} + 2>/dev/null
  find "$tmp/pytest-of-$(id -un)" -mindepth 1 -maxdepth 1 -newer "$DRILL_MARK" -exec rm -rf {} + 2>/dev/null
  rm -f "$DRILL_MARK"
  find "$DRILL_TMP" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
  rm -rf "$COPY_BASE"/*."$$".* 2>/dev/null; rmdir "$COPY_BASE" 2>/dev/null || true   # a copy whose job was stopped
}
trap drill_cleanup EXIT
trap 'echo "drill: interrupted" >&2; exit 130' INT TERM

# The tests keep the repository's config/ as they found it: each writes connector files, packs and settings to its own scratch
# folder. What is under config/ (untracked or git-ignored) before the tests is listed; a file that appears there during the run
# fails the drill with its name.
config_files() { git status --porcelain --ignored --untracked-files=all -- config/ | sort; }
CONFIG_BEFORE=$(config_files)
config_unchanged() {
  local added; added=$(comm -13 <(printf '%s\n' "$CONFIG_BEFORE") <(config_files) | sed -E 's/^(\?\?|!!) //')
  [[ -z "$added" ]] && return 0
  echo "drill: FAILED: the tests left these files under config/ (a test writes to its own scratch folder):" >&2
  printf '%s\n' "$added" | sed 's/^/  /' >&2
  return 1
}

# The same for the data folder: no test writes to it (the sutra command line, too, writes to a scratch folder of its own). Every
# file under it (name, time, size) is listed before the tests; one added or changed during the run fails the drill with its name.
# Not compared: the drill's own logs (drill/), Maven's temp folder (tmp/maven), the drill's temporary folder (tmp/drill), the committed test data (testdata/) and the
# local cluster's other nodes (stack/). A server running with this folder as its working folder (your own stack) writes here
# while the tests run, so then the changes are listed as a warning, not failed: the drill cannot tell its files from the tests'.
data_files() {
  [[ -d "$DATA" ]] || return 0
  find "$DATA" \( -path "$DATA/drill" -o -path "$DATA/tmp/maven" -o -path "$DATA/tmp/drill" -o -path "$DRILL_TMP" -o -path "$DATA/testdata" -o -path "$DATA/stack" \) -prune \
    -o -type f -printf '%P\t%T@\t%s\n' 2>/dev/null | sort
}
servers_here() {   # java processes whose working folder is this repository (a server started from the IDE or by drishti-stack)
  local here p; here=$(pwd -P)
  for p in /proc/[0-9]*; do
    [[ "$(readlink "$p/cwd" 2>/dev/null)" == "$here" && "$(cat "$p/comm" 2>/dev/null)" == java ]] && echo "${p#/proc/}"
  done
  return 0
}
DATA_BEFORE=$(data_files)
SERVERS_HERE=$(servers_here | tr '\n' ' ')
data_unchanged() {
  local changed; changed=$(comm -13 <(printf '%s\n' "$DATA_BEFORE") <(data_files) | cut -f1 | grep -v '^$' || true)
  [[ -z "$changed" ]] && return 0
  if [[ -n "${SERVERS_HERE// /}" ]]; then
    echo "drill: warning: files under $DATA/ changed during the tests, while a server ran from this folder (pid $SERVERS_HERE);" \
         "these are its files or a test's:" >&2
    printf '%s\n' "$changed" | head -n 30 | sed "s|^|  $DATA/|" >&2
    return 0
  fi
  echo "drill: FAILED: the tests wrote these files under $DATA/ (a test writes to its own scratch folder):" >&2
  printf '%s\n' "$changed" | sed "s|^|  $DATA/|" >&2
  return 1
}

# ---- the run -----------------------------------------------------------------------------------------------------------------
JAVA21_HOME="${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JAVA25_HOME="${JAVA25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
if [[ "$MODE" != docs ]]; then
  [[ -x "$JAVA21_HOME/bin/java" ]] || { echo "drill: no JDK 21 at $JAVA21_HOME (set JAVA21_HOME)" >&2; exit 1; }
fi

t=$SECONDS
start_job gate-static gate_static
start_job gate-docs console_files gate 4 2 "${GATE_CONSOLE[@]}"
gate_jobs=(gate-static gate-docs)
if [[ "$MODE" != docs ]]; then start_job gate-java gate_java; gate_jobs+=(gate-java); fi
if ! watch_jobs "${gate_jobs[@]}"; then
  stop_jobs
  echo "drill: FAILED at the gate after $(( SECONDS - t ))s (logs in $LOGS/: ${gate_jobs[*]/%/.log})" >&2; exit 1
fi
stage_done "gate ($(IFS=,; echo "${gate_jobs[*]}"))" $t

t=$SECONDS
if [[ "$MODE" == docs ]]; then
  # the docs console tests the gate did not run (the help page's browser test, the guides' images, the examples)
  docs_rest=(drishti-console/tests/test_help*_browser.py drishti-console/tests/test_guide_images.py drishti-console/tests/test_examples.py)
  start_job tools python_tool_tests
  start_job console-docs console_files docs "$other_workers" 1 "${docs_rest[@]}"
  rc=0; watch_jobs console-docs tools || rc=1
  config_unchanged || rc=1; data_unchanged || rc=1
  (( rc == 0 )) || { echo "drill: FAILED (docs mode; logs in $LOGS/)" >&2; exit 1; }
  stage_done "docs console tests | tool tests" $t
else
  python3 "$PLAN" lanes --durations "$DURATIONS" --out "$LOGS/lanes" --browser-workers "$browser_workers" --other-workers "$other_workers" \
    --exclude "${GATE_CONSOLE[@]}"
  mv "$LOGS/lanes/browser.txt" "$LOGS/lane-browser.txt"; mv "$LOGS/lanes/other.txt" "$LOGS/lane-other.txt"; rmdir "$LOGS/lanes"
  stage2=(console-browser console-other tools java21)
  start_job console-browser console_lane browser "$browser_workers" "${DRILL_BROWSER_RERUNS:-1}"
  start_job console-other niced 5 console_lane other "$other_workers" 2
  start_job tools niced 5 python_tool_tests
  start_job java21 niced 5 java21_verify
  if [[ -x "$JAVA25_HOME/bin/java" ]]; then start_job java25 niced 10 java25_verify; stage2+=(java25)
  else echo "drill: no JDK 25 at $JAVA25_HOME (set JAVA25_HOME): the forward-compatibility run is skipped" >&2; fi
  rc=0; watch_jobs "${stage2[@]}" || rc=1
  config_unchanged || rc=1; data_unchanged || rc=1
  (( rc == 0 )) || { echo "drill: FAILED (logs in $LOGS/)" >&2; exit 1; }
  stage_done "console lanes | tools | Java 21 | Java 25" $t
  for n in "${stage2[@]}"; do printf '  %-16s %5ss\n' "$n" "$(cat "$LOGS/$n.secs" 2>/dev/null || echo '?')"; done
fi

echo "drill: stages ($MODE mode; console lanes: browser $browser_workers, other $other_workers workers):"; printf '  %s\n' "${STAGES[@]}"; echo "  total $(( SECONDS - T0 ))s"
if [[ $PUSH == 0 ]]; then echo "drill: verified, not pushed: $(git log --oneline -1)"; exit 0; fi

git push -q origin develop
git checkout -q main
git merge -q --ff-only develop
git push -q origin main
git checkout -q develop
echo "drilled ($MODE): $(git log --oneline -1)"
