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


"""Four-eyes: self-approval via every route; approval by the right person; design status afterwards."""
from sec import *
P = "/api/v1/builder/designs"; fx = json.load(open("fixtures.json")); D = fx["D"]
s, b = api("qa-author", "GET", f"{P}/{D}"); sut = jl(b)["sutra"]
api("qa-author", "PATCH", f"{P}/{D}", {"sutra": sut + "\n# four eyes\n"})
s, b = api("qa-author", "POST", f"{P}/{D}/propose", {"note": "four eyes"}); pid = jl(b)["proposal"]["id"]; open("pid_D.txt", "w").write(pid)
s, b = api("qa-author", "GET", f"/api/v1/sutras/proposals/{pid}"); print(jl(b)["status"])
ROLES["qa-author-appr"] = ["author", "approver"]
for u, roles in [("qa-author", ["author", "approver"]), ("qa-author", ["admin"]), ("qa-author", ["author", "approver", "admin"])]:
    tok = mint(u, roles)
    s, h, b = call("POST", SRV + f"/api/v1/sutras/proposals/{pid}/approve", tok, {"comment": "self"})
    log("four-eyes self approve", f"POST approve as {u} roles={roles}", "refused (nobody approves their own)", f"{s} {b[:90]}", "PASS" if s in (403, 409) else "FAIL")

# fresh valid design: a new Sutra name, so approval can succeed
SUT = "rachana: 1\nsutra: qa-flow\nversion: 1\ndescription: x\nmatch: { kind: qaflow, priority: 1 }\ntitle: { id: $.id }\npanels: []\n"
s, b = api("qa-author", "POST", P, {"name": "flow", "kind": "qaflow", "sutra": SUT}); F = jl(b)["id"]
api("qa-author", "POST", f"{P}/{F}/samples", {"samples": [{"name": "s", "document": {"id": "A1"}}]})
s, b = api("qa-author", "POST", f"{P}/{F}/propose", {"note": "flow"}); pid = jl(b)["proposal"]["id"]
for u, roles in [("qa-author", ["author", "approver"]), ("qa-author", ["admin"])]:
    s, h, b = call("POST", SRV + f"/api/v1/sutras/proposals/{pid}/approve", mint(u, roles), {"comment": "self"})
    log("four-eyes self approve (valid proposal)", f"approve as {u} roles={roles}", "403 four eyes", f"{s} {b[:80]}", "PASS" if s == 403 else "FAIL")
s, b = api("qa-author2", "POST", f"/api/v1/sutras/proposals/{pid}/approve", {"comment": "no right"}); log("four-eyes author2 (not approver)", "approve", "403", s, "PASS" if s == 403 else "FAIL")
s, b = api("qa-approver", "POST", f"/api/v1/sutras/proposals/{pid}/approve", {"comment": "ok"}); log("four-eyes real approval", "qa-approver approves", "200", f"{s} {b[:80]}", "PASS" if s == 200 else "FAIL")
s, b = api("qa-author", "GET", f"{P}/{F}"); log("design status after approval", "GET design", "live(v1)", jl(b)["status"])
s, b = api("qa-author", "GET", "/api/v1/sutras/qa-flow/1/source"); log("approved Sutra readable", "GET source", "200", s)
# a non-author, non-approver tries to re-approve / the design owner edits afterwards: status resets
s, b = api("qa-author", "PATCH", f"{P}/{F}", {"sutra": SUT + "# edit\n"}); log("edit after live", "PATCH", "status draft again", jl(b)["status"])
