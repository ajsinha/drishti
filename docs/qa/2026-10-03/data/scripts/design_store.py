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

"""Auto-design from 8 stored entities per kind (refs): panels drafted vs the pack's own view, check matrix, pruning reasons."""
import json, sys
from qa import *
KINDS = sys.argv[1:] or "trade counterparty book desk netting-set bond curve var cva simm lcr loss-event customer deposit-account gene variant vessel poll macro-indicator ir-curve fx-vol-surface stress-result pnl-explain third-party mortgage".split()
tot = {"kinds": 0, "err": 0}
for k in KINDS:
    st, d = j("POST", "/api/v1/builder/designs", body={"name": "qa-" + k, "kind": k})
    did = d["id"]
    st, o = j("POST", f"/api/v1/builder/designs/{did}/samples", body={"refs": {"kind": k, "count": 8}})
    if st != 200: print(k, "refs", st, str(o)[:120]); j("DELETE", f"/api/v1/builder/designs/{did}"); continue
    names = [s["name"] for s in o["samples"]]
    st, a = j("POST", f"/api/v1/builder/designs/{did}/autodesign", timeout=120)
    if st != 200: print(k, "autodesign", st, str(a)[:200]); j("DELETE", f"/api/v1/builder/designs/{did}"); continue
    st, c = j("POST", f"/api/v1/builder/designs/{did}/check", timeout=120)
    eid = names[0].split(" ", 1)[1]
    st2, v = j("GET", f"/api/v1/views/{k}/{eid}")
    real = sorted({p["kind"] for p in v.get("panels", [])}) if st2 == 200 else st2
    import yaml
    auto = yaml.safe_load(a["yaml"]); ak = sorted({p["kind"] for p in auto.get("panels", [])})
    cnt = c.get("counts") if st == 200 else (st, str(c)[:100])
    pr = [(p.get("id") or p.get("panel") or p) if isinstance(p, dict) else p for p in a.get("pruned", [])]
    tot["kinds"] += 1; tot["err"] += (cnt or {}).get("error", 0) if isinstance(cnt, dict) else 1
    print(f"{k}: real={real}\n    auto={ak} strip={len(auto.get('strip',[]))} counts={cnt} pruned={pr[:5]}")
    j("DELETE", f"/api/v1/builder/designs/{did}")
print(tot)
