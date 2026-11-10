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
#   tools/drill.sh --gate       the gate only (stage 1), then stop: no lanes, no push; on any branch (a quick check before a drill)
#   tools/drill.sh --affected [--base REF] [--dry-run]   only the Maven modules and test files the diff against REF (default origin/develop) can reach
#                               (tools/drillkit/affected.py; for builders, never a release proof; working-tree edits included; no push)
#   DRILL_MVN_ARGS="..."        extra Maven arguments for every Maven run (e.g. -Ddrishti.test.forks=1 to run the test classes serially)
#   DRILL_BROWSER_WORKERS, DRILL_OTHER_WORKERS   the console lanes' pytest workers (default from the CPUs and the memory; the browser
#                               lane's from the memory available: tools/drillkit/drill_plan.py workers)
#   DRILL_GATE_THREADS          Maven threads of the gate's build (default 1C: one per CPU)
#   DRILL_BROWSER_RERUNS        browser-lane files re-run at a time (default 1); DRILL_LANE_LIMIT a console lane's time limit in seconds
#
# Full drill, in two stages (docs/guides/CLI_REFERENCE.md, "The release drill"):
# 1. The gate, a few minutes, every job at once; the first failure stops the drill with the list:
#    licence headers; the generated documents and data that must be current (settings catalogue, data contracts and their pages,
#    data samples, the packs' generated content, the Python API reference); the docs and help console tests; the repository rules
#    (no hidden names); the quick file checks (every test marked static: they read files and start no server, console or browser,
#    such as the Java launch guard, the asset names, the branding guard, the browser-test rules); and one Java 21 build of the working tree (-P api-docs: Javadoc with doclint; Spotless) that runs only the
#    rule tests: every drishti-it test (architecture, file size, caches, locks, branding, licence, connections, schemas), the
#    token-scope guard and the Arrow answer sample the console reads. That build makes the server jar every later stage uses.
# 2. Everything else at once, each job with its own log under <data>/drill/:
#    - the console suite in two lanes: "browser" (files that drive a browser or start a live stack on the scratch ports; as many
#      workers as the memory available holds, normal priority, handed out by test: --dist loadgroup, a file that keeps a server or
#      state is one group on one worker, a small test marked spread is a unit of its own; docs/architecture/TEST_HARNESS.md) and
#      "other" (the rest; more workers, lower priority, by file), each file ordered longest first from the durations the last runs
#      recorded (<data>/drill/durations.json). The browser lane's memory is sampled (<data>/drill/monitor-browser.csv) and every
#      browser file over the per-file time budget is flagged (<data>/drill/budget-browser.txt; tools/drill-budgets.txt lists the
#      files allowed over it, each with its reason); every page a browser test opens is timed (<data>/drill/pageloads.jsonl), and
#      after the lanes p95 and median per page are printed, slowest first, and written to docs/admin/BENCHMARK.md 18.2;
#    - the Python tool tests;
#    - the Java 21 verify (clean verify -P api-docs, every test, containers included) in a copy of the committed tree, so it
#      cannot touch the jar the console runs; and the Java 25 lane, in a copy too: the whole tree compiled on Java 25 and the tests of
#      what the JDK version can change (tools/drill-java25.txt), started once 20 GB are free.
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

MODE=auto; PUSH=1; KEEP_GOING=0; GATE_ONLY=0
if [[ "${1:-}" == --affected ]]; then
  shift; base=(); dry=()
  while (( $# )); do
    case "$1" in
      --base) base=(--base "$2"); shift 2 ;;
      --base=*) base=(--base "${1#--base=}"); shift ;;
      --dry-run) dry=(--dry-run); shift ;;
      *) echo "drill: --affected takes --base REF and --dry-run" >&2; exit 2 ;;
    esac
  done
  exec python3 tools/drillkit/affected.py run "${base[@]}" "${dry[@]}"
fi
for a in "$@"; do
  case "$a" in
    --gate) GATE_ONLY=1; PUSH=0 ;;
    --docs) MODE=docs ;;
    --full) MODE=full ;;
    --no-push) PUSH=0 ;;
    --keep-going) KEEP_GOING=1 ;;
    *) echo "drill: unknown option $a (use --docs, --full, --no-push, --keep-going, --gate, --affected)" >&2; exit 2 ;;
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
echo "drill: mode = $MODE$([[ $GATE_ONLY == 1 ]] && echo ' (gate only)')$([[ $PUSH == 0 ]] && echo ' (no push)')$([[ $KEEP_GOING == 1 ]] && echo ' (keep going)')"

