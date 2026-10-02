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

"""reload_loop.py <rounds> <sleep> -- <cmdA...> ;; <cmdB...>   runs A, sleeps, B, sleeps, `rounds` times (alternating loads)."""
import subprocess, sys, time

rounds, pause = int(sys.argv[1]), float(sys.argv[2])
rest = sys.argv[sys.argv.index("--") + 1:]
cmds, cur = [], []
for a in rest:
    if a == ";;":
        cmds.append(cur)
        cur = []
    else:
        cur.append(a)
cmds.append(cur)
for k in range(rounds):
    for c in cmds:
        t = time.time()
        r = subprocess.run(c, capture_output=True, text=True)
        tail = (r.stdout + r.stderr).strip().splitlines()[-1:]
        print(f"round {k} exit {r.returncode} {time.time() - t:.1f}s {tail}", flush=True)
        time.sleep(pause)
