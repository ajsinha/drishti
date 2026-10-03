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

"""QA helper (data/engine area): mint tokens, call the scratch server :18962."""
import base64, hashlib, hmac, json, time, urllib.request, urllib.error
SECRET = "qa-secret-qa-secret-qa-secret-qa-secret-1234"
SRV = "http://127.0.0.1:18962"
ROLES = {'qa-admin': ['admin'], 'qa-author': ['author'], 'qa-author2': ['author'], 'qa-approver': ['approver'],
         'qa-viewer': ['viewer']}
def b64(d): return base64.urlsafe_b64encode(d).rstrip(b"=").decode()
def mint(sub, roles, ttl=3000):
    h = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    c = b64(json.dumps({"sub": sub, "roles": list(roles), "exp": int(time.time()) + ttl}, separators=(",", ":")).encode())
    return f"{h}.{c}." + b64(hmac.new(SECRET.encode(), f"{h}.{c}".encode(), hashlib.sha256).digest())
def call(method, path, user="qa-author", body=None, raw=None, ctype="application/json", timeout=60, srv=None, headers=None):
    h = dict(headers or {})
    if user: h["Authorization"] = "Bearer " + mint(user, ROLES.get(user, []))
    data = raw if raw is not None else (None if body is None else (body.encode() if isinstance(body, str) else json.dumps(body).encode()))
    if data is not None: h["Content-Type"] = ctype
    req = urllib.request.Request((srv or SRV) + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
    except Exception as e: return 0, repr(e).encode()
def j(method, path, user="qa-author", body=None, **k):
    st, b = call(method, path, user, body, **k)
    try: return st, json.loads(b)
    except Exception: return st, b.decode("utf-8", "replace")
