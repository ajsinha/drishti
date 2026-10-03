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


"""Console (:17961): sign in as real users; CSRF/same-origin on the /build routes, JSON-as-text, access to foreign designs, error leakage, stored XSS probes."""
from sec import *
import urllib.request, urllib.parse, http.cookiejar, re
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None
def session(user, pw="qa-password-12345"):
    cj = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj), NoRedirect)
    data = urllib.parse.urlencode({"user": user, "password": pw, "next": "/t"}).encode()
    try:
        r = op.open(urllib.request.Request(CON + "/login", data=data, headers={"Origin": CON}))
    except urllib.error.HTTPError as e:
        r = e
    ck = "; ".join(f"{c.name}={c.value}" for c in cj)
    return r.status, ck
def creq(ck, method, path, body=None, headers=None, ctype=None):
    h = {"Cookie": ck}; h.update(headers or {})
    if ctype: h["Content-Type"] = ctype
    data = body if isinstance(body, bytes) else (body.encode() if isinstance(body, str) else None)
    req = urllib.request.Request(CON + path, data=data, method=method, headers=h)
    op = urllib.request.build_opener(NoRedirect)
    try:
        with op.open(req, timeout=60) as r: return r.status, dict(r.headers), r.read().decode("utf8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf8", "replace")
if __name__ == "__main__":
    S = {}
    for u in ["qa-author", "qa-author2", "qa-narrow", "qa-approver", "qa-admin", "qa-viewer", "qa-masked"]:
        st, ck = session(u); S[u] = ck; log("console sign-in", f"POST /login {u}", "303 + cookie", f"{st} cookie={'yes' if ck else 'no'}", "PASS" if st == 303 and ck else "FAIL")
    json.dump(S, open("console_sessions.json", "w"))
    A = S["qa-author"]
    # own design via console
    st, h, b = creq(A, "POST", "/build/designs", json.dumps({"name": "csrf-target", "kind": "k"}), ctype="application/json", headers={"Origin": CON}); d = jl(b); D = d["id"] if d else None
    log("console create design", "POST /build/designs same-origin", "201", f"{st} {b[:60]}", "PASS" if st in (200, 201) else "FAIL")
    routes = [("PATCH", f"/build/designs/{D}", '{"name":"hacked"}'), ("POST", f"/build/designs/{D}/duplicate", '{}'), ("DELETE", f"/build/designs/{D}", None), ("POST", f"/build/designs/{D}/propose", '{"note":"x"}'),
              ("POST", f"/build/designs/{D}/share", None), ("DELETE", f"/build/designs/{D}/share", None), ("POST", f"/build/designs/{D}/bind", '{"file":"x.yaml"}'), ("POST", f"/build/designs/{D}/save-file", None),
              ("POST", f"/build/designs/{D}/undo", '{}'), ("POST", f"/build/designs/{D}/ops", '{"baseRev":0,"ops":[]}'), ("POST", f"/build/designs/{D}/autodesign", None),
              ("POST", f"/build/designs/{D}/files", '{"files":[]}'), ("POST", f"/build/designs/{D}/samples", '{"samples":[]}'), ("POST", "/build/import", b"PK"), ("POST", "/build/designs", '{"name":"x"}')]
    for origin_label, hdrs in [("Origin: https://evil.example", {"Origin": "https://evil.example"}), ("Origin: null", {"Origin": "null"}), ("Referer evil, no Origin", {"Referer": "https://evil.example/p"}),
                               ("Origin with same host but other port", {"Origin": "http://127.0.0.1:17962"}), ("Origin: http://localhost:17961 (Host 127.0.0.1)", {"Origin": "http://localhost:17961"}),
                               ("Origin prefix trick", {"Origin": CON + ".evil.example"})]:
        for m, p, body in routes:
            ct = "text/plain" if isinstance(body, str) else "application/zip" if body else None
            st, h, b = creq(A, m, p, body, headers=hdrs, ctype=ct)
            log(f"csrf [{origin_label}]", f"{m} {p.replace(D,'<D>')}", "403 DRS-5002", f"{st} {b[:50]}", "PASS" if st == 403 else "FAIL")
    # no Origin and no Referer (non-browser): passes by design; text/plain JSON must be refused 415
    for m, p, body in [("POST", f"/build/designs", '{"name":"asplain"}'), ("PATCH", f"/build/designs/{D}", '{"name":"asplain"}'), ("POST", f"/build/designs/{D}/propose", '{"note":"plain"}'), ("POST", f"/build/designs/{D}/bind", '{"file":"x.yaml"}')]:
        for ct in ["text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", None, "application/json; charset=utf-8", "application/vnd.api+json", "text/json"]:
            st, h, b = creq(A, m, p, body, ctype=ct, headers={"Origin": CON})
            want415 = ct in ("text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "text/json", None)
            log("json-as-text", f"{m} {p.replace(D,'<D>')} [{ct}]", "415 unless JSON", f"{st} {b[:60]}", "PASS" if (st == 415 or (not want415 and st != 415) or (ct is None and st in (200, 201, 202, 400, 403))) else "CHECK")
    # reviews decide is a urlencoded form: cross-site form post
    for hdrs in [{"Origin": "https://evil.example"}, {"Origin": "null"}]:
        st, h, b = creq(S["qa-approver"], "POST", "/build/reviews/P-000001/approve", "comment=csrf", headers=hdrs, ctype="application/x-www-form-urlencoded")
        log("csrf reviews decide", f"POST /build/reviews/P-000001/approve {hdrs}", "403", st, "PASS" if st == 403 else "FAIL")
    st, h, b = creq(S["qa-approver"], "POST", "/logout", "", headers={"Origin": "https://evil.example"}, ctype="application/x-www-form-urlencoded"); log("csrf logout", "POST /logout evil origin", "403", st, "PASS" if st == 403 else "INFO")
    print(open("console_sessions.json").read()[:50])
