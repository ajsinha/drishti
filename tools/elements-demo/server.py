#!/usr/bin/env python3
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

"""A sample HOST application for <drishti-view> (docs/architecture/ELEMENTS.md, build step 0).

It has nothing to do with Drishti except what a real host would do: its own page on its own origin, its own sign-in
(a cookie naming a demo user), and its own backend endpoint that asks Drishti for an embed token on the page's behalf,
server to server, so the app secret never reaches the browser. The page is served with the Content-Security-Policy a
strict host would have (no 'unsafe-inline', no frame-src); reports are collected at /api/csp-reports.

    python3 tools/elements-demo/server.py --port 17968 --console http://127.0.0.1:17969 --secret <app secret>

The token is exchanged at the Drishti server (RFC 8693) with this host's client secret and an RS256 assertion signed with
demo-host-key.json (a DEMO key; the host is registered with its public half); see README.md.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import mimetypes
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent / "static"
USERS = ("viewer", "author")                              # the host's demo users: they must also exist in Drishti (the exchange refuses a stranger)
REPORTS: list = []


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


# DigestInfo prefix of SHA-256 (RFC 8017, section 9.2): RS256 needs no library, only big-integer arithmetic
_SHA256_INFO = bytes.fromhex("3031300d060960864801650304020105000420")


def sign_rs256(key: dict, claims: dict) -> str:
    """A compact JWT signed with the demo host's own RSA key (``demo-host-key.json``): what a real host does with its registered key."""
    head = _b64(json.dumps({"alg": "RS256", "typ": "JWT", "kid": key["kid"]}, separators=(",", ":")).encode())
    body = _b64(json.dumps(claims, separators=(",", ":")).encode())
    n, d = int(key["n"], 16), int(key["d"], 16)
    size = (n.bit_length() + 7) // 8
    t = _SHA256_INFO + hashlib.sha256(f"{head}.{body}".encode()).digest()
    em = b"\x00\x01" + b"\xff" * (size - len(t) - 3) + b"\x00" + t
    sig = pow(int.from_bytes(em, "big"), d, n).to_bytes(size, "big")
    return f"{head}.{body}.{_b64(sig)}"


class Host(BaseHTTPRequestHandler):
    console = "http://127.0.0.1:17969"       # the audience of the tokens: the console that serves the views
    drishti = "http://127.0.0.1:18969"       # the Drishti server: the token exchange is made here, server to server
    secret = ""
    app = "elements-demo"
    key: dict = {}

    def log_message(self, *args):          # quiet
        pass

    # -- helpers ----------------------------------------------------------------------------------------------------
    def user(self):
        jar = SimpleCookie(self.headers.get("Cookie", ""))
        name = jar["demo_user"].value if "demo_user" in jar else ""
        return name if name in USERS else ""

    def send(self, status, body: bytes, media="application/json", headers=None, trusted_types=False):
        self.send_response(status)
        self.send_header("Content-Type", media)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # what a strict host page sends: nothing inline, no frames, and only the Drishti console beyond itself
        self.send_header("Content-Security-Policy", (
            f"default-src 'self'; script-src 'self' {self.console}; style-src 'self'; font-src {self.console}; "
            f"connect-src 'self' {self.console}; img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'self'; "
            "report-uri /api/csp-report" + ("; require-trusted-types-for 'script'; trusted-types drishti-elements" if trusted_types else "")))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def json(self, status, obj, headers=None):
        self.send(status, json.dumps(obj).encode(), headers=headers)

    # -- routes -----------------------------------------------------------------------------------------------------
    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/api/me":
            return self.json(200, {"user": self.user()})
        if path == "/api/drishti-token":                    # the host's backend: who is signed in here, then ask Drishti
            user = self.user()
            if not user:
                return self.json(401, {"error": "not signed in"})
            return self.mint(user)
        if path == "/api/csp-reports":
            return self.json(200, REPORTS)
        if path in ("/", "/index.html"):
            html = (HERE / "index.html").read_text(encoding="utf-8").replace("__CONSOLE__", self.console)
            return self.send(200, html.encode(), "text/html; charset=utf-8", trusted_types="tt=1" in self.path)     # /?tt=1: a host that enforces Trusted Types (names only drishti-elements)
        target = (HERE / path.lstrip("/")).resolve()
        if HERE in target.parents and target.is_file():
            return self.send(200, target.read_bytes(), mimetypes.guess_type(target.name)[0] or "application/octet-stream")
        self.json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if path == "/api/csp-report":
            try:
                REPORTS.append(json.loads(raw or b"{}"))
            except ValueError:
                REPORTS.append({"raw": raw.decode("utf-8", "replace")})
            return self.send(204, b"")
        if path == "/api/login":                            # a demo sign-in: pick a user
            name = (json.loads(raw or b"{}").get("user") or "").strip()
            if name not in USERS:
                return self.json(400, {"error": "unknown user"})
            return self.json(200, {"user": name}, {"Set-Cookie": f"demo_user={name}; Path=/; SameSite=Lax; HttpOnly"})
        if path == "/api/logout":
            return self.json(200, {"user": ""}, {"Set-Cookie": "demo_user=; Path=/; Max-Age=0"})
        self.json(404, {"error": "not found"})

    def mint(self, user: str):
        """The RFC 8693 exchange, server to server: the app's client secret (Basic) and an assertion this host signed about its user."""
        now = int(time.time())
        endpoint = self.drishti + "/api/v1/embed/token"
        assertion = sign_rs256(self.key, {"iss": self.app, "sub": user, "aud": endpoint, "iat": now, "exp": now + 60, "jti": uuid.uuid4().hex})
        form = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:token-exchange", "subject_token": assertion,
                                       "subject_token_type": "urn:ietf:params:oauth:token-type:jwt", "audience": self.console,
                                       "scope": "embed:view embed:about"}).encode()
        basic = base64.b64encode(f"{self.app}:{self.secret}".encode()).decode()
        req = urllib.request.Request(endpoint, form, {"Content-Type": "application/x-www-form-urlencoded", "Authorization": f"Basic {basic}"})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                out = json.loads(r.read())
        except urllib.error.HTTPError as e:
            return self.json(502, {"error": "Drishti refused the token request", "status": e.code, "detail": e.read().decode("utf-8", "replace")[:300]})
        except OSError as e:
            return self.json(502, {"error": f"Drishti is not reachable: {e}"})
        self.json(200, {"token": out["access_token"], "expiresIn": out["expires_in"]})


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=17968)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--console", default="http://127.0.0.1:17969", help="the Drishti console's origin")
    ap.add_argument("--server", default="http://127.0.0.1:18969", help="the Drishti server's URL (the token exchange is made there)")
    ap.add_argument("--secret", required=True, help="the client secret Drishti showed once when the host application was registered")
    ap.add_argument("--app", default="elements-demo", help="the host application's id as registered")
    ap.add_argument("--key", default=str(Path(__file__).resolve().parent / "demo-host-key.json"), help="the host's signing key (JSON n, e, d, kid)")
    a = ap.parse_args()
    Host.console, Host.drishti, Host.secret, Host.app = a.console.rstrip("/"), a.server.rstrip("/"), a.secret, a.app
    Host.key = json.loads(Path(a.key).read_text())
    print(f"host app on http://{a.host}:{a.port}  (Drishti console {Host.console})")
    ThreadingHTTPServer((a.host, a.port), Host).serve_forever()


if __name__ == "__main__":
    main()
