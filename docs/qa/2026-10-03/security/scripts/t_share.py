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


"""Share links: what a holder sees, revocation, renewal, anonymous."""
from sec import *
fx = json.load(open("fixtures.json")); D = fx["D"]; P = "/api/v1/builder/designs"; T = fx["token"]
V = "qa-narrow"   # a user who may not open trade
s, b = api(V, "GET", f"{P}/shared/{D}?token={T}")
log("share view as narrow user", f"GET {P}/shared/{D}?token=..", "Sutra, ops, sample names only", f"{s} keys={list(jl(b).keys()) if jl(b) else b[:80]}", "")
print(b[:1500])
log("share leak scan", "body scan", "no SECRET / sample content / notes", f"SECRET present={'SECRET' in b}; MX-20000001 present={'MX-20000001' in b}; CP-MERIDIAN present={'CP-MERIDIAN' in b}; owner present={'qa-author' in b}", "")
s, b = api(None, "GET", f"{P}/shared/{D}?token={T}"); log("share anon", "no auth", "401", s, "PASS" if s == 401 else "FAIL")
s, b = api(V, "GET", f"{P}/shared/{D}"); log("share no token", "missing token param", "400/404 no trace", f"{s} {b[:100]}", "")
# try the share token on other endpoints (as a different user)
for m, p in [("GET", f"{P}/{D}?share={T}"), ("GET", f"{P}/{D}/samples/document?name=brought&share={T}"), ("GET", f"{P}/{D}/preview?share={T}&token={T}"), ("GET", f"{P}/{D}/export?token={T}"), ("GET", f"{P}/{D}/versions?share={T}")]:
    s, b = api(V, m, p); log("share token on owner endpoint", f"{m} {p[:70]}..", "404 (token grants only /shared)", f"{s} leak={'SECRET' in b}", "PASS" if s == 404 and 'SECRET' not in b else "FAIL")
# tamper token: wrong secret, truncated, other id
sec = T.split(".")[1]
for name, t in [("flip last char", T[:-1] + ("A" if T[-1] != "A" else "B")), ("truncated", T[:-3]), ("empty secret", T.split(".")[0] + "."), ("only owner", T.split(".")[0]), ("padded", T + "="), ("space", T + "%20"), ("double dot", T + ".x")]:
    s, b = api(V, "GET", f"{P}/shared/{D}?token={t}"); log("share tamper", name, "404", f"{s}", "PASS" if s == 404 else "FAIL")
# other id with this token
s, b = api(V, "GET", f"{P}/shared/{fx['N']}?token={T}"); log("share token vs other design", "N id + D token", "404", s, "PASS" if s == 404 else "FAIL")
# revoke
s, b = api("qa-author2", "DELETE", f"{P}/{D}/share"); log("unshare by non-owner", "DELETE share", "404", s, "PASS" if s == 404 else "FAIL")
s, b = api("qa-author", "DELETE", f"{P}/{D}/share"); log("unshare", "DELETE share", "200", s)
s, b = api(V, "GET", f"{P}/shared/{D}?token={T}"); log("share after revoke", "old token", "404 at once", s, "PASS" if s == 404 else "FAIL")
s, b = api("qa-author", "POST", f"{P}/{D}/share"); T2 = jl(b)["token"]
s, b = api(V, "GET", f"{P}/shared/{D}?token={T2}"); log("share new token", "T2", "200", s)
s, b = api(V, "GET", f"{P}/shared/{D}?token={T}"); log("old token after re-share", "T1", "404", s, "PASS" if s == 404 else "FAIL")
s, b = api("qa-author", "POST", f"{P}/{D}/share"); T3 = jl(b)["token"]
s, b = api(V, "GET", f"{P}/shared/{D}?token={T2}"); log("renew kills previous", "T2 after renew", "404", s, "PASS" if s == 404 else "FAIL")
s, b = api(V, "GET", f"{P}/shared/{D}?token={T3}"); log("renewed works", "T3", "200", s)
# does a shared design survive delete / duplicate: duplicate shouldn't copy share
s, b = api("qa-author", "POST", f"{P}/{D}/duplicate", {"name": "dupe"}); dup = jl(b); log("duplicate share flag", "duplicate of shared design", "shared=false", f"{s} shared={dup.get('shared')}", "PASS" if dup.get('shared') is False else "FAIL")
s, b = api(V, "GET", f"{P}/shared/{dup['id']}?token={T3}"); log("old token on duplicate", "T3 on dup id", "404", s, "PASS" if s == 404 else "FAIL")
api("qa-author", "DELETE", f"{P}/{dup['id']}")
# edit after share: does the link show the live Sutra (incl. data pasted into it)? 
fx["token"] = T3; json.dump(fx, open("fixtures.json", "w"))
