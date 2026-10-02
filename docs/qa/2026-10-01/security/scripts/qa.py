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

"""QA helper: mint tokens like the console does, call the scratch server (18981) and console (17981), and log."""
import base64, hashlib, hmac, json, os, time, datetime, urllib.request, urllib.error, urllib.parse, http.cookiejar

S = os.path.dirname(os.path.abspath(__file__))
SECRET = open(os.path.join(S, "token_secret")).read().strip()
SRV = "http://127.0.0.1:18981"
CON = "http://127.0.0.1:17981"
LOG = os.path.join(S, "LOG.md")

ROLES = {}  # user -> roles, filled by setup


def b64(d):
    return base64.urlsafe_b64encode(d).rstrip(b"=").decode()


def mint(sub, roles, ttl=300, alg="HS256", secret=None, exp=None):
    h = b64(json.dumps({"alg": alg, "typ": "JWT"}, separators=(",", ":")).encode())
    c = b64(json.dumps({"sub": sub, "roles": list(roles), "exp": exp if exp is not None else int(time.time()) + ttl}, separators=(",", ":")).encode())
    s = b64(hmac.new((secret or SECRET).encode(), f"{h}.{c}".encode(), hashlib.sha256).digest())
    return f"{h}.{c}.{s}"


def call(method, url, token=None, body=None, headers=None, raw=False, ctype="application/json", timeout=30, cookies=None):
    h = dict(headers or {})
    if token:
        h["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        h.setdefault("Content-Type", ctype)
    if cookies:
        h["Cookie"] = cookies
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa
        return 0, {}, f"EXC {e!r}"


def as_user(user, method, path, body=None, **kw):
    tok = None if user is None else mint(user, ROLES.get(user, []))
    return call(method, SRV + path, tok, body, **kw)


def log(check, request, expected, observed, verdict=""):
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(LOG, "a") as f:
        f.write(f"| {ts} | {check} | `{request}` | {expected} | {observed} | {verdict} |\n")


def jl(text):
    try:
        return json.loads(text)
    except Exception:
        return None
