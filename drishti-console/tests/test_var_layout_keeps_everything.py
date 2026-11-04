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

"""The VaR screen's layout (2.9.11.b, the approved mock-up docs/lenses/mockups/var-layout/A.html and A-drawer.html) changed two
things only: the baskets left the left-hand column for two searchable drop-downs in a toolbar (System baskets: the bank's portfolios;
My baskets: the saved baskets, mine and shared with me), and the right-hand "How this number was built" column became a drawer opened
by How calculated (H). This file is the guard that nothing else went: the inventory below was taken from the page BEFORE the change
(terminal/var.html, var-explorer.js, var-explain.js): every tile, panel, chart, table, tab, control, key, every expected-shortfall
figure and every line of the explanation. The server-rendered page and the scripts are checked here; the drawn page in Chromium
(a stand-in engine, the console in-process) at the end of the file. Skipped without Playwright or Chromium (the browser part only)."""
import os
import re
import threading
import time
from pathlib import Path

import pytest

from conftest import CONSOLE, FakeBackend

# ------------------------------------------------------------------------------------------------------------------ the inventory
#: the page as served (terminal/var.html): (what it is, the text that must be in the page)
PAGE_INVENTORY = [
    # the status strip
    ("the command echo", "data-vx-cmd"), ("the business date", "data-vx-date"), ("the scenario set", "data-vx-set"),
    ("the data version", "data-vx-version"), ("the intraday badge", "data-vx-intraday"), ("the paint timing", "data-vx-timing"),
    ("F1 help: the VaR guide", 'href="/help/context/var"'), ("the reporting currency", 'aria-label="Reporting currency"'),
    ("Normal or stressed", "data-vx-scenarios"), ("the horizon", "data-vx-horizon"), ("the VaR confidence", "data-vx-conf"),
    ("the ES confidence", "data-vx-esconf"), ("the ES confidence's label", 'aria-label="ES confidence"'),
    # notices and the basket's sheet (collapsed until asked)
    ("the notice", "data-vx-notice"), ("the notices", "data-vx-notices"), ("the basket's sheet", "data-vx-basket hidden"),
    ("the sheet's opener (Alt+B)", 'aria-keyshortcuts="Alt+B"'), ("the basket's count", "data-basket-count"),
    # the hierarchy tree (stays on the left)
    ("the scopes panel", 'aria-label="Scopes"'), ("the hierarchy select", "data-vx-hierarchy"), ("the tree's freshness", 'data-vx-fresh="tree"'),
    ("the tree", 'role="tree" data-vx-tree'),
    # the scope
    ("the breadcrumb", "data-vx-crumb"), ("the tiles", "data-vx-tiles"), ("the method strip", "data-vx-method"),
    ("the tab list", "data-vx-tabs"), ("the tabs' freshness", 'data-vx-fresh="main"'),
    ("tab: P&L path", 'data-tab="path"'), ("its scenario count", "data-vx-n"), ("tab: VaR / ES history", 'data-tab="hist"'),
    ("tab: Contributions", 'data-tab="contrib"'), ("tab: Captured results", 'data-tab="captures"'),
    ("tab: In other currencies", 'data-tab="ccy"'), ("tab: Why it moved", 'data-tab="why"'),
    # P&L path
    ("the histogram", "data-vx-histo"), ("the VaR legend", "data-vx-var-legend"), ("the ES legend", "data-vx-es-legend"),
    ("the tail scenarios legend", "tail scenarios"), ("the worst 25", "data-vx-tail"), ("Worst 25 scenarios, sorted", "Worst 25 scenarios, sorted"),
    ("the P&L by scenario date", "data-vx-series"), ("its title", "data-vx-series-title"), ("the drill", "data-vx-drill"),
    ("the path table", "data-vx-pathtable"), ("its Largest loss from", "<th>Largest loss from</th>"), ("Next page", "data-vx-more"),
    ("the path note", "data-vx-path-note"), ("Download the path (CSV)", "data-vx-csv-path"), ("the path as JSONL", "data-vx-jsonl-path"),
    # VaR / ES history
    ("the history chart", "data-vx-hline"), ("its −VaR", "−VaR"), ("its −ES", "−ES"), ("its hypothetical P&L", "hypothetical P&amp;L"),
    ("its backtest exceptions", "backtest exception"), ("the history note", "data-vx-hist-note"), ("the history table", "data-vx-histtable"),
    ("the history table's ES", '<th class="n">VaR</th><th class="n">ES</th><th class="n">P&amp;L</th>'),
    # contributions
    ("Children", 'data-by="child"'), ("By position", 'data-by="position"'), ("By trade", 'data-by="trade"'),
    ("rows shown", "data-vx-ctable-shown"), ("the contributions table", "data-vx-ctable"), ("Standalone VaR", "Standalone VaR"),
    ("Component VaR", "Component VaR"), ("Marginal (per 1m)", "Marginal (per 1m)"), ("Incremental VaR", "Incremental VaR"),
    ("the contributions note", "data-vx-ctable-note"),
    # captures
    ("the capture note", "data-vx-cap-note"), ("Capture VaR", "data-vx-capture>"), ("the capture message", "data-vx-cap-msg"),
    ("the captures table", "data-vx-captable"), ("the captures table's ES", '<th class="n">VaR</th><th class="n">ES</th><th>Note</th>'),
    ("the compare", "data-vx-compare"),
    # other currencies
    ("the currencies note", "data-vx-ccy-note"), ("the currencies table", "data-vx-ccytable"),
    ("the currencies table's ES", '<th class="n">VaR</th><th class="n">ES</th><th class="n" data-vx-ccy-inpage>'),
    # why it moved
    ("Waterfall", 'data-vw-view="waterfall"'), ("Matrix", 'data-vw-view="matrix"'), ("Sentences", 'data-vw-view="sentences"'),
    ("the dates", "data-vw-dates"), ("From", "data-vw-from"), ("To", "data-vw-to"), ("Explain", ">Explain</button>"),
    ("the working (.xlsx)", "data-vw-xlsx"), ("the explain message", "data-vw-msg"), ("the explain host", "data-vw-host"),
    ("how it is worked out (F1)", 'href="/help/context/var-explain"'),
    # how this number was built (now the drawer): its list, its note and its three actions
    ("How this number was built", "How this number was built"), ("the explanation's list", "<dl data-vx-prov></dl>"),
    ("the reproducibility note", "Every figure on this page is reproducible from the vector batch, the scenario set and the engine above."),
    ("Export CSV", "data-vx-csv "), ("Capture VaR (side)", "data-vx-capture-side"), ("What-if…", "data-vx-whatif"),
]

