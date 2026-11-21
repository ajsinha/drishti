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


"""The client renderer on a page that is not the console (EMBED_GUIDE.md sections 7.4, 12 and 16): the host part's options, in a blank page
under the console's CSP on one made-up origin (render_harness.py), Chromium. Every panel is drawn by Drishti Elements' painter, which has
its own tables, tabs, pivots and data tables: ``behaviours: 'console'`` (the console's own scripts inside the container, the old
renderer's) is accepted and draws the same panels with the painter's behaviours. ``about: true`` draws About this page from JSON in a side
sheet inside the container. ``navigation: 'follow'`` lets a link be a
link. ``subscribe(fn)`` takes the host's own channel. The candlestick prints the server's texts. No request leaves the origin, no CSP
violation, no script error, and window.open is never called."""
import copy
import json

import pytest

import render_cases as cases
import render_harness as H
from table_chart_backend import SPEC, flows_panel

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
EXPLAIN = json.loads((cases.FIXTURES / "explain_trade_IRS-48213.json").read_text())


def tables_vm() -> dict:
    """A view with a table of 40 flows (funnels, Chart any table), a table with a Pivot tab, the showcase's nested pivot and a drill."""
    vm = copy.deepcopy(cases.fixture_views()["view_trade_IRS-48213"])
    shapes = cases.edge_cases()["more-shapes"]
    drill = next(p for p in shapes["panels"] if (p.get("data") or {}).get("levels") and p["kind"] in ("table", "ladder"))
    nested = next(p for p in cases.showcase()["panels"] if p["id"] == "grid")
    pivoted = copy.deepcopy(flows_panel(6))
    pivoted.update(id="trades", title="Trades")
    pivoted["data"]["pivot"] = copy.deepcopy(SPEC)
    vm["panels"] = [flows_panel(), pivoted, nested, drill]
    vm["provenance"]["live"] = False
    return vm


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture(scope="module")
def site():
    s = H.Site()
    s.add_vm("tables", tables_vm())
    s.add_vm("showcase", cases.showcase())
    s.pages["/v/trade/IRS-48213/about?format=json&generation=1742"] = json.dumps({"explain": EXPLAIN, "index": {"terms": [], "panels": {}}})
    s.pages["/v/trade/IRS-48213/about?format=json"] = s.pages["/v/trade/IRS-48213/about?format=json&generation=1742"]
    return s


@pytest.fixture
def lab(browser, site):
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.route("**/*", site.handle)
    before = len(site.requests)
    page.goto(H.ORIGIN + "/render-test/lab.html")
    page.wait_for_function("() => window.__lab === true")
    start = page.url
    yield page
    try:
        assert errors == [], errors
        assert page.evaluate("() => window.__csp") == []
        assert page.evaluate("() => window.__opened") == []
        assert [u for u in site.requests[before:] if not u.startswith(H.ORIGIN + "/")] == []
        assert page.url == start, "the host's address never changes"
    finally:
        page.close()


def render(page, name, opts):
    page.evaluate("async ([n, o]) => { window.h = window.R.render(document.getElementById('host'), await window.vm(n), o); await window.h.ready; }", [name, opts])
    page.wait_for_timeout(500)


ROOT = "document.getElementById('host').shadowRoot"


def test_console_behaviours_are_the_painter_s(lab):
    """The console's scripts are not started inside the container any more: the painter's tables (paging, sort, filter) are the one way."""
    render(lab, "tables", {"behaviours": "console"})
    assert lab.evaluate(f"() => {ROOT}.querySelectorAll('.dv-panel').length") == 4
    assert lab.evaluate("() => [...document.scripts].filter((s) => /\\/js\\/drishti_(colfilter|tables|zoom|controls)\\.js/.test(s.src)).length") == 0


def test_about_is_drawn_from_json_inside_the_container(lab):
    render(lab, "tables", {"about": True})
    lab.locator("#host [data-r-about]").click()
    lab.wait_for_function(f"() => !!{ROOT}.querySelector('.r-about .about-card')", timeout=10000)
    text = lab.evaluate(f"() => {ROOT}.querySelector('.r-about').innerText")
    assert "Where the data came from" in text and "Where next" in text
    lab.keyboard.press("Escape")
    assert lab.evaluate(f"() => {ROOT}.querySelector('.r-about').hidden")


def test_a_followed_link_is_a_link_and_the_event_comes_first(lab):
    render(lab, "tables", {"navigation": "follow", "behaviours": "host"})
    lab.evaluate("() => { window.__cancel = ['navigate']; }")              # the host keeps the page where it is: the event can stop it
    lab.locator("#host [data-panel='flows'] a.dv-link").first.click()
    nav = [e for e in lab.evaluate("() => window.__events") if e[0] == "navigate"]
    assert nav and nav[-1][1]["target"] == "entity" and nav[-1][1]["navigation"] == "follow" and nav[-1][1]["kind"] == "trade"


def test_subscribe_takes_the_host_s_own_channel(lab):
    render(lab, "tables", {})
    lab.evaluate("""() => {
      window.__stop = 0;
      window.h.subscribe((on) => { window.__on = on; return () => { window.__stop++; }; });
      window.__on.frame({ seq: 1, generation: 9, patches: [{ op: 'strip', index: 0, cell: { label: 'X', text: 'from the host' } }] });
    }""")
    lab.wait_for_function(f"() => {ROOT}.querySelector('.dv-fig[data-index=\"0\"] dd').textContent === 'from the host'")
    lab.evaluate("() => window.h.unsubscribe()")
    assert lab.evaluate("() => window.__stop") == 1


@pytest.mark.xfail(strict=True, reason="Elements finding (G4_REHEARSAL pass 3, E11): drishti-views' candlestick data table prints the raw numbers, not the producer's openText/highText/lowText/closeText the contract carries")
def test_the_candles_print_the_servers_texts(lab, site):
    vm = cases.showcase()
    bars = next(p for p in vm["panels"] if p["id"] == "bars")
    for k in bars["data"]["candles"]:
        k.update(openText="o" + str(k["open"]), highText="h", lowText="l", closeText="c", volumeText="v" if k.get("volume") is not None else None)
    site.add_vm("candles", vm)
    render(lab, "candles", {})
    cells = lab.evaluate(f"() => [...{ROOT}.querySelectorAll('[data-panel=\"bars\"] details td')].slice(0, 5).map((c) => c.textContent)")
    assert cells[:4] == ["o" + str(bars["data"]["candles"][0]["open"]), "h", "l", "c"]
