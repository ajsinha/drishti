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

"""auto-design on hostile + random sample sets: the drafted Sutra must be valid and checking it on the same samples must show no error cells."""
import json, sys, random
from qa import *
from shape_hostile import CASES
def one(name, docs, kind="qak"):
    st, o = j("POST", "/api/v1/builder/design", body={"kind": kind, "samples": [{"name": f"s{i}", "document": d} for i, d in enumerate(docs)]}, timeout=120)
    if st != 200: return name, "design-%s" % st, str(o)[:200]
    y = o.get("yaml")
    st2, c = j("POST", "/api/v1/builder/check", body={"yaml": y, "kind": kind, "samples": [{"name": f"s{i}", "document": d} for i, d in enumerate(docs)]}, timeout=120)
    if st2 != 200: return name, "check-%s" % st2, (str(c)[:300], y[:300])
    cnt = c.get("counts")
    errs = [(p["id"], [x for x in p["cells"] if x.get("state") == "error"][:1] if isinstance(p["cells"], list) else None) for p in c["panels"] if "error" in json.dumps(p["cells"])]
    return name, "ok", {"panels": len(c["panels"]), "counts": cnt, "errors": errs[:3], "pruned": len(o.get("pruned", []))}
if __name__ == "__main__":
    for n, s in CASES.items():
        if s is None or isinstance(s, str): continue
        r = one(n, s); print(r[0], r[1], str(r[2])[:260])
