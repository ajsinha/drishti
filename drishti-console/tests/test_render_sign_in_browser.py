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

"""The renderer's optional sign-in window, ``auth: 'popup'`` (CLIENT_RENDERER.md 10.1), end to end in Chromium, with no internet: a stand-in
OpenID Connect provider (a tiny IdP in this file: discovery, authorize, token with PKCE, refresh, keys), the real server (the built jar, security
and single sign-on on, the trading pack), the console (embed and auth.oidc on) and a host page with NO backend on another origin.

What it proves: no window opens without a click; the click opens one window, which goes through the single sign-on and closes, and the screen is
drawn in place; the token lives in memory only (never in localStorage, sessionStorage or a cookie of any origin); it is renewed silently while the
provider's session lasts and, when the session ends, the button comes back (drishti:signed-out); a page whose origin is not registered gets the
button disabled with DRS-8002 naming its origin and no window; the same-tab flow (webviews) comes back to the page; Elements 2.1 does the same in
<drishti-view auth="popup">; and nothing is requested of any host but the page's, the console's and the provider's. Skipped without Playwright,
Chromium, a JDK or the server jar."""
import base64
import glob
import hashlib
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import sync_playwright  # noqa: E402

from conftest import CONSOLE  # noqa: E402
from java_launch import java_cmd  # noqa: E402
from scratch_ports import worker_ports  # noqa: E402
from wb_live import JAVA  # noqa: E402

ROOT = CONSOLE.parent
SERVER_PORT, CONSOLE_PORT, HOST_PORT, IDP_PORT = worker_ports(18924, 17924, 17925, 17927)
SERVER, CONSOLE_URL, IDP = (f"http://127.0.0.1:{p}" for p in (SERVER_PORT, CONSOLE_PORT, IDP_PORT))
HOST = f"http://127.0.0.1:{HOST_PORT}"            # registered at the server as a host application
STRANGER = f"http://localhost:{HOST_PORT}"        # the same files on an origin nobody registered
SECRET = "sign-in-window-scratch-secret-0123456789abcdef"
USER = "pat"
TRADE = "1000008"
APP = "intranet"
LIB = CONSOLE / "web" / "static" / "render" / "1.1.0"