#: the scripts the page loads, in order
SCRIPTS = ["Arrow.es2015.min.js", "drishti-table.js", "var-basket-sheet.js", "var-basket.js", "scope-delete.js", "var-charts.js",
           "var-explorer.js", "var-explain.js"]

#: what the scripts draw (var-explorer.js): the six tiles, the tree's columns, every line of How this number was built, the actions
EXPLORER_INVENTORY = [
    '(stressed ? "Stressed VaR " : "VaR ")', '(stressed ? "Stressed ES " : "ES ")', "C.money(d.es)", '"mean of worst " + d.esCount',
    '"1-day ES × √10"', '"Sum of standalone VaR"', '"Diversification"', '"Limit utilisation"', '"Backtest exceptions ("',
    '"-week average"', '["Scope", "VaR " + S.ccy, "ES", "Limit"]', "n.es", "r.standaloneEs",
    '"ES " + esc + "% (mean of worst "', '"Reporting currency"', '"Input"', '"By source system"', '"Intraday"', '"Scenario FX"',
    '"Cross rates"', '"Resolved"', '"Positions"', '"Trades"', '"Outside your view"', '"Unknown ids"', '"Warnings"', '"Rows left out"',
    '"Vectors"', '"Scenario set"', '"Method"', '["ES", p.es]', '"Components"', '"Positions by"', '"Engine"', '"View"', '"Members"',
    '"Computed"', "doCapture", "csvOfPath", "jsonlOfPath", "B.openWhatIf()", "DrishtiScopeDelete.ask", "B.edit(scope)",
    '"Edit a copy"', "addButton(", "b.owner === S.user || S.admin", "switchTab", "setCcy", "setLens", "/help/context/var-explain",
]


