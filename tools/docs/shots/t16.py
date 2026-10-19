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

"""Pictures of tutorial 16, Embed Drishti (docs/tutorials/16-embed-drishti.md), written to docs/tutorials/img/t16/.

The stack must run with embedding on (DRISHTI_EMBED_ENABLED=true on the server and the console, DRISHTI_PUBLIC_URL and DRISHTI_CONSOLE_URL set). The pictures show Admin,
Embedding (registering the demo host application: origins, scopes, the kinds and the one report it may show, the client secret shown once), and then the sample host
(tools/elements-demo, on its own origin) showing a <drishti-view> and a <drishti-report> under a strict content-security policy.

The host is started by hand with the secret the registration printed (it is passed in the environment variable DRISHTI_SHOTS_EMBED_SECRET: see register() below)."""
from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import urllib.parse
from pathlib import Path

from _bi import quiet_inbox
from _tut import ADMIN, OUT, ROOT, SERVER_ENV, call, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t16", OUT / "t16")
HOST_PORT = int(os.environ.get("DRISHTI_SHOTS_HOST_PORT", "17944"))
HOST = f"http://127.0.0.1:{HOST_PORT}"
APP = "harbour-crm"
KEY = json.loads((ROOT / "tools" / "elements-demo" / "demo-host-key.json").read_text())
SECRET_FILE = Path(os.environ.get("DRISHTI_SHOTS_WORK") or "/tmp") / "embed-secret.txt"      # the registration's secret, for the host to read (the work folder is never committed)
REPORT = "report:file:report:live-trading-desk"
MODEL_REPORT = "report:file:report:trades-overview"            # a report on a model (trades-basic@1)


def start_servers(work):
    """A scratch stack with embedding on (the server's public address and the console's, which are the token's issuer and audience)."""
    import shotlib
    return shotlib.start_servers(work, {**SERVER_ENV, "DRISHTI_EMBED_ENABLED": "true", "DRISHTI_PUBLIC_URL": f"http://127.0.0.1:{shotlib.SERVER_PORT}",
                                        "DRISHTI_CONSOLE_URL": f"http://127.0.0.1:{shotlib.CONSOLE_PORT}"})


def b64(hexa: str) -> str:
    return base64.urlsafe_b64encode(bytes.fromhex(hexa if len(hexa) % 2 == 0 else "0" + hexa)).rstrip(b"=").decode()


def jwks() -> str:
    return json.dumps({"keys": [{"kty": "RSA", "kid": KEY["kid"], "n": b64(KEY["n"]), "e": b64(KEY["e"])}]})


def host_users(c: Ctx):
    """The host's two demo users must exist in Drishti too (the token exchange refuses a stranger)."""
    login(c)
    for name, role in (("viewer", "viewer"), ("author", "author")):
        call(c, "POST", "/admin/api/users", {"username": name, "displayName": name.capitalize(), "roles": [role], "password": f"Embed-Demo-Pass-{name}-1!", "mustChangePassword": False})


