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

"""delta_rewrite.py <lake root> <jsonl> <date> [rounds] [mode]
Rewrites one business date of trading/trade from loader rows, in the trading pack's layout, as a new Delta version
(overwrite-partition by default). rounds>1 alternates the given file with its mtm-1 twin (written in memory)."""
import json, sys, time, pathlib

W = "/home/ashutosh/IdeaProjects/drishti/.claude/worktrees/agent-a3a597410e145c292"
sys.path.insert(0, W + "/tools")
from samplegen.layout import layouts_for_domain, write_partition  # noqa
from datetime import date

root, src, day = sys.argv[1], sys.argv[2], date.fromisoformat(sys.argv[3])
rounds = int(sys.argv[4]) if len(sys.argv) > 4 else 1
mode = sys.argv[5] if len(sys.argv) > 5 else "overwrite-partition"
lay = layouts_for_domain("trading", pathlib.Path(W) / "packs")["trade"]
rows = []
for l in open(src, encoding="utf-8"):
    r = json.loads(l)
    if r["kind"] == "trade" and r["date"] == day.isoformat():
        d = json.loads(r["doc"])
        rows.append((r["id"], r["doc"], d))
alt = []
for i, text, d in rows:
    d2 = dict(d, mtm=d["mtm"] - 1)
    alt.append((i, json.dumps(d2), d2))
table = root + "/trading/trade"
for k in range(rounds):
    use = rows if k % 2 == 0 else alt
    t = time.time()
    write_partition(table, day, use, lay, mode)
    print(f"round {k}: wrote {len(use)} rows for {day} ({'given' if k % 2 == 0 else 'mtm-1'}) in {time.time() - t:.1f}s", flush=True)
    time.sleep(1)
