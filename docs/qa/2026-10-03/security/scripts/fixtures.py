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


"""Fixtures: designs owned by qa-author (with marker samples, notes, refs, a share link) and by qa-narrow-author. Writes fixtures.json."""
from sec import *
fx = {}
A = "qa-author"
st, b = api(A, "POST", "/api/v1/builder/designs", {"name": "victim", "kind": "trade", "base": "abs@1", "notes": "SECRET-NOTE-9f3a"})
d = jl(b); fx["D"] = d["id"]; print(st, d["id"], d.get("rev"))
doc = {"tradeId": "SECRETDOC-1", "mtm": 123456789, "trader": "SECRET-TRADER-NAME", "productType": "ABS", "notes": "SECRET-SAMPLE-CONTENT-77c1"}
st, b = api(A, "POST", f"/api/v1/builder/designs/{fx['D']}/samples", {"samples": [{"name": "brought", "document": doc}], "refs": {"kind": "trade", "ids": ["MX-20000001"]}})
print(st, b[:200])
st, b = api(A, "POST", f"/api/v1/builder/designs/{fx['D']}/samples", {"refs": {"kind": "counterparty", "ids": ["CP-MERIDIAN-RE"]}}); print(st, b[:120])
st, b = api(A, "POST", f"/api/v1/builder/designs/{fx['D']}/share"); s = jl(b); print(st, b[:200]); fx["token"] = s["token"]
fx["srcdoc"] = doc
N = "qa-narrow-author"
st, b = api(N, "POST", "/api/v1/builder/designs", {"name": "narrow", "kind": "counterparty"}); fx["N"] = jl(b)["id"]; print(st, fx["N"])
json.dump(fx, open("fixtures.json", "w")); print(fx)
