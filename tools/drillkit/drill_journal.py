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

"""The drill's journal (tools/drill.sh): one line per finished test, so a killed drill resumes instead of starting over.

``<data>/drill/journal.jsonl`` (DRILL_JOURNAL), one line per test as it finishes:
``{"lane", "id", "outcome", "seconds", "commit", "key", "module"}``. id is a pytest node id or a Java ``class#method``; key is a hash of the
test's own files (a pytest test: its file and the conftest.py files above it; a Java class: its source file and its module's pom.xml), so a
test that changed never counts as done. Lines are appended with O_APPEND in one write each, safe for many writers.

Commands: ``prune`` (drop the lines of other commits), ``snapshot`` (the passed tests of a lane at a commit whose key still matches, for
the pytest plugin's --deselect), ``java-watch`` / ``java-scan`` (Surefire's TEST-*.xml of a tree into the journal as classes finish),
``java-done`` (the classes whose every method passed: the exclusion file, or a -Dtest list less those classes), ``summary``.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def append(path: str, rec: dict) -> None:
    """One line, one write, O_APPEND: lines of concurrent writers never interleave."""
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o644)
    try:
        os.write(fd, (json.dumps(rec, separators=(",", ":")) + "\n").encode("utf-8"))
    finally:
        os.close(fd)


def read(path: str) -> list[dict]:
    out = []
    try:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            try:
                out.append(json.loads(line))
            except ValueError:
                pass   # a torn last line of a killed run
    except OSError:
        pass
    return out


def hash_files(paths) -> str:
    h = hashlib.sha1()
    for p in paths:
        try:
            h.update(Path(p).read_bytes())
        except OSError:
            h.update(b"-")
    return h.hexdigest()[:16]


def py_key(root: Path, nodeid: str) -> str:
    """The test file and the conftest.py files from its folder up to the root."""
    f = (root / nodeid.split("::", 1)[0]).resolve()
    files = [f]
    d = f.parent
    while True:
        files.append(d / "conftest.py")
        if d == root.resolve() or d == d.parent:
            break
        d = d.parent
    return hash_files(files)


def top_class(classname: str) -> str:
    return classname.split("$", 1)[0]


def java_key(tree: Path, module: str, classname: str) -> str:
    top = top_class(classname)
    src = tree / module / "src" / "test" / "java" / (top.replace(".", "/") + ".java")
    return hash_files([src, tree / module / "pom.xml"])


def java_records(tree: Path, lane: str, commit: str, xml: Path):
    try:
        root = ET.parse(xml).getroot()
    except (ET.ParseError, OSError):
        return None   # being written: the next scan
    module = str(xml.parent.parent.parent.relative_to(tree))
    for tc in root.iter("testcase"):
        cls = tc.get("classname") or root.get("name") or xml.stem
        bad = any(tc.find(t) is not None for t in ("failure", "error"))
        skipped = tc.find("skipped") is not None
        yield {"lane": lane, "id": f"{cls}#{tc.get('name')}", "outcome": "failed" if bad else "skipped" if skipped else "passed",
               "seconds": round(float(tc.get("time") or 0), 2), "commit": commit,
               "key": java_key(tree, module, cls), "module": module}


def scan(tree: Path, lane: str, commit: str, journal: str, seen: dict) -> int:
    n = 0
    for xml in tree.glob("*/*/target/surefire-reports/TEST-*.xml"):
        try:
            m = xml.stat().st_mtime
        except OSError:
            continue
        if seen.get(xml) == m:
            continue
        recs = java_records(tree, lane, commit, xml)
        if recs is None:
            continue
        seen[xml] = m
        for r in recs:
            append(journal, r)
            n += 1
    return n


def passed(journal: str, lane: str, commit: str) -> list[dict]:
    """The latest line of each test of the lane at the commit."""
    last: dict[str, dict] = {}
    for r in read(journal):
        if r.get("lane") == lane and r.get("commit") == commit:
            last[r["id"]] = r
    return list(last.values())


def java_done_classes(journal: str, lane: str, commit: str, tree: Path) -> list[str]:
    """Top-level classes (fully qualified) with at least one line, none failed, every key still current."""
    by: dict[str, list[dict]] = {}
    for r in passed(journal, lane, commit):
        by.setdefault(top_class(r["id"].split("#", 1)[0]), []).append(r)
    return sorted(c for c, rs in by.items()
                  if all(r["outcome"] != "failed" and r["key"] == java_key(tree, r["module"], c) for r in rs))


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("prune", "snapshot", "java-watch", "java-scan", "java-done", "summary", "resumed"):
        p = sub.add_parser(name)
        p.add_argument("--journal", default=os.environ.get("DRILL_JOURNAL", "data/drill/journal.jsonl"))
        p.add_argument("--commit", default=os.environ.get("DRILL_COMMIT", ""))
        p.add_argument("--lane", default="")
        p.add_argument("--out", default="")
        p.add_argument("--root", default=".")
        p.add_argument("--list", default="", help="java-done: a comma list of simple class names to filter")
        p.add_argument("--count", type=int, default=0)
        p.add_argument("--resumed-file", default=os.environ.get("DRILL_RESUMED", ""))
    a = ap.parse_args()
    if a.cmd == "prune":
        keep = [r for r in read(a.journal) if r.get("commit") == a.commit]
        Path(a.journal).parent.mkdir(parents=True, exist_ok=True)
        Path(a.journal).write_text("".join(json.dumps(r, separators=(",", ":")) + "\n" for r in keep), encoding="utf-8")
    elif a.cmd == "snapshot":   # pytest: the passed ids (keys compared by the plugin against the files as they are now)
        recs = [r for r in passed(a.journal, a.lane, a.commit) if r["outcome"] == "passed"]
        Path(a.out).write_text("".join(json.dumps({"id": r["id"], "key": r["key"]}) + "\n" for r in recs), encoding="utf-8")
        print(len(recs))
    elif a.cmd == "java-scan":
        print(scan(Path(a.root).resolve(), a.lane, a.commit, a.journal, {}))
    elif a.cmd == "java-watch":
        tree, seen = Path(a.root).resolve(), {}
        while tree.exists():
            scan(tree, a.lane, a.commit, a.journal, seen)
            time.sleep(10)
    elif a.cmd == "java-done":
        done = java_done_classes(a.journal, a.lane, a.commit, Path(a.root).resolve())
        if a.list:   # the -Dtest list without the finished classes (prints nothing when every class is done)
            names = {c.rsplit(".", 1)[-1] for c in done}
            print(",".join(x for x in a.list.split(",") if x and x not in names))
        else:
            Path(a.out).write_text("".join("**/" + c.replace(".", "/") + ".java\n" for c in done), encoding="utf-8")
            print(len(done), file=sys.stderr)
    elif a.cmd == "resumed":   # shell side: note how many tests/classes a lane skipped
        append(a.resumed_file, {"lane": a.lane, "count": a.count})
    elif a.cmd == "summary":
        lanes: dict[str, dict] = {}
        for r in read(a.journal):
            if r.get("commit") == a.commit:
                d = lanes.setdefault(r["lane"], {"passed": 0, "failed": 0, "skipped": 0})
                d[r["outcome"]] = d.get(r["outcome"], 0) + 1
        res: dict[str, int] = {}
        for r in read(a.resumed_file) if a.resumed_file else []:
            res[r["lane"]] = res.get(r["lane"], 0) + r["count"]
        print(f"drill: journal ({a.journal}), commit {a.commit[:9]}:")
        for lane in sorted(set(lanes) | set(res)):
            d = lanes.get(lane, {})
            print(f"  {lane:<16} {d.get('passed', 0)} passed, {d.get('failed', 0)} failed, {d.get('skipped', 0)} skipped; "
                  f"{res.get(lane, 0)} resumed (passed earlier at this commit, not run again)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
