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


"""Open-design / gated-deploy policy: who can save/propose/approve/bind/load a pack, by any route, method or path variant."""
from sec import *
P = "/api/v1/builder/designs"
SUT = "rachana: 1\nsutra: qa-evil\nversion: 1\ndescription: x\nmatch: { kind: qaevil, priority: 1 }\ntitle: { id: $.id }\npanels: []\n"
nonauthors = ["qa-viewer", "qa-narrow", "qa-masked", None]
# 1. direct Sutra save routes by non-authors, with variants
for u in nonauthors:
    for path in ["/api/v1/sutras", "/api/v1/sutras/", "/api/v1/sutras;x=1", "/api/v1/sutras?note=a", "/api/v1//sutras", "/api/v1/%73utras"]:
        for ct in ["text/yaml", "text/plain", "application/yaml"]:
            s, b = api(u, "POST", path, SUT, ctype=ct)
            ok = s in (400, 401, 403, 404, 405, 415) and not (s == 400 and "sutra" in b.lower() and "path" not in b.lower() and "decod" not in b.lower() and "DRS-5001" not in b)
            log(f"policy save {u}", f"POST {path} [{ct}]", "refused", f"{s} {b[:60]}", "PASS" if ok else "FAIL")
    for m, path in [("PUT", "/api/v1/sutras"), ("PATCH", "/api/v1/sutras/qa-evil"), ("PUT", "/api/v1/sutras/qa-evil/1"), ("DELETE", "/api/v1/sutras/abs"), ("DELETE", "/api/v1/sutras/abs/1")]:
        s, b = api(u, m, path, SUT, ctype="text/yaml"); log(f"policy save {u}", f"{m} {path}", "refused", f"{s} {b[:60]}", "PASS" if s in (401, 403, 404, 405) else "FAIL")
# 2. design propose by non-authors (own design, any user can create)
for u in ["qa-viewer", "qa-narrow", "qa-masked"]:
    s, b = api(u, "POST", P, {"name": "mine", "kind": "qaevil", "sutra": SUT}); d = jl(b)["id"]
    for m, path in [("POST", f"{P}/{d}/propose"), ("POST", f"{P}/{d}/propose/"), ("POST", f"{P}/{d}/propose;x=1"), ("PUT", f"{P}/{d}/propose"), ("GET", f"{P}/{d}/propose"),
                    ("POST", f"{P}/{d}/bind"), ("POST", f"{P}/{d}/save-file"), ("PATCH", f"{P}/{d}")]:
        s, b = api(u, m, path, {"file": "evil.yaml", "name": "n", "note": "x"})
        want = (m == "PATCH" and s == 200)
        log(f"policy propose {u}", f"{m} {path.replace(d,'<own>')}", "403 (PATCH of own design 200)", f"{s} {b[:90]}", "PASS" if (s in (400, 403, 404, 405) or want) else "FAIL")
    api(u, "DELETE", f"{P}/{d}")
# 3. approve/reject/withdraw
s, b = api("qa-author", "GET", f"{P}/{json.load(open('fixtures.json'))['D']}"); sut = jl(b)["sutra"]
api("qa-author", "PATCH", f"{P}/{json.load(open('fixtures.json'))['D']}", {"sutra": sut + "\n# again\n"})
s, b = api("qa-author", "POST", f"{P}/{json.load(open('fixtures.json'))['D']}/propose", {"note": "policy test"}); pid = jl(b)["proposal"]["id"]; open("pid_D.txt", "w").write(pid)
for u in ["qa-viewer", "qa-narrow", "qa-masked", "qa-author2", "qa-mauth", None, "qa-author"]:
    for act in ["approve", "reject", "withdraw"]:
        for path in [f"/api/v1/sutras/proposals/{pid}/{act}", f"/api/v1/sutras/proposals/{pid}/{act}/", f"/api/v1/sutras/proposals/{pid.lower()}/{act}"]:
            s, b = api(u, "POST", path, {"comment": "qa"})
            expect_ok = (u == "qa-author" and act == "withdraw")
            if expect_ok and s == 200: pass
            log(f"policy {act} {u}", f"POST {path}", "403 unless owner withdraw", f"{s} {b[:70]}", "PASS" if (s in (400, 401, 403, 404, 405) or expect_ok) else "FAIL")
# state still pending?
s, b = api("qa-admin", "GET", f"/api/v1/sutras/proposals/{pid}"); log("policy state", pid, "pending/withdrawn only by owner", f"{jl(b)['status']}", "")
