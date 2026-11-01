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
"""What a benchmark's JFR recordings say (RESOURCES_AND_SCALE.md section 8.4, docs/admin/BENCHMARK.md section 4), standard library and the JDK's ``jfr``:

    python3 tools/bench/jfr_summary.py stages <open-DATE.jfr> [--java /usr/lib/jvm/java-21-openjdk-amd64]
    python3 tools/bench/jfr_summary.py views <run.jfr> --to <folder>

``stages``: a VaR date's first open split by stage from its CPU samples (jdk.ExecutionSample): each sample goes to the first stage a frame
of its stack names, from the leaf down: write (the hot file), aggregate (node and book sums), sort (positions, sort, permute), intake
(rows into the spill), read (Parquet and the Delta table), other. One JSON object: samples and share per stage.
``views``: the jfr views the diet needs written to --to (allocation by class and by site, what survives, hot methods, GC pauses and CPU,
pinned threads, contention) and one JSON object of counts (pinned events, GC pauses, the longest).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

STAGES = [  # (stage, frame substrings), first match from the leaf down
    ("write", ("VarHotFile.write", "VarHotFile.lambda$positionRows", "CurrencyTotals.write", "VarHotFile$Out", "FileChannel")),
    ("aggregate", ("ParallelSums", "MappedMatrix.addRangeInto", "PermutedVectors", "OrdinalSet.forEachRun", "VarBook$Builder.aggregate",
                   "CurrencyTotals.build")),
    ("sort", ("VarBook$Builder", "VarSpill", "Sorter", "derivePosition", "positionRow")),
    ("intake", ("VarIntake", "VarLoader.intake", "VarInputs", "VarPositions")),
    ("read", ("org.apache.parquet", "deltalake", "DeltaTable", "DeltaSourcePlugin", "io.delta", "VarLoader.documents", "SourceRouter")),
]
VIEWS = ["allocation-by-class", "allocation-by-site", "memory-leaks-by-class", "memory-leaks-by-site", "hot-methods", "gc-pauses", "gc-cpu-time",
         "gc", "pinned-threads", "contention-by-site", "thread-cpu-load", "file-reads-by-path", "exception-count"]


def jfr(java: str, *args: str) -> str:
    return subprocess.run([str(Path(java) / "bin" / "jfr"), *args], capture_output=True, text=True, timeout=1800).stdout


def stages(a) -> dict:
    text = jfr(a.java, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", a.file)
    count, total, load_total = Counter(), 0, 0
    for block in text.split("jdk.ExecutionSample")[1:]:
        frames = re.findall(r"^\s+([\w.$<>]+)\(", block, re.M)
        if not frames:
            continue
        total += 1
        if not any("VarLoader" in f or "VarBook" in f or "VarHotFile" in f or "VarService" in f for f in frames):
            count["not the load"] += 1
            continue
        load_total += 1
        for f in frames:
            stage = next((s for s, keys in STAGES if any(k in f for k in keys)), None)
            if stage:
                count[stage] += 1
                break
        else:
            count["other"] += 1
    return {"file": a.file, "samples": total, "loadSamples": load_total,
            "stages": {k: {"samples": v, "share": round(v / load_total, 3) if load_total and k != "not the load" else None} for k, v in count.most_common()}}


def views(a) -> dict:
    to = Path(a.to)
    to.mkdir(parents=True, exist_ok=True)
    for v in VIEWS:
        (to / f"{v}.txt").write_text(jfr(a.java, "view", "--width", "220", "--cell-height", "12", v, a.file), encoding="utf-8")
    pinned = jfr(a.java, "print", "--events", "jdk.VirtualThreadPinned", "--stack-depth", "24", a.file)
    (to / "pinned-events.txt").write_text(pinned, encoding="utf-8")
    pauses = jfr(a.java, "print", "--events", "jdk.GarbageCollection", a.file)
    longest = [float(m) for m in re.findall(r"longestPause = ([\d.]+) ms", pauses)]
    secs = [float(m) for m in re.findall(r"longestPause = ([\d.]+) s", pauses)]
    longest += [s * 1000 for s in secs]
    return {"file": a.file, "pinnedEvents": pinned.count("jdk.VirtualThreadPinned {"), "gcCount": pauses.count("jdk.GarbageCollection {"),
            "longestPauseMs": round(max(longest), 2) if longest else None, "views": str(to)}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("stages")
    s.add_argument("file")
    v = sub.add_parser("views")
    v.add_argument("file")
    v.add_argument("--to", required=True)
    for p in (s, v):
        p.add_argument("--java", default="/usr/lib/jvm/java-21-openjdk-amd64")
    a = ap.parse_args(argv)
    print(json.dumps(stages(a) if a.cmd == "stages" else views(a)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
