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

"""derived_check.py <port> <truth trade.jsonl> <asOf>: desk-pnl totals vs independent sums over every trade."""
import json, sys, urllib.request, collections, math

port, truth, asof = sys.argv[1], sys.argv[2], sys.argv[3]
docs = {}
for l in open(truth, encoding="utf-8"):
    r = json.loads(l)
    docs[r["id"]] = json.loads(r["doc"]) if isinstance(r["doc"], str) else r["doc"]
by = collections.defaultdict(list)
for i, d in docs.items():
    by[d.get("desk")].append((i, d))
bad = 0
for desk, ts in sorted(by.items(), key=lambda x: str(x[0])):
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/api/v1/entities/desk-pnl/{desk}/raw?asOf={asof}", timeout=60) as r:
            got = json.loads(r.read())
    except urllib.error.HTTPError as e:
        print("ERR", desk, e.code, e.read()[:200]); bad += 1; continue
    g = got["data"]
    def s(p):
        vals = [d for _, d in ts]
        out = 0.0
        for d in vals:
            v = d
            for k in p.split("."):
                v = v.get(k) if isinstance(v, dict) else None
            if isinstance(v, (int, float)):
                out += v
        return out
    exp = {"tradeCount": len(ts), "mtm": s("mtm"), "pnl1d": s("pnl1d"), "dv01": s("risk.dv01"),
           "worstMtm": min(d["mtm"] for _, d in ts if isinstance(d.get("mtm"), (int, float))),
           "books": sorted({d["book"] for _, d in ts}), "currencies": sorted({d["currency"] for _, d in ts}),
           "memberCount": len(ts)}
    probs = []
    for k, v in exp.items():
        gv = g.get(k)
        if isinstance(v, float) or isinstance(v, int) and not isinstance(v, bool):
            if gv is None or abs(gv - v) > 1e-6 * max(1, abs(v)):
                probs.append(f"{k} got {gv} exp {v}")
        elif isinstance(v, list):
            if sorted(gv or []) != v:
                probs.append(f"{k} got {gv} exp {v}")
    m = g.get("tradeIds", [])
    if len(m) != min(250, len(ts)):
        probs.append(f"members {len(m)} expected {min(250, len(ts))}")
    if any(x not in docs or docs[x].get("desk") != desk for x in m):
        probs.append("a member is not of this desk")
    if len(set(m)) != len(m):
        probs.append("duplicate members")
    print(("FAIL " if probs else "ok   ") + str(desk), got["provenance"].get("businessDate"), "; ".join(probs))
    bad += bool(probs)
print("SUMMARY bad", bad, "desks", len(by))
