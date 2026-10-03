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


"""Console pages: foreign designs, share page, reviews, stored-XSS probes, error leakage."""
from t_console import *
S = json.load(open("console_sessions.json")); fx = json.load(open("fixtures.json")); D = fx["D"]; T = fx["token"]
H = {"Origin": CON}
def page(u, path, **k):
    st, h, b = creq(S[u], "GET", path, **k); return st, h, b
# foreign design by every console GET route, as author2 / narrow / admin
routes = ["/build/d/{D}", "/build/designs/{D}", "/build/designs/{D}/shape", "/build/designs/{D}/shape/download", "/build/designs/{D}/preview", "/build/designs/{D}/sample?name=brought", "/build/designs/{D}/versions", "/build/designs/{D}/versions/0",
          "/build/designs/{D}/diff", "/build/designs/{D}/export", "/build/designs/{D}/sync"]
for u in ["qa-author2", "qa-narrow", "qa-admin"]:
    for r in routes:
        st, h, b = page(u, r.format(D=D)); leak = any(k in b for k in ["SECRET", "victim"])
        log(f"console foreign design {u}", f"GET {r.replace('{D}','<D>')}", "404/403, no leak", f"{st} leak={leak}", "PASS" if st in (401, 403, 404) and not leak else "FAIL")
    for m, r, body in [("POST", "/build/designs/{D}/check", None), ("POST", "/build/designs/{D}/autodesign", None), ("POST", "/build/designs/{D}/ops", '{"baseRev":1,"ops":[]}'), ("POST", "/build/designs/{D}/preview-file", '{"document":{}}'),
                       ("POST", "/build/designs/{D}/duplicate", '{}'), ("DELETE", "/build/designs/{D}", None), ("PATCH", "/build/designs/{D}", '{"name":"x"}'), ("POST", "/build/designs/{D}/suggest", '{"path":"$.a"}')]:
        st, h, b = creq(S[u], m, r.format(D=D), body, headers=H, ctype="application/json" if body else None); leak = any(k in b for k in ["SECRET", "victim"])
        log(f"console foreign design {u}", f"{m} {r.replace('{D}','<D>')}", "404/403", f"{st} leak={leak}", "PASS" if st in (401, 403, 404) and not leak else "FAIL")
# share page
for u in ["qa-narrow", "qa-viewer"]:
    st, h, b = page(u, f"/build/d/{D}?share={T}")
    log(f"console share page {u}", "GET /build/d/<D>?share=..", "200 read-only; no sample contents/notes", f"{st} SECRET={('SECRET' in b)} notes={('SECRET-NOTE' in b)} sampleNames={('trade MX-20000001' in b)}", "")
    st, h, b2 = creq(S[u], "POST", f"/build/shared/{D}/preview", json.dumps({"token": T, "kind": "trade", "id": "MX-20000001"}), headers=H, ctype="application/json")
    log(f"console share preview stored entity {u}", "POST /build/shared/<D>/preview {kind:trade,id:MX-20000001}", "narrow: no access; viewer: data", f"{st} {b2[:120]}", "")
    st, h, b2 = creq(S[u], "POST", f"/build/shared/{D}/preview", json.dumps({"token": T, "document": {"tradeId": "mine"}}), headers=H, ctype="application/json"); log(f"console share preview own doc {u}", "POST preview {document}", "200", f"{st}", "")
    st, h, b2 = creq(S[u], "POST", f"/build/shared/{D}/preview", json.dumps({"token": "bad", "document": {}}), headers=H, ctype="application/json"); log(f"console share bad token {u}", "POST preview bad token", "404", st, "PASS" if st == 404 else "FAIL")
    st, h, b2 = creq(S[u], "POST", f"/build/shared/{D}/preview", json.dumps({"token": T, "kind": "../../x", "id": "a/b"}), headers=H, ctype="application/json"); log(f"console share preview odd kind {u}", "kind=../../x", "4xx no trace", f"{st} {b2[:100]}")
# anon pages
for path in [f"/build/d/{D}?share={T}", "/build", "/build/reviews", f"/build/designs/{D}"]:
    st, h, b = creq("", "GET", path); log("console anon", f"GET {path[:50]}", "302 to sign-in / 401", f"{st} {h.get('Location','')[:40]}", "PASS" if st in (302, 303, 401) else "FAIL")
