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

"""The React 18, Vue 3 and Angular 18 sample host pages (docs/guides/examples/embed, docs/guides/ELEMENTS.md) in Chromium against a HOST application on another origin
(tools/elements-demo, which serves them at /samples/<framework>/ under its strict policy), the real server (embedding on, security on, the demo source ticking) and the
real console (tests/bi_live_stack.py): each page draws <drishti-view>, <drishti-about> and <drishti-report> for the signed-in user, the framework shows the view's state in
its own markup, a pick in <drishti-search> points the view (and the About body) at the entity, and the page's policy reports no violation.

The framework builds are not in the repository: bash docs/guides/examples/embed/fetch-vendor.sh. A page whose build is missing is skipped. Ports 18949, 17949 and 17947
(the live stack's, moved to this test's); never run with pytest -n."""
from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

import pytest

os.environ.setdefault("DRISHTI_BI_LIVE_SERVER_PORT", "18949")
os.environ.setdefault("DRISHTI_BI_LIVE_CONSOLE_PORT", "17949")
HOST_PORT = int(os.environ.get("DRISHTI_EMBED_FRAMEWORKS_HOST_PORT", "17947"))

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_live_stack as S  # noqa: E402
from conftest import CONSOLE  # noqa: E402

ROOT = CONSOLE.parent
HOST = f"http://127.0.0.1:{HOST_PORT}"
APP = "elements-frameworks-demo"
VENDOR = ROOT / "docs" / "guides" / "examples" / "embed" / "vendor"
BUILDS = {"react": VENDOR / "react" / "react.production.min.js", "vue": VENDOR / "vue" / "vue.runtime.global.prod.js", "angular": VENDOR / "angular" / "core" / "fesm2022" / "core.mjs"}
KEY = json.loads((ROOT / "tools/elements-demo/demo-host-key.json").read_text())
VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def _wait(url, seconds=60):
    end = time.time() + seconds
    while time.time() < end:
        try:
            urllib.request.urlopen(url, timeout=2).read()
            return
        except Exception:  # noqa: BLE001 - not up yet
            time.sleep(0.4)
    raise RuntimeError(f"{url} did not come up")


def register_host() -> str:
    """The host application (every scope the samples ask for, every kind) and the users it signs in, at the server; returns the client secret (shown once)."""
    for name, role in (("viewer", "viewer"), ("author", "author")):
        S.admin("POST", "/admin/users", {"username": name, "displayName": name, "roles": [role], "packs": ["trading", "counterparty-risk"],
                                         "password": f"Embed-Demo-Pass-{name}-1!", "mustChangePassword": False})
    enc = lambda h: base64.urlsafe_b64encode(bytes.fromhex(h if len(h) % 2 == 0 else "0" + h)).rstrip(b"=").decode()  # noqa: E731
    jwks = json.dumps({"keys": [{"kty": "RSA", "kid": KEY["kid"], "n": enc(KEY["n"]), "e": enc(KEY["e"])}]})
    status, made = S.admin("POST", "/admin/embed/apps", {"id": APP, "name": "Elements frameworks demo", "origins": [HOST],
                                                          "scopes": ["embed:view", "embed:about", "embed:report", "embed:search"], "jwks": jwks, "subjectTypes": ["jwt"],
                                                          "callsPerMinute": 100000, "userCallsPerMinute": 100000})
    assert status == 201, made
    return made["secret"]


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (S.SERVER_PORT, S.CONSOLE_PORT, HOST_PORT):
        if S.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    if not any(p.exists() for p in BUILDS.values()):
        pytest.skip("the framework builds are not fetched: bash docs/guides/examples/embed/fetch-vendor.sh")
    mp = pytest.MonkeyPatch()
    mp.setenv("DRISHTI_EMBED_ENABLED", "true")
    mp.setenv("DRISHTI_PUBLIC_URL", S.SERVER)
    mp.setenv("DRISHTI_CONSOLE_URL", S.CONSOLE_URL)
    tmp = tmp_path_factory.mktemp("embed-frameworks")
    host = None
    try:
        s = S.LiveStack(tmp).start()
    except FileNotFoundError as e:
        mp.undo()
        pytest.skip(str(e))
    try:
        secret = register_host()
        host = subprocess.Popen([sys.executable, str(ROOT / "tools/elements-demo/server.py"), f"--port={HOST_PORT}", f"--console={S.CONSOLE_URL}", f"--server={S.SERVER}",
                                 f"--secret={secret}", f"--app={APP}"], stdout=open(tmp / "host.log", "ab"), stderr=subprocess.STDOUT)
        _wait(HOST + "/api/me")
        yield s
    finally:
        if host:
            host.terminate()
            host.wait(10)
        s.close()
        mp.undo()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def shot(page, name):
    """The documentation picture, only when DRISHTI_SHOTS is set."""
    if os.environ.get("DRISHTI_SHOTS"):
        folder = ROOT / "docs" / "tutorials" / "img" / "t16"
        folder.mkdir(parents=True, exist_ok=True)
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=str(folder / name), type="jpeg", quality=85)


@pytest.mark.parametrize("framework", ["react", "vue", "angular"])
def test_a_framework_page_draws_the_elements_follows_a_search_and_keeps_its_policy(stack, browser, framework):
    if not BUILDS[framework].exists():
        pytest.skip(f"{framework} is not fetched: bash docs/guides/examples/embed/fetch-vendor.sh --only {framework}")
    context = browser.new_context(viewport={"width": 1280, "height": 1100})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(f"{HOST}/samples/{framework}/index.html")

    view, about, report = page.locator("#view"), page.locator("#about"), page.locator("#report")
    expect(view).to_have_attribute("data-state", re.compile("^(live|static)$"), timeout=60000)
    expect(about).to_have_attribute("data-state", "loaded", timeout=60000)
    expect(report).to_have_attribute("data-ready", "yes", timeout=90000)
    assert page.evaluate("document.querySelector('#view').shadowRoot.querySelector('.view') !== null")
    assert len(page.evaluate("document.querySelector('#about').shadowRoot.querySelector('.about-body').innerText")) > 20
    expect(page.locator('[data-testid="state"]')).to_have_text(re.compile("^(live|static)$"), timeout=30000)      # the framework shows the view's state in its own markup

    box = page.locator("drishti-search input")
    start = page.evaluate("document.querySelector('#view').getAttribute('entity')")
    box.fill("END-1000")
    hits = page.locator("drishti-search button.ds-hit")
    expect(hits.first).to_be_visible(timeout=30000)
    ids = [hits.nth(i).get_attribute("data-id") for i in range(hits.count())]
    pick = next((i for i in ids if i != start), None)
    assert pick, ids
    shot(page, "10-react-sample.jpg") if framework == "react" else None
    page.locator(f'drishti-search button.ds-hit[data-id="{pick}"]').click()
    expect(view).to_have_attribute("entity", pick, timeout=30000)
    expect(about).to_have_attribute("entity", pick, timeout=30000)
    expect(view).to_have_attribute("data-state", re.compile("^(live|static)$"), timeout=60000)
    expect(about).to_have_attribute("data-state", "loaded", timeout=60000)
    assert page.evaluate("window.__csp") == [], "a policy violation in the host page"
    assert errors == [], errors
    context.close()
