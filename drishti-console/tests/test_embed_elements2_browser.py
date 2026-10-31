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


"""Elements 2.0 (docs/architecture/ELEMENTS.md section 19): <drishti-view> drawn by the client-side renderer from the embed API v2, on the
same host application (tools/elements-demo, ``/?elements=2``) and the same real scratch server as Elements 1.x's acceptance tests, in
Chromium. Elements 1.x stays as it is (test_embed_elements_browser.py, unchanged); this file runs on its own ports.

What it proves: the first view is live under the host's strict CSP, drawn from the ViewModel (``"contract": "1.0"``, no HTML over the
wire), with masks; a link is a ``drishti:navigate`` the host takes over; the console's behaviours run inside the element (the Pivot tab's
grid from the records the embed API reads, the column funnels); About is drawn from JSON in the element; one live connection per page
(a ``vm:`` subscription, panels as JSON); the versioned paths answer (``/embed/v2/render/<v>/``, ``/embed/v2/elements/2/``, a partial
version's 302, a retired one's 410) and the served bytes match the integrity record. Skipped without Playwright, Chromium or the jar."""
import json
import os
import urllib.error
import urllib.request

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import sync_playwright  # noqa: E402

import test_embed_elements_browser as E  # noqa: E402
from core import render_dist as rd  # noqa: E402
from scratch_ports import worker_ports  # noqa: E402
from test_embed_elements_browser import stack  # noqa: E402,F401 - the fixture

PORTS = worker_ports(*(os.environ.get(k, d) for k, d in (("DRISHTI_ELEMENTS2_SERVER_PORT", 18973), ("DRISHTI_ELEMENTS2_CONSOLE_PORT", 17977), ("DRISHTI_ELEMENTS2_HOST_PORT", 17978))))


@pytest.fixture(scope="module")
def browser(stack):  # noqa: F811
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"Chromium is not installed or cannot start: {str(e)[:120]}")
        yield b
        b.close()


class Page2(E.Page):
    def open(self, path="/?elements=2"):
        self.page.goto(E.HOST + path)
        self.state("#main", "live", timeout=30000)
        return self


@pytest.fixture
def host(browser):
    pages = []

    def make(**kw):
        p = Page2(browser, **kw)
        pages.append(p)
        return p

    yield make
    for p in pages:
        p.close()