@shot("01-embedding-empty.jpg", also=("02-register-form.jpg", "03-secret-once.jpg", "04-registered.jpg"))
def registering(c: Ctx):
    host_users(c)
    quiet_inbox(c)
    call(c, "DELETE", f"/admin/embedding/api/apps/{APP}")
    go(c, "/admin/embedding", height=1300, wait="[data-new]", settle=800)
    c.save("01-embedding-empty.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    c.page.click("[data-new]")
    d = c.page.locator("[data-new-dialog]")
    d.wait_for(state="visible")
    d.locator("input[name=name]").fill("Harbour CRM")
    d.locator("input[name=id]").fill(APP)
    d.locator("textarea[name=origins]").fill(HOST)
    d.locator("input[name=auth][value=both]").check()
    d.locator("textarea[name=jwks]").fill(jwks())
    d.locator("input[name=scope][value='embed:about']").check()          # the demo host asks for view and about (the page's About text) together
    d.locator("input[name=scope][value='embed:view']").check()
    d.locator("input[name=scope][value='embed:report']").check()
    d.locator("input[name=scope][value='embed:search']").check()         # <drishti-search> of the sample pages
    d.locator("input[name=kinds]").fill(f"trade, trade-valuation, {REPORT}, {MODEL_REPORT}")
    d.locator("input[name=callsPerMinute]").fill("6000")                 # a live report asks again at every tick: the defaults (600 and 60 a minute) are for a quiet page
    d.locator("input[name=userCallsPerMinute]").fill("600")
    d.locator("input[name=contact]").fill("crm-team@harbour.example")
    c.page.wait_for_timeout(500)
    c.save("02-register-form.jpg", locator="[data-new-dialog]")
    d.locator("[data-create]").click()
    secret = c.page.locator("[data-secret-value]")
    secret.wait_for(state="visible", timeout=30000)
    SECRET_FILE.write_text(secret.input_value())
    c.page.wait_for_timeout(500)
    c.save("03-secret-once.jpg")
    c.page.locator("[data-secret-dialog] [data-close], [data-secret-dialog] button").first.click()
    c.page.wait_for_timeout(1500)
    c.page.reload()
    c.page.wait_for_timeout(1200)
    c.save("04-registered.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})


def start_host() -> subprocess.Popen:
    from shotlib import CONSOLE_PORT, SERVER_PORT
    return subprocess.Popen([sys.executable, str(ROOT / "tools" / "elements-demo" / "server.py"), f"--port={HOST_PORT}", f"--console=http://127.0.0.1:{CONSOLE_PORT}",
                             f"--server=http://127.0.0.1:{SERVER_PORT}", f"--secret={SECRET_FILE.read_text()}", f"--app={APP}"], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def host_page(c: Ctx, path: str, user: str, width: int = 1280, height: int = 900):
    """Opens a page of the host application, signed in there as `user` (the host's own demo sign-in), and waits for the element."""
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.context.clear_cookies()
    c.page.goto(HOST + path)
    c.page.locator(f'[data-login="{user}"]').click()
    c.page.wait_for_timeout(500)


@shot("05-host-view.jpg", also=("06-host-report.jpg", "07-host-report-click.jpg", "08-host-phone.jpg", "12-host-model-report.jpg", "09-usage.jpg"))
def host(c: Ctx):
    proc = start_host()
    try:
        import time
        import urllib.request
        for _ in range(40):
            try:
                urllib.request.urlopen(HOST + "/api/me", timeout=1).read()
                break
            except OSError:
                time.sleep(0.25)
        host_page(c, "/", "author")
        c.page.locator("drishti-view#main").wait_for(timeout=30000)
        c.page.wait_for_function("() => { const e = document.querySelector('drishti-view#main'); return e && e.shadowRoot && e.shadowRoot.querySelector('[data-panel], .pnl') }", timeout=30000)
        c.page.wait_for_timeout(2500)
        c.save("05-host-view.jpg")
        host_page(c, "/report", "viewer")
        c.page.wait_for_function("() => { const e = document.querySelector('drishti-report#rep'); return e && e.shadowRoot && e.shadowRoot.querySelector('.bi-vis') }", timeout=45000)
        c.page.wait_for_timeout(4000)
        c.save("06-host-report.jpg")
        c.page.wait_for_timeout(500)
        c.page.evaluate("document.querySelector('#log').scrollIntoView()")
        c.page.wait_for_timeout(500)
        c.save("07-host-report-click.jpg")
        host_page(c, "/report", "viewer", width=390, height=844)
        c.page.wait_for_timeout(6000)
        c.save("08-host-phone.jpg")
        host_page(c, "/report?report=file:report:trades-overview&visual=by-desk", "viewer")
        c.page.wait_for_function("() => [...document.querySelectorAll('drishti-report')].every(e => e.shadowRoot && e.shadowRoot.querySelector('.bi-vis'))", timeout=45000)
        c.page.wait_for_timeout(4000)
        c.save("12-host-model-report.jpg")
        print("LOG", c.page.locator("#log").inner_text())

        login(c, height=900)
        go(c, "/admin/embedding", height=900, wait="table", settle=1500)
        c.page.evaluate("document.querySelectorAll('.tbl-wrap').forEach((w) => { w.scrollLeft = w.scrollWidth; })")   # the Usage column, past two kinds and two reports
        c.page.wait_for_timeout(300)
        c.save("09-usage.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 620})
        print("CSP", urllib.request.urlopen(HOST + "/").headers.get("Content-Security-Policy"))
        print("CSPREPORTS", urllib.request.urlopen(HOST + "/api/csp-reports").read().decode())
    finally:
        proc.terminate()
        proc.wait(10)


def reregister(c: Ctx, per_user: int) -> None:
    """Registers the host again (a new secret, written to the secret file) with a per-user call limit."""
    login(c)
    call(c, "DELETE", f"/admin/embedding/api/apps/{APP}")
    made = call(c, "POST", "/admin/embedding/api/apps", {"id": APP, "name": "Harbour CRM", "origins": [HOST], "kinds": ["trade", "trade-valuation", REPORT, MODEL_REPORT], "scopes": ["embed:view", "embed:about", "embed:report", "embed:search"],
                                                       "secret": True, "jwks": jwks(), "callsPerMinute": 6000, "userCallsPerMinute": per_user, "tokenSeconds": 300})
    SECRET_FILE.write_text(made["secret"])


@shot("10-rate-limited.jpg")
def rate_limited(c: Ctx):
    """A live report asks again at every tick: with the per-user limit at 5 calls a minute the page is told DRS-8004 (the limit is the application's, set when it is registered)."""
    import time
    import urllib.request
    reregister(c, 5)
    proc = start_host()
    try:
        for _ in range(40):
            try:
                urllib.request.urlopen(HOST + "/api/me", timeout=1).read()
                break
            except OSError:
                time.sleep(0.25)
        host_page(c, "/report", "viewer", width=390, height=844)
        c.page.wait_for_function("() => /DRS-8004/.test(document.body.innerText) || [...document.querySelectorAll('drishti-report')].some(e => e.shadowRoot && /DRS-8004/.test(e.shadowRoot.textContent))", timeout=60000)
        c.page.wait_for_timeout(1500)
        c.save("10-rate-limited.jpg")
    finally:
        proc.terminate()
        proc.wait(10)
        reregister(c, 600)


@shot("11-react-sample.jpg")
def react_sample(c: Ctx):
    """The React 18 sample host page (docs/guides/examples/embed/react): search, view, About body and report in one page of the host (needs the framework builds: fetch-vendor.sh)."""
    import time
    import urllib.request
    if not (ROOT / "docs" / "guides" / "examples" / "embed" / "vendor" / "react" / "react.production.min.js").exists():
        raise FileNotFoundError("the framework builds are not fetched: bash docs/guides/examples/embed/fetch-vendor.sh --only react")
    reregister(c, 600)
    host_users(c)
    proc = start_host()
    try:
        for _ in range(40):
            try:
                urllib.request.urlopen(HOST + "/api/me", timeout=1).read()
                break
            except OSError:
                time.sleep(0.25)
        c.page.set_viewport_size({"width": 1280, "height": 1100})
        c.page.context.clear_cookies()
        c.page.goto(HOST + "/samples/react/index.html")
        c.page.wait_for_function("() => { const e = document.querySelector('#view'); return e && /^(live|static)$/.test(e.getAttribute('data-state') || '') }", timeout=60000)
        c.page.wait_for_function("() => { const e = document.querySelector('#about'); return e && e.getAttribute('data-state') === 'loaded' }", timeout=60000)
        c.page.locator("drishti-search input").fill("END-1000")
        c.page.locator("drishti-search button.ds-hit").first.wait_for(timeout=30000)
        c.page.wait_for_timeout(1500)
        c.save("11-react-sample.jpg")
    finally:
        proc.terminate()
        proc.wait(10)
