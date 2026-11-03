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

"""The release drill's console planner (tools/drill.sh; docs/guides/TOOLS.md, "The release drill").

  drill_plan.py lanes  --tests DIR --durations FILE --out DIR [--exclude FILE ...] [--browser-workers N] [--other-workers N]
      Splits the console test files into two lanes and orders each longest first (from the recorded durations):
      browser.txt  the files that drive a browser or start a live stack on the scratch ports (worker_port, a *_stack module)
      other.txt    the rest; they share no scratch port with the browser lane, so the two lanes run side by side safely
  drill_plan.py merge  --durations FILE TIMINGS...
      Folds one run's per-file seconds (tools/drillkit/drill_timings.py) into the recorded durations.
  drill_plan.py rerun  --report FILE... --durations FILE --known FILE --logs DIR --python PY [--parallel N] [--label NAME]
      Runs each failed test once more, file by file, under a time limit per file (twice its recorded duration, at least
      10 minutes); continues past a file that fails again and reports every one. A test listed in the known file is reported
      as accepted and not run again. Exit 0 when every failed test passed the second time or is accepted.

Standard library only: the drill runs it with the console's Python before anything is installed for the tests.
"""
from __future__ import annotations

import argparse
import ast
import json
import os
import re
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# a file's seconds when it has never been measured: a browser file is assumed slow, so it starts early
DEFAULT_BROWSER_SECONDS = 120.0
DEFAULT_OTHER_SECONDS = 15.0
# what the files known to be slow took alone on the release machine, until this machine has measured them itself
SEED_SECONDS = {"drishti-console/tests/test_render_console_browser.py": 1300.0,
                "drishti-console/tests/test_exposure_explorer_browser.py": 700.0}
MIN_LIMIT = 600              # a re-run's time limit is twice the file's recorded duration, never under 10 minutes
UNKNOWN_LIMIT = 1200         # and 20 minutes for a file never measured
COLLECT_ERROR = re.compile(r"^ERROR ([^ :]+\.py)(?: - .*)?$")
FAILED_LINE = re.compile(r"^(?:FAILED|ERROR) ([^ ]+::[^ ]+(?: [^-][^ ]*)*)(?: - .*)?$")
BROWSER_WORDS = re.compile(r"playwright|chromium", re.IGNORECASE)
PORT_WORDS = re.compile(r"worker_ports?\(|PYTEST_XDIST_WORKER")


# ---- durations ---------------------------------------------------------------------------------------------------------------

def load_durations(path: Path, seed: bool = True) -> dict[str, float]:
    """The recorded seconds per test file (repository-relative path) over the seed; only the seed when nothing is recorded yet."""
    out = dict(SEED_SECONDS) if seed else {}
    out.update(_read_files(path))
    return out


def _read_files(path: Path) -> dict[str, float]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    files = data.get("files", data) if isinstance(data, dict) else {}
    return {str(k): float(v) for k, v in files.items() if isinstance(v, (int, float))}


def merge(durations: Path, timings: list[Path]) -> dict[str, float]:
    """Each file's latest measured seconds replace the recorded ones; files not measured this run keep theirs."""
    known = _read_files(durations)
    for t in timings:
        known.update(_read_files(t))
    durations.parent.mkdir(parents=True, exist_ok=True)
    tmp = durations.with_suffix(".tmp")
    tmp.write_text(json.dumps({"updated": time.strftime("%Y-%m-%dT%H:%M:%S"), "files": dict(sorted(known.items()))}, indent=1) + "\n",
                   encoding="utf-8")
    tmp.replace(durations)
    return known


# ---- lanes -------------------------------------------------------------------------------------------------------------------

def _local_imports(path: Path, names: set[str]) -> set[str]:
    """The modules of the tests folder that this file imports (import x, from x import y)."""
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
    except (OSError, SyntaxError, ValueError):
        return set()
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            out |= {a.name.split(".")[0] for a in node.names}
        elif isinstance(node, ast.ImportFrom) and node.module and not node.level:
            out.add(node.module.split(".")[0])
    return out & names


def classify(tests: Path) -> dict[str, bool]:
    """test file name -> True when it belongs in the browser lane: it drives a browser (Playwright, Chromium) or starts something
    on the scratch ports (scratch_ports.worker_port, a module reading PYTEST_XDIST_WORKER), itself or through a helper module it
    imports, at any depth. Two pytest sessions number their workers alike (gw0, gw1, ...), so every file that uses the worker's
    port offset must run in one session."""
    modules = {p.stem: p for p in tests.glob("*.py")}
    text = {name: p.read_text(encoding="utf-8", errors="replace") for name, p in modules.items()}
    direct = {name for name, t in text.items() if BROWSER_WORDS.search(t) or PORT_WORDS.search(t)}
    direct.discard("scratch_ports")          # the helper itself: a file that imports it is caught below
    direct.discard("conftest")               # every file has it; its browser fixtures count where a test asks for them
    imports = {name: _local_imports(p, set(modules) - {"conftest"}) for name, p in modules.items()}
    heavy = set(direct) | {"scratch_ports"}
    changed = True
    while changed:                            # a helper that imports a heavy helper is heavy too
        changed = False
        for name, deps in imports.items():
            if name not in heavy and deps & heavy:
                heavy.add(name)
                changed = True
    return {p.name: (p.stem in heavy or p.name.endswith("_browser.py")) for p in sorted(tests.glob("test_*.py"))}