def get(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Origin": E.HOST}), timeout=15) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def test_the_first_view_is_live_drawn_from_the_view_model_under_the_host_s_csp(host):
    h = host()
    before = len(json.loads(urllib.request.urlopen(E.HOST + "/api/csp-reports").read()))
    h.open()
    assert h.js("window.DrishtiElements.version") == "2.1.0" and h.js("window.DrishtiElements.renderer") == rd.VERSION
    assert h.title() == E.MAIN_ID
    assert "MTM" in h.js("document.querySelector('#main').shadowRoot.querySelector('.strip').innerText")
    h.until(lambda: len(h.events("tick", "main")) >= 2, 25, "two ticks of the main view")
    views = [r for r in h.requests if r[0] == "GET" and "/embed/v2/views/" in r[1]]
    assert views and not [r for r in h.requests if "/embed/v1/" in r[1]], "Elements 2.0 speaks the embed API v2 only"
    gets = [r for r in h.requests if r[0] == "GET" and "/embed/v2/channel?" in r[1]]
    assert len(gets) == 1 and "vm%3A" in gets[0][1], "one connection per page, vm: subscriptions (panels as JSON)"
    assert h.js("window.__viol") == []
    reports = json.loads(urllib.request.urlopen(E.HOST + "/api/csp-reports").read())[before:]
    assert reports == [], json.dumps(reports)
    assert not [c for c in h.console if c.startswith("PAGEERROR")], h.console


def test_the_view_model_carries_its_contract_and_masks(host):
    h = host()
    h.open()
    vm = h.js("document.querySelector('#main').handle.viewModel")
    assert vm["contract"] == "1.0" and vm["ref"] == {"kind": E.KIND, "id": E.MAIN_ID}
    E.show(h, "trade", E.TRADE_ID)
    assert "•••" in h.js("document.querySelector('#main').shadowRoot.querySelector('.view').innerText")


def test_a_link_is_a_navigate_event_the_host_takes_over(host):
    h = host()
    h.open()
    link = h.page.locator("#main a.lnk[data-r-link='entity']").first
    kind, ident = link.get_attribute("data-r-kind"), link.get_attribute("data-r-id")
    link.click()
    h.until(lambda: h.events("navigate", "main"), 10, "drishti:navigate")
    h.until(lambda: h.js("document.querySelector('#main').getAttribute('entity')") == ident, 15, "the element shows the linked entity")
    assert h.js("document.querySelector('#main').getAttribute('kind')") == kind
    assert h.js("location.pathname + location.search") == "/?elements=2", "the host's page never navigates"


def test_the_console_s_behaviours_run_inside_the_element_and_about_comes_as_json(host):
    h = host()
    h.open()
    E.show(h, "netting-set", E.NETTING_SET)
    root = "document.querySelector('#main').shadowRoot"
    h.until(lambda: h.js(f"!!{root}.querySelector('th .cf-btn')"), 15, "the column funnels")
    tab = h.page.locator("#main [data-pv-tab='pivot']").first
    if tab.count():
        tab.click()
        h.until(lambda: h.js(f"!!{root}.querySelector('.pv-host table')"), 20, "the Pivot tab's grid")
        assert [r for r in h.requests if "/embed/v2/views/" in r[1] and "/records" in r[1]], "the pivot's rows come from the embed API v2"
    h.page.locator("#main [data-r-about]").click()
    h.until(lambda: h.js(f"!!{root}.querySelector('.r-about .about-card')"), 15, "About this page")
    assert [r for r in h.requests if "/embed/v2/views/" in r[1] and "/about" in r[1]]
    assert "Where the data came from" in h.js(f"{root}.querySelector('.r-about').innerText")


def test_the_versioned_paths_answer_and_the_bytes_match_the_record():
    status, headers, _ = get(E.CONSOLE_URL + "/embed/v2/render/1/drishti-render.js")
    assert status == 200 or status == 302
    req = urllib.request.Request(E.CONSOLE_URL + "/embed/v2/render/1/drishti-render.js", headers={"Origin": E.HOST})

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    try:
        opener.open(req, timeout=10)
        raise AssertionError("a partial version must redirect")
    except urllib.error.HTTPError as e:
        assert e.code == 302 and e.headers["Location"] == f"/embed/v2/render/{rd.VERSION}/drishti-render.js"
    try:
        opener.open(urllib.request.Request(E.CONSOLE_URL + "/embed/v2/elements/2/drishti-elements.js"), timeout=10)
        raise AssertionError("Elements 2 must redirect to the renderer that carries it")
    except urllib.error.HTTPError as e:
        assert e.code == 302 and e.headers["Location"] == f"/embed/v2/render/{rd.VERSION}/drishti-elements.js"
    status, _, body = get(E.CONSOLE_URL + "/embed/v2/render/9.9.9/drishti-render.js")
    assert status == 410 and json.loads(body)["code"] == "DRS-8006"
    record = json.loads((rd.ROOT / rd.VERSION / rd.RECORD).read_text())
    for rel in ("drishti-render.js", "drishti-elements.js", "m/screen.js", "drishti-render.css"):
        status, headers, body = get(E.CONSOLE_URL + f"/embed/v2/render/{rd.VERSION}/{rel}")
        assert status == 200 and rd.sri(body) == record["files"][rel]["integrity"], rel
        assert "immutable" in headers["Cache-Control"] and headers["Access-Control-Allow-Origin"] == E.HOST and headers["Drishti-Embed-Api"] == "2.0"
    status, _, _ = get(E.CONSOLE_URL + "/embed/v2/js/not-a-script.js")
    assert status == 404