def _page(client, path="/var?scope=BANK"):
    r = client.get(path)
    assert r.status_code == 200
    return r.text


def test_every_part_of_the_var_screen_is_still_served(client):
    html = _page(client)
    missing = [what for what, needle in PAGE_INVENTORY if needle not in html]
    assert not missing, "gone from the VaR page: " + ", ".join(missing)


def test_the_six_tabs_stay_in_their_order(client):
    html = _page(client)
    tabs = re.findall(r'class="vx-tab" role="tab" data-tab="(\w+)"', html)
    assert tabs == ["path", "hist", "contrib", "captures", "ccy", "why"]


def test_the_scripts_and_styles_stay(client):
    html = _page(client)
    at = [html.index(s) for s in SCRIPTS]
    assert at == sorted(at), "the scripts' order changed"
    assert "/static/css/var.css" in html and "/static/css/var-basket.css" in html


def test_the_explorer_still_draws_every_tile_es_figure_and_explanation_line(client):
    js = client.get("/static/js/var-explorer.js").text
    missing = [needle for needle in EXPLORER_INVENTORY if needle not in js]
    assert not missing, "gone from var-explorer.js: " + ", ".join(missing)
    explain = client.get("/static/js/var-explain.js").text
    assert "drishtiVarExplain" in explain


def test_the_new_layout_baskets_in_two_drop_downs_and_the_drawer(client):
    """The two changes themselves: the drop-downs (combobox, listbox, keys B and M), + New basket, How calculated (H) and its drawer."""
    html = _page(client)
    assert "data-vx-adhoc" not in html and "vx-sec\">Ad-hoc scopes" not in html        # the baskets left the column
    for pick, key, label in (("system", "B", "System baskets"), ("mine", "M", "My baskets")):
        m = re.search(r'<div class="vx-pick" data-vx-pick="%s".*?</div>\s*</div>' % pick, html, re.S)
        assert m, pick
        block = m.group(0)
        assert 'aria-haspopup="listbox"' in block and f'aria-keyshortcuts="{key}"' in block and label in block
        assert 'role="combobox"' in block and 'role="listbox"' in block and 'aria-autocomplete="list"' in block
    assert "data-vx-basket-new" in html                                               # + New basket at the bottom of My baskets
    assert re.search(r'data-vx-basket-open[^>]*>\s*\+ New basket', html)              # and in the toolbar
    assert 'data-vx-how ' in html and 'aria-keyshortcuts="H"' in html and 'aria-controls="vxHow"' in html
    drawer = re.search(r'<aside class="vx-panel vx-prov vx-drawer[^"]*"[^>]*>', html).group(0)
    assert 'role="dialog"' in drawer and "hidden" in drawer and 'aria-labelledby="vxHowH"' in drawer
    assert "data-vx-how-close" in html
    # the drawer's state is in the link: ?how=1 serves it open
    opened = _page(client, "/var?scope=BANK&how=1")
    drawer = re.search(r'<aside class="vx-panel vx-prov vx-drawer[^"]*"[^>]*>', opened).group(0)
    assert " hidden" not in drawer and "vx-open" in drawer
    assert re.search(r'data-vx-how [^>]*aria-expanded="true"', opened)
    js = client.get("/static/js/var-layout.js").text
    assert "VarLayout" in js and "aria-activedescendant" in js and '"how"' in js


# ------------------------------------------------------------------------------------------------------------------ the browser
ES, VAR = 171920410.0, 158495340.0
PROV = {"scenarioSet": "SS-509 · 509 scenarios", "vectors": "batch B-7 · data version 42", "method": "historical, 99% (6th worst of 509)",
        "es": "mean of the 13 worst, 97.5%", "components": "Euler at the VaR scenario", "engine": "drishti-var 2.9.11", "view": "MGMT",
        "membershipHash": "abc123", "computedAt": "2026-10-28T08:00:00", "computeMs": 12, "cache": "miss", "positions": 40, "trades": 120,
        "scope": "BANK", "resolved": ["NODE:MGMT~BANK"], "notices": [], "warnings": [], "inputs": {}}