def lpt_makespan(seconds: list[float], workers: int) -> float:
    """The wall time of these files on this many workers, longest first, each to the worker that frees first."""
    loads = [0.0] * max(1, workers)
    for s in sorted(seconds, reverse=True):
        i = loads.index(min(loads))
        loads[i] += s
    return max(loads)


def plan_lanes(tests: Path, durations: dict[str, float], exclude: set[str]) -> dict[str, list[tuple[str, float]]]:
    """{"browser": [(path, seconds)], "other": [...]}, each longest first; paths relative to the repository."""
    lanes: dict[str, list[tuple[str, float]]] = {"browser": [], "other": []}
    for name, browser in classify(tests).items():
        rel = os.path.relpath(tests / name, ROOT)
        if rel in exclude or name in exclude:
            continue
        secs = durations.get(rel, DEFAULT_BROWSER_SECONDS if browser else DEFAULT_OTHER_SECONDS)
        lanes["browser" if browser else "other"].append((rel, secs))
    for files in lanes.values():
        files.sort(key=lambda f: (-f[1], f[0]))
    return lanes


def cmd_lanes(a) -> int:
    durations = load_durations(Path(a.durations))
    exclude = set()
    for e in a.exclude or []:
        exclude |= {os.path.relpath(Path(p).resolve(), ROOT) for p in sorted(ROOT.glob(e))} if any(c in e for c in "*?[") else {e}
    lanes = plan_lanes(Path(a.tests), durations, exclude)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    workers = {"browser": a.browser_workers, "other": a.other_workers}
    for lane, files in lanes.items():
        (out / f"{lane}.txt").write_text("".join(f"{p}\n" for p, _ in files), encoding="utf-8")
        secs = [s for _, s in files]
        measured = sum(1 for p, _ in files if p in durations)
        top = ", ".join(f"{Path(p).name} {s:.0f}s" for p, s in files[:3])
        print(f"drill: console lane {lane}: {len(files)} files ({measured} measured), {sum(secs):.0f}s of work, about "
              f"{lpt_makespan(secs, workers[lane]):.0f}s on {workers[lane]} workers; longest: {top}")
    return 0


# ---- re-runs -----------------------------------------------------------------------------------------------------------------

def failed_ids(reports: list[Path]) -> list[str]:
    """The failed and errored test ids of these pytest outputs (their -rfE summary), in order, without repeats."""
    seen: dict[str, None] = {}
    for r in reports:
        try:
            lines = r.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for line in lines:
            m = FAILED_LINE.match(line)
            if m:
                seen.setdefault(m.group(1), None)
    return list(seen)


def collection_errors(reports: list[Path]) -> list[str]:
    """The files that could not be collected (an ERROR line naming a file, not a test): nothing of theirs ran, so no re-run helps."""
    out: dict[str, None] = {}
    for r in reports:
        try:
            for line in r.read_text(encoding="utf-8", errors="replace").splitlines():
                m = COLLECT_ERROR.match(line)
                if m:
                    out.setdefault(m.group(1), None)
        except OSError:
            continue
    return list(out)


def load_known(path: Path) -> list[tuple[str, str]]:
    """(pattern, reason) per line `test id or fnmatch pattern | reason`; # starts a comment line."""
    out = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return out
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        pattern, _, reason = line.partition("|")
        out.append((pattern.strip(), reason.strip() or "no reason given"))
    return out


def known_reason(test_id: str, known: list[tuple[str, str]]) -> str | None:
    """The reason a known entry gives for this test, None when none matches. An entry is the test id; * in it matches any text
    (brackets are literal: they belong to parametrised ids)."""
    for pattern, reason in known:
        if test_id == pattern or re.fullmatch(".*".join(re.escape(part) for part in pattern.split("*")), test_id):
            return reason
    return None


def limit_for(file: str, durations: dict[str, float]) -> int:
    secs = durations.get(file)
    return UNKNOWN_LIMIT if secs is None else max(MIN_LIMIT, int(2 * secs))


def _rerun_file(file: str, ids: list[str], limit: int, python: str, logs: Path) -> tuple[str, int, float]:
    log = logs / f"rerun-{Path(file).stem}.log"
    began = time.monotonic()
    with log.open("w", encoding="utf-8") as fh:
        # a browser that dies mid-test leaves Playwright waiting for ever: a hung re-run must fail the drill, not block it
        rc = subprocess.run(["timeout", "--kill-after=30", str(limit), python, "-m", "pytest", "-q", "-rfE", "-p", "no:xdist", *ids],
                            cwd=ROOT, stdout=fh, stderr=subprocess.STDOUT, check=False).returncode
    return file, rc, time.monotonic() - began


