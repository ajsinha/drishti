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

"""Tree tables (table/ladder with children:) vs the source tree: structure, order, cell texts, totals; hostile children values."""
import json, random, sys
from qa import *
R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
def tree(depth, maxd, hostile):
    n = {"name": "n%d" % R.randint(0, 10**6), "val": R.randint(-5000, 5000)}
    if depth < maxd and R.random() < .75:
        k = R.randint(1, 4)
        n["kids"] = [tree(depth + 1, maxd, hostile) for _ in range(k)]
    if hostile:
        c = R.random()
        if c < .06: n["kids"] = "oops"
        elif c < .1: n["kids"] = None
        elif c < .14: n["kids"] = {"name": "single", "val": 1}
        elif c < .18: n["kids"] = [1, "x", None, {"name": "ok", "val": 2}]
        elif c < .2: n["val"] = None
        elif c < .22: n.pop("name")
    return n
def expect(nodes, exp_depth):
    out = []
    for n in nodes:
        kids = n.get("kids")
        out.append((str(n.get("name", "")) if n.get("name") is not None else "", n.get("val"), expect([k for k in kids if isinstance(k, dict)], exp_depth) if isinstance(kids, list) else []))
    return out
def got(rows):
    return [(r["cells"][0].get("text", ""), r["cells"][1].get("text", ""), got(r.get("children", []))) for r in rows]
bad = 0
for it in range(int(sys.argv[2]) if len(sys.argv) > 2 else 16):
    hostile = it >= 6; kind_ = "table" if it % 2 == 0 else "ladder"
    roots = [tree(0, R.choice([1, 3, 5]), hostile) for _ in range(R.randint(1, 6))]
    y = f"""rachana: 1
sutra: tb
version: 1
match: {{ kind: tb }}
title: {{ pill: "T", id: "'x'" }}
panels:
  - id: t
    kind: {kind_}
    title: t
    rows: $.roots
    children: "@.kids"
    expand: all
    columns:
      - {{ label: N, bind: "@.name" }}
      - {{ label: V, bind: "@.val", fmt: amount0, total: true }}
"""
    st, d = j("POST", "/api/v1/builder/designs", body={"name": "tb", "kind": "tb", "sutra": y}); i = d["id"]
    j("POST", f"/api/v1/builder/designs/{i}/samples", body={"samples": [{"name": "s", "document": {"roots": roots}}]})
    st, p = j("GET", f"/api/v1/builder/designs/{i}/preview?sample=s"); j("DELETE", f"/api/v1/builder/designs/{i}")
    if st != 200: print(it, st, str(p)[:200]); bad += 1; continue
    pn = p["panels"][0]
    if pn.get("error"): print(it, "ERROR", pn["error"]); bad += 1; continue
    data = pn["data"]
    e = expect([r for r in roots if isinstance(r, dict)], 0)
    g = got(data["rows"])
    def fmtv(v): return "" if v is None else f"{v:,}".replace("-", "−")
    def flat_e(x): return [(a, fmtv(b), flat_e(c)) for a, b, c in x]
    fe = flat_e(e)
    ok = fe == g
    # totals
    tot_exp = sum(r["val"] for r in roots if isinstance(r.get("val"), int))
    print(it, kind_, "hostile" if hostile else "clean", "roots", len(roots), "OK" if ok else "MISMATCH", "extra data keys", [k for k in data if k not in ("columns", "numeric", "rows", "search")], "total expected", tot_exp, "got", data.get("total"))
    if not ok:
        bad += 1
        def first_diff(a, b, path=""):
            if len(a) != len(b): return f"{path} len {len(a)} vs {len(b)}"
            for n, (x, y2) in enumerate(zip(a, b)):
                if x[:2] != y2[:2]: return f"{path}/{n}: expected {x[:2]} got {y2[:2]}"
                d_ = first_diff(x[2], y2[2], path + f"/{n}")
                if d_: return d_
        print("    ", first_diff(fe, g))
print("cases with mismatches:", bad)
