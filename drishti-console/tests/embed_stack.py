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

"""The stack the embed browser tests run on (docs/guides/INTEGRATION_GUIDE.md): a HOST application on another origin (tools/elements-demo), a real console and a real
scratch server, with the fixtures (``stack``, ``browser``, ``host``) and the page helper the embed acceptance tests share (test_embed_elements2_browser.py,
test_embed_controls_browser.py, test_embed_frameworks_browser.py).

The stack is three processes: the Drishti server (port 18969, the built jar, security on, the trading pack), the console
(17969, embed on) and the host (17968). The host application is registered at the server through the admin API (a client secret
shown once, the demo host's public key), two users are created, and the host's backend does the RFC 8693 exchange with a
user assertion it signs. A server already answering on 18969 is used as it is (the same token secret is assumed, embedding on);
the console and the host are always started here. Skipped when Playwright, a browser or the server jar is
missing. Measurements are appended to the file named by DRISHTI_ELEMENTS_RESULTS (default: the test's temp folder).
"""
import base64
import glob
import gzip
import json
import re
import os
import signal
import socket
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

from conftest import CONSOLE  # noqa: E402
from scratch_ports import worker_ports  # noqa: E402
from java_launch import java_cmd, server_jars
from wb_live import should_reload  # noqa: E402 - one reload, for a changed network only

ROOT = CONSOLE.parent
# (server, console, host) plus the pytest-xdist worker offset; a file that reuses this stack (test_embed_controls_browser.py) names its own PORTS
PORTS = worker_ports(*(os.environ.get(k, d) for k, d in (("DRISHTI_ELEMENTS_SERVER_PORT", 18969), ("DRISHTI_ELEMENTS_CONSOLE_PORT", 17969), ("DRISHTI_ELEMENTS_HOST_PORT", 17968))))


def use_ports(ports) -> None:
    """Points the stack and the helpers at the running file's (server, console, host) ports, never whichever file was imported last."""
    global SERVER_PORT, CONSOLE_PORT, HOST_PORT, SERVER, CONSOLE_URL, HOST
    SERVER_PORT, CONSOLE_PORT, HOST_PORT = ports
    SERVER, CONSOLE_URL, HOST = (f"http://127.0.0.1:{p}" for p in ports)


use_ports(PORTS)
SECRET = os.environ.get("DRISHTI_ELEMENTS_TOKEN_SECRET", "poc-scratch-secret-0123456789abcdef0123")
APP_ID = "elements-demo"
KEY = json.loads((ROOT / "tools/elements-demo/demo-host-key.json").read_text())


