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

"""Base Sutra moves under an open design: does anything notice / replay?"""
import json
from qa import *
st, d = j("POST", "/api/v1/builder/designs", body={"name": "rb", "kind": "book", "base": "book@1"}); print("create", st, {k: d.get(k) for k in ("id", "base", "rev", "status")})
i = d["id"]
st, o = j("POST", f"/api/v1/builder/designs/{i}/ops", body={"baseRev": d["rev"], "ops": [{"op": "setTitle", "title": {"pill": "RB", "id": "$.id"}}]}); print("op", st, o.get("applied"))
st, src = j("GET", "/api/v1/sutras/book/1/source"); 
st, p = j("POST", "/api/v1/sutras?note=moving-base", user="qa-author2", raw=(src.replace("version: 1","version: 2",1) + "\n# base moved\n").encode(), ctype="text/yaml"); print("propose", st, str(p)[:160])
pid = (p.get("proposal") or {}).get("id")
st, a = j("POST", f"/api/v1/sutras/proposals/{pid}/approve", user="qa-approver"); print("approve", st, str(a)[:200])
st, lst = j("GET", "/api/v1/sutras"); print([x for x in lst if x["name"] == "book"])
st, d2 = j("GET", f"/api/v1/builder/designs/{i}"); print("design after base moved:", {k: d2.get(k) for k in ("base", "rev", "status")}, [k for k in d2 if "base" in k.lower() or "move" in k.lower() or "conflict" in k.lower()])
st, v = j("GET", f"/api/v1/builder/designs/{i}/versions"); print(str(v)[:200])
j("DELETE", f"/api/v1/builder/designs/{i}")
