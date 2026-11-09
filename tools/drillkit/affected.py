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
"""The tests a diff can reach, for a builder's iteration (tools/drill.sh --affected [--base REF]; docs/architecture/TEST_HARNESS.md, "Affected tests").

Conservative: a file it cannot place selects more, never less. Shared infrastructure (the build, the test harness, the common modules) selects
everything. The Java side uses the module graph of the poms (a changed module and every module that depends on it); the console and tools sides use
tools/drillkit/affected-map.txt and the test files' own text. It is no substitute for the release's single full drill.

    affected.py plan [--base REF] [--json]     what would run, and why
    affected.py run  [--base REF] [--dry-run]  run it (waits for 20 GB free first: one heavy job at a time on a busy machine)

Standard library only.
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAP_FILE = "tools/drillkit/affected-map.txt"
CONSOLE_TESTS = "drishti-console/tests"
DEFAULT_BASE = "origin/develop"
FREE_GB = 20.0
CONSOLE_WORKERS = 4        # at most four: a builder's lane beside other builders (drill lessons, 2026-11-04)
DOCS_RE = re.compile(r"^(docs/|tools/docs/)|\.md$|(^|/)help\.yaml$")
DOCS_TESTS = ("test_docs_*.py", "test_help*.py", "test_tools_guide.py")
# whatever every test depends on: a change here reaches everything
SHARED = ("pom.xml", "mvnw", ".mvn/*", "pytest.ini", "tools/drill.sh", "tools/drill-*.txt", "tools/drillkit/*", "tools/javacmd.py",
          "drishti-console/requirements*.txt", f"{CONSOLE_TESTS}/conftest.py", f"{CONSOLE_TESTS}/scratch_ports.py", f"{CONSOLE_TESTS}/*harness*.py",
          f"{CONSOLE_TESTS}/wb_live.py", "drishti-api/*", "drishti-common/*", "drishti-testkit/*", "drishti-it/*")
# words of a file name that say nothing about what the file is
WEAK = {"drishti", "test", "tests", "browser", "index", "main", "utils", "util", "common", "core", "base", "js", "css", "html", "py"}


@dataclass
class Plan:
    """What a diff reaches. ``everything`` overrides the sets."""

    everything: bool = False
    modules: set = field(default_factory=set)
    console: set = field(default_factory=set)
    tools: set = field(default_factory=set)
    all_console: bool = False
    all_tools: bool = False
    reasons: list = field(default_factory=list)

    def why(self, path: str, what: str) -> None:
        self.reasons.append(f"{path}: {what}")

    def to_dict(self) -> dict:
        return {"everything": self.everything, "all_console": self.all_console, "all_tools": self.all_tools, "modules": sorted(self.modules),
                "console": sorted(self.console), "tools": sorted(self.tools), "reasons": self.reasons}


def changed_files(base: str, root: Path = ROOT) -> list[str]:
    """Files that differ from the merge base of ``base`` and HEAD, the working tree's edits and new files included."""
    def git(*a: str) -> list[str]:
        return [x for x in subprocess.run(["git", *a], cwd=root, capture_output=True, text=True, check=True).stdout.splitlines() if x]
    fork = git("merge-base", base, "HEAD")[0]
    return sorted(set(git("diff", "--name-only", fork)) | set(git("ls-files", "--others", "--exclude-standard")))


def modules_of(root: Path) -> dict[str, dict]:
    """{module dir: {"artifact": id, "deps": artifact ids}} from the root pom's modules (profiles included) and each module's pom."""
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    out = {}
    for mod in ET.parse(root / "pom.xml").getroot().iterfind(".//m:module", ns):
        pom = root / mod.text / "pom.xml"
        if not pom.is_file():
            continue
        tree = ET.parse(pom).getroot()
        deps = {d.text for d in tree.iterfind("m:dependencies/m:dependency/m:artifactId", ns)}
        out[mod.text] = {"artifact": tree.find("m:artifactId", ns).text, "deps": deps}
    return out


def downstream(start: set[str], mods: dict[str, dict]) -> set[str]:
    """``start`` and every module that depends on one of them, directly or not."""
    by_artifact = {m["artifact"]: name for name, m in mods.items()}
    users: dict[str, set[str]] = {name: set() for name in mods}
    for name, m in mods.items():
        for d in m["deps"]:
            if d in by_artifact:
                users[by_artifact[d]].add(name)
    seen, todo = set(), list(start)
    while todo:
        n = todo.pop()
        if n not in seen:
            seen.add(n)
            todo.extend(users.get(n, ()))
    return seen


def module_of(path: str, mods: dict[str, dict]) -> str | None:
    best = None
    for m in mods:
        if path.startswith(m + "/") and (best is None or len(m) > len(best)):
            best = m
    return best


