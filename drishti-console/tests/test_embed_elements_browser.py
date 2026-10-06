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

"""Embedded views (docs/architecture/ELEMENTS.md, build step 0, run against the real token exchange of steps 1 to 3): the acceptance criteria, in Chromium,
Firefox and WebKit, against a HOST application on another origin (tools/elements-demo) and a real scratch server.

The stack is three processes: the Drishti server (port 18969, the built jar, security on, the trading pack), the console
(17969, embed on) and the host (17968). The host application is registered at the server through the admin API (a client secret
shown once, the demo host's public key), two users are created, and the host's backend does the RFC 8693 exchange with a
user assertion it signs. A server already answering on 18969 is used as it is (the same token secret is assumed, embedding on);
the console and the host are always started here. Skipped when Playwright, a browser or the server jar is
missing. Measurements are appended to the file named by DRISHTI_ELEMENTS_RESULTS (default: the test's temp folder).

    drishti-console/.venv/bin/python -m pytest -q drishti-console/tests/test_embed_elements_browser.py
"""
import base64
import glob
import gzip
import json
import re
import os
import socket
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

from conftest import CONSOLE  # noqa: E402

ROOT = CONSOLE.parent
SERVER_PORT, CONSOLE_PORT, HOST_PORT = (int(os.environ.get(k, d)) for k, d in (("DRISHTI_ELEMENTS_SERVER_PORT", 18969), ("DRISHTI_ELEMENTS_CONSOLE_PORT", 17969), ("DRISHTI_ELEMENTS_HOST_PORT", 17968)))
SERVER = f"http://127.0.0.1:{SERVER_PORT}"
CONSOLE_URL = f"http://127.0.0.1:{CONSOLE_PORT}"
HOST = f"http://127.0.0.1:{HOST_PORT}"
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
    return made["secret"]
BROWSERS = ["chromium", "firefox", "webkit"]
MAIN_ID, SIDE_ID = "END-1000008", "END-1000002"
RAPID = [f"END-10000{n:02d}" for n in (6, 7, 9, 10, 11, 12, 13, 14, 15, 16)]      # ten trades for the rapid-switching test


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
            time.sleep(0.4)
    raise RuntimeError(f"{url} did not come up")


