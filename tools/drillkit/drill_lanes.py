#!/usr/bin/env python3
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
"""The fix drill's lane choice (tools/drill.sh --fix; docs/architecture/TEST_HARNESS.md, "Fix drills").

  drill_lanes.py passed --state FILE --commit SHA LANE...
      Records that these lanes passed whole at this commit (the drill calls it for every lane that passed, in any kind of drill).
  drill_lanes.py failed --state FILE --commit SHA --lane LANE [FILE...]
      Records that a console lane ran whole at this commit and failed only in these test files (its pass is forgotten).
  drill_lanes.py fix --state FILE [--head REF]
      The lanes a fix drill runs, one per line ("lane<TAB>why<TAB>files"): every lane that has not passed at an ancestor of HEAD, and every
      lane that passed at a commit since which a changed path can reach it (LANE_RULES; a path no rule names reaches every lane). A lane
      is left out only when it passed at an ancestor of HEAD and nothing changed since that commit can reach it. ``files`` is "all", or
      for a console lane that ran whole at an ancestor and failed only in some files: those files plus every console test file the
      changes since can reach (tools/drillkit/affected.py, which selects everything for a path it cannot place).
  drill_lanes.py touched --base REF [--head REF]
      The lanes the changes base..head can reach, with the first path that reaches each.

The gate (licence headers, generated documents, quick file checks, the Java build with the rule tests) runs in every drill: it is not a lane.
Standard library only.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

#: every lane of the full drill's second stage (tools/drill.sh), in its order
LANES = ("browser", "other", "tools-a", "tools-b", "java21", "java25", "containers")
ALL = frozenset(LANES)
_JAVA_RUNS = frozenset({"browser", "tools-a", "tools-b", "java21", "java25", "containers"})   # what starts the server jar or the Java code
#: path (regular expression) -> the lanes a change there can reach; a path reaches the union of every rule it matches, and a path no
#: rule matches reaches every lane (the safe default: a new folder is tested everywhere until a rule names it). Measured, not guessed:
#: Java tests read the console's files and the documents (ConsoleFiguresTest, DocumentedSutrasTest, ...) and the tools' tests read
#: both, so a console or documentation change reaches the Java and tools lanes too; only the console's own tests stay in its lanes.
_DOCS_READERS = frozenset({"browser", "other", "tools-a", "tools-b", "java21"})
LANE_RULES: tuple[tuple[str, frozenset[str]], ...] = (
    (r"^tools/drill\.sh$|^tools/drillkit/|^tools/drill-[^/]+\.txt$", ALL),                       # the drill itself
    (r"^docs/|^tools/docs/|\.md$|(^|/)help\.yaml$", _DOCS_READERS),                               # Help, the guides' checks, docs tests
    (r"^drishti-console/tests/", frozenset({"browser", "other"})),
    (r"^drishti-console/(?!tests/)", ALL),
    (r"^deploy/docker/|(^|/)\.dockerignore$|^bin/drishti-node\.sh$", frozenset({"containers"})),
    (r"^deploy/", frozenset({"tools-b"})),                                                         # deploy/aws's tool tests
    (r"^drishti-compute/src/test/python/", frozenset({"tools-b"})),
    (r"^tools/", frozenset({"tools-a", "tools-b", "browser", "other"})),                          # the lake builders the browser files use
    (r"\.java$|(^|/)pom\.xml$|^\.mvn/|^mvnw|/src/main/resources/|/src/test/resources/", _JAVA_RUNS),
    (r"^(drishti-(?!console/)[^/]+|modules|plugins|lib|sdk)/", _JAVA_RUNS),
    (r"^config/|^data/testdata/|^sutras/", ALL),                                                  # what every server and lake reads
    (r"^(drishti-elements|drishti-compute|drishti-stack|clients|examples)/", ALL),                # shared by the console, the tools and Java
    (r"^LICENSE$|^NOTICE|^THIRD-PARTY-NOTICES\.md$|^CHANGELOG\.md$|^RELEASE_NOTES\.md$", frozenset({"other"})),
)


def lanes_for(path: str) -> frozenset[str]:
    """The lanes a change to ``path`` can reach."""
    hit: set[str] = set()
    matched = False
    for pattern, lanes in LANE_RULES:
        if re.search(pattern, path):
            matched = True
            hit |= lanes
    return frozenset(hit) if matched else ALL


def touched(paths: list[str]) -> dict[str, str]:
    """{lane: the first path that reaches it}."""
    out: dict[str, str] = {}
    for p in paths:
        for lane in lanes_for(p):
            out.setdefault(lane, p)
    return out


def _git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=False)


def changed(base: str, head: str = "HEAD") -> list[str] | None:
    """The paths changed between base and head (renames as both names); None when git cannot tell (an unknown commit)."""
    r = _git("diff", "--name-only", "--no-renames", f"{base}..{head}")
    return [p for p in r.stdout.splitlines() if p] if r.returncode == 0 else None


def is_ancestor(commit: str, head: str = "HEAD") -> bool:
    return _git("merge-base", "--is-ancestor", commit, head).returncode == 0


def load_state(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def console_reach(base: str, head: str = "HEAD") -> list[str] | None:
    """The console test files the changes base..HEAD can reach (tools/drillkit/affected.py); None when it selects every one."""
    if head != "HEAD":
        return None
    r = subprocess.run([sys.executable, str(Path(__file__).resolve().parent / "affected.py"), "plan", "--base", base, "--json"],
                       cwd=ROOT, capture_output=True, text=True, check=False)
    try:
        plan = json.loads(r.stdout)
    except ValueError:
        return None
    if r.returncode != 0 or plan.get("everything") or plan.get("all_console"):
        return None
    return sorted(plan.get("console") or [])


def fix_lanes(state: dict, head: str = "HEAD", diff=changed, ancestor=is_ancestor,
              reach=console_reach) -> dict[str, tuple[str, list[str] | None]]:
    """{lane: (why, files)} for every lane a fix drill must run (see the module's docstring); files None: the whole lane."""
    out: dict[str, tuple[str, list[str] | None]] = {}
    for lane in LANES:
        st = state.get(lane) or {}
        at, ran = st.get("passed_at"), st.get("ran_at")
        if not at and ran and st.get("failed_files") and lane in ("browser", "other") and ancestor(ran, head):
            more = reach(ran, head)
            if more is None:
                out[lane] = (f"failed at {ran[:9]}, and the changes since reach every console test", None)
            else:
                files = sorted(set(st["failed_files"]) | set(more))
                out[lane] = (f"failed at {ran[:9]} in {len(st['failed_files'])} file(s); {len(more)} more reached by the changes since", files)
            continue
        if not at:
            out[lane] = ("no recorded pass", None)
            continue
        if not ancestor(at, head):
            out[lane] = (f"its last pass ({at[:9]}) is not an ancestor of {head}", None)
            continue
        paths = diff(at, head)
        if paths is None:
            out[lane] = (f"cannot diff from its last pass ({at[:9]})", None)
            continue
        hit = touched(paths)
        if lane in hit:
            out[lane] = (f"{hit[lane]} changed since its last pass ({at[:9]})", None)
    return out


def cmd_passed(a) -> int:
    path = Path(a.state)
    state = load_state(path)
    for lane in a.lanes:
        if lane not in ALL:
            print(f"drill_lanes: no lane {lane} (lanes: {', '.join(LANES)})", file=sys.stderr)
            return 2
        state[lane] = {"passed_at": a.commit}
    _save(path, state)
    return 0


def _save(path: Path, state: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def cmd_failed(a) -> int:
    path = Path(a.state)
    state = load_state(path)
    state[a.lane] = {"ran_at": a.commit, "failed_files": sorted(set(a.files))} if a.files else {}
    _save(path, state)
    return 0


def cmd_fix(a) -> int:
    for lane, (why, files) in fix_lanes(load_state(Path(a.state)), a.head).items():
        print(f"{lane}\t{why}\t{'all' if files is None else ','.join(files)}")
    return 0


def cmd_touched(a) -> int:
    paths = changed(a.base, a.head)
    if paths is None:
        print(f"drill_lanes: cannot diff {a.base}..{a.head}", file=sys.stderr)
        return 2
    for lane, p in sorted(touched(paths).items(), key=lambda kv: LANES.index(kv[0])):
        print(f"{lane}\t{p}")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="drill_lanes.py", description="The fix drill's lane choice.")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("passed")
    s.add_argument("--state", required=True)
    s.add_argument("--commit", required=True)
    s.add_argument("lanes", nargs="+")
    s.set_defaults(run=cmd_passed)
    s = sub.add_parser("failed")
    s.add_argument("--state", required=True)
    s.add_argument("--commit", required=True)
    s.add_argument("--lane", required=True)
    s.add_argument("files", nargs="*")
    s.set_defaults(run=cmd_failed)
    s = sub.add_parser("fix")
    s.add_argument("--state", required=True)
    s.add_argument("--head", default="HEAD")
    s.set_defaults(run=cmd_fix)
    s = sub.add_parser("touched")
    s.add_argument("--base", required=True)
    s.add_argument("--head", default="HEAD")
    s.set_defaults(run=cmd_touched)
    a = p.parse_args(argv)
    return a.run(a)


if __name__ == "__main__":
    sys.exit(main())
