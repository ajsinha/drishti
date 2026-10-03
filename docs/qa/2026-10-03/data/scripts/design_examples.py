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

"""Auto-design on the 10 shipped examples: kinds drafted vs the hand-made Sutra, check matrix, pruning, coverage of top-level fields."""
import json, glob, os, re, yaml
from qa import *
E = "/home/ashutosh/IdeaProjects/drishti/docs/guides/examples/"
for f in sorted(glob.glob(E + "*.json")):
    n = os.path.basename(f)[:-5]; doc = json.load(open(f))
    hand = yaml.safe_load(open(E + n + ".sutra.yaml")); hk = sorted({p["kind"] for p in hand.get("panels", [])})
    kind = hand["match"]["kind"]
    st, o = j("POST", "/api/v1/builder/design", body={"kind": kind, "samples": [{"name": n, "document": doc}]}, timeout=120)
    if st != 200: print(n, "DESIGN", st, str(o)[:200]); continue
    auto = yaml.safe_load(o["yaml"]); ak = [p["kind"] for p in auto.get("panels", [])]
    st, c = j("POST", "/api/v1/builder/check", body={"yaml": o["yaml"], "kind": kind, "samples": [{"name": n, "document": doc}]})
    cnt = c.get("counts") if st == 200 else st
    txt = o["yaml"]
    top = [k for k in doc.keys()] if isinstance(doc, dict) else []
    missing = [k for k in top if "$." + k not in txt]
    print(f"{n}: hand={len(hand.get('panels',[]))} kinds={hk}\n   auto={len(ak)} kinds={sorted(set(ak))} counts={cnt} pruned={[p.get('id') or p for p in o.get('pruned',[])][:4]}\n   top-level fields not referenced: {missing}")