def load_map(root: Path) -> list[tuple[str, list[str], list[str]]]:
    """(path glob, console test globs, modules) per line of the map: ``glob -> console globs [| modules]``; '#' starts a comment."""
    rows = []
    for raw in (root / MAP_FILE).read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            glob, _, rest = line.partition("->")
            tests, _, mods = rest.partition("|")
            rows.append((glob.strip(), [t.strip() for t in tests.split(",") if t.strip()], [m.strip() for m in mods.split(",") if m.strip()]))
    return rows


def tests_naming(words: list[str], files: list[Path], root: Path) -> set[str]:
    """The test files whose text names one of ``words``."""
    if not words:
        return set()
    r = re.compile("|".join(re.escape(w) for w in words))
    return {str(f.relative_to(root)) for f in files if r.search(f.read_text(encoding="utf-8", errors="replace"))}


def mapped_tests(globs: list[str], console: list[str]) -> set[str]:
    return {c for c in console for g in globs if fnmatch.fnmatch(Path(c).name, g)}


def stem_words(path: str) -> list[str]:
    """The words of a file's name worth searching for, whole name first."""
    stem = Path(path).stem
    parts = [w for w in re.split(r"[_\-.]", stem) if len(w) > 2 and w.lower() not in WEAK]
    return ([stem] if stem not in WEAK else []) + parts


def classify(files: list[str], root: Path = ROOT) -> Plan:
    """The plan of a list of changed files."""
    mods = modules_of(root)
    rows = load_map(root)
    console = sorted(str(p.relative_to(root)) for p in (root / CONSOLE_TESTS).glob("test_*.py"))
    console_paths = [root / c for c in console]
    tool_tests = sorted(p for p in (root / "tools").rglob("test_*.py") if ".venv" not in p.parts and "cache" not in p.parts)
    plan = Plan()

    def all_of_console(path: str, why: str) -> None:
        plan.all_console = True
        plan.why(path, why + ": every console test")

    for f in files:
        hit = next((r for r in rows if fnmatch.fnmatch(f, r[0])), None)
        if any(fnmatch.fnmatch(f, s) for s in SHARED):
            plan.everything = True
            plan.why(f, "shared infrastructure: everything")
        elif DOCS_RE.search(f):
            for g in DOCS_TESTS:
                plan.console |= mapped_tests([g], console)
            plan.why(f, "documentation: the docs and help tests")
        elif f.startswith(f"{CONSOLE_TESTS}/"):
            if Path(f).name.startswith("test_") and f.endswith(".py"):
                if (root / f).exists():
                    plan.console.add(f)
                plan.why(f, "a test file: itself")
            else:
                named = tests_naming([Path(f).stem], console_paths, root)
                if named:
                    plan.console |= named
                    plan.why(f, "a test helper: the files that use it")
                else:
                    all_of_console(f, "a test helper nothing names")
        elif f.startswith("drishti-console/"):
            named = tests_naming(stem_words(f), console_paths, root)
            if hit:
                named |= mapped_tests(hit[1], console)
            if named:
                plan.console |= named
                plan.why(f, f"console code: {len(named)} test files that name it")
            else:
                all_of_console(f, "console code no test names")
        elif f.startswith("tools/"):
            if Path(f).name.startswith("test_") and f.endswith(".py"):
                if (root / f).exists():
                    plan.tools.add(f)
                plan.why(f, "a tools test: itself")
                continue
            near = {str(p.relative_to(root)) for p in tool_tests if p.parent == (root / f).parent}
            named = tests_naming([Path(f).stem], tool_tests, root)
            found = named | (near if f.count("/") > 1 else set())
            if found:
                plan.tools |= found
                plan.why(f, f"a tool: {len(found)} tool test files beside it or naming it")
            else:
                plan.all_tools = True
                plan.why(f, "a tool no test names: every tools test")
        elif (m := module_of(f, mods)) is not None:
            if "/src/test/" in f:
                plan.modules.add(m)
                plan.why(f, f"a Java test: module {m} only")
            else:
                plan.modules |= downstream({m}, mods)
                if hit:
                    plan.console |= mapped_tests(hit[1], console)
                    plan.why(f, f"Java: {m} and its users; the console tests the map names")
                else:
                    all_of_console(f, f"Java in {m}, which the map does not narrow")
        elif hit:                                                  # config/, data/testdata and the like: the map decides
            plan.console |= mapped_tests(hit[1], console)
            plan.modules |= downstream(set(hit[2]), mods)
            plan.why(f, "data or configuration: as the map says")
        elif f.startswith(("config/", "data/testdata/")):
            words = [p for p in f.split("/")[1:3] if p not in WEAK]
            named = tests_naming(words, console_paths, root)
            if named:
                plan.console |= named
                plan.why(f, f"data or configuration: {len(named)} test files that name it")
            else:
                all_of_console(f, "data or configuration no test names")
        else:
            plan.everything = True
            plan.why(f, "not placed: everything")
    return plan


