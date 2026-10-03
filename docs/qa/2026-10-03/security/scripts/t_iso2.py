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


"""Path/method variants against another user's design, id oracle comparison, list enumeration, owner-name traversal in share tokens."""
from sec import *
import base64
fx = json.load(open("fixtures.json")); D = fx["D"]; P = "/api/v1/builder/designs"
U = "qa-author2"
variants = [("GET", f"{P}/{D}/"), ("GET", f"{P}/{D.upper()}"), ("GET", f"{P}/{D};x=1"), ("GET", f"{P};x/{D}"), ("GET", f"{P}//{D}"), ("GET", f"{P}/%2e/{D}"),
 ("GET", f"{P}/{D}%00"), ("GET", f"{P}/{D}.json"), ("GET", f"/api/v1/builder/designs/../designs/{D}"), ("GET", f"/api/%76%31/builder/designs/{D}"), ("GET", f"/api/v1;x/builder/designs/{D}"),
 ("HEAD", f"{P}/{D}"), ("OPTIONS", f"{P}/{D}"), ("TRACE", f"{P}/{D}"), ("PUT", f"{P}/{D}", {"name": "x"}), ("GET", f"{P}/{D}/preview/"), ("GET", f"{P}/shared/{D}"),
 ("GET", f"{P}/shared/{D}?token=x"), ("GET", f"{P}/{D}/export.zip"), ("GET", f"{P}/{D}/samples/document?name=brought&name=x"),
 ("GET", f"{P}/{D}?id={D}&user=qa-author"), ("GET", f"{P}/{D}", None, {"X-User": "qa-author", "X-Forwarded-User": "qa-author"})]
for v in variants:
    m, p = v[0], v[1]; body = v[2] if len(v) > 2 else None; hd = v[3] if len(v) > 3 else None
    tok = mint(U, ROLES[U])
    st, h, b = call(m, SRV + p, tok, body, headers=hd)
    leak = any(k in b for k in ["SECRET", "MX-20000001", "victim"])
    log("iso-variant", f"{m} {p}", "no leak, not 200 with data", f"{st} leak={leak} {b[:70]}", "PASS" if not leak and st != 200 or (st == 200 and not leak and m in ('HEAD','OPTIONS')) else "FAIL")
# existence oracle: foreign id vs unknown id
s1, b1 = api(U, "GET", f"{P}/{D}"); s2, b2 = api(U, "GET", f"{P}/{'0'*12}"); s3, b3 = api(U, "GET", f"{P}/zzzz")
log("id-oracle", "foreign vs random vs malformed id", "same status/body shape", f"{s1} {b1[:90]} | {s2} {b2[:90]} | {s3} {b3[:90]}", "PASS" if s1 == s2 else "FAIL")
# the 'before anything is said' on samples POST
for u in [U]:
    s, b = api(u, "POST", f"{P}/{D}/samples", {"refs": {"kind": "secretkind", "ids": ["x"]}}); log("iso-order", "POST samples refs kind=unopenable on foreign design", "404 not 403 (no kind oracle)", f"{s} {b[:80]}", "")
# enumeration: lists
for u in [U, "qa-viewer", "qa-admin"]:
    s, b = api(u, "GET", P); log("list", f"GET {P} as {u}", "only own", f"{s} {[x['id'] for x in jl(b)['designs']]}", "PASS" if D not in b else "FAIL")
# owner traversal in share token: owner in token = base64 user
for owner in ["../qa-author", "qa-author/../qa-author", "QA-AUTHOR", "qa-author ", "qa-author\u0000", "", "%2e%2e"]:
    t = base64.urlsafe_b64encode(owner.encode()).rstrip(b"=").decode() + "." + fx["token"].split(".")[1]
    s, b = api(U, "GET", f"{P}/shared/{D}?token={t}")
    log("share-owner-variant", f"token owner={owner!r}", "404", f"{s} {b[:80]}", "PASS" if s == 404 else "FAIL")
# random guess rate: 20 random ids
import secrets
codes = {api(U, "GET", f"{P}/{secrets.token_hex(6)}")[0] for _ in range(20)}
log("id-guess", "20 random 12-hex ids", "all 404", sorted(codes), "PASS" if codes == {404} else "FAIL")
