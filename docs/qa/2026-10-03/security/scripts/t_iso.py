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


"""Isolation: every design endpoint of qa-author's design D, called by other users / anonymous. Expect 404 DRS-5006 (or 401) and no marker in any body."""
from sec import *
fx = json.load(open("fixtures.json")); D = fx["D"]; B = f"/api/v1/builder/designs/{D}"
calls = [("GET", B), ("PATCH", B, {"name": "pwned"}), ("POST", B + "/duplicate", {}), ("POST", B + "/samples", {"samples": [{"name": "x", "document": {"a": 1}}]}),
 ("DELETE", B + "/samples?name=brought"), ("GET", B + "/samples/document?name=brought"), ("POST", B + "/shape"), ("GET", B + "/preview"),
 ("GET", B + "/preview?sample=brought"), ("POST", B + "/autodesign"), ("POST", B + "/ops", {"baseRev": 1, "ops": []}), ("GET", B + "/versions"), ("GET", B + "/versions/0"),
 ("POST", B + "/undo", {}), ("POST", B + "/redo", {}), ("POST", B + "/check"), ("POST", B + "/propose", {}), ("GET", B + "/export"), ("POST", B + "/share"),
 ("DELETE", B + "/share"), ("POST", B + "/bind", {"file": "x.yaml"}), ("DELETE", B + "/bind"), ("POST", B + "/save-file"), ("GET", B + "/sync"), ("DELETE", B)]
bad = 0
for u in [None, "qa-author2", "qa-viewer", "qa-narrow", "qa-approver", "qa-admin"]:
    for c in calls:
        m, p = c[0], c[1]; body = c[2] if len(c) > 2 else None
        st, b = api(u, m, p, body)
        leak = any(k in b for k in ["SECRET", "MX-20000001", "victim"])
        ok = (st in (401, 403, 404)) and not leak
        if not ok: bad += 1
        log(f"isolation {u}", f"{m} {p}", "401/403/404, no leak", f"{st} leak={leak} {b[:80]}", "PASS" if ok else "FAIL")
st, b = api("qa-author", "GET", B); print("owner still sees design intact:", st, jl(b)["name"], jl(b)["rev"], len(jl(b)["samples"]))
print("failures:", bad)
