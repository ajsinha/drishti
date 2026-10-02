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

"""probe_after.py <port> <id> <asOf> <search> <seconds> -- <command...>
Runs the command, then every second for <seconds> prints the raw mtm of <id> and the matched count of <search>."""
import json, subprocess, sys, time, urllib.request, urllib.parse

port, id_, asof, q, secs = sys.argv[1:6]
cmd = sys.argv[sys.argv.index("--") + 1:]
B = f"http://localhost:{port}/api/v1"


def probe():
    try:
        d = json.load(urllib.request.urlopen(f"{B}/entities/trade/{id_}/raw?asOf={asof}", timeout=30))
        m = d["data"].get("mtm")
    except Exception as e:  # noqa
        m = repr(e)[:80]
    try:
        s = json.load(urllib.request.urlopen(f"{B}/search?q={urllib.parse.quote(q)}&asOf={asof}", timeout=30))
        c = (s.get("matched"), s.get("scanned"), s.get("partial"))
    except Exception as e:  # noqa
        c = repr(e)[:80]
    return m, c


t0 = time.time()
r = subprocess.run(cmd, capture_output=True, text=True)
print(f"command exit {r.returncode} in {time.time() - t0:.1f}s: {(r.stdout + r.stderr).strip().splitlines()[-1:] }")
t1 = time.time()
last = None
while time.time() - t1 < float(secs):
    p = probe()
    if p != last:
        print(f"t+{time.time() - t1:5.1f}s raw mtm={p[0]} search={p[1]}")
        last = p
    time.sleep(1)
print("final", last)
