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

"""Design lifecycle: stale baseRev, concurrent ops, Text op with broken YAML, undo/redo across the 100-step cap, rebase when base changes."""
import json, threading
from qa import *
base = open("base.sutra.yaml").read()
doc = {"id": "T1", "mtm": 5, "coupon": 0.1, "qty": 3, "history": [{"date": "2026-01-0%d" % i, "v": i} for i in range(1, 6)], "legs": [{"name": "a", "amount": 1}]}
def mk(name="life"):
    st, d = j("POST", "/api/v1/builder/designs", body={"name": name, "kind": "trade", "sutra": base}); assert st == 201, d
    j("POST", f"/api/v1/builder/designs/{d['id']}/samples", body={"samples": [{"name": "s1", "document": doc}]})
    return d["id"]
D = lambda i, p="": f"/api/v1/builder/designs/{i}{p}"
i = mk(); st, d = j("GET", D(i)); rev = d["rev"]; print("start rev", rev, "keys", sorted(d)[:30])
# 1 stale baseRev
st, o = j("POST", D(i, "/ops"), body={"baseRev": rev, "ops": [{"op": "setOption", "panel": "terms", "option": "span", "value": 3}]}); print("ops ok", st, o.get("rev"))
st, o2 = j("POST", D(i, "/ops"), body={"baseRev": rev, "ops": [{"op": "setOption", "panel": "terms", "option": "span", "value": 4}]}); print("stale", st, str(o2)[:200])
st, o3 = j("POST", D(i, "/ops"), body={"baseRev": 99999, "ops": [{"op": "remove", "panel": "terms"}]}); print("future baseRev", st, str(o3)[:160])
st, o3 = j("POST", D(i, "/ops"), body={"ops": [{"op": "remove", "panel": "terms"}]}); print("no baseRev", st, str(o3)[:160])
st, o3 = j("POST", D(i, "/ops"), body={"baseRev": "x", "ops": []}); print("bad baseRev", st, str(o3)[:160])
st, o3 = j("POST", D(i, "/ops"), body={"baseRev": o["rev"], "ops": []}); print("empty ops", st, o3.get("rev") if isinstance(o3, dict) else o3, "(rev bumped?)")
# 2 concurrent
st, d = j("GET", D(i)); r0 = d["rev"]; res = []
def go(n): res.append(j("POST", D(i, "/ops"), body={"baseRev": r0, "ops": [{"op": "setOption", "panel": "legs", "option": "title", "value": "T%d" % n}]})[0])
ts = [threading.Thread(target=go, args=(n,)) for n in range(16)]; [t.start() for t in ts]; [t.join() for t in ts]
print("concurrent same baseRev x16 ->", sorted(res))
# 3 Text op broken
st, d = j("GET", D(i)); r = d["rev"]; cur = d["sutra"]
for name, txt in [("broken yaml", "rachana: 1\nsutra: [unclosed\n  - x: : :\n"), ("tabs", "rachana: 1\n\tsutra: x\n"), ("empty", ""), ("valid yaml invalid sutra", "foo: bar\n"), ("huge", "a: " + "x" * 3_000_000), ("binary", "\u0000\u0001abc"), ("dup keys", base + "\nsutra: again\n"), ("anchors bomb", "a: &a [1,1,1,1,1,1,1,1,1]\nb: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]\nc: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]\nd: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]\ne: [*d,*d,*d,*d,*d,*d,*d,*d,*d]\n")]:
    st, o = j("POST", D(i, "/ops"), body={"baseRev": r, "ops": [{"op": "text", "yaml": txt}]}, timeout=60)
    pr = o.get("problems") if isinstance(o, dict) else None
    st2, d2 = j("GET", D(i))
    print(f"text[{name}] {st} applied={o.get('applied') if isinstance(o,dict) else None} problem={(pr[0]['code'], pr[0]['message'][:80], pr[0].get('line')) if pr else None} rev {r}->{d2['rev']} sutraSame={d2['sutra']==cur if st2==200 else st2}")
    if isinstance(o, dict) and o.get("rev"): r = o["rev"]; cur = o.get("yaml", cur)
# 4 undo cap
i2 = mk("cap"); st, d = j("GET", D(i2)); r = d["rev"]
for n in range(130):
    st, o = j("POST", D(i2, "/ops"), body={"baseRev": r, "ops": [{"op": "setOption", "panel": "legs", "option": "title", "value": "v%d" % n}]}); r = o["rev"]
st, v = j("GET", D(i2, "/versions")); print("after 130 ops: versions listed", len(v["versions"]), "opsAt", v["opsAt"], "first", v["versions"][0]["ops"])
n_undo = 0
while True:
    st, o = j("POST", D(i2, "/undo"))
    if st != 200: print("undo stops at", n_undo, st, str(o)[:120]); break
    n_undo += 1
st, d = j("GET", D(i2)); print("after all undos title:", [l for l in d["sutra"].splitlines() if "Legs" in l or "v0" in l or "v29" in l][:3])
n_redo = 0
while True:
    st, o = j("POST", D(i2, "/redo"))
    if st != 200: print("redo stops at", n_redo, st, str(o)[:100]); break
    n_redo += 1
st, d = j("GET", D(i2)); print("after redo all title:", [l for l in d["sutra"].splitlines() if "title" in l and "v1" in l])
# ops after undo drop redo tail
st, o = j("POST", D(i2, "/undo")); st, o = j("POST", D(i2, "/ops"), body={"baseRev": o["rev"], "ops": [{"op": "setTitle", "title": {"pill": "ZZ", "id": "$.id"}}]}); st, o = j("POST", D(i2, "/redo")); print("redo after new op", st, str(o)[:100])
for x in (i, i2): j("DELETE", D(x))