def test_classes(modules: set[str], root: Path = ROOT) -> str:
    """Maven's -Dtest list: every unit test class of these modules."""
    names = set()
    for m in modules:
        names |= {p.stem for p in (root / m / "src/test/java").rglob("*Test.java")}
    return ",".join(sorted(names))


def wait_for_memory(need_gb: float = FREE_GB, limit: int = 900) -> bool:
    """Blocks until ``need_gb`` GB are free (MemAvailable); False when that does not happen within ``limit`` seconds."""
    sys.path.insert(0, str(Path(__file__).parent))
    from drill_plan import mem_available_gb
    began = time.monotonic()
    while mem_available_gb() < need_gb:
        if time.monotonic() - began > limit:
            return False
        print(f"affected: {mem_available_gb():.0f} GB free, waiting for {need_gb:.0f}", flush=True)
        time.sleep(15)
    return True


def commands(plan: Plan, root: Path = ROOT) -> list[tuple[str, list[str]]]:
    """(label, command) in order: Maven first (it builds the server jar the console tests start), then the console, then the tools."""
    mods = modules_of(root)
    cmds: list[tuple[str, list[str]]] = []
    if plan.everything:
        cmds.append(("maven", ["./mvnw", "-o", "-q", "clean", "verify", "-P", "api-docs", "-T", "1C"]))
    elif plan.modules:
        wanted = [m for m in sorted(plan.modules) if m in mods]
        cmds.append(("maven", ["./mvnw", "-o", "-q", "clean", "verify", "-T", "1C", "-pl", ",".join(wanted), "-am", "-Dtest=" + test_classes(set(wanted), root),
                               "-Dsurefire.failIfNoSpecifiedTests=false"]))
    py = "drishti-console/.venv/bin/python"
    if plan.everything or plan.all_console:
        targets = [CONSOLE_TESTS]
    else:
        targets = sorted(plan.console)
    if targets:
        cmds.append(("console", [py, "-m", "pytest", "-q", "-p", "no:cacheprovider", "-n", str(CONSOLE_WORKERS), "--dist", "loadfile", *targets]))
    tools = [str(p.relative_to(root)) for p in (root / "tools").rglob("test_*.py") if ".venv" not in p.parts and "cache" not in p.parts] \
        if plan.everything or plan.all_tools else sorted(plan.tools)
    uv = ["uv", "run", "-q", "--with", "deltalake", "--with", "pyarrow", "--with", "pyyaml", "--with", "pytest", "--with", "moto[server]==5.2.3", "python", "-m", "pytest", "-q", "-p", "no:cacheprovider"]
    for t in tools:
        cmds.append((t, [*uv, t]))
    return cmds


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("cmd", choices=("plan", "run"))
    ap.add_argument("--base", default=DEFAULT_BASE, help=f"the ref to compare with (default {DEFAULT_BASE})")
    ap.add_argument("--json", action="store_true", help="plan: print JSON")
    ap.add_argument("--dry-run", action="store_true", help="run: only print the commands")
    a = ap.parse_args(argv)
    plan = classify(changed_files(a.base))
    cmds = commands(plan)
    if a.cmd == "plan":
        if a.json:
            print(json.dumps(plan.to_dict(), indent=1))
            return 0
        print(f"affected: {len(plan.reasons)} changed files against {a.base}")
        for r in plan.reasons:
            print("  " + r)
    if a.cmd == "plan" or a.dry_run:
        for label, c in cmds:
            line = " ".join(c)
            print(f"  [{label}] {line if len(line) < 400 else line[:400] + ' ...'}")
        return 0
    if not cmds:
        print("affected: nothing to run")
        return 0
    if not wait_for_memory():
        print(f"affected: fewer than {FREE_GB:.0f} GB free after waiting: not started (another heavy job is running)", file=sys.stderr)
        return 3
    env = {**os.environ, "TMPDIR": os.environ.get("TMPDIR") or str((ROOT / "data/tmp/affected").resolve())}
    Path(env["TMPDIR"]).mkdir(parents=True, exist_ok=True)
    failed = []
    for label, c in cmds:
        began = time.monotonic()
        print(f"affected: {label} ...", flush=True)
        rc = subprocess.run(c, cwd=ROOT, env=env).returncode
        print(f"affected: {label}: {time.monotonic() - began:.0f}s, exit {rc}", flush=True)
        if rc != 0:
            failed.append(label)
    print("affected: all passed" if not failed else f"affected: FAILED: {failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