PY=drishti-console/.venv/bin/python
# a new worktree has no console environment of its own: stop at once with the remedy, not after the gate
if [[ ! -x "$PY" ]]; then
  echo "drill: $PY not found: create the console environment (python3 -m venv drishti-console/.venv, then pip install -r" >&2
  echo "       drishti-console/requirements.txt), or in a worktree link the main checkout's: ln -s <main>/drishti-console/.venv drishti-console/.venv" >&2
  exit 1
fi
DATA="${DRISHTI_DATA_DIR:-data}"; mkdir -p "$DATA/drill"; LOGS=$(cd "$DATA/drill" && pwd -P)
# the previous run's failure logs are kept in runs/<when>/ (the last 20 runs), so a flake's traceback is not lost to the next run
if compgen -G "$LOGS/console-*.txt" >/dev/null || compgen -G "$LOGS/rerun-*.log" >/dev/null || compgen -G "$LOGS/*.log" >/dev/null; then
  prev="$LOGS/runs/$(date -r "$LOGS/durations.json" +%Y%m%d-%H%M%S 2>/dev/null || date +%Y%m%d-%H%M%S)"; mkdir -p "$prev"
  cp -p "$LOGS"/*.log "$LOGS"/console-*.txt "$LOGS"/*.secs "$LOGS"/budget-*.txt "$prev"/ 2>/dev/null || true
  rm -f "$prev/flakes.log"
  ls -1d "$LOGS"/runs/*/ 2>/dev/null | sort | head -n -20 | xargs -r rm -rf
