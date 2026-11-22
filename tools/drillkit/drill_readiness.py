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
"""Read-only readiness check before a release drill (docs/plans/I7B_DRILL_PLAN.md, section 3).

    python3 tools/drillkit/drill_readiness.py [--repo DIR] [--product tools=v1.1.0 ...] [--no-network]

Changes nothing: it reads the disk, memory, ports, processes, installed tools and the products' tags on GitHub, and prints one PASS, WARN or
FAIL line per check. Exit 0 when nothing FAILs, 1 otherwise. A WARN is a thing to look at, not a stop.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import socket
import subprocess
import sys
from pathlib import Path

OWNER_PORTS = (17480, 18480, 18791, 18792)          # the owner's running stack: never touched, never a drill port
DRILL_FIXED_PORTS = (20783, 20784, 20785, 21783, 21784, 21785)   # the java21 and java21-docker copies (tools/drill.sh)
SCRATCH_LOW, SCRATCH_HIGH = 17750, 32767            # the console workers' windows (tests/scratch_ports.py)
MIN_DISK_GB, MIN_MEM_GB, WARN_LOAD = 150, 30, 8.0
PRODUCTS = {"tools": ("drishti-tools", "v1.1.0"), "rachana": ("drishti-rachana", "v0.12.0"),
            "lake": ("drishti-lake", "v0.9.0"), "elements": ("drishti-elements", None)}   # elements: the version the pom pins

results: list[tuple[str, str, str]] = []


def note(status: str, name: str, detail: str = "") -> None:
    results.append((status, name, detail))


def run(cmd: list[str], timeout: int = 30) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def listeners() -> dict[int, str]:
    out = {}
    for line in run(["ss", "-ltnHp"]).splitlines():
        parts = line.split()
        if len(parts) >= 4 and (m := re.search(r":(\d+)$", parts[3])):
            out[int(m.group(1))] = parts[-1] if "users:" in parts[-1] else ""
    return out


def check_disk(data: Path) -> None:
    free = shutil.disk_usage(data).free / 2**30
    note("PASS" if free >= MIN_DISK_GB else "FAIL", f"disk free on {data}", f"{free:.0f} GB (need {MIN_DISK_GB})")
    tmp = shutil.disk_usage("/tmp")
    note("PASS", "/tmp is never used by the drill (DRILL_TMP is on disk)", f"{tmp.free / 2**30:.0f} GB free")


def check_memory() -> None:
    kb = {k: int(v.split()[0]) for k, v in (l.split(":", 1) for l in Path("/proc/meminfo").read_text().splitlines())}
    avail = kb["MemAvailable"] / 2**20
    note("PASS" if avail >= MIN_MEM_GB else "FAIL", "memory available", f"{avail:.0f} GB of {kb['MemTotal'] / 2**20:.0f} GB (need {MIN_MEM_GB}: 20 for the Java verify, 4 spare, the rest for browser workers)")
    load = os.getloadavg()[0]
    note("PASS" if load < WARN_LOAD else "WARN", "load average (1 min)", f"{load:.1f} on {os.cpu_count()} threads (a drill started on a busy machine is slow and flaky)")


def check_ports(listening: dict[int, str]) -> None:
    for p in OWNER_PORTS:
        note("PASS", f"owner port {p} (not ours)", "in use by the owner's stack: left alone" if p in listening else "free")
    busy = [p for p in DRILL_FIXED_PORTS if p in listening]
    note("FAIL" if busy else "PASS", "drill fixed ports 20783-20785, 21783-21785 free", f"in use: {busy}" if busy else "free")
    window = sorted(p for p in listening if SCRATCH_LOW <= p <= SCRATCH_HIGH and p not in OWNER_PORTS)
    note("WARN" if window else "PASS", f"console scratch window {SCRATCH_LOW}-{SCRATCH_HIGH} has no other listener",
         f"listening: {window[:12]}{' ...' if len(window) > 12 else ''}" if window else "clear")


def check_tools(repo: Path) -> None:
    j21 = os.environ.get("JAVA21_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
    j25 = os.environ.get("JAVA25_HOME", "/usr/lib/jvm/java-25-openjdk-amd64")
    for name, home, required in (("JDK 21 (JAVA21_HOME)", j21, True), ("JDK 25 (JAVA25_HOME; the java25 lane)", j25, (repo / "tools/drill-java25.txt").exists())):
        java = Path(home) / "bin/java"
        ver = run([str(java), "-version"]).strip() or subprocess.run([str(java), "-version"], capture_output=True, text=True).stderr.splitlines()[0:1]
        ok = java.exists()
        note("PASS" if ok else ("FAIL" if required else "WARN"), name, f"{home}" if ok else f"{home}/bin/java not found")
    for exe in ("python3", "uv", "git", "ss", "curl"):
        note("PASS" if shutil.which(exe) else "FAIL", f"{exe} on PATH", shutil.which(exe) or "missing")
    note("PASS" if (repo / "mvnw").exists() else "FAIL", "mvnw in the repository", str(repo / "mvnw"))


def check_browsers(repo: Path) -> None:
    venv = repo / "drishti-console/.venv/bin/python"
    main_venv = Path.home() / "IdeaProjects/drishti/drishti-console/.venv/bin/python"
    if not venv.exists():
        note("FAIL", "console venv (drishti-console/.venv)", "missing here: the drill worktree needs it linked from the main checkout (drill.sh stops at once without it)")
        venv = main_venv   # still tell the owner which Playwright the linked environment holds
        if not venv.exists():
            return
    ver = run([str(venv), "-c", "import importlib.metadata as m;print(m.version('playwright'))"]).strip()
    note("PASS" if ver.startswith("1.64.") else "FAIL", "Playwright 1.64.x in the console venv", f"found {ver or 'none'}")
    cache = Path(os.environ.get("PLAYWRIGHT_BROWSERS_PATH", Path.home() / ".cache/ms-playwright"))
    for kind in ("chromium", "chromium_headless_shell", "webkit", "firefox", "ffmpeg"):
        found = sorted(p.name for p in cache.glob(f"{kind}-*")) if cache.exists() else []
        note("PASS" if found else ("WARN" if kind == "firefox" else "FAIL"), f"Playwright browser {kind}", ", ".join(found) or f"none in {cache}")
    helper = repo / "tools/browser/webkit_env.py"
    if helper.exists():
        rc = subprocess.run([sys.executable, str(helper), "--check"], capture_output=True, text=True)
        note("PASS" if rc.returncode == 0 else "FAIL", "WebKit can start (tools/browser/webkit_env.py --check)", (rc.stdout + rc.stderr).strip()[:160] or "ok")
    else:
        note("WARN", "WebKit libraries helper (tools/browser/webkit_env.py)", "not in this tree yet (G4 follow-ups lane): needed on a machine without WebKit's system libraries")


def check_stale(repo: Path) -> None:
    ps = run(["ps", "-eo", "pid=,args="]).splitlines()
    drills = [l.strip()[:90] for l in ps if re.search(r"^\s*\d+\s+(/bin/)?bash (\S*/)?tools/drill\.sh", l)]
    note("FAIL" if drills else "PASS", "no drill.sh running", "; ".join(drills) or "none")
    forks = [l for l in ps if re.search(r"surefire|drill_monitor|chrom(e|ium)[^ ]*headless|webkit", l, re.I) and "readiness" not in l]
    note("WARN" if forks else "PASS", "no stray test forks or headless browsers", f"{len(forks)} process(es) (other lanes at work?)" if forks else "none")
    wts = [w for w in run(["git", "-C", str(repo), "worktree", "list"]).splitlines() if re.search(r"drill|train2|i7b-drill", w)]
    note("WARN" if wts else "PASS", "no stale drill worktrees", "; ".join(w.split()[0] for w in wts) or "none")
    copies = Path.home() / "IdeaProjects/drishti-drill-copies"
    left = [p.name for p in copies.iterdir()] if copies.is_dir() else []
    note("WARN" if left else "PASS", "drill copies folder empty (~/IdeaProjects/drishti-drill-copies)", f"{len(left)} left: {left[:3]}" if left else "empty")


def pinned_elements(repo: Path) -> str | None:
    m = re.search(r"<drishti\.elements\.version>([^<]+)<", (repo / "pom.xml").read_text())
    return "v" + m.group(1) if m else None


def check_tags(repo: Path, wanted: dict[str, str | None]) -> None:
    for key, (repo_name, default) in PRODUCTS.items():
        tag = wanted.get(key) or default or pinned_elements(repo)
        url = f"https://github.com/ajsinha/{repo_name}.git"
        out = run(["git", "ls-remote", "--tags", url, tag], 40)
        tags = run(["git", "ls-remote", "--tags", "--refs", url], 40)
        have = sorted(re.findall(r"refs/tags/(\S+)", tags))
        note("PASS" if f"refs/tags/{tag}" in out else "FAIL", f"{key}: tag {tag} on GitHub ({repo_name})", f"tags there: {' '.join(have[-4:]) or 'none'}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--product", action="append", default=[], help="key=tag, e.g. rachana=v0.12.0")
    ap.add_argument("--no-network", action="store_true")
    a = ap.parse_args()
    repo = Path(a.repo)
    wanted = dict(p.split("=", 1) for p in a.product)
    check_disk(repo)
    check_memory()
    listening = listeners()
    check_ports(listening)
    check_tools(repo)
    check_browsers(repo)
    check_stale(repo)
    if not a.no_network:
        check_tags(repo, wanted)
    width = max(len(n) for _, n, _ in results)
    for s, n, d in results:
        print(f"{s:4}  {n:<{width}}  {d}")
    fails = sum(1 for s, _, _ in results if s == "FAIL")
    warns = sum(1 for s, _, _ in results if s == "WARN")
    print(f"\nreadiness: {len(results) - fails - warns} PASS, {warns} WARN, {fails} FAIL -> {'READY' if not fails else 'NOT READY'}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