class LayoutBackend(FakeBackend):
    """A small stand-in engine: the bank, two desks, one portfolio, two saved baskets (one mine, one shared with me)."""

    def __init__(self):
        super().__init__()
        self.kept = {"ash/unwind": {"id": "ash/unwind", "name": "Unwind candidates", "owner": "ash", "version": 1, "sharedWith": [],
                                    "description": "positions to unwind this week", "type": "BASKET", "scope": "TRD:A", "fromSheet": True},
                     "rita/hedges": {"id": "rita/hedges", "name": "Hedges", "owner": "rita", "version": 1, "sharedWith": ["ash"],
                                     "description": "the euro hedges", "type": "BASKET", "scope": "TRD:B", "fromSheet": True}}

    def head(self, scope):
        kind = "portfolio" if scope.startswith("PORT:") else "basket" if scope.startswith("BASKET:") else "node"
        return {"scope": scope, "canonical": scope, "kind": kind, "label": scope, "node": "MGMT~BANK", "businessDate": "2026-10-28",
                "var": VAR, "es": ES, "k": 6, "esCount": 13, "scenarios": 509, "confidence": 0.99, "esConfidence": 0.975,
                "sumStandalone": 228200000.0, "diversification": 69704660.0, "children": 2, "childKind": "desk", "limit": 200000000.0,
                "limitUse": 0.792, "backtest": {"days": 250, "exceptions": 2, "light": "green"}, "currency": "USD", "empty": False,
                "trades": 120, "terms": [], "ancestors": [], "lens": {"horizon": "1D", "method": "", "label": "1-day", "confidence": 0.99,
                                                                     "esConfidence": 0.975, "set": ""},
                "provenance": dict(PROV, scope=scope)}

    async def var(self, method, path, ident, /, body=None, raw=False, accept="", **params):
        from core.backend import BackendError
        if path == "/scope":
            return self.head(params.get("scope", "BANK"))
        if path == "/tree":
            nodes = [{"id": "BANK", "scope": "NODE:MGMT~BANK", "name": "BANK", "depth": 0, "var": VAR, "es": ES, "limitUse": 0.79, "expandable": True,
                      "level": "bank"},
                     {"id": "D1", "parent": "BANK", "scope": "NODE:MGMT~DESK-RATES", "name": "Rates", "depth": 1, "var": 72400000.0, "es": 78554000.0,
                      "limitUse": 0.5, "expandable": False, "level": "desk"},
                     {"id": "D2", "parent": "BANK", "scope": "NODE:MGMT~DESK-FX", "name": "FX", "depth": 1, "var": 38600000.0, "es": 41881000.0,
                      "limitUse": 0.4, "expandable": False, "level": "desk"}]
            return {"nodes": nodes, "businessDate": "2026-10-28", "hierarchies": ["MGMT"],
                    "portfolios": [{"id": "PF-USD-RATES", "name": "USD rates", "type": "BASKET", "scope": "PORT:PF-USD-RATES", "owner": "rates-risk"},
                                   {"id": "PF-EM-SOV", "name": "EM sovereign exposure", "type": "RULE", "scope": "PORT:PF-EM-SOV", "owner": "risk"}]}
        if path == "/baskets" and method == "GET":
            return list(self.kept.values())
        if path == "/baskets" and method == "POST":
            bid = "ash/" + body["name"]
            b = {"id": bid, "name": body.get("title", body["name"]), "owner": "ash", "version": 1, "sharedWith": [], "type": "BASKET",
                 "scope": "TRD:C", "fromSheet": True, "description": ""}
            self.kept[bid] = b
            return b
        if path == "/basket/sheet" and method == "POST":
            return {"businessDate": "2026-10-28", "currency": "USD", "wholeBank": False, "columns": [{"key": "TRD", "title": "Trade"}],
                    "found": 1, "notFound": 0, "flagged": 0, "cells": [], "scope": "TRD:C", "empty": False, "trades": 1, "var": 1.0, "es": 1.2,
                    "sumOfParts": 1.0, "diversification": 0.0, "partsAdded": 1, "confidence": 0.99, "esConfidence": 0.975, "computeMs": 1}
        raise BackendError(404, "DRS-1003", "nothing here in this test")


