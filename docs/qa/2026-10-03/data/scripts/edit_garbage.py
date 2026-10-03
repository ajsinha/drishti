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

"""setOption with wrong-typed values: does the applier refuse (located problem) or write a Sutra that then fails to load / render?"""
import json
from qa import *
base = open("base.sutra.yaml").read()
doc = {"id": "T1", "mtm": 5, "coupon": 0.1, "qty": 3, "history": [{"date": "2026-01-0%d" % i, "v": i} for i in range(1, 6)], "legs": [{"name": "a", "amount": 1}]}
cases = [("terms", "area", {"a": 1}), ("terms", "area", 5), ("terms", "area", "bottom"), ("terms", "span", "wide"), ("terms", "span", {"a": 1}), ("terms", "span", 99), ("terms", "span", -3), ("terms", "span", 0), ("terms", "span", 2.5),
         ("terms", "span", True), ("terms", "height", "tall"), ("terms", "height", 100000), ("legs", "rows", {"a": 1}), ("legs", "rows", 5), ("legs", "columns", "oops"), ("legs", "columns", [1, 2]), ("terms", "kind", "nope"), ("terms", "kind", "table"),
         ("terms", "id", "legs"), ("terms", "id", "has space"), ("terms", "id", ""), ("terms", "id", None), ("hist", "x", [1]), ("hist", "title", {"a": 1}), ("terms", "columns", None), ("terms", "tone", "red"), ("legs", "children", "@.x")]
for p, opt, v in cases:
    st, o = j("POST", "/api/v1/builder/edit", body={"yaml": base, "ops": [{"op": "setOption", "panel": p, "option": opt, "value": v}]})
    res = "APPLIED" if o.get("applied") else "refused: " + (o["problems"][0]["code"] + " " + o["problems"][0]["message"][:90] if o.get("problems") else "?")
    extra = ""
    if o.get("applied"):
        st2, c = j("POST", "/api/v1/builder/check", body={"yaml": o["yaml"], "kind": "trade", "samples": [{"name": "s", "document": doc}]})
        extra = f"check={st2} " + (json.dumps(c.get("counts")) if st2 == 200 else str(c)[:140])
    print(f"{p}.{opt}={json.dumps(v)[:20]:22} {res} {extra}")
