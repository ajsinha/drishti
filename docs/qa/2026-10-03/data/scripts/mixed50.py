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

"""50 stored entities of ~50 different kinds as one sample set: shape validates all, design + check run, limits (51, depth, size)."""
import json
from qa import *
from jsonschema import Draft202012Validator
kinds = "trade counterparty book desk netting-set bond var cva simm lcr loss-event customer deposit-account gene variant poll macro-indicator ir-curve fx-vol-surface stress-result pnl-explain mortgage exposure-profile agreement csa party legal-entity equity fx-spot commodity credit-curve hqla-holding nsfr cyber-incident third-party key-risk-indicator risk-control collections-case card-account personal-loan retail-portfolio election poll economy fiscal-position clinical-trial protein sequencing-run".split()
docs = []
for k in dict.fromkeys(kinds):
    st, d = j("POST", "/api/v1/builder/designs", body={"name": "m", "kind": k}); did = d["id"]
    st, o = j("POST", f"/api/v1/builder/designs/{did}/samples", body={"refs": {"kind": k, "count": 1}})
    if st == 200:
        eid = o["samples"][0]["name"].split(" ", 1)[1]
        st, raw = j("GET", f"/api/v1/entities/{k}/{eid}/raw")
        if st == 200: docs.append({"name": f"{k}.json", "document": raw["data"]})
    j("DELETE", f"/api/v1/builder/designs/{did}")
print("collected", len(docs), "docs of", len({d['name'] for d in docs}), "kinds")
docs = docs[:50]
st, sh = j("POST", "/api/v1/builder/shape", body={"samples": docs}); print("shape", st, len(json.dumps(sh)) if st == 200 else str(sh)[:200])
v = Draft202012Validator(sh["schema"]); bad = [d["name"] for d in docs if not v.is_valid(d["document"])]; print("invalid samples:", bad)
print("conflicts", len(sh["report"]["conflicts"]), "rare", len(sh["report"]["rare"]), "paths", len(sh["report"]["paths"]))
top = sh["schema"]; print("root required:", top.get("required"), "root type:", top.get("type"), "is map:", top.get("x-drishti", {}).get("map"))
st, de = j("POST", "/api/v1/builder/design", body={"kind": "mixed", "samples": docs}, timeout=120); print("design", st, str(de)[:150] if st != 200 else (len(de["yaml"].splitlines()), "yaml lines", "pruned", len(de.get("pruned", []))))
if st == 200:
    st, c = j("POST", "/api/v1/builder/check", body={"yaml": de["yaml"], "kind": "mixed", "samples": docs}, timeout=120); print("check", st, c.get("counts") if st == 200 else str(c)[:200])
    print("panel kinds:", sorted({l.split(":")[1].strip() for l in de["yaml"].splitlines() if l.strip().startswith("kind:") and l.startswith("    ")}))
extra = docs + [{"name": "x%d.json" % i, "document": {"a": i}} for i in range(5)]
st, o = j("POST", "/api/v1/builder/shape", body={"samples": extra}); print("55 samples:", st, str(o)[:130])