fi
rm -f "$LOGS"/*.secs "$LOGS"/timings-*.json "$LOGS"/rerun-*.log "$LOGS"/console-*.txt "$LOGS"/monitor-*.csv "$LOGS"/budget-*.txt   # this run's own; durations.json and flakes.log stay
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
other_workers=${DRILL_OTHER_WORKERS:-$(clamp $(( cpus / 3 )) 1 8)}
while (( other_workers + 20 > mem_gb && other_workers > 2 )); do other_workers=$(( other_workers - 1 )); done
# the browser lane: (memory available now less the Java verify's 20 GB, the other lane's GB per worker and 4 GB to spare) divided by one
# browser worker's budget, at most the CPU threads less two and the scratch ports' 12 (TEST_HARNESS.md, "Worker sizing")
browser_workers=${DRILL_BROWSER_WORKERS:-$(python3 tools/drillkit/drill_plan.py workers --reserve-gb $(( 20 + other_workers + 4 )) --threads "$cpus")}
export DRISHTI_LAKE_CACHE="${DRISHTI_LAKE_CACHE:-$(pwd -P)/$DATA/tmp/lake-cache}"   # the demo lakes, built once per hash (tools/drillkit/lake_cache.py)
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
    "python3 tools/api_docs.py check-python")          # every public Python function, class and method has a docstring and type hints
  # (tools/test_no_hidden_names.py runs in the tool tests only: its pack-make case needs the server jar, which the gate runs before)
  local gen
  for gen in $(grep -l -- "--check" config/packs/*/*/tools/make_*.py tools/packgen/*/make*.py 2>/dev/null); do checks+=("python3 $gen --check"); done   # generated pack content is current
  all_of "${checks[@]}"
}

# The quick file checks (TEST_HARNESS.md, "Quick file checks"): every test marked static reads files and starts no server, console or
# browser, so it runs here and fails in the gate's first minutes, not hours later in a lane (the console lanes run -m "not static",
# the tools' lanes leave the static tools files out). The console's in one pytest session, each tools file in a process of its own.
gate_checks() {
  local console=() tools=() f out rc=0
  mapfile -t console < <(python3 "$PLAN" static --kind console)
  mapfile -t tools < <(python3 "$PLAN" static --kind tools)
  (( ${#console[@]} > 10 && ${#tools[@]} > 3 )) || { echo "drill: only ${#console[@]} console and ${#tools[@]} tools files of quick checks found" >&2; return 1; }
  console_files checks 4 2 -m static "${console[@]}" || rc=1
  for f in "${tools[@]}"; do
    out="$LOGS/checks-$(basename "$f" .py).txt"
    timeout --kill-after=30 600 "$PY" -m pytest -q -p no:cacheprovider -m static "$f" > "$out" 2>&1 \
      || { echo "drill: FAILED: $f (output in $out)" >&2; tail -n 30 "$out" >&2; rc=1; }
  done
  return $rc
}

# the console tests of the gate, and in the light drill the docs ones; a test that fails runs once more (tools/drillkit/drill_plan.py)
console_files() {   # name, workers, failed files re-run at a time, then the files: pytest-xdist workers, one file per worker at a time
  local name=$1 workers=$2 reruns=$3; shift 3
  local out="$LOGS/console-$name.txt" rc=0 dist=loadfile
  # the browser lane by test: a file's tests stay one group on one worker unless they are marked spread (TEST_HARNESS.md)
  [[ $name == browser ]] && dist=loadgroup
  DRISHTI_PAGELOAD_LOG="$LOGS/pageloads.jsonl" DRILL_MIN_FREE_GB="${DRILL_MIN_FREE_GB:-6}" DRILL_TIMINGS_OUT="$LOGS/timings-$name.json" PYTHONPATH="$PWD/tools/drillkit${PYTHONPATH:+:$PYTHONPATH}" \
    timeout --kill-after=60 "$LANE_LIMIT" "$PY" -m pytest -q -rfE -p drill_timings -n "$workers" --dist "$dist" --no-loadscope-reorder "$@" \
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
# The tools' tests: EVERY test file under tools/ (tools/**/test_*.py, and the tests/ folders as folders), found, not listed: a test
# file nobody named never ran. Each file runs in a pytest process of its own (the tools put themselves on sys.path; a module of one
# must not shadow another's), all in one uv environment (deltalake, pyarrow, PyYAML, pytest, moto for the S3 simulators); a failed
# file fails its lane after every file ran, so one drill shows every failure. Two lanes run side by side (about 15 minutes each
# beside each other): tools-a the tools' own command tests and the test data check, tools-b the tools' folders (lake, generators, public data,
# benchmarks) with three long command tests (schema to pack, the sample pages, the generators). The gate built the exec jar: the tests that start the Java loader run.
tool_test_files() {   # lane (a or b): its test files and folders
  local f all=()
  mapfile -t all < <(find tools \( -name .venv -o -name cache -o -name node_modules \) -prune -o -name 'test_*.py' -print | grep -v '/tests/' | sort)
  local gate; gate=" $(python3 "$PLAN" static --kind tools | tr '\n' ' ') "   # the quick file checks: the gate ran them
  for f in "${all[@]}"; do
    [[ $gate == *" $f "* ]] && continue
    case "$f" in
      tools/test_schemapack.py|tools/test_sample_docs.py|tools/test_generators_write_jsonl.py) [[ $1 == b ]] && echo "$f" ;;   # balancing the lanes
      tools/test_*.py) [[ $1 == a ]] && echo "$f" ;;
      *) [[ $1 == b ]] && echo "$f" ;;
    esac
  done
  if [[ $1 == b ]]; then find tools \( -name .venv -o -name cache \) -prune -o -type d -name tests -print | sort; fi
}
run_tool_tests() {   # files and folders: each in its own pytest process; returns 1 when any failed
  command -v uv >/dev/null || { echo "drill: the tools' tests need uv (it provides deltalake, pyarrow, PyYAML, pytest and moto)" >&2; return 1; }
  timeout --kill-after=60 3600 uv run -q --with deltalake --with pyarrow --with pyyaml --with pytest --with "moto[server]==5.2.3" bash -c '
    rc=0
    for f in "$@"; do
      t=$SECONDS
      timeout --kill-after=30 900 python -m pytest -q -p no:cacheprovider "$f" 2>&1 | grep -v "^{\"at\""
      r=${PIPESTATUS[0]}
      echo "drill: tools $f: $(( SECONDS - t ))s$( (( r == 0 )) || echo " FAILED (exit $r)")"
      (( r == 0 )) || rc=1
    done
    exit $rc' _ "$@"
}
tools_lane_a() {
  set -e
  local files=()
  mapfile -t files < <(tool_test_files a)
  (( ${#files[@]} > 40 )) || { echo "drill: only ${#files[@]} tools test files found for lane a" >&2; return 1; }
  local rc=0
  run_tool_tests "${files[@]}" || rc=1
  python3 tools/testdata/make_testdata.py --check || rc=1     # the committed test data (data/testdata) is what its generator writes
  # the API references (Help -> Developer): the gate checked the Python side; in the full drill every public Java type and member of
  # the Javadoc the gate's build made (-P api-docs, doclint on) has a comment
  if [[ "$MODE" != docs ]]; then python3 tools/api_docs.py check-java || rc=1; fi
  return $rc
}
tools_lane_b() {
  set -e
  local files=()
  mapfile -t files < <(tool_test_files b)
  (( ${#files[@]} > 30 )) || { echo "drill: only ${#files[@]} tools test files found for lane b" >&2; return 1; }
  local rc=0
  run_tool_tests "${files[@]}" || rc=1
  # outside tools/: the compute worker SDK and its engines (pure Python), and the AWS connectors' tool scripts (botocore's Stubber and
  # an in-memory S3, no AWS account)
  timeout --kill-after=30 600 uv run -q --with pytest python -m pytest -q -p no:cacheprovider drishti-compute/src/test/python || rc=1
  timeout --kill-after=30 600 uv run -q --with pytest --with boto3 --with pyarrow --with deltalake python -m pytest -q -p no:cacheprovider deploy/aws/s3-tables deploy/aws/delta-s3 || rc=1
  return $rc
}

# A console lane: its files longest first (the planner's list), then the failed tests once more.
console_lane() {   # name, workers, re-runs at a time
  local name=$1 mon="" rc=0; mapfile -t files < "$LOGS/lane-$1.txt"
  (( ${#files[@]} )) || { echo "drill: console $name: no files"; return 0; }
  files=(-m "not static" "${files[@]}")        # the quick file checks ran in the gate
  if [[ $name == browser ]]; then   # the lane's processes and memory, sampled every 5 s (tools/drillkit/drill_monitor.py)
    python3 tools/drillkit/drill_monitor.py watch --out "$LOGS/monitor-browser.csv" --under $BASHPID & mon=$!
  fi
  console_files "$name" "$2" "$3" "${files[@]}" || rc=$?
  if [[ -n $mon ]]; then
    kill "$mon" 2>/dev/null || true; wait "$mon" 2>/dev/null || true
    echo "drill: browser lane memory: $(python3 tools/drillkit/drill_monitor.py summary "$LOGS/monitor-browser.csv")"
    python3 "$PLAN" budget --timings "$LOGS/timings-browser.json" --exceptions tools/drill-budgets.txt --out "$LOGS/budget-browser.txt" || true
  fi
  return $rc
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
# Java 21 runs beside the browser lane with Docker hidden: containers starting and stopping change the machine's network, and Chromium
# then cancels its requests (ERR_NETWORK_CHANGED). The container tests (@Testcontainers, disabledWithoutDocker) skip here and run
# on their own in java21-docker once the browser lane has finished (TEST_HARNESS.md rule 8).
java21_verify() {
  export DOCKER_HOST=unix:///nonexistent/drill-java21 TESTCONTAINERS_RYUK_DISABLED=true
  copy_verify java21 "$JAVA21_HOME" 20783,20784,20785 check -P api-docs
}
docker_test_classes() {   # every test class that uses Testcontainers, as Maven's -Dtest list
  grep -rl --include='*Test.java' -e 'org.testcontainers' "$REPO" --exclude-dir=.claude --exclude-dir=target 2>/dev/null \
    | sed -E 's#.*/##; s#\.java$##' | sort -u | paste -sd, -
}
java21_docker() {
  after 7200 "$LOGS/console-browser.secs"
  echo "drill: java21-docker starts at $(date +%T)"
  local classes; classes=$(docker_test_classes)
  [[ -n "$classes" ]] || { echo "drill: java21-docker: no container tests found" >&2; return 1; }
  copy_verify java21-docker "$JAVA21_HOME" 21783,21784,21785 none -Dtest="$classes" -Dsurefire.failIfNoSpecifiedTests=false
}
# Java 21 is what production runs; Java 25 checks forward compatibility: the whole tree compiles on it (a removed API fails here) and the tests of
# the code the JDK version can change run on it (native maths and memory access, codecs, the store that relies on Unsafe or the Foreign API: the
# lists in tools/drill-java25.txt, "@class" a test class, "module" every test of a module). It starts once 20 GB are free (the memory budget), not after both heavy
# lanes, at lower priority with two test forks. Docker is hidden from it: the container tests ran on 21 and test the brokers, not the JVM.
java25_tests() {   # Maven's -Dtest list
  local line m out=()
  while read -r line; do
    line=${line%%#*}; line=${line//[[:space:]]/}; [[ -n "$line" ]] || continue
    if [[ $line == @* ]]; then out+=("${line#@}"); else
      [[ -d "$line/src/test/java" ]] || { echo "drill: tools/drill-java25.txt names no module $line" >&2; return 1; }
      while read -r m; do out+=("$(basename "$m" .java)"); done < <(find "$line/src/test/java" -name '*Test.java')
    fi
  done < tools/drill-java25.txt
  (( ${#out[@]} > 5 )) || { echo "drill: the Java 25 lane names only ${#out[@]} tests" >&2; return 1; }
  local IFS=,; echo "${out[*]}"
}
java25_verify() {
  python3 "$PLAN" wait-memory --gb 20 --limit 5400
  echo "drill: java25 starts at $(date +%T)"
  local tests; tests=$(java25_tests)
  export DOCKER_HOST=unix:///nonexistent/drill-java25 TESTCONTAINERS_RYUK_DISABLED=true
  copy_verify java25 "$JAVA25_HOME" 19783,19784,19785 none -Ddrishti.test.forks=2 -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false
}

# ---- clean-up and the working tree's checks ----------------------------------------------------------------------------------
# Every run cleans up after itself, passed or failed: the temporary folders the tests made in its temporary folder during this run
# (drishti-* and pytest's), never older ones, never the console's static cache; and the console files' durations are kept.
DRILL_MARK=$(mktemp "${TMPDIR:-/tmp}/drill-mark.XXXXXX")
drill_cleanup() {
  stop_jobs 2>/dev/null || true
  python3 tools/drillkit/lake_cache.py prune >/dev/null 2>&1 || true
  python3 "$PLAN" merge --durations "$DURATIONS" "$LOGS"/timings-*.json 2>/dev/null || true
  local tmp="${TMPDIR:-/tmp}"
  # || true: a folder already gone (or none made) is not a failure; under set -e it would turn a green drill's exit into 1
  find "$tmp" -maxdepth 1 -name 'drishti-*' ! -name 'drishti-console-static' -newer "$DRILL_MARK" -exec rm -rf {} + 2>/dev/null || true
  chmod -R u+w "$tmp/pytest-of-$(id -un)" 2>/dev/null || true
  find "$tmp/pytest-of-$(id -un)" -mindepth 1 -maxdepth 1 -newer "$DRILL_MARK" -exec rm -rf {} + 2>/dev/null || true
  rm -f "$DRILL_MARK"
  # the tests' compute SDK copies are read-only: give the owner write rights first, or rm -rf leaves them behind
  chmod -R u+w "$DRILL_TMP" "$tmp/pytest-of-$(id -un)" 2>/dev/null || true
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
# Not compared: the drill's own logs (drill/), Maven's temp folder (tmp/maven), the drill's temporary folder (tmp/drill), the cached demo lakes (tmp/lake-cache), the committed test data (testdata/) and the
# local cluster's other nodes (stack/). A server running with this folder as its working folder (your own stack) writes here
# while the tests run, so then the changes are listed as a warning, not failed: the drill cannot tell its files from the tests'.
data_files() {
  [[ -d "$DATA" ]] || return 0
  find "$DATA" \( -path "$DATA/drill" -o -path "$DATA/tmp/maven" -o -path "$DATA/tmp/drill" -o -path "$DATA/tmp/lake-cache" -o -path "$DRILL_TMP" -o -path "$DATA/testdata" -o -path "$DATA/stack" \) -prune \
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
start_job gate-checks gate_checks
gate_jobs=(gate-static gate-docs gate-checks)
if [[ "$MODE" != docs ]]; then start_job gate-java gate_java; gate_jobs+=(gate-java); fi
if ! watch_jobs "${gate_jobs[@]}"; then
  stop_jobs
  echo "drill: FAILED at the gate after $(( SECONDS - t ))s (logs in $LOGS/: ${gate_jobs[*]/%/.log})" >&2; exit 1
fi
stage_done "gate ($(IFS=,; echo "${gate_jobs[*]}"))" $t
if (( GATE_ONLY )); then
  echo "drill: stages (gate only):"; printf '  %s\n' "${STAGES[@]}"; echo "  total $(( SECONDS - T0 ))s"
  echo "drill: the gate passed: $(git log --oneline -1)"; exit 0
fi

t=$SECONDS
if [[ "$MODE" == docs ]]; then
  # the docs console tests the gate did not run (the help page's browser test, the guides' images, the examples)
  docs_rest=(drishti-console/tests/test_help*_browser.py drishti-console/tests/test_guide_images.py drishti-console/tests/test_examples.py)
  start_job tools-a tools_lane_a
  start_job tools-b tools_lane_b
  start_job console-docs console_files docs "$other_workers" 1 "${docs_rest[@]}"
  rc=0; watch_jobs console-docs tools-a tools-b || rc=1
  config_unchanged || rc=1; data_unchanged || rc=1
  (( rc == 0 )) || { echo "drill: FAILED (docs mode; logs in $LOGS/)" >&2; exit 1; }
  stage_done "docs console tests | tool tests" $t
else
  python3 "$PLAN" lanes --durations "$DURATIONS" --out "$LOGS/lanes" --browser-workers "$browser_workers" --other-workers "$other_workers" \
    --exclude "${GATE_CONSOLE[@]}"
  mv "$LOGS/lanes/browser.txt" "$LOGS/lane-browser.txt"; mv "$LOGS/lanes/other.txt" "$LOGS/lane-other.txt"; rmdir "$LOGS/lanes"
  stage2=(console-browser console-other tools-a tools-b java21 java21-docker)
  : > "$LOGS/pageloads.jsonl"                   # each page's load time in the lanes' browser tests (tools/drillkit/pageloads.py)
  start_job console-browser console_lane browser "$browser_workers" "${DRILL_BROWSER_RERUNS:-1}"
  start_job console-other niced 5 console_lane other "$other_workers" 2
  start_job tools-a niced 5 tools_lane_a
  start_job tools-b niced 5 tools_lane_b
  start_job java21 niced 5 java21_verify
  start_job java21-docker niced 5 java21_docker
  if [[ -x "$JAVA25_HOME/bin/java" ]]; then start_job java25 niced 10 java25_verify; stage2+=(java25)
  else echo "drill: no JDK 25 at $JAVA25_HOME (set JAVA25_HOME): the forward-compatibility run is skipped" >&2; fi
  rc=0; watch_jobs "${stage2[@]}" || rc=1
  # p95 and median per page, slowest first; the latest into BENCHMARK.md 18.2 (reported, never failed: the tests' budget is 10 s)
  python3 tools/drillkit/pageloads.py "$LOGS/pageloads.jsonl" --top 25 --write docs/admin/BENCHMARK.md || true
  config_unchanged || rc=1; data_unchanged || rc=1
  (( rc == 0 )) || { echo "drill: FAILED (logs in $LOGS/)" >&2; exit 1; }
  stage_done "console lanes | tools (two lanes) | Java 21 | Java 25" $t
  for n in "${stage2[@]}"; do printf '  %-16s %5ss\n' "$n" "$(cat "$LOGS/$n.secs" 2>/dev/null || echo '?')"; done
  if [[ -s "$LOGS/budget-browser.txt" ]]; then echo "drill: browser files over the per-file budget:"; sed 's/^/  /' "$LOGS/budget-browser.txt"; fi
fi

echo "drill: stages ($MODE mode; console lanes: browser $browser_workers, other $other_workers workers):"; printf '  %s\n' "${STAGES[@]}"; echo "  total $(( SECONDS - T0 ))s"
if [[ $PUSH == 0 ]]; then echo "drill: verified, not pushed: $(git log --oneline -1)"; exit 0; fi

git push -q origin develop
git checkout -q main
git merge -q --ff-only develop
git push -q origin main
git checkout -q develop
echo "drilled ($MODE): $(git log --oneline -1)"