@pytest.fixture(scope="module")
def served():
    pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
    import uvicorn

    from core.app import create_app
    from core.config import load_settings
    from test_live_tabs_browser import _free_port

    app = create_app(load_settings(CONSOLE / "config"))
    be = LayoutBackend()
    app.state.backend = be
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}", be
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


def _wait(pg, js, seconds=15):
    from test_workbench_browser import wait            # polls (the console's CSP forbids the string Playwright's own wait evaluates)
    wait(pg, js, seconds)


def _shot(pg, name):
    """A screenshot of the page when DRISHTI_VAR_SHOTS names a folder (as the explorer's browser test)."""
    folder = os.environ.get("DRISHTI_VAR_SHOTS")
    if folder:
        Path(folder).mkdir(parents=True, exist_ok=True)
        pg.screenshot(path=str(Path(folder) / f"{name}.png"))


def _open(browser, url, width=1500, theme="terminal"):
    ctx = browser.new_context(viewport={"width": width, "height": 844 if width < 640 else 1000}, is_mobile=width < 640, has_touch=width < 640)
    ctx.add_init_script(f"try {{ localStorage.setItem('drishti.theme', '{theme}'); localStorage.removeItem('drishti.varBasket2'); }} catch (e) {{}}")
    pg = ctx.new_page()
    pg.goto(url)
    _wait(pg, "document.querySelector('[data-var]') && document.querySelector('[data-var]').dataset.painted")
    return ctx, pg


@pytest.mark.parametrize("theme", ("terminal", "light"))
def test_the_drawn_screen_keeps_every_figure_and_the_explanation_moves_into_the_drawer(served, browser, theme):
    url, _ = served
    ctx, pg = _open(browser, url + "/var?scope=BANK", theme=theme)
    try:
        # the six tiles, ES the second, with the server's figure
        assert pg.locator(".vx-tile").count() == 6
        es_tile = pg.locator('.vx-tile[data-tile="1"]')
        assert es_tile.locator(".l").inner_text().startswith("ES 97.5%")
        assert es_tile.locator(".v").inner_text() == pg.evaluate(f"window.VarCharts.money({ES})")
        assert "mean of worst 13" in es_tile.inner_text()
        assert pg.locator('.vx-tile[data-tile="0"] .l').inner_text().startswith("VaR 99%")
        # the tree stays on the left, with its VaR and ES columns; a click on a node moves the page
        hdr = pg.locator(".vx-tree .vx-row.hdr").first.inner_text()
        assert "VaR USD" in hdr and "ES" in hdr and "Limit" in hdr
        assert pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DESK-FX"]').is_visible()
        tree_box = pg.locator(".vx-tree-panel").bounding_box()
        main_box = pg.locator(".vx-main").bounding_box()
        assert tree_box["x"] < main_box["x"]
        pg.locator('.vx-tree .vx-row[data-scope="NODE:MGMT~DESK-FX"]').click()
        _wait(pg, "document.querySelector('[data-var]').dataset.painted === 'NODE:MGMT~DESK-FX'")
        # the six tabs, each with its pane
        for tab in ("path", "hist", "contrib", "captures", "ccy", "why"):
            pg.locator(f'.vx-tab[data-tab="{tab}"]').click()
            assert pg.locator(f'[data-pane="{tab}"]').is_visible(), tab
        pg.locator('.vx-tab[data-tab="path"]').click()
        # no right-hand column: the scope's panel takes the width; the drawer is closed
        assert not pg.locator("[data-vx-drawer]").is_visible()
        assert main_box["width"] > 1000
        # How calculated (H): the drawer, the server's lines, Esc closes, the focus goes back, the link says so
        pg.locator("body").click(position={"x": 5, "y": 5})
        pg.keyboard.press("h")
        _wait(pg, "!document.querySelector('[data-vx-drawer]').hidden")
        drawer = pg.locator("[data-vx-drawer]")
        assert drawer.is_visible() and "how=1" in pg.url
        text = drawer.inner_text()
        for line in ("Scope", "Reporting currency", "Scenario set", "Method", "ES", "Components", "Engine", "Computed"):
            assert line in text, line
        assert PROV["es"] in text and PROV["method"] in text and PROV["engine"] in text   # the server's text, as sent
        assert pg.evaluate("document.activeElement.closest('[data-vx-drawer]') !== null")
        for action in ("[data-vx-csv]", "[data-vx-capture-side]", "[data-vx-whatif]"):
            assert pg.locator(action).is_visible(), action
        assert pg.get_attribute("[data-vx-how]", "aria-expanded") == "true"
        box = drawer.bounding_box()
        assert box["x"] + box["width"] >= 1499 and box["width"] < 700                  # over the right of the screen
        pg.wait_for_timeout(300)
        _shot(pg, f"var-layout-drawer-{theme}")
        pg.keyboard.press("Escape")
        _wait(pg, "document.querySelector('[data-vx-drawer]').hidden")
        assert "how=1" not in pg.url and pg.evaluate("document.activeElement.hasAttribute('data-vx-how')")
        # a link opens it
        pg.goto(url + "/var?scope=BANK&how=1")
        _wait(pg, "document.querySelector('[data-var]').dataset.painted === 'BANK'")
        assert pg.locator("[data-vx-drawer]").is_visible() and "6th worst of 509" in pg.locator("[data-vx-drawer]").inner_text()
        pg.locator("[data-vx-how-close]").click()
        assert not pg.locator("[data-vx-drawer]").is_visible()
    finally:
        ctx.close()


