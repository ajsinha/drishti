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

import qa, base64, json, time, hmac, hashlib
URL=qa.SRV+"/api/v1/admin/status"   # admin-only, good target
def hit(tok):
    return qa.call("GET", URL, tok)[0]
def b64(d): return base64.urlsafe_b64encode(d).rstrip(b"=").decode()

# 1 valid admin token
print("valid admin:", hit(qa.mint("qa-admin",["admin"])) , "expect 200")
# 2 valid token but non-admin role -> 403
print("valid viewer on admin ep:", hit(qa.mint("qa-viewer",["viewer"])), "expect 403")
# 3 alg=none, no signature trickery
h=b64(json.dumps({"alg":"none","typ":"JWT"}).encode())
c=b64(json.dumps({"sub":"qa-admin","roles":["admin"],"exp":int(time.time())+300}).encode())
print("alg=none (empty sig):", hit(f"{h}.{c}."), "expect 401")
print("alg=none (text sig):", hit(f"{h}.{c}.{b64(b'x')}"), "expect 401")
# 4 alg confusion: header says RS256 but we HMAC-sign with the shared secret
h2=b64(json.dumps({"alg":"RS256","typ":"JWT"}).encode())
sig=b64(hmac.new(qa.SECRET.encode(), f"{h2}.{c}".encode(), hashlib.sha256).digest())
print("alg=RS256 confusion:", hit(f"{h2}.{c}.{sig}"), "expect 401")
# 5 wrong secret
print("wrong secret:", hit(qa.mint("qa-admin",["admin"],secret="x"*48)), "expect 401")
# 6 expired beyond skew (default 30s)
print("expired 120s ago:", hit(qa.mint("qa-admin",["admin"],exp=int(time.time())-120)), "expect 401")
# 7 expired within skew (10s ago) -> should PASS (clock skew tolerance)
print("expired 10s ago (within 30s skew):", hit(qa.mint("qa-admin",["admin"],exp=int(time.time())-10)), "expect 200 (skew)")
# 8 no exp claim
h3=b64(json.dumps({"alg":"HS256","typ":"JWT"}).encode())
c3=b64(json.dumps({"sub":"qa-admin","roles":["admin"]}).encode())
sig3=b64(hmac.new(qa.SECRET.encode(), f"{h3}.{c3}".encode(), hashlib.sha256).digest())
print("no exp claim:", hit(f"{h3}.{c3}.{sig3}"), "expect 401")
# 9 tampered payload (bump roles after signing with valid base)
good=qa.mint("qa-viewer",["viewer"])
parts=good.split(".")
tampered=parts[0]+"."+b64(json.dumps({"sub":"qa-viewer","roles":["admin"],"exp":int(time.time())+300}).encode())+"."+parts[2]
print("tampered roles->admin:", hit(tampered), "expect 401")
# 10 missing token
print("no token:", qa.call("GET",URL)[0], "expect 401")
# 11 malformed (2 parts)
print("2-part token:", hit("aaa.bbb"), "expect 401")
# 12 future exp but huge (far future) still valid? sanity
print("far-future exp:", hit(qa.mint("qa-admin",["admin"],exp=int(time.time())+10**9)), "expect 200 (no nbf/iat/max-ttl check server-side)")
# 13 roles claim with '*' wildcard -> does server honor '*'? (Principal.anonymous uses '*' only when sec off)
print("roles=['*'] forged:", hit(qa.mint("attacker",["*"])), "expect 200 if server trusts '*' in token (check Entitlements.roleAllows)")
