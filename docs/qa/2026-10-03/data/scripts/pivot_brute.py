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

"""Nested pivot (by: [a,b,c]) subtotals vs brute force over the raw rows, all aggregations, with null/missing/odd keys and values."""
import json, random, sys, math, os
from qa import *
R = random.Random(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
AGGS = ["sum", "count", "avg", "min", "max"]
def mkrows(n, messy):
    rows = []
    for _ in range(n):
        r = {"a": R.choice(["R", "C", "F"]), "b": R.choice(["b1", "b2", "b3", "b4"]), "c": R.choice(["x", "y", "z"]), "d": R.choice(["USD", "EUR", "JPY"]), "v": round(R.uniform(-1e5, 1e5), R.choice([0, 2]))}
        if messy:
            c = R.random()
            if c < .08: r.pop("v")
            elif c < .14: r["v"] = None
            elif c < .17: r["v"] = "n/a"
            if R.random() < .06: r["b"] = None
            if R.random() < .05: r.pop("c")
            if R.random() < .04: r["a"] = ""
            if R.random() < .04: r["d"] = None
            if R.random() < .03: r["b"] = 7
            if R.random() < .03: r["c"] = "x"  # dup
        rows.append(r)
    return rows
def num(x): return x if isinstance(x, (int, float)) and not isinstance(x, bool) else None
def agg(vals, how, recs):
    nums = [num(v) for v in vals if num(v) is not None]
    if how == "count": return len(nums) if nums else None
    if how == "distinct": return len({json.dumps(v) for v in vals if v is not None})
    if not nums: return None
    return {"sum": sum(nums), "avg": sum(nums) / len(nums), "min": min(nums), "max": max(nums)}[how]
tot = bad = 0
for it in range(int(sys.argv[2]) if len(sys.argv) > 2 else 12):
    how = AGGS[it % len(AGGS)]; messy = it >= 6
    rows = mkrows(R.randint(5, 120), messy)
    allrows = rows; rows = [r for r in rows if num(r.get('v')) is not None] if os.environ.get('NUMONLY') else rows
    y = f"""rachana: 1
sutra: pv-brute
version: 1
match: {{ kind: pvb }}
title: {{ pill: "PV", id: "'x'" }}
panels:
  - id: nested
    kind: pivot
    title: nested
    rows: $.rows
    by: [a, b, c]
    across: d
    value: v
    agg: {how}
    expand: all
    fmt: amount0
"""
    st, c = j("POST", "/api/v1/builder/design", body={"kind": "pvb", "samples": [{"name": "s", "document": {"rows": rows}}]}) if False else (0, 0)
    st, dd = j("POST", "/api/v1/builder/designs", body={"name": "pb", "kind": "pvb", "sutra": y}); i = dd["id"]
    j("POST", f"/api/v1/builder/designs/{i}/samples", body={"samples": [{"name": "s", "document": {"rows": rows}}]})
    st, p = j("GET", f"/api/v1/builder/designs/{i}/preview?sample=s")
    j("DELETE", f"/api/v1/builder/designs/{i}")
    if st != 200: print(it, "preview", st, str(p)[:200]); bad += 1; continue
    pn = p["panels"][0]
    if pn.get("error"): print(it, how, "PANEL ERROR", pn["error"][:200]); bad += 1; continue
    data = pn["data"]; cols = data["columns"]
    def key(r, f):
        v = r.get(f); return "(none)" if v is None or v == "" else str(v)
    pb = 0
    for row in data["rows"]:
        path = row["path"]
        recs = [r for r in rows if all(key(r, f) == path[k] for k, f in enumerate("abc"[:len(path)]))]
        for ci, col in enumerate(cols):
            sub = [r.get("v") for r in recs if key(r, "d") == col]
            e = agg(sub, how, recs); g = row["values"][ci]
            if (e is None) != (g is None) or (e is not None and abs(e - g) > 1e-6 * max(1, abs(e))):
                pb += 1
                if pb <= 3: print(f"  it{it} {how} path={path} col={col} expected={e} got={g}")
        allv = [r.get("v") for r in recs]; e = agg(allv, how, recs); g = row.get("total_value") if "total_value" in row else None
        row_total_txt = row["total"]["text"] if row.get("total") else None
        if row_total_txt is not None and e is not None:
            try:
                g2 = float(row_total_txt.replace("\u2212", "-").replace(",", "").replace("\u202f", "").replace(" ", ""))
                if abs(g2 - e) > 0.51 + 1e-9: pb += 1; print(f"  it{it} {how} TOTAL path={path} expected={e:.2f} text={row_total_txt}")
            except ValueError: pb += 1; print("  total unparsable", row_total_txt)
        elif (row_total_txt in (None, "", "\u2014", "-")) != (e is None) and recs: pb += 1; print(f"  it{it} {how} TOTAL presence path={path} expected={e} text={row_total_txt}")
    # expected group set
    exp_paths = set()
    for r in rows:
        if num(r.get('v')) is None: continue
        for L in (1, 2, 3): exp_paths.add(tuple(key(r, f) for f in "abc"[:L]))
    got_paths = {tuple(r["path"]) for r in data["rows"]}
    if exp_paths != got_paths: pb += 1; print(f"  it{it} {how} PATHS differ: missing {sorted(exp_paths - got_paths)[:3]} extra {sorted(got_paths - exp_paths)[:3]}")
    exp_cols = {key(r, "d") for r in rows if num(r.get('v')) is not None}
    if set(cols) != exp_cols: pb += 1; print(f"  it{it} COLUMNS differ: exp {sorted(exp_cols)} got {cols}")
    tot += 1; bad += (pb > 0)
    print(f"it{it} agg={how} messy={messy} rows={len(rows)} panelRows={len(data['rows'])} mismatches={pb}")
print("cases", tot, "with mismatches", bad)