# ---- the stand-in identity provider -----------------------------------------------------------------------------------------------
class Idp:
    """Discovery, an authorize endpoint that signs ``pat`` in at once (an SSO session that already stands), a token endpoint with PKCE and
    refresh tokens, and its key set. ``end_session()`` ends the SSO session: refreshes are refused from then on."""

    def __init__(self, port):
        from cryptography.hazmat.primitives.asymmetric import rsa
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.codes, self.refresh, self.alive, self.authorized, self.refreshed = {}, set(), True, 0, 0
        idp = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _json(self, body, status=200):
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                u = urllib.parse.urlsplit(self.path)
                q = dict(urllib.parse.parse_qsl(u.query))
                if u.path == "/.well-known/openid-configuration":
                    return self._json({"issuer": IDP, "authorization_endpoint": IDP + "/authorize", "token_endpoint": IDP + "/token",
                                       "jwks_uri": IDP + "/jwks", "id_token_signing_alg_values_supported": ["RS256"]})
                if u.path == "/jwks":
                    n = idp.key.public_key().public_numbers()
                    b = lambda i: base64.urlsafe_b64encode(i.to_bytes((i.bit_length() + 7) // 8, "big")).rstrip(b"=").decode()  # noqa: E731
                    return self._json({"keys": [{"kty": "RSA", "kid": "idp-1", "use": "sig", "alg": "RS256", "n": b(n.n), "e": b(n.e)}]})
                if u.path == "/authorize":
                    idp.authorized += 1
                    code = secrets.token_urlsafe(16)
                    idp.codes[code] = q
                    self.send_response(302)
                    self.send_header("Location", q["redirect_uri"] + "?" + urllib.parse.urlencode({"code": code, "state": q["state"]}))
                    self.end_headers()
                    return None
                return self._json({"error": "not_found"}, 404)

            def do_POST(self):
                form = dict(urllib.parse.parse_qsl(self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()))
                if self.path != "/token":
                    return self._json({"error": "not_found"}, 404)
                if form.get("grant_type") == "authorization_code":
                    asked = idp.codes.pop(form.get("code"), None)
                    challenge = base64.urlsafe_b64encode(hashlib.sha256(form.get("code_verifier", "").encode()).digest()).rstrip(b"=").decode()
                    if not asked or asked.get("code_challenge") != challenge or asked.get("redirect_uri") != form.get("redirect_uri"):
                        return self._json({"error": "invalid_grant"}, 400)
                    return self._json(idp.tokens(asked.get("nonce")))
                if form.get("grant_type") == "refresh_token":
                    if not idp.alive or form.get("refresh_token") not in idp.refresh:
                        return self._json({"error": "invalid_grant", "error_description": "Session not active"}, 400)
                    idp.refresh.discard(form["refresh_token"])
                    idp.refreshed += 1
                    return self._json(idp.tokens(None))
                return self._json({"error": "unsupported_grant_type"}, 400)

        self.server = ThreadingHTTPServer(("127.0.0.1", port), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tokens(self, nonce):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        now = int(time.time())
        claims = {"iss": IDP, "aud": "drishti", "sub": USER, "preferred_username": USER, "name": "Pat Reader", "email": "pat@bank.example",
                  "iat": now, "exp": now + 300}
        if nonce:
            claims["nonce"] = nonce
        enc = lambda d: base64.urlsafe_b64encode(json.dumps(d, separators=(",", ":")).encode()).rstrip(b"=").decode()  # noqa: E731
        head = enc({"alg": "RS256", "kid": "idp-1", "typ": "JWT"}) + "." + enc(claims)
        sig = self.key.sign(head.encode(), padding.PKCS1v15(), hashes.SHA256())
        rt = secrets.token_urlsafe(16)
        self.refresh.add(rt)
        return {"id_token": head + "." + base64.urlsafe_b64encode(sig).rstrip(b"=").decode(), "refresh_token": rt, "token_type": "Bearer",
                "access_token": secrets.token_urlsafe(8), "expires_in": 300}

    def end_session(self):
        self.alive = False

    def close(self):
        self.server.shutdown()


# ---- the host page: no backend, only files ----------------------------------------------------------------------------------------
PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>Intranet risk page</title></head><body>
<h1>Intranet</h1><div id="risk" style="min-height:300px"></div>
<script type="module">
import { render } from '%(lib)s';
window.__ev = [];
const el = document.getElementById('risk');
for (const n of ['auth-required', 'signed-in', 'signed-out', 'loaded', 'error']) { el.addEventListener('drishti:' + n, (e) => window.__ev.push([n, e.detail])); }
const mode = new URLSearchParams(location.search).get('mode') || 'auto';
window.h = render(el, { kind: 'trade', id: '%(trade)s' }, { auth: 'popup', server: '%(console)s', signInWindow: mode });
</script></body></html>"""
ELEMENT_PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>Intranet elements</title></head><body>
<script type="module" src="%(console)s/embed/v2/elements/2/drishti-elements.js"></script>
<drishti-view id="main" auth="popup" server="%(console)s" kind="trade" entity="%(trade)s"></drishti-view>
<script>window.__ev = []; for (const n of ['auth-required', 'signed-in', 'loaded', 'state']) { document.addEventListener('drishti:' + n, (e) => window.__ev.push([n, e.detail]), true); }</script>
</body></html>"""


class Host:
    def __init__(self, port):
        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                path = urllib.parse.urlsplit(self.path).path
                if path == "/page.html":
                    body, kind = (PAGE % {"lib": CONSOLE_URL + "/embed/v2/render/1.1.0/drishti-render.js", "console": CONSOLE_URL, "trade": TRADE}).encode(), "text/html"
                elif path == "/bundled.html":                # the library bundled with the page (the npm package's files), on any origin
                    body, kind = (PAGE % {"lib": "/lib/drishti-render.js", "console": CONSOLE_URL, "trade": TRADE}).encode(), "text/html"
                elif path == "/elements.html":
                    body, kind = (ELEMENT_PAGE % {"console": CONSOLE_URL, "trade": TRADE}).encode(), "text/html"
                elif path.startswith("/lib/") and (LIB / path[5:]).resolve().is_file() and LIB in (LIB / path[5:]).resolve().parents:
                    f = (LIB / path[5:]).resolve()
                    body, kind = f.read_bytes(), {".js": "text/javascript", ".css": "text/css"}.get(f.suffix, "application/octet-stream")
                else:
                    self.send_response(404)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Type", kind)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", port), H)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()


# ---- the stack ----------------------------------------------------------------------------------------------------------------------
def _listening(port):
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def _wait(url, seconds=60.0, proc=None, log=None):
    end = time.time() + seconds
    while time.time() < end:
        if proc is not None and proc.poll() is not None:
            pytest.skip(f"{url} did not start: " + (log.read_text()[-600:] if log and log.exists() else ""))
        try:
            urllib.request.urlopen(url, timeout=2).read(10)
            return
        except urllib.error.HTTPError:
            return
        except OSError:
            time.sleep(0.4)
    raise RuntimeError(f"{url} did not come up")


def _admin(method, path, body=None):
    from core.auth import mint_token
    req = urllib.request.Request(SERVER + "/api/v1" + path, None if body is None else json.dumps(body).encode(),
                                 {"Authorization": "Bearer " + mint_token("root", ["admin"], SECRET, 120), "Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(req, timeout=15) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (SERVER_PORT, CONSOLE_PORT, HOST_PORT, IDP_PORT):
        if _listening(port):
            pytest.skip(f"port {port} is in use: this test starts its own server, console, host and provider there")
    jars = sorted(glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar")), key=os.path.getmtime, reverse=True)
    if not jars or not JAVA.exists():
        pytest.skip("needs the built server (./mvnw -o -q package -DskipTests -pl drishti-server -am) and a JDK")
    tmp = tmp_path_factory.mktemp("sign-in")
    idp, host, procs = Idp(IDP_PORT), Host(HOST_PORT), {}
    try:
        work = tmp / "server"
        work.mkdir()
        env = dict(os.environ, DRISHTI_PACKS="trading", DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_SECURITY_ENABLED="true",
                   DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_EMBED_ENABLED="true", DRISHTI_PUBLIC_URL=SERVER, DRISHTI_CONSOLE_URL=CONSOLE_URL,
                   DRISHTI_OIDC_ENABLED="true", DRISHTI_OIDC_ISSUER=IDP, DRISHTI_OIDC_CLIENT_ID="drishti")
        log = tmp / "server.log"
        procs["server"] = subprocess.Popen(java_cmd(str(JAVA), ["-Xmx768m", "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false",
                                                                 "--drishti.security.oidc.default-roles=viewer"], tmp=tmp / "jvm-tmp"),
                                           cwd=work, env=env, stdout=log.open("ab"), stderr=subprocess.STDOUT)
        _wait(SERVER + "/actuator/health", 600, procs["server"], log)
        _admin("POST", "/admin/embed/apps", {"id": APP, "name": "Intranet page", "origins": [HOST], "scopes": ["embed:view", "embed:about"],
                                             "subjectTypes": ["id_token"], "tokenSeconds": 60, "callsPerMinute": 100000, "userCallsPerMinute": 100000})
        cenv = dict(os.environ, DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_EMBED_ENABLED="true", DRISHTI_OIDC_ENABLED="true", DRISHTI_OIDC_ISSUER=IDP,
                    DRISHTI_OIDC_CLIENT_ID="drishti", DRISHTI_OIDC_CLIENT_SECRET="", DRISHTI_SECURE_COOKIE="false")
        clog = tmp / "console.log"
        procs["console"] = subprocess.Popen([sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"],
                                            env=cenv, stdout=clog.open("ab"), stderr=subprocess.STDOUT, start_new_session=True)
        _wait(CONSOLE_URL + "/healthz", 60, procs["console"], clog)
        yield idp
    finally:
        import signal
        if "console" in procs:
            os.killpg(procs["console"].pid, signal.SIGKILL)
            procs["console"].wait(10)
        if "server" in procs:
            procs["server"].terminate()
            try:
                procs["server"].wait(20)
            except subprocess.TimeoutExpired:
                procs["server"].kill()
        host.close()
        idp.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"Chromium is not installed or cannot start: {str(e)[:120]}")
        yield b
        b.close()


class Visit:
    """A fresh browser context on a host page, with every request it and its windows made and every window it opened."""

    def __init__(self, browser, origin=HOST, path="/page.html"):
        self.ctx = browser.new_context(viewport={"width": 1200, "height": 900})
        self.requests, self.windows = [], []
        self.ctx.on("request", lambda r: self.requests.append(r.url))
        self.page = self.ctx.new_page()
        self.ctx.on("page", lambda p: self.windows.append(p) if p is not self.page else None)
        self.errors = []
        self.page.on("pageerror", lambda e: self.errors.append(str(e)))
        self.origin, self.path = origin, path

    def open(self, query=""):
        self.page.goto(self.origin + self.path + query)
        return self

    def events(self, name=None):
        return [e for e in self.page.evaluate("window.__ev || []") if name is None or e[0] == name]

    def until(self, fn, seconds=30.0, what="condition"):
        end = time.time() + seconds
        while time.time() < end:
            try:
                v = fn()
            except Exception:  # noqa: BLE001 - the page is navigating (the same-tab flow): ask again
                v = None
            if v:
                return v
            self.page.wait_for_timeout(100)
        raise AssertionError(f"timed out waiting for {what}; events {self.events()}; errors {self.errors}")

    def gate(self, sel="#risk"):
        return self.page.locator(f"{sel} [data-r-sign-in]")

    def sign_in(self, sel="#risk"):
        with self.page.expect_popup(timeout=20000) as w:
            self.gate(sel).click()
        win = w.value
        win.wait_for_event("close", timeout=30000)
        return win

    def close(self):
        self.ctx.close()


@pytest.fixture
def visit(browser):
    made = []

    def make(**kw):
        v = Visit(browser, **kw)
        made.append(v)
        return v

    yield make
    for v in made:
        v.close()


def test_no_window_without_a_click_then_one_window_and_the_screen_in_place(stack, visit):
    v = visit().open()
    v.until(lambda: v.events("auth-required"), what="the sign-in button")
    assert v.gate().is_enabled() and "Sign in to see this" in v.gate().inner_text()
    before = stack.authorized
    v.page.wait_for_timeout(1500)
    assert v.windows == [] and stack.authorized == before, "no window and no trip to the provider before a click"
    v.sign_in()
    assert len(v.windows) == 1 and stack.authorized == before + 1
    v.until(lambda: v.events("loaded"), what="the screen")
    assert v.events("signed-in")[0][1]["user"] == USER
    assert TRADE in v.page.locator("#risk").evaluate("e => e.shadowRoot.querySelector('.view').innerText")
    assert v.page.url == HOST + "/page.html", "the page itself never navigated"
    assert not v.errors, v.errors


def test_the_token_lives_in_memory_only_and_nothing_leaves_for_other_hosts(stack, visit):
    v = visit().open()
    v.until(lambda: v.events("auth-required"), what="the sign-in button")
    v.sign_in()
    v.until(lambda: v.events("loaded"), what="the screen")
    token = v.page.evaluate("window.h._s.signin.token")
    assert token and token.count(".") == 2
    stored = v.page.evaluate("() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage), document.cookie])")
    assert token not in stored and token[-20:] not in stored
    assert all(token not in (c["value"] or "") for c in v.ctx.cookies()), "no cookie of any origin holds the token"
    allowed = {HOST, CONSOLE_URL, IDP}
    off = [u for u in v.requests if not any(u.startswith(o + "/") or u == o for o in allowed) and not u.startswith(("data:", "blob:"))]
    assert off == [], off
    views = [u for u in v.requests if "/embed/v2/views/trade/" in u]
    assert views, "the view comes from the embed API v2 with the token"


def test_silent_renewal_while_the_session_lasts_then_the_button_comes_back(stack, visit):
    v = visit()
    v.page.clock.install()
    v.open()
    v.until(lambda: v.events("auth-required"), what="the sign-in button")
    v.sign_in()
    v.until(lambda: v.events("loaded"), what="the screen")
    first = v.page.evaluate("window.h._s.signin.token")
    renewed = stack.refreshed
    v.page.clock.fast_forward(55_000)                     # the token lives 60 s: renewed some seconds before it expires
    v.until(lambda: v.page.evaluate("window.h._s.signin.token") not in (None, first), what="a renewed token")
    assert stack.refreshed == renewed + 1 and not v.events("signed-out")
    assert v.page.locator("#risk").evaluate("e => !!e.shadowRoot.querySelector('.view .vtitle')"), "the screen stays"
    stack.end_session()
    try:
        v.page.clock.fast_forward(120_000)
        v.until(lambda: v.events("signed-out"), what="drishti:signed-out")
        assert v.events("signed-out")[0][1]["reason"] == "expired"
        v.until(lambda: v.gate().count() and v.gate().is_enabled(), what="the button again")
        assert "sign in again" in v.page.locator("#risk [data-r-sign-in-note]").inner_text()
        assert v.page.evaluate("window.h._s.signin.token") is None
    finally:
        stack.alive = True


def test_a_page_whose_origin_is_not_registered_gets_the_button_disabled_and_no_window(stack, visit):
    v = visit(origin=STRANGER, path="/bundled.html").open()
    v.until(lambda: v.events("auth-required"), what="the sign-in button")
    detail = v.events("auth-required")[0][1]
    assert detail["allowed"] is False and detail["code"] == "DRS-8002" and STRANGER in detail["detail"]
    assert v.gate().is_disabled()
    why = v.page.locator("#risk .r-signin-why").inner_text()
    assert "DRS-8002" in why and STRANGER in why
    v.gate().click(force=True)
    v.page.wait_for_timeout(1000)
    assert v.windows == [], "a disabled button opens nothing"


def test_the_same_tab_flow_comes_back_to_the_page(stack, visit):
    v = visit().open("?mode=redirect")
    v.until(lambda: v.events("auth-required"), what="the sign-in button")
    with v.page.expect_event("framenavigated", predicate=lambda f: f == v.page.main_frame and "drishti-sign-in=" in f.url, timeout=30000):
        v.gate().click()
    v.until(lambda: v.events("loaded"), what="the screen after coming back")
    assert "#drishti-sign-in" not in v.page.url, "the one-time code is taken out of the address at once"
    assert v.windows == []


def test_elements_2_1_signs_in_through_the_window_too(stack, visit):
    v = visit(path="/elements.html").open()
    v.until(lambda: v.page.evaluate("document.querySelector('#main').state") == "sign-in", what="the element's sign-in state")
    assert v.page.evaluate("window.DrishtiElements.version") == "2.1.0"
    v.sign_in("#main")
    v.until(lambda: v.page.evaluate("document.querySelector('#main').state") in ("live", "static"), what="the element drawn")
    assert TRADE in v.page.evaluate("document.querySelector('#main').shadowRoot.querySelector('.view').innerText")
