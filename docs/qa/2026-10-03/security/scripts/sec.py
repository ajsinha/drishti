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


"""Helpers for the 2026-10-03 security QA: mint tokens (shared secret), call the scratch server :18961 / console :17961, log."""
import base64, hashlib, hmac, json, os, time, datetime, urllib.request, urllib.error
W = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".workdir")).read().strip()
SECRET = open(W + "/secret").read().strip()
SRV = "http://127.0.0.1:18961"
CON = "http://127.0.0.1:17961"
LOG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "LOG.md")
ROLES = {"qa-admin": ["admin"], "qa-author": ["author"], "qa-author2": ["author"], "qa-approver": ["approver"],
         "qa-narrow": ["qa-narrow"], "qa-masked": ["qa-masked"], "qa-viewer": ["viewer"], "qa-mauth": ["qa-mauth"], "qa-narrow-author": ["qa-narrow-author"], "qa-root": ["admin"]}

def b64(d): return base64.urlsafe_b64encode(d).rstrip(b"=").decode()
def mint(sub, roles, ttl=600):
    h = b64(b'{"alg":"HS256","typ":"JWT"}')
    c = b64(json.dumps({"sub": sub, "roles": list(roles), "exp": int(time.time()) + ttl}, separators=(",", ":")).encode())
    return f"{h}.{c}." + b64(hmac.new(SECRET.encode(), f"{h}.{c}".encode(), hashlib.sha256).digest())

def call(method, url, token=None, body=None, headers=None, ctype="application/json", timeout=60, raw=None):
    h = dict(headers or {})
    if token: h["Authorization"] = f"Bearer {token}"
    data = raw
    if body is not None:
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
    if data is not None: h.setdefault("Content-Type", ctype)
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, {}, f"EXC {e!r}"

def api(user, method, path, body=None, **kw):
    tok = None if user is None else mint(user, ROLES.get(user, []))
    st, h, b = call(method, SRV + path, tok, body, **kw)
    return st, b

def jl(t):
    try: return json.loads(t)
    except Exception: return None

def log(check, req, expected, observed, verdict=""):
    ts = datetime.datetime.now().strftime("%H:%M:%S")
    esc = lambda s: str(s).replace("|", "/").replace("\n", " ")[:300]
    with open(LOG, "a") as f:
        f.write(f"| {ts} | {esc(check)} | `{esc(req)}` | {esc(expected)} | {esc(observed)} | {verdict} |\n")
    print(verdict, check, "->", str(observed)[:160])