def _admin_call(method: str, path: str, body=None):
    """The server's admin API, as an administrator (the stack's token secret signs the token, as the console's does)."""
    from core.auth import mint_token

    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(SERVER + "/api/v1" + path, data, {"Authorization": "Bearer " + mint_token("root", ["admin"], SECRET, 120),
                                                                    "Content-Type": "application/json"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        if e.code == 409 or e.code == 400 and b"already exists" in e.read():
            return {}
        raise


def register_host() -> str:
    """Users the host signs in, and the host application itself; returns the client secret (shown once)."""
    for name, role in (("viewer", "viewer"), ("author", "author")):
        _admin_call("POST", "/admin/users", {"username": name, "displayName": name, "roles": [role], "password": f"Embed-Demo-Pass-{name}-1!",
                                             "mustChangePassword": False})
    try:
        _admin_call("DELETE", f"/admin/embed/apps/{APP_ID}")
    except urllib.error.HTTPError:
        pass
    enc = lambda h: base64.urlsafe_b64encode(bytes.fromhex(h if len(h) % 2 == 0 else "0" + h)).rstrip(b"=").decode()  # noqa: E731
    jwks = json.dumps({"keys": [{"kty": "RSA", "kid": KEY["kid"], "n": enc(KEY["n"]), "e": enc(KEY["e"])}]})
    made = _admin_call("POST", "/admin/embed/apps", {"id": APP_ID, "name": "Elements demo", "origins": [HOST], "scopes": ["embed:view", "embed:about"],
                                                     "jwks": jwks, "subjectTypes": ["jwt"], "callsPerMinute": 100000, "userCallsPerMinute": 100000})
    if "secret" not in made:                 # already registered (a stack this file left running): a new secret, shown once; the old one works an hour more
        made = _admin_call("POST", f"/admin/embed/apps/{APP_ID}/rotate-secret", {"graceSeconds": 3600})
    return made["secret"]
BROWSERS = ["chromium", "firefox", "webkit"]
# Since 2.3.0 trades are static (a trade changes when its source books a version) and the market-risk valuations tick: the host shows valuations
KIND = "trade-valuation"
MAIN_ID, SIDE_ID = "TVAL-1000008", "TVAL-1000002"
TRADE_ID = "1000008"                 # the trade TVAL-1000008 values: static, with a masked trader
NETTING_SET = "NS-ALDERSHOT-FRA"     # a live view with a table, a pivot and controls (counterparty-risk pack)
RAPID = [f"TVAL-10000{n:02d}" for n in (1, 6, 7, 9, 10, 11, 12, 13, 14, 15)]      # ten valuations for the rapid-switching test


def _listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def _wait(url: str, seconds: float = 60) -> None:
    end = time.time() + seconds
    while time.time() < end:
        try:
            urllib.request.urlopen(url, timeout=2).read()
            return
        except Exception:  # noqa: BLE001 - not up yet
            time.sleep(0.4)  # wait-ok: the poll interval while a port starts listening
    raise RuntimeError(f"{url} did not come up")


class Stack:
    def __init__(self, tmp: Path):
        self.tmp, self.procs, self.results = tmp, {}, Path(os.environ.get("DRISHTI_ELEMENTS_RESULTS") or tmp / "results.jsonl")

    def start_console(self):
        # its temp folders in the test's own folder: the crash below kills its workers before they can remove theirs
        env = dict(os.environ, DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_EMBED_ENABLED="true", DRISHTI_CONSOLE_TMP_DIR=str(self.tmp / "console-tmp"))
        args = [sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"]
        # its own process group: the console runs several uvicorn workers, and a crash must take them all (a killed parent alone leaves its
        # workers serving the port, so the "restarted" console would be the old one)
        self.procs["console"] = subprocess.Popen(args, env=env, stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT, start_new_session=True)
        _wait(CONSOLE_URL + "/healthz")

    def stop_console(self):
        p = self.procs.pop("console", None)
        if p:
            os.killpg(p.pid, signal.SIGKILL)            # a crash, not a drain: open streams would hold a graceful stop
            p.wait(10)

    def close(self):
        self.stop_console()
        for p in self.procs.values():
            p.terminate()
        for p in self.procs.values():
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()

    def record(self, key: str, value) -> None:
        with self.results.open("a") as f:
            f.write(json.dumps({key: value}) + "\n")


@pytest.fixture(scope="module")
def stack(request, tmp_path_factory):
    use_ports(getattr(request.module, "PORTS", PORTS))
    for port in (CONSOLE_PORT, HOST_PORT):
        if _listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there (this test starts its own console and host)")
    tmp = tmp_path_factory.mktemp("elements")
    s = Stack(tmp)
    if not _listening(SERVER_PORT):
        jars = server_jars()
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            pytest.skip("needs the built server: ./mvnw -o -q package -DskipTests -pl :drishti-app -am")
        work = tmp / "server"
        work.mkdir()
        env = dict(os.environ, DRISHTI_PACKS="trading,counterparty-risk,market-risk", DRISHTI_PACKS_DIR=str(ROOT / "config" / "packs"), DRISHTI_SECURITY_ENABLED="true",
                   DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_EMBED_ENABLED="true", DRISHTI_PUBLIC_URL=SERVER, DRISHTI_CONSOLE_URL=CONSOLE_URL)
        s.procs["server"] = subprocess.Popen(java_cmd(java, ["-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false"], tmp=tmp / "jvm-tmp", cwd=work, env=env),
                                             cwd=work, env=env, stdout=open(tmp / "server.log", "ab"), stderr=subprocess.STDOUT)
        _wait(SERVER + "/actuator/health", 120)
    s.app_secret = register_host()
    s.start_console()
    s.procs["host"] = subprocess.Popen([sys.executable, str(ROOT / "tools/elements-demo/server.py"), "--port", str(HOST_PORT), "--console", CONSOLE_URL,
                                        "--server", SERVER, "--secret", s.app_secret], stdout=open(tmp / "host.log", "ab"), stderr=subprocess.STDOUT)
    _wait(HOST + "/api/me")
    yield s
    s.close()


@pytest.fixture(scope="module", params=BROWSERS)
def browser(request, stack):
    with sync_playwright() as p:
        try:
            b = getattr(p, request.param).launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"{request.param} is not installed or cannot start: {str(e)[:120]}")
        yield b
        b.close()


# Everything the tests watch is collected in the page by this script (it runs before the host's own scripts).
INIT = """
window.__ev = []; window.__aborts = []; window.__views = []; window.__delay = {}; window.__viol = [];
for (const t of ['loaded', 'paint', 'tick', 'navigate', 'error', 'state', 'masked', 'token-needed'])
  document.addEventListener('drishti:' + t, (e) => window.__ev.push([t, e.target.id, e.detail, performance.now()]), true);
document.addEventListener('securitypolicyviolation', (e) => window.__viol.push([e.violatedDirective, e.blockedURI]));
const f = window.fetch.bind(window);
window.fetch = (u, o) => {
  u = String((u && u.url) || u);
  const m = /\\/embed\\/v1\\/views\\/[^/?]+\\/([^?/]+)(?:\\?|$)/.exec(u);
  if (m && o && o.signal) { window.__views.push(m[1]); o.signal.addEventListener('abort', () => window.__aborts.push(m[1])); }
  const d = m && window.__delay[m[1]];
  if (!d) { return f(u, o); }
  const o2 = Object.assign({}, o); delete o2.signal;       // the answer is already in the pipe: only the sequence guard can stop it
  return f(u, o2).then((r) => new Promise((res) => setTimeout(() => res(r), d)));
};
"""


class Page:
    """One signed-in host page, with the requests it made."""

    def __init__(self, browser, user="viewer", viewport=(1300, 1100), touch=False):
        opts = {"viewport": {"width": viewport[0], "height": viewport[1]}}
        if touch:                                           # a phone: touch always, the mobile viewport where the engine offers it (Firefox has no is_mobile)
            opts["has_touch"] = True
            opts["is_mobile"] = browser.browser_type.name != "firefox"
        self.ctx = browser.new_context(**opts)
        self.ctx.request.post(HOST + "/api/login", data=json.dumps({"user": user}))
        self.page = self.ctx.new_page()
        self.page.add_init_script(INIT)
        self.requests = []
        self.page.on("request", lambda r: self.requests.append((r.method, r.url, r.post_data, r.headers)))
        self.console = []
        self.page.on("console", lambda m: self.console.append(m.text))
        self.page.on("pageerror", lambda e: self.console.append("PAGEERROR " + str(e)))
        self.failed = []
        self.page.on("requestfailed", lambda r: self.failed.append(f"request failed: {r.url} ({r.failure})"))

    def open(self):
        """The host page with its main element live. The page needs the console's drishti-elements.js before any of its own code runs (a
        module script): when that request is cancelled because the machine's network changed (Docker starting a container beside the drill),
        nothing on the page starts ("signed in as nobody", no data-state). The page is then loaded once more, for that cause only
        (wb_live.should_reload, TEST_HARNESS.md rule 7); any other failure says which requests failed."""
        self.page.goto(HOST + "/")
        try:
            self.state("#main", "live")
        except AssertionError as e:
            if not should_reload(self.failed, False):
                raise AssertionError(f"{e}\nfailed requests: {self.failed or 'none'}\nconsole: {self.console[-10:]}") from None
            self.page.reload()
            self.state("#main", "live")
        return self

    def state(self, sel, want, timeout=20000):
        expect(self.page.locator(sel)).to_have_attribute("data-state", want, timeout=timeout)

    def js(self, code):
        return self.page.evaluate(code)

    def events(self, kind=None, target=None):
        ev = self.js("window.__ev")
        return [e for e in ev if (kind is None or e[0] == kind) and (target is None or e[1] == target)]

    def until(self, fn, timeout=20.0, what="condition"):
        end = time.time() + timeout
        while time.time() < end:
            v = fn()
            if v:
                return v
            self.page.wait_for_timeout(50)
        seen = [(e[0], e[1]) for e in self.js("window.__ev") if e[0] != "state"][:30]
        raise AssertionError(f"timed out waiting for {what}; events {seen}; console {self.console[:5]}")

    def channel_gets(self):
        return [r for r in self.requests if r[0] == "GET" and "/embed/v2/channel?" in r[1]]

    def channel_posts(self):
        return [json.loads(r[2]) for r in self.requests if r[0] == "POST" and "/embed/v2/channel/" in r[1] and r[2]]

    def state_of(self, sel):
        return self.js(f"document.querySelector('{sel}').getAttribute('data-state')")

    def title(self, sel="#main"):
        return self.js(f"(document.querySelector('{sel}').shadowRoot.querySelector('.vid')||{{}}).textContent")

    def close(self):
        self.ctx.close()


def show(h, kind, id_):
    """The host shows another entity in the main element (its own show(), as a click in its recent list does) and it has loaded."""
    h.js(f"window.hostApp.show('{kind}', '{id_}')")
    h.until(lambda: any(e[2]["ref"]["kind"] == kind and e[2]["ref"]["id"] == id_ for e in h.events("loaded", "main")), 15, f"the {kind} view")
    h.until(lambda: h.state_of("#main") in ("live", "static"), 15, f"the {kind} view settled")


@pytest.fixture
def host(browser):
    pages = []

    def make(**kw):
        p = Page(browser, **kw)
        pages.append(p)
        return p

    yield make
    for p in pages:
        p.close()


# ---- 1. the host page shows the live valuation, under the host's CSP -------------------------------------------------------


SR = "document.querySelector('#main').shadowRoot"