class Stack:
    def __init__(self, tmp: Path):
        self.tmp, self.procs, self.results = tmp, {}, Path(os.environ.get("DRISHTI_ELEMENTS_RESULTS") or tmp / "results.jsonl")

    def start_console(self):
        env = dict(os.environ, DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_EMBED_ENABLED="true")
        args = [sys.executable, str(CONSOLE / "run_drishti_web.py"), f"--server.port={CONSOLE_PORT}", f"--backend.url={SERVER}"]
        self.procs["console"] = subprocess.Popen(args, env=env, stdout=open(self.tmp / "console.log", "ab"), stderr=subprocess.STDOUT)
        _wait(CONSOLE_URL + "/healthz")

    def stop_console(self):
        p = self.procs.pop("console", None)
        if p:
            p.kill()                                    # a crash, not a drain: open streams would hold a graceful stop
            p.wait(10)

    def close(self):
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
def stack(tmp_path_factory):
    for port in (CONSOLE_PORT, HOST_PORT):
        if _listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there (this test starts its own console and host)")
    tmp = tmp_path_factory.mktemp("elements")
    s = Stack(tmp)
    if not _listening(SERVER_PORT):
        jars = glob.glob(str(ROOT / "drishti-server/target/drishti-server-*-exec.jar"))
        java = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64") + "/bin/java"
        if not jars or not Path(java).exists():
            pytest.skip("needs the built server: ./mvnw -o -q package -DskipTests -pl drishti-server -am")
        work = tmp / "server"
        work.mkdir()
        env = dict(os.environ, DRISHTI_PACKS="trading", DRISHTI_PACKS_DIR=str(ROOT / "packs"), DRISHTI_SECURITY_ENABLED="true",
                   DRISHTI_TOKEN_SECRET=SECRET, DRISHTI_EMBED_ENABLED="true", DRISHTI_PUBLIC_URL=SERVER, DRISHTI_CONSOLE_URL=CONSOLE_URL)
        s.procs["server"] = subprocess.Popen([java, "-jar", jars[0], f"--server.port={SERVER_PORT}", "--drishti.security.registered-users-only=false"],
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
for (const t of ['loaded', 'tick', 'navigate', 'error', 'state', 'masked'])
  document.addEventListener('drishti:' + t, (e) => window.__ev.push([t, e.target.id, e.detail, performance.now()]), true);
document.addEventListener('securitypolicyviolation', (e) => window.__viol.push([e.violatedDirective, e.blockedURI]));
const f = window.fetch.bind(window);
window.fetch = (u, o) => {
  u = String((u && u.url) || u);
  const m = /\\/embed\\/v1\\/views\\/trade\\/([^?]+)/.exec(u);
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

    def open(self):
        self.page.goto(HOST + "/")
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
        return [r for r in self.requests if r[0] == "GET" and "/embed/v1/channel?" in r[1]]

    def channel_posts(self):
        return [json.loads(r[2]) for r in self.requests if r[0] == "POST" and "/embed/v1/channel/" in r[1] and r[2]]

    def title(self, sel="#main"):
        return self.js(f"(document.querySelector('{sel}').shadowRoot.querySelector('.vid')||{{}}).textContent")

    def close(self):
        self.ctx.close()


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


# ---- 1. the host page shows the live trade, under the host's CSP -------------------------------------------------------
def test_first_view_is_live_and_the_csp_holds(host, stack, browser):
    h = host()
    t0 = time.time()
    h.open()
    assert h.title() == MAIN_ID
    strip = h.js("document.querySelector('#main').shadowRoot.querySelector('.strip').innerText")
    assert "MTM" in strip
    h.until(lambda: len(h.events("tick", "main")) >= 2, 20, "two ticks of the main view")        # the MTM cell ticks at the demo rate
    assert h.js("window.__viol") == []
    reports = json.loads(urllib.request.urlopen(HOST + "/api/csp-reports").read())
    assert reports == [], reports
    loaded = h.events("loaded", "main")[0]
    stack.record(f"{browser.browser_type.name}.first_loaded_ms_since_navigation", round(loaded[3]))
    stack.record(f"{browser.browser_type.name}.first_load_wall_s", round(time.time() - t0, 2))
    stack.record(f"{browser.browser_type.name}.main_view_total_ms", h.js("document.querySelector('#main').view.timings.totalMs"))


# ---- 2. masks: always, whatever the user's roles ---------------------------------------------------------------------------
@pytest.mark.parametrize("user", ["viewer", "author"])
def test_masked_for_every_user(host, user):
    h = host(user=user)
    h.open()
    text = h.js("document.querySelector('#main').shadowRoot.querySelector('.view').innerText")
    assert "•••" in text and "TRDR-" not in text                      # the trader named in the lifecycle text reads •••
    masked = h.events("masked", "main")
    assert masked and masked[0][2]["count"] >= 1
    # a counterparty's own id is a masked field too: the view of one shows ••• for it
    h.page.evaluate("window.hostApp.show('counterparty', 'CP-HARBORPT')")
    h.until(lambda: h.title() == "•••", 20, "the masked counterparty id")


# ---- 3. search drives the element ----------------------------------------------------------------------------------------
def test_search_box_drives_the_element(host, stack, browser):
    h = host()
    h.open()
    h.page.evaluate("window.__ev.length = 0")
    h.until(lambda: h.channel_gets(), 5, "the channel request")
    h.page.fill("#q", "TRD MX-20000001")
    t0 = h.js("performance.now()")
    h.page.press("#q", "Enter")
    h.until(lambda: any(e[2]["ref"]["id"] == "MX-20000001" for e in h.events("loaded", "main")), 10, "the new view")
    h.state("#main", "live")
    loaded = [e for e in h.events("loaded", "main") if e[2]["ref"]["id"] == "MX-20000001"][0]
    stack.record(f"{browser.browser_type.name}.search_to_loaded_ms", round(loaded[3] - t0))      # resolve + view + paint
    assert h.page.get_attribute("#main", "entity") == "MX-20000001"
    assert h.title() == "MX-20000001"
    h.until(lambda: any(f"view:trade/{MAIN_ID}" in p.get("remove", []) for p in h.channel_posts()), 5, "remove of the old key")
    assert any("view:trade/MX-20000001" in p.get("add", []) for p in h.channel_posts())
    h.page.evaluate("window.__ev.length = 0")
    h.until(lambda: any(e[1] == "main" for e in h.events("tick")), 20, "a tick of the new view")
    recents = h.page.locator("#recent button").all_inner_texts()
    assert recents[0] == "TRD MX-20000001" and "TRD END-1000008" in recents
    # the same through the property, and through a recent entity
    h.js("document.querySelector('#main').entity = 'END-1000004'")
    h.until(lambda: h.title() == "END-1000004", 10, "the property change")
    t1 = h.js("performance.now()")
    h.page.click("#recent button:has-text('END-1000008')")
    h.until(lambda: h.title() == MAIN_ID, 10, "a click on a recent entity")
    h.state("#main", "live")
    switch = [e for e in h.events("loaded", "main") if e[2]["ref"]["id"] == MAIN_ID][-1][3] - t1
    stack.record(f"{browser.browser_type.name}.recent_click_to_loaded_ms", round(switch))


def test_switching_aborts_the_request_in_flight(host):
    h = host()
    h.open()
    h.js("window.__delay['END-1000003'] = 600")
    h.js("document.querySelector('#main').entity = 'END-1000003'")      # held back 600 ms in the page: in flight
    h.page.wait_for_timeout(100)
    h.js("document.querySelector('#main').entity = 'END-1000004'")
    h.until(lambda: h.title() == "END-1000004", 10, "the last entity")
    assert "END-1000003" in h.js("window.__aborts")                        # the first one was aborted, not just ignored
    h.page.wait_for_timeout(800)                                           # the held answer arrives late and is never painted
    assert h.title() == "END-1000004"


# ---- 4. navigation round trip ---------------------------------------------------------------------------------------------
def test_linked_entity_navigates_through_the_host(host):
    h = host()
    h.open()
    h.page.click("#main .with-cp a")                                       # the counterparty linked from the title
    h.until(lambda: h.events("navigate", "main"), 5, "drishti:navigate")
    nav = h.events("navigate", "main")[0][2]
    assert nav["kind"] == "counterparty" and nav["href"].startswith(CONSOLE_URL + "/v/counterparty/")
    h.until(lambda: h.page.input_value("#q").startswith("CPTY "), 5, "the host's search box")
    h.until(lambda: h.page.get_attribute("#main", "kind") == "counterparty", 5, "the element's kind")
    h.until(lambda: any(e[2]["ref"]["kind"] == "counterparty" for e in h.events("loaded", "main")), 10, "the counterparty view")
    assert "CPTY" in h.page.locator("#recent button").first.inner_text()


# ---- 5. as-of -----------------------------------------------------------------------------------------------------------------
def test_as_of_reloads_as_a_static_snapshot_and_live_resubscribes(host):
    h = host()
    h.open()
    h.until(lambda: h.channel_gets(), 5, "the channel request")
    h.page.fill("#asof", "2026-09-30")
    h.state("#main", "static")
    assert h.page.get_attribute("#main", "as-of") == "2026-09-30"
    h.until(lambda: any(f"view:trade/{MAIN_ID}" in p.get("remove", []) for p in h.channel_posts()), 5, "unsubscribed")
    before = len(h.events("tick", "main"))
    h.page.wait_for_timeout(2500)
    assert len(h.events("tick", "main")) == before                         # a snapshot does not tick
    h.page.click("#live")
    h.state("#main", "live")
    h.until(lambda: len(h.events("tick", "main")) > before, 20, "ticks again")


# ---- 6. last request wins ----------------------------------------------------------------------------------------------------
def test_rapid_switching_never_paints_a_stale_view(host, stack, browser):
    h = host()
    h.open()
    delays = {tid: (len(RAPID) - i) * 120 for i, tid in enumerate(RAPID)}       # the earlier the request, the later its answer
    h.js(f"Object.assign(window.__delay, {json.dumps(delays)})")
    h.page.evaluate("window.__ev.length = 0")
    h.page.evaluate("""async (ids) => { const m = document.querySelector('#main');
      for (const id of ids) { m.setAttribute('entity', id); await new Promise((r) => setTimeout(r, 20)); } }""", RAPID)
    last = RAPID[-1]
    h.until(lambda: h.title() == last, 10, "the last entity")
    h.page.wait_for_timeout(1800)                                          # every held answer has arrived by now
    assert h.title() == last and h.page.get_attribute("#main", "entity") == last
    loaded = [e[2]["ref"]["id"] for e in h.events("loaded", "main")]
    assert loaded == [last], loaded                                         # drishti:loaded for the last entity only
    keys = [k for p in h.channel_posts() for k in p.get("add", [])]
    assert set(keys) <= {f"view:trade/{last}"}, keys                       # no earlier entity was ever subscribed
    h.state("#main", "live")
    h.until(lambda: any(e[1] == "main" for e in h.events("tick")), 20, "a tick of the last entity only")
    assert h.js("window.__aborts.length") >= len(RAPID) - 1


# ---- 7. one connection for both elements ----------------------------------------------------------------------------------------
def test_two_elements_share_one_channel(host):
    h = host()
    h.open()
    h.state("#side", "live")
    h.until(lambda: h.events("tick", "main") and h.events("tick", "side"), 20, "both elements ticking")
    assert len(h.channel_gets()) == 1, [r[1] for r in h.channel_gets()]
    sent = h.channel_gets()[0][3]
    assert sent.get("authorization", "").startswith("Bearer ")              # streamed fetch, cross-origin, with Authorization
    n_side = len(h.events("tick", "side"))
    h.page.fill("#q", "")
    h.js("window.hostApp.show('trade', 'END-1000005')")                   # switch the main one
    h.until(lambda: h.title() == "END-1000005", 10, "the main switch")
    h.until(lambda: len(h.events("tick", "side")) > n_side + 1, 20, "the side element still ticking")
    assert len(h.channel_gets()) == 1


# ---- 8. the console restarts ------------------------------------------------------------------------------------------------------
def test_console_restart_reconnects_and_repaints(host, stack):
    h = host()
    h.open()
    h.js("DrishtiElements.configure({retryMinMs: 300, retryMaxMs: 1500})")
    h.until(lambda: h.events("tick", "main"), 20, "a first tick")
    stack.stop_console()
    try:
        h.state("#main", "reconnecting", 20000)
        h.page.wait_for_timeout(1200)
    finally:
        stack.start_console()
    h.state("#main", "live", 30000)
    h.page.evaluate("window.__ev.length = 0")
    h.until(lambda: h.events("tick", "main"), 20, "ticks again after the restart")
    assert h.title() == MAIN_ID


# ---- pause while hidden -------------------------------------------------------------------------------------------------------------
def test_hidden_page_pauses_and_returns(host):
    h = host()
    h.open()
    h.js("DrishtiElements.configure({hiddenGraceMs: 300})")
    h.js("Object.defineProperty(document, 'hidden', {value: true, configurable: true}); document.dispatchEvent(new Event('visibilitychange'))")
    h.state("#main", "paused", 5000)
    h.state("#side", "paused", 5000)
    h.page.wait_for_timeout(500)                                           # (frames in flight)
    quiet = len(h.events("tick"))
    h.page.wait_for_timeout(2000)
    assert len(h.events("tick")) == quiet                                  # nothing is listened to while paused
    h.js("Object.defineProperty(document, 'hidden', {value: false, configurable: true}); document.dispatchEvent(new Event('visibilitychange'))")
    h.state("#main", "live", 20000)
    h.page.evaluate("window.__ev.length = 0")
    h.until(lambda: h.events("tick", "main"), 20, "ticks after returning")


# ---- 9. the spike's checks, in each browser ---------------------------------------------------------------------------------------------
def test_browser_capabilities(host, browser):
    h = host()
    h.open()
    h.until(lambda: len(h.events("tick", "main")) >= 2, 20, "composed events (document-level listener saw the element's ticks)")
    got = h.js("""(() => { const r = document.querySelector('#main').shadowRoot;
      return { adopted: r.adoptedStyleSheets.length, token: getComputedStyle(document.querySelector('#main')).getPropertyValue('--d-ink').trim(),
        pill: getComputedStyle(r.querySelector('.pill')).borderTopWidth,
        fontLoaded: [...document.fonts].some((f) => f.family.includes('bootstrap-icons') && f.status === 'loaded'),
        iconCheck: document.fonts.check('16px bootstrap-icons'),
        lineChart: r.querySelectorAll('.chart svg path').length, waterfall: r.querySelectorAll('.xchart svg').length,
        styleEls: r.querySelectorAll('style').length, hostLeak: getComputedStyle(document.body).getPropertyValue('--d-ink').trim() }; })()""")
    assert got["adopted"] == 2 and got["token"]                           # adoptedStyleSheets under a CSP without 'unsafe-inline'
    assert got["pill"] not in ("", "0px")                                  # the console's panel styles apply in the shadow root
    assert got["fontLoaded"] and got["iconCheck"]                          # the icon font through FontFace
    assert got["lineChart"] > 0 and got["waterfall"] > 0                  # ECharts drew inside the shadow root
    assert got["hostLeak"] == ""                                           # the element's tokens do not leak into the host page
    assert h.js("window.__viol") == []
    assert h.page.evaluate("getComputedStyle(document.querySelector('h2')).textTransform") == "uppercase"      # and the host's styles did not enter either


def test_switch_timing_over_ten_switches(host, stack, browser):
    h = host()
    h.open()
    times = []
    for i in range(10):
        target = ("END-1000003", "END-1000004")[i % 2]
        mark = h.js("performance.now()")
        h.js(f"document.querySelector('#main').entity = '{target}'")
        h.until(lambda: any(e[2]["ref"]["id"] == target and e[3] > mark for e in h.events("loaded", "main")), 10, "the switch")
        times.append(round([e for e in h.events("loaded", "main") if e[2]["ref"]["id"] == target and e[3] > mark][0][3] - mark))
        h.page.wait_for_timeout(150)
    name = browser.browser_type.name
    stack.record(f"{name}.switch_ms_median_p95_max", [statistics.median(times), sorted(times)[-2], max(times)])
    assert statistics.median(times) < 500, times                            # acceptance 3: rendered within 500 ms


# ---- 10. measurements --------------------------------------------------------------------------------------------------------------------------
def test_measurements(stack, browser):
    if browser.browser_type.name != "chromium":
        pytest.skip("payloads do not depend on the browser")
    host_session = urllib.request.Request(HOST + "/api/login", json.dumps({"user": "viewer"}).encode(), {"Content-Type": "application/json"})
    cookie = urllib.request.urlopen(host_session).headers["Set-Cookie"].split(";")[0]
    tok = json.loads(urllib.request.urlopen(urllib.request.Request(HOST + "/api/drishti-token", headers={"Cookie": cookie})).read())["token"]
    auth = {"Authorization": "Bearer " + tok, "Origin": HOST}

    def get(path):
        return urllib.request.urlopen(urllib.request.Request(CONSOLE_URL + path, headers=auth)).read()

    sizes = {}
    for name, path in (("view_trade", f"/embed/v1/views/trade/{MAIN_ID}"), ("view_counterparty", "/embed/v1/views/counterparty/CP-HARBORPT"),
                       ("element_js", "/embed/v1/poc/drishti-elements.js"), ("element_css", "/embed/v1/poc/drishti-view.css"),
                       ("echarts_js", "/embed/v1/poc/echarts.js"), ("charts_js", "/embed/v1/poc/charts.js"), ("icons_woff2", "/embed/v1/poc/icons.woff2")):
        body = get(path)
        sizes[name] = {"bytes": len(body), "gzip": len(gzip.compress(body, 6))}
    stack.record("sizes", sizes)
    # frames of a live trade: the size of each event of the channel (the same frames /api/channel sends)
    req = urllib.request.Request(CONSOLE_URL + f"/embed/v1/channel?s=view:trade/{MAIN_ID}", headers=auth)
    frames, buf, end = [], b"", time.time() + 12
    with urllib.request.urlopen(req, timeout=15) as r:
        while time.time() < end and len(frames) < 12:
            chunk = r.read1(65536) if hasattr(r, "read1") else r.read(4096)
            if not chunk:
                break
            buf += chunk
            while b"\n\n" in buf:
                block, buf = buf.split(b"\n\n", 1)
                if block.startswith(b"event: frame"):
                    frames.append(len(block) + 2)
    assert frames, "no live frame within 12 s"
    stack.record("frames", {"count": len(frames), "median_bytes": statistics.median(frames), "max_bytes": max(frames)})


# ---- 11. the console's enhancers inside the element (ELEMENTS.md build step 6) ---------------------------------------------
SR = "document.querySelector('#main').shadowRoot"
PAGED = ("<div class='tbl-wrap'><table class='tbl' aria-label='Injected'><thead><tr><th>Name</th><th>Amount</th></tr></thead><tbody>"
         + "".join(f"<tr><td>row{i:02d}</td><td>{(i * 37) % 31}</td></tr>" for i in range(30)) + "</tbody></table></div>")
TREE = ("<div class='tbl-wrap'><table class='tbl tbl-tree' data-plain data-tree><thead><tr><th>Item</th></tr></thead><tbody>"
        "<tr data-depth='0'><td><span class='tr-ind tr-d0'><button type='button' class='tr-tog' aria-expanded='false' aria-label='Expand Parent'>▸</button>Parent</span></td></tr>"
        "<tr data-depth='1'><td><span class='tr-ind tr-d1'>Child</span></td></tr></tbody></table></div>")


def inject(h, html):
    h.js(f"(() => {{ const d = document.createElement('div'); d.className = 'injected'; d.innerHTML = {json.dumps(html)}; {SR}.appendChild(d); }})()")


def test_enhancers_are_registered_and_the_host_page_is_untouched(host):
    h = host()
    h.open()
    h.until(lambda: h.js("!!(window.drishtiModules && window.drishtiModules.tables && window.drishtiModules.pivot)"), 20, "modules registered")
    # one namespace and none of the globals the console's scripts publish on its own pages
    assert h.js("[window.drishtiAbout, window.drishtiPivot, window.drishtiCharts, window.drishti, window.drishtiPivotEngine, window.drishtiPivotGrid]") == [None] * 6
    assert h.js(f"{SR}.querySelectorAll('table.tbl').length") >= 1


def test_table_sorts_and_pages_inside_the_element(host):
    h = host()
    h.open()
    inject(h, PAGED)
    t = h.page.locator("#main").locator(".injected table.tbl")
    expect(t.locator("th").first).to_have_class(re.compile("tbl-sortable"), timeout=10000)
    t.locator("th").nth(1).click()
    expect(t.locator("th").nth(1)).to_have_attribute("aria-sort", "ascending")
    assert t.locator("tbody tr:not([hidden]) td").first.inner_text() == "row00"          # amount 0 sorts first
    bar = h.page.locator("#main").locator(".injected .tbl-pg")
    expect(bar.locator(".tbl-pg-info")).to_contain_text("1–25 of 30")
    bar.locator("[data-pg=fwd]").first.click()
    expect(bar.locator(".tbl-pg-info")).to_contain_text("26–30 of 30")
    assert h.js("document.querySelectorAll('.tbl-pg, .tbl-sortable').length") == 0          # nothing was added to the host page


def test_tree_row_expands_inside_the_element(host):
    h = host()
    h.open()
    inject(h, TREE)
    t = h.page.locator("#main").locator(".injected table.tbl-tree")
    child = t.locator("tbody tr").nth(1)
    expect(child).to_be_hidden()
    t.locator(".tr-tog").click()
    expect(child).to_be_visible()
    expect(t.locator(".tr-tog")).to_have_attribute("aria-expanded", "true")


def test_pivot_regroups_without_saving_inside_the_element(host):
    h = host()
    h.open()
    box = h.page.locator("#main").locator(".pv-box").first
    for trade in ("BBG-60000001", "BBG-60000005", "BBG-60000011", *RAPID):           # the first trade that has a pivot panel
        if box.count():
            break
        h.page.evaluate(f"window.hostApp.show('trade', '{trade}')")
        h.page.wait_for_timeout(1500)
        h.state("#main", "live") if h.js(f"document.querySelector('#main').getAttribute('data-state')") == "loading" else None
    assert box.count() == 1, "no trade of the pack has a pivot panel"
    box.locator("[data-pv-tab=pivot]").click()
    expect(box.locator(".pv-host table").first).to_be_visible(timeout=20000)
    before = box.locator(".pv-host").inner_text()
    assert any("/embed/v1/poc/records/" in r[1] for r in h.requests), "the rows were not fetched through the embed API"
    chip = box.locator(".pv-fields .pv-chip").first          # regroup with the console's own key: C puts the field in Columns
    chip.focus()
    h.page.keyboard.press("c")
    expect(box.locator(".pv-host")).not_to_have_text(before, timeout=10000)
    assert box.locator("[data-pv-save], [data-pv-reset], [data-pv-promote], [data-pv-export]").count() == 0        # layouts are the Sutra's only
    assert not [r for r in h.requests if r[0] in ("PUT", "DELETE") or "/api/pivot" in r[1]]


def test_panel_help_popover_and_the_about_drawer_inside_the_element(host):
    h = host()
    h.open()
    view = h.page.locator("#main")
    help_ = view.locator("section[data-panel] a.pnl-help[data-about-help]").first
    expect(help_).to_be_visible(timeout=15000)
    help_.click()
    pop = view.locator(".about-pop")
    expect(pop).to_be_visible(timeout=15000)
    h.page.keyboard.press("Escape")
    expect(pop).to_be_hidden()
    view.locator("[data-about-open]").click()
    drawer = view.locator("#aboutDrawer")
    expect(drawer).to_be_visible()
    expect(drawer.locator("[data-about-body]")).not_to_contain_text("Loading", timeout=20000)
    assert any("/embed/v1/poc/about/" in r[1] for r in h.requests)
    h.page.keyboard.press("Escape")
    expect(drawer).to_be_hidden()


def test_keys_are_scoped_to_the_element(host):
    h = host()
    h.open()
    view = h.page.locator("#main")
    drawer = view.locator("#aboutDrawer")
    h.page.locator("body").click(position={"x": 5, "y": 5})
    h.page.keyboard.press("?")                                        # a key in the host page: not Drishti's
    h.page.keyboard.press("F1")
    h.page.wait_for_timeout(300)
    expect(drawer).to_be_hidden()
    view.locator(".vtitle").first.click()                             # focus inside the element: the same key is Drishti's
    h.page.keyboard.press("?")
    expect(drawer).to_be_visible()
    h.page.keyboard.press("Escape")
    expect(drawer).to_be_hidden()


# ---- responsive: the element answers to its OWN width (container queries), not the host page's viewport -------------------
COLS = "(sel) => getComputedStyle(document.querySelector('#main').shadowRoot.querySelector(sel)).gridTemplateColumns.split(' ').length"
PAGE_OVERFLOW = "() => document.documentElement.scrollWidth - document.documentElement.clientWidth"


def _box(h, sel):
    return h.js("(() => { const e = document.querySelector('#main').shadowRoot.querySelector('%s'); const r = e.getBoundingClientRect();"
                " return {x: r.x, y: r.y, w: r.width, h: r.height, b: r.bottom}; })()" % sel)


def test_a_phone_host_with_a_full_width_element_has_no_sideways_scroll_and_stacks(host):
    h = host(viewport=(390, 844), touch=True)
    h.open()
    expect(h.page.locator("#main").locator("section[data-panel]").first).to_be_visible(timeout=15000)
    assert h.js(PAGE_OVERFLOW) <= 0, "the host page scrolls sideways"
    assert h.page.evaluate(COLS, ".vgrid") == 1                         # main and context stack
    assert h.page.evaluate(COLS, ".strip") <= 2                         # the key figures wrap to two columns
    panels = h.js("[...document.querySelector('#main').shadowRoot.querySelectorAll('.vmain > section[data-panel]')].slice(0, 4).map((e) => e.getBoundingClientRect().x)")
    assert len(set(round(x) for x in panels)) == 1, "the panels sit side by side on a phone"
    assert h.js("document.querySelector('#main').getBoundingClientRect().width") <= 390
    for sel in ("a.pnl-help[data-about-help]", "[data-about-open]"):       # finger-sized controls
        b = _box(h, sel)
        assert b["w"] >= 43.5 and b["h"] >= 43.5, f"{sel} is {b['w']}x{b['h']}"


def test_a_desktop_host_with_the_element_in_a_narrow_sidebar_uses_the_narrow_layout(host):
    h = host(viewport=(1800, 1000))
    h.open()
    expect(h.page.locator("#main").locator("section[data-panel]").first).to_be_visible(timeout=15000)
    wide = (h.page.evaluate(COLS, ".vgrid"), h.page.evaluate(COLS, ".strip"))
    assert wide[0] == 2 and wide[1] >= 4, f"the full-width layout is not wide: {wide}"
    h.js("document.querySelector('section.main').style.cssText = 'width: 360px'")
    h.page.wait_for_function("document.querySelector('#main').clientWidth <= 360")
    assert h.js("window.innerWidth") == 1800                                # the host viewport did not change
    h.until(lambda: h.page.evaluate(COLS, ".vgrid") == 1, what="the narrow one-column layout")
    assert h.page.evaluate(COLS, ".strip") <= 2
    assert h.js(PAGE_OVERFLOW) <= 0
    table = h.js("(() => { const w = document.querySelector('#main').shadowRoot.querySelector('.tbl-wrap'); return w ? getComputedStyle(w).overflowX : 'auto'; })()")
    assert table in ("auto", "scroll")                                      # a table scrolls inside its panel, never the host page


def test_taps_open_the_panel_help_and_a_field_hint_and_the_about_sheet_is_a_bottom_sheet(host):
    h = host(viewport=(390, 844), touch=True)
    h.open()
    view = h.page.locator("#main")
    help_ = view.locator("section[data-panel] a.pnl-help[data-about-help]").first
    expect(help_).to_be_visible(timeout=15000)
    help_.tap()
    expect(view.locator(".about-pop")).to_be_visible(timeout=15000)
    gloss = view.locator("[data-gloss]").first
    gloss.scroll_into_view_if_needed()
    gloss.tap()
    expect(view.locator(".about-tip")).to_be_visible(timeout=5000)             # a tap pins the field hint: no hover needed
    view.locator("[data-about-open]").tap()
    drawer = view.locator("#aboutDrawer")
    expect(drawer).to_be_visible()
    expect(drawer.locator("[data-about-body]")).not_to_contain_text("Loading", timeout=20000)
    el = h.js("(() => { const r = document.querySelector('#main').getBoundingClientRect(); return {w: r.width}; })()")
    h.until(lambda: _box(h, "#aboutDrawer")["b"] <= 846, timeout=3, what="the sheet scrolled into view")
    box = _box(h, "#aboutDrawer")
    assert abs(box["w"] - el["w"]) <= 2, f"the sheet is {box['w']} wide in an element {el['w']} wide"
    assert box["y"] > 0 and box["h"] < 844, "a bottom sheet, not a full-height side drawer"
    assert 0 < box["b"] <= 844 + 2, f"the sheet is out of view (bottom {box['b']})"
    assert h.js(PAGE_OVERFLOW) <= 0
