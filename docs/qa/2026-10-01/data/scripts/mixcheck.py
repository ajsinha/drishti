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

"""mixcheck.py <port> <date> <old jsonl> <new jsonl>: after an interrupted reload, for ids whose mtm differs between the
old and new book, which version do views show and which do the columns (searches) hold?"""
import json, random, sys, urllib.request

port, date, old, new = sys.argv[1:5]
t8, t10 = {}, {}
for f, t in ((old, t8), (new, t10)):
    for l in open(f):
        r = json.loads(l)
        if r["date"] == date:
            t[r["id"]] = json.loads(r["doc"])["mtm"]
diff = [i for i in t8 if i in t10 and t8[i] != t10[i]]
print("ids in both books with a different mtm on", date, ":", len(diff), "of", len(t8))
random.seed(1)
cnt = {"old": 0, "new": 0, "other": 0}
for i in random.sample(diff, min(200, len(diff))):
    d = json.load(urllib.request.urlopen(f"http://localhost:{port}/api/v1/entities/trade/{i}/raw?asOf={date}"))
    m = d["data"]["mtm"]
    cnt["old" if m == t8[i] else "new" if m == t10[i] else "other"] += 1
print("views (sample of 200):", cnt)
s = json.load(urllib.request.urlopen(f"http://localhost:{port}/api/v1/search/columns/TRD?paths=mtm&asOf={date}&limit=100000"))
col = dict(zip(s["ids"], s["values"]["mtm"]))
print("columns: old values", sum(1 for i in diff if col.get(i) == t8[i]), "new values", sum(1 for i in diff if col.get(i) == t10[i]),
      "total ids", s["total"])