def test_the_basket_drop_downs_search_keys_actions_and_new_basket(served, browser):
    url, be = served
    ctx, pg = _open(browser, url + "/var?scope=BANK")
    try:
        _wait(pg, "document.querySelectorAll('[data-vx-baskets-mine] [role=option]').length === 2")
        assert pg.locator("[data-vx-basket]").is_hidden()                                 # the sheet is collapsed on load
        # B: System baskets, the bank's portfolios; type to search (name or description), arrows and Enter choose
        pg.locator("body").click(position={"x": 5, "y": 5})
        pg.keyboard.press("b")
        q = pg.locator('[data-vx-pick="system"] [data-vx-pick-q]')
        _wait(pg, "document.activeElement && document.activeElement.matches('[data-vx-pick=\"system\"] [data-vx-pick-q]')")
        assert q.get_attribute("aria-expanded") == "true"
        assert pg.locator("[data-vx-baskets-system] [role=option]").count() == 2
        q.type("sover")
        _wait(pg, "document.querySelectorAll('[data-vx-baskets-system] [role=option]:not([hidden])').length === 1")
        _shot(pg, "var-layout-system-baskets")
        pg.keyboard.press("ArrowDown")
        active = q.get_attribute("aria-activedescendant")
        assert active and pg.locator(f"#{active}").get_attribute("data-scope") == "PORT:PF-EM-SOV"
        pg.keyboard.press("Enter")
        _wait(pg, "document.querySelector('[data-var]').dataset.painted === 'PORT:PF-EM-SOV'")
        assert pg.locator('[data-vx-pick="system"] [data-vx-pick-cur]').inner_text() == "EM sovereign exposure"
        assert pg.locator('[data-vx-pick="system"] [data-vx-pick-pop]').is_hidden()
        # Esc closes without choosing
        pg.keyboard.press("b")
        pg.locator('[data-vx-pick="system"] [data-vx-pick-q]').wait_for(state="visible")
        pg.keyboard.press("Escape")
        assert pg.locator('[data-vx-pick="system"] [data-vx-pick-pop]').is_hidden()
        # the portfolio keeps Edit a copy and + basket
        pg.keyboard.press("b")
        assert pg.locator('[data-vx-baskets-system] [data-vx-edit="PORT:PF-USD-RATES"]').inner_text() == "Edit a copy"
        assert pg.locator('[data-vx-baskets-system] [data-vx-add="PORT:PF-USD-RATES"]').count() == 1
        pg.keyboard.press("Escape")
        # M: My baskets, mine and shared with me; search by description; Edit and Delete as before
        pg.locator("body").click(position={"x": 5, "y": 5})
        pg.keyboard.press("m")
        mq = pg.locator('[data-vx-pick="mine"] [data-vx-pick-q]')
        mq.wait_for(state="visible")
        mine = pg.locator("[data-vx-baskets-mine]").text_content()
        assert "Unwind candidates" in mine and "Hedges" in mine and "Shared with me" in mine
        assert pg.locator('[data-vx-baskets-mine] [data-vx-edit="BASKET:ash/unwind"]').inner_text() == "Edit"
        assert pg.locator('[data-vx-baskets-mine] [data-vx-delete="ash/unwind"]').count() == 1
        mq.type("euro hedge")
        _wait(pg, "document.querySelectorAll('[data-vx-baskets-mine] [role=option]:not([hidden])').length === 1")
        pg.keyboard.press("Enter")
        _wait(pg, "document.querySelector('[data-var]').dataset.painted === 'BASKET:rita/hedges'")
        # + New basket (the foot of My baskets): the sheet opens; Save puts the basket in My baskets and collapses the sheet
        pg.keyboard.press("m")
        pg.locator("[data-vx-basket-new]").click()
        _wait(pg, "!document.querySelector('[data-vx-basket]').hidden")
        pg.evaluate("window.VarBasket.add('TRD:C', {name: 'C'})")
        pg.locator("[data-vb-name]").fill("fresh")
        pg.locator("[data-vb-save]").click()
        _wait(pg, "document.querySelector('[data-vx-basket]').hidden")
        _wait(pg, "!!document.querySelector('[data-vx-baskets-mine] [data-scope=\"BASKET:ash/fresh\"]')")
        # the toolbar's + New basket and Alt+B open it again
        pg.locator("[data-vx-basket-open]").click()
        _wait(pg, "!document.querySelector('[data-vx-basket]').hidden")
    finally:
        ctx.close()