def cmd_rerun(a) -> int:
    logs = Path(a.logs)
    logs.mkdir(parents=True, exist_ok=True)
    label = a.label or "console"
    reports = [Path(r) for r in a.report]
    broken = collection_errors(reports)
    if broken:
        print(f"drill: {label}: FAILED: these files could not be collected (their tests never ran):", file=sys.stderr)
        for f in broken:
            print(f"  {f}", file=sys.stderr)
        return 1
    failed = failed_ids(reports)
    if not failed:
        print(f"drill: {label}: the suite failed with no failed test to re-run (a collection error, a crash or the time limit?)",
              file=sys.stderr)
        return 1
    known = load_known(Path(a.known))
    accepted = [(t, known_reason(t, known)) for t in failed if known_reason(t, known) is not None]
    todo = [t for t in failed if known_reason(t, known) is None]
    durations = load_durations(Path(a.durations))
    by_file: dict[str, list[str]] = {}
    for t in todo:
        by_file.setdefault(t.split("::", 1)[0], []).append(t)
    print(f"drill: {label}: {len(failed)} failed the first time; re-running {len(todo)} in {len(by_file)} files once "
          f"({a.parallel} at a time), {len(accepted)} known and accepted", file=sys.stderr)
    for t in todo:
        print(f"  {t}", file=sys.stderr)
    # the longest files first, so the time limits of the slow ones overlap the short ones
    order = sorted(by_file, key=lambda f: -durations.get(f, 0.0))
    results: dict[str, tuple[int, float]] = {}
    with ThreadPoolExecutor(max_workers=max(1, a.parallel)) as pool:
        futures = [pool.submit(_rerun_file, f, by_file[f], limit_for(f, durations), a.python, logs) for f in order]
        for fut in futures:
            f, rc, secs = fut.result()
            results[f] = (rc, secs)
            state = "passed" if rc == 0 else ("TIME LIMIT" if rc in (124, 137) else f"FAILED again (exit {rc})")
            print(f"drill: {label}: re-ran {f} in {secs:.0f}s (limit {limit_for(f, durations)}s): {state}", file=sys.stderr)
    again = {f: rc for f, (rc, _) in results.items() if rc != 0}
    flaky = [t for f, (rc, _) in results.items() if rc == 0 for t in by_file[f]]
    if flaky:
        with (logs / "flakes.log").open("a", encoding="utf-8") as fh:
            head = subprocess.run(["git", "log", "--oneline", "-1"], cwd=ROOT, capture_output=True, text=True, check=False).stdout.strip()
            fh.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S%z')} {head}\n" + "".join(f"{t}\n" for t in flaky))
        print(f"drill: {label}: FLAKY (failed once, passed again), kept in {logs / 'flakes.log'}:", file=sys.stderr)
        for t in flaky:
            print(f"  {t}", file=sys.stderr)
    if accepted:
        print(f"drill: {label}: known and accepted (tools/drill-known.txt), not failing the drill:", file=sys.stderr)
        for t, why in accepted:
            print(f"  {t}\n      {why}", file=sys.stderr)
    if again:
        print(f"drill: {label}: FAILED twice ({len(again)} files; each log under {logs}/rerun-<file>.log):", file=sys.stderr)
        for f, rc in again.items():
            print(f"  {f} (exit {rc}{': the time limit' if rc in (124, 137) else ''})", file=sys.stderr)
            for t in by_file[f]:
                print(f"    {t}", file=sys.stderr)
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="drill_plan.py", description="The release drill's console planner: lanes, durations, re-runs.")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("lanes", help="split the console files into the browser and the other lane, longest first")
    s.add_argument("--tests", default=str(ROOT / "drishti-console" / "tests"))
    s.add_argument("--durations", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--exclude", nargs="*", help="files (or globs) run elsewhere, such as the gate's docs tests")
    s.add_argument("--browser-workers", type=int, default=4)
    s.add_argument("--other-workers", type=int, default=8)
    s.set_defaults(run=cmd_lanes)
    s = sub.add_parser("merge", help="fold one run's per-file seconds into the recorded durations")
    s.add_argument("--durations", required=True)
    s.add_argument("timings", nargs="*")
    s.set_defaults(run=lambda a: (merge(Path(a.durations), [Path(t) for t in a.timings if Path(t).exists()]), 0)[1])
    s = sub.add_parser("rerun", help="run each failed test once more, file by file, and report every outcome")
    s.add_argument("--report", nargs="+", required=True, help="the pytest outputs (with -rfE) whose failures to re-run")
    s.add_argument("--durations", required=True)
    s.add_argument("--known", required=True)
    s.add_argument("--logs", required=True)
    s.add_argument("--python", default=sys.executable)
    s.add_argument("--parallel", type=int, default=1)
    s.add_argument("--label")
    s.set_defaults(run=cmd_rerun)
    a = p.parse_args(argv)
    return a.run(a)


if __name__ == "__main__":
    sys.exit(main())
