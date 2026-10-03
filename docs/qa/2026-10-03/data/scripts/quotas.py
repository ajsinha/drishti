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

"""Quota checks: designs per user (50), samples per design (50), MB per design (25), MB per user (250), per-document 5 MB, body 25 MB."""
import json, sys
from qa import *
srv = None
D = "/api/v1/builder/designs"
U = "qa-quota"
ids = []
for n in range(53):
    st, o = j("POST", D, user=U, body={"name": "q%d" % n, "kind": "trade"})
    if st == 201: ids.append(o["id"])
    else: print("design", n + 1, "->", st, str(o)[:200]); break
print("created", len(ids))
# scratch (unnamed) designs count?
st, o = j("POST", D, user=U, body={}); print("unnamed beyond limit:", st, str(o)[:160])
first = ids[0]
for n in range(52):
    st, o = j("POST", f"{D}/{first}/samples", user=U, body={"samples": [{"name": "s%d" % n, "document": {"n": n}}]})
    if st != 200: print("sample", n + 1, "->", st, str(o)[:200]); break
# batch over limit in a single request
st, o = j("POST", f"{D}/{ids[1]}/samples", user=U, body={"samples": [{"name": "b%d" % n, "document": {"n": n}} for n in range(51)]}); print("51 in one request:", st, str(o)[:170])
st, o = j("GET", f"{D}/{ids[1]}", user=U); print("  design[1] samples after:", len(o["samples"]))
st, o = j("POST", f"{D}/{ids[1]}/samples", user=U, body={"schema": {"type": "object"}, "count": 500}); print("synthetic count 500:", st, len(o.get("samples", [])) if isinstance(o, dict) else str(o)[:100])
# same-name replacement should not count
st, o = j("POST", f"{D}/{first}/samples", user=U, body={"samples": [{"name": "s0", "document": {"n": 999}}]}); print("replace existing at 50:", st, str(o)[:120])
# size
big = {"blob": "x" * (4_500_000)}
for n in range(7):
    st, o = j("POST", f"{D}/{ids[2]}/samples", user=U, body={"samples": [{"name": "big%d" % n, "document": big}]}, timeout=120)
    print("4.5MB sample", n + 1, "->", st, str(o)[:150] if st != 200 else "ok (design bytes %s)" % o.get("bytes"))
    if st != 200: break
st, o = j("POST", f"{D}/{ids[3]}/samples", user=U, body={"samples": [{"name": "huge", "document": {"blob": "x" * 5_300_000}}]}, timeout=120); print("5.3MB doc:", st, str(o)[:150])
st, o = j("POST", f"{D}/{ids[3]}/samples", user=U, body={"samples": [{"name": "a%d" % k, "document": {"blob": "y" * 2_000_000}} for k in range(14)]}, timeout=120); print("28MB in one request:", st, str(o)[:150])
# user total 250 MB
tot = 0
for k in range(3, 16):
    ok = 0
    for n in range(5):
        st, o = j("POST", f"{D}/{ids[k]}/samples", user=U, body={"samples": [{"name": "m%d" % n, "document": {"blob": chr(97 + n) * 4_500_000}}]}, timeout=120)
        if st != 200: print("design", k, "sample", n, "->", st, str(o)[:170]); break
        ok += 1; tot += 4.5
    if ok < 5: break
print("total MB stored approx", tot)
st, o = j("GET", D, user=U); print("limits", o["limits"], "sum bytes", sum(d["bytes"] for d in o["designs"]) / 1e6)
for x in ids: j("DELETE", f"{D}/{x}", user=U)
