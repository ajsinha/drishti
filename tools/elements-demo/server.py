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

The token minting on the console is a DEV-ONLY path (embed.poc.enabled); see README.md.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import urllib.error
import urllib.request
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent / "static"
USERS = {"viewer": ["viewer"], "author": ["author"]}      # the host's demo users and the roles it says they hold
REPORTS: list = []


class Host(BaseHTTPRequestHandler):
    console = "http://127.0.0.1:17969"
    secret = ""
    app = "elements-demo"

    def log_message(self, *args):          # quiet
        pass

    # -- helpers ----------------------------------------------------------------------------------------------------
    def user(self):
        jar = SimpleCookie(self.headers.get("Cookie", ""))
        name = jar["demo_user"].value if "demo_user" in jar else ""
        return name if name in USERS else ""

    def send(self, status, body: bytes, media="application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", media)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        # what a strict host page sends: nothing inline, no frames, and only the Drishti console beyond itself
        self.send_header("Content-Security-Policy", (
            f"default-src 'self'; script-src 'self' {self.console}; style-src 'self'; font-src {self.console}; "
            f"connect-src 'self' {self.console}; img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'self'; "
            "report-uri /api/csp-report"))
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
            return self.send(200, html.encode(), "text/html; charset=utf-8")
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
        body = json.dumps({"app": self.app, "secret": self.secret, "user": user, "roles": USERS[user]}).encode()
        req = urllib.request.Request(self.console + "/embed/v1/poc/token", body, {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                out = json.loads(r.read())
        except urllib.error.HTTPError as e:
            return self.json(502, {"error": "Drishti refused the token request", "status": e.code})
        except OSError as e:
            return self.json(502, {"error": f"Drishti is not reachable: {e}"})
        self.json(200, {"token": out["access_token"], "expiresIn": out["expires_in"]})


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=17968)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--console", default="http://127.0.0.1:17969", help="the Drishti console's origin")
    ap.add_argument("--secret", required=True, help="the app secret declared under embed.poc.apps in the console's config")
    ap.add_argument("--app", default="elements-demo")
    a = ap.parse_args()
    Host.console, Host.secret, Host.app = a.console.rstrip("/"), a.secret, a.app
    print(f"host app on http://{a.host}:{a.port}  (Drishti console {Host.console})")
    ThreadingHTTPServer((a.host, a.port), Host).serve_forever()


if __name__ == "__main__":
    main()
