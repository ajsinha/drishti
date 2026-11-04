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

"""What a test run holds in memory, sampled (docs/architecture/TEST_HARNESS.md, "Measuring a run").

  drill_monitor.py watch   --out FILE.csv [--every SECONDS] [--under PID] [--stop-below GB --stop-pid PID]
      Every few seconds one CSV row: the machine's available memory and, by kind, how many processes run and their resident memory:
      server JVMs (java ... drishti-server), other JVMs (Maven, surefire forks), Chromium, console processes (run_drishti_web.py),
      pytest processes (the controller and its xdist workers). --under counts only that process and its descendants (one lane).
      --stop-below stops --stop-pid (SIGTERM, the process and its descendants) when available memory falls below that many GB.
      Ends when --under's process ends, or on SIGTERM.
  drill_monitor.py summary FILE.csv [--json]
      The peaks of a recorded run: available memory at its lowest, the most server JVMs at once and their memory, Chromium, pytest.

Linux only (/proc); standard library only.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import signal
import sys
import time
from pathlib import Path

KINDS = ("server_jvm", "other_jvm", "chromium", "console", "pytest", "other")
FIELDS = ["t", "avail_gb"] + [f"{k}_{x}" for k in KINDS for x in ("n", "gb")]


def classify(args: str) -> str:
    """The kind of one process from its command line."""
    a = args.lower()
    if "java" in a.split(" ", 1)[0] or "/bin/java" in a:
        return "server_jvm" if "drishti-server" in a and "-jar" in a else "other_jvm"
    if "chrom" in a or "headless_shell" in a:
        return "chromium"
    if "run_drishti_web.py" in a:
        return "console"
    if "pytest" in a or "execnet" in a or "exec(eval(sys.stdin.readline()))" in a:
        return "pytest"
    return "other"


def processes() -> dict[int, tuple[int, str, int]]:
    """{pid: (ppid, command line, resident bytes)} for every process we can read."""
    out = {}
    page = os.sysconf("SC_PAGE_SIZE")
    for d in Path("/proc").iterdir():
        if not d.name.isdigit():
            continue
        try:
            stat = (d / "stat").read_text()
            ppid = int(stat.rsplit(")", 1)[1].split()[1])
            args = (d / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace").strip()
            rss = int((d / "statm").read_text().split()[1]) * page
        except (OSError, ValueError, IndexError):
            continue
        if args:
            out[int(d.name)] = (ppid, args, rss)
    return out


def descendants(procs: dict[int, tuple[int, str, int]], root: int) -> set[int]:
    kids: dict[int, list[int]] = {}
    for pid, (ppid, _a, _r) in procs.items():
        kids.setdefault(ppid, []).append(pid)
    seen, todo = set(), [root]
    while todo:
        p = todo.pop()
        if p in seen:
            continue
        seen.add(p)
        todo.extend(kids.get(p, []))
    return seen


def available_gb() -> float:
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) / 1048576
    return 0.0


def sample(under: int | None = None) -> dict:
    procs = processes()
    pids = descendants(procs, under) if under else set(procs)
    row = {"t": round(time.time(), 1), "avail_gb": round(available_gb(), 2)}
    for k in KINDS:
        row[f"{k}_n"], row[f"{k}_gb"] = 0, 0.0
    for pid in pids:
        if pid not in procs:
            continue
        k = classify(procs[pid][1])
        row[f"{k}_n"] += 1
        row[f"{k}_gb"] += procs[pid][2] / 2**30
    for k in KINDS:
        row[f"{k}_gb"] = round(row[f"{k}_gb"], 2)
    return row


def stop_tree(pid: int) -> None:
    procs = processes()
    for p in sorted(descendants(procs, pid), reverse=True):
        try:
            os.kill(p, signal.SIGTERM)
        except OSError:
            pass


def cmd_watch(a) -> int:
    running = {"on": True}
    signal.signal(signal.SIGTERM, lambda *_: running.update(on=False))
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS)
        w.writeheader()
        while running["on"]:
            if a.under and not Path(f"/proc/{a.under}").exists():
                break
            row = sample(a.under)
            w.writerow(row)
            fh.flush()
            if a.stop_below and a.stop_pid and row["avail_gb"] < a.stop_below:
                print(f"drill_monitor: available memory {row['avail_gb']} GB < {a.stop_below} GB: stopping {a.stop_pid}", file=sys.stderr)
                stop_tree(a.stop_pid)
                break
            time.sleep(a.every)
    return 0


def summarise(path: Path) -> dict:
    with path.open(newline="") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        return {"samples": 0}
    num = lambda r, k: float(r[k])  # noqa: E731
    out = {"samples": len(rows), "seconds": round(num(rows[-1], "t") - num(rows[0], "t")),
           "min_avail_gb": min(num(r, "avail_gb") for r in rows)}
    for k in KINDS:
        out[f"max_{k}_n"] = int(max(num(r, f"{k}_n") for r in rows))
        out[f"max_{k}_gb"] = round(max(num(r, f"{k}_gb") for r in rows), 2)
    out["max_total_gb"] = round(max(sum(num(r, f"{k}_gb") for k in KINDS) for r in rows), 2)
    return out


def cmd_summary(a) -> int:
    s = summarise(Path(a.file))
    if a.json:
        print(json.dumps(s, indent=1))
    else:
        print(" ".join(f"{k}={v}" for k, v in s.items()))
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("watch")
    s.add_argument("--out", required=True)
    s.add_argument("--every", type=float, default=5.0)
    s.add_argument("--under", type=int)
    s.add_argument("--stop-below", type=float)
    s.add_argument("--stop-pid", type=int)
    s.set_defaults(fn=cmd_watch)
    s = sub.add_parser("summary")
    s.add_argument("file")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_summary)
    a = p.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
