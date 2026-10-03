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

"""`source:` on every panel kind: panels auto-designed for an entity kind, then given source: link($.ref,'<kind>') on a wrapper document,
must render exactly like the same panels rendered on the entity itself. Reports which panel kinds were covered."""
import json, sys, yaml, copy
from qa import *
KINDS = sys.argv[1:] or "trade counterparty book desk netting-set bond var cva simm lcr loss-event customer deposit-account gene variant poll macro-indicator ir-curve fx-vol-surface stress-result pnl-explain mortgage exposure-profile".split()
covered = {}; bad = 0
for k in KINDS:
    st, d = j("POST", "/api/v1/builder/designs", body={"name": "sa-" + k, "kind": k}); did = d["id"]
    st, o = j("POST", f"/api/v1/builder/designs/{did}/samples", body={"refs": {"kind": k, "count": 2}})
    if st != 200: print(k, "no entities", st); j("DELETE", f"/api/v1/builder/designs/{did}"); continue
    sname = o["samples"][0]["name"]; eid = sname.split(" ", 1)[1]
    st, a = j("POST", f"/api/v1/builder/designs/{did}/autodesign", timeout=120)
    st, base_p = j("GET", f"/api/v1/builder/designs/{did}/preview?sample=" + sname.replace(" ", "%20"))
    truth = {p["id"]: p for p in base_p["panels"]}
    y = yaml.safe_load(a["yaml"])
    y["sutra"] = "sa-wrap"; y["match"] = {"kind": "wrapqa"}; y["title"] = {"pill": "W", "id": "$.ref"}; y.pop("strip", None); y.pop("keys", None)
    y["panels"] = [p for p in y["panels"] if p["kind"] not in ("links", "provenance")]
    for p in y["panels"]:
        p["source"] = f"link($.ref, '{k}')"
    st, w = j("POST", "/api/v1/builder/designs", body={"name": "sw-" + k, "kind": "wrapqa", "sutra": yaml.safe_dump(y, sort_keys=False)}); wid = w["id"]
    j("POST", f"/api/v1/builder/designs/{wid}/samples", body={"samples": [{"name": "w", "document": {"ref": eid}}]})
    st, wp = j("GET", f"/api/v1/builder/designs/{wid}/preview?sample=w")
    if st != 200: print(k, "wrapper preview", st, str(wp)[:200]); bad += 1
    else:
        for p in wp["panels"]:
            t = truth.get(p["id"])
            kind = p["kind"]; covered.setdefault(kind, [0, 0]); covered[kind][0] += 1
            if t is None: continue
            same = (p.get("data") == t.get("data")) and bool(p.get("empty")) == bool(t.get("empty")) and bool(p.get("error")) == bool(t.get("error"))
            if kind in ("links", "provenance"): same = True if not p.get("error") else same     # these describe the viewed entity itself
            if same: covered[kind][1] += 1
            else:
                dk = [x for x in set(p.get("data") or {}) | set(t.get("data") or {}) if (p.get("data") or {}).get(x) != (t.get("data") or {}).get(x)]
                bad += 1; print(f"  {k}/{p['id']} ({kind}) differing data keys {dk}: ", {x: ((p.get('data') or {}).get(x), (t.get('data') or {}).get(x)) for x in dk[:1]} if kind != "graph" else "", "DIFFERS: source={json.dumps(p.get('data'))[:140]} error={p.get('error')} | truth={json.dumps(t.get('data'))[:140]}")
    for x in (did, wid): j("DELETE", "/api/v1/builder/designs/" + x)
print("coverage kind: [rendered, equal]", covered); print("differences", bad)
