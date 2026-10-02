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

"""mqcheck.py <port> <n> [expected-add-for-first-k k]: reads the first n trades of the 09-30 book from the server and
classifies each as current (latest mtm published), stale (an older mtm), missing (404) or error."""
import json, sys, urllib.request, collections

S = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data"
port, n = sys.argv[1], int(sys.argv[2])
add, k = (float(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (0.0, 0)
c = collections.Counter()
ex = {}
for i, l in enumerate(open(f"{S}/files/trading/2026-09-30/trade.jsonl")):
    if i >= n:
        break
    d = json.loads(json.loads(l)["doc"])
    want = d["mtm"] + (add if i < k else 0)
    try:
        got = json.load(urllib.request.urlopen(f"http://localhost:{port}/api/v1/entities/trade/{d['tradeId']}/raw", timeout=30))
        m = got["data"].get("mtm")
        key = "current" if m == want else "stale"
    except urllib.error.HTTPError as e:
        key = f"http {e.code}"
    c[key] += 1
    ex.setdefault(key, d["tradeId"])
print(dict(c), ex)