@pytest.mark.parametrize("theme", ("terminal", "light"))
def test_on_a_phone_the_drop_downs_are_full_width_and_the_drawer_a_bottom_sheet(served, browser, theme):
    url, _ = served
    ctx, pg = _open(browser, url + "/var?scope=BANK", width=390, theme=theme)
    try:
        audit = "document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"
        assert pg.evaluate(audit)
        bar = pg.locator(".vx-bar").bounding_box()
        for pick in ("system", "mine"):
            b = pg.locator(f'[data-vx-pick="{pick}"] [data-vx-pick-btn]').bounding_box()
            assert b["width"] >= bar["width"] - 40, pick
        pg.locator('[data-vx-pick="system"] [data-vx-pick-btn]').click()
        drop = pg.locator('[data-vx-pick="system"] [data-vx-pick-pop]').bounding_box()
        assert drop["x"] >= 0 and drop["x"] + drop["width"] <= 391
        assert pg.evaluate(audit)
        _shot(pg, f"var-layout-phone-{theme}")
        pg.keyboard.press("Escape")
        pg.locator("[data-vx-how]").click()
        _wait(pg, "!document.querySelector('[data-vx-drawer]').hidden")
        pg.wait_for_timeout(400)                                                          # the sheet has risen
        d = pg.locator("[data-vx-drawer]").bounding_box()
        assert d["width"] >= 389 and d["y"] > 100 and abs(d["y"] + d["height"] - 844) <= 2      # the bottom sheet
        assert pg.evaluate(audit)
        _shot(pg, f"var-layout-phone-drawer-{theme}")
    finally:
        ctx.close()
