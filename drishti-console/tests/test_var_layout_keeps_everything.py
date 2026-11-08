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
(terminal/var.html, drishti_var-explorer.js, drishti_var-explain.js): every tile, panel, chart, table, tab, control, key, every expected-shortfall
figure and every line of the explanation. The server-rendered page and the scripts are checked here; the drawn page in Chromium
(a stand-in engine, the console in-process) at the end of the file. Skipped without Playwright or Chromium (the browser part only).

2.9.11.e folded the drawer and the Why it moved tab into the one Explain (SHARED_SCREENS.md section 3, drishti-insight): every line
of How this number was built is now the server's (VarNumberExplainer.java, checked below row for row), the tab is the drawer's Why it
moved, and Explain › (E) opens it; the drawer's What-if moved to the toolbar."""
import os
import re
import threading
import time
from pathlib import Path

import pytest

from conftest import CONSOLE, FakeBackend
import engine_fakes  # noqa: E402

# ------------------------------------------------------------------------------------------------------------------ the inventory
#: the page as served (terminal/var.html): (what it is, the text that must be in the page)
PAGE_INVENTORY = [
    # the status strip
    ("the command echo", "data-vx-cmd"), ("the business date", "data-vx-date"), ("the scenario set", "data-vx-set"),
    ("the data version", "data-vx-version"), ("the intraday badge", "data-vx-intraday"), ("the paint timing", "data-vx-timing"),
    ("F1 help: the VaR guide", 'href="/help/context/var"'), ("the reporting currency", ' label="Reporting currency"'),
    ("Normal or stressed", "data-vx-scenarios"), ("the horizon", "data-vx-horizon"), ("the VaR confidence", "data-vx-conf"),
    ("the ES confidence", "data-vx-esconf"), ("the ES confidence's label", ' label="ES confidence"'),
    # notices and the basket's sheet (collapsed until asked)
    ("the notice", "data-vx-notice"), ("the notices", "data-vx-notices"), ("the basket's sheet", "data-vx-basket hidden"),
    ("the sheet's opener (Alt+B)", 'aria-keyshortcuts="Alt+B"'), ("the basket's count", "data-basket-count"),
    # the hierarchy tree (stays on the left)
    ("the scopes panel", 'aria-label="Scopes"'), ("the hierarchy select", "data-vx-hierarchy"), ("the tree's freshness", 'data-vx-fresh="tree"'),
    ("the tree", 'data-vx-tree aria-label="Scope tree"'),
    # the scope
    ("the breadcrumb", "data-vx-crumb"), ("the tiles", "data-vx-tiles"), ("the method strip", "data-vx-method"),
    ("the tab list", "data-vx-tabs"), ("the tabs' freshness", 'data-vx-fresh="main"'),
    ("tab: P&L path", 'data-tab="path"'), ("its scenario count", "data-vx-n"), ("tab: VaR / ES history", 'data-tab="hist"'),
    ("tab: Contributions", 'data-tab="contrib"'), ("tab: Captured results", 'data-tab="captures"'),
    ("tab: In other currencies", 'data-tab="ccy"'),
    # P&L path
    ("the histogram", "data-vx-histo"), ("the VaR legend", "data-vx-var-legend"), ("the ES legend", "data-vx-es-legend"),
    ("the tail scenarios legend", "tail scenarios"), ("the worst 25", "data-vx-tail"), ("Worst 25 scenarios, sorted", "Worst 25 scenarios, sorted"),
    ("the P&L by scenario date", "data-vx-series"), ("its title", "data-vx-series-title"), ("the drill", "data-vx-drill"),
    ("the path table", "data-vx-pathtable"), ("its Largest loss from", 'header: "Largest loss from"'), ("Next page", "data-vx-more"),
    ("the path note", "data-vx-path-note"), ("Download the path (CSV)", "data-vx-csv-path"), ("the path as JSONL", "data-vx-jsonl-path"),
    # VaR / ES history
    ("the history chart", "data-vx-hline"), ("its −VaR", "−VaR"), ("its −ES", "−ES"), ("its hypothetical P&L", "hypothetical P&amp;L"),
    ("its backtest exceptions", "backtest exception"), ("the history note", "data-vx-hist-note"), ("the history table", "data-vx-histtable"),
    ("the history table's ES", 'R.figureColumn("var", "VaR", { format: money }), R.figureColumn("es", "ES", { format: money }),'),
    # contributions
    ("Children", 'data-by="child"'), ("By position", 'data-by="position"'), ("By trade", 'data-by="trade"'),
    ("rows shown", "data-vx-ctable-shown"), ("the contributions table", "data-vx-ctable"), ("Standalone VaR", "Standalone VaR"),
    ("Component VaR", "Component VaR"), ("Marginal (per 1m)", "Marginal (per 1m)"), ("Incremental VaR", "Incremental VaR"),
    ("the contributions note", "data-vx-ctable-note"),
    # captures
    ("the capture note", "data-vx-cap-note"), ("Capture VaR", "data-vx-capture>"), ("the capture message", "data-vx-cap-msg"),
    ("the captures table", "data-vx-captable"), ("the captures table's ES", 'R.figureColumn("es", "ES", { format: function (v, c) { return int(v) + lensOf(c); } }),'),
    ("the compare", "data-vx-compare"),
    # other currencies
    ("the currencies note", "data-vx-ccy-note"), ("the currencies table", "data-vx-ccytable"),
    ("the currencies table's ES", '"VaR in " + d.currency + " at today\'s rate"'),
    # the one Explain (the drawer: How this number was built and Why it moved), What-if in the toolbar
    ("Explain › (E)", "data-vx-explain data-explain-primary"), ("its key", 'aria-keyshortcuts="E"'), ("What-if…", "data-vx-whatif"),
]

#: the scripts the page loads, in order
SCRIPTS = ["drishti_insight.js", "drishti_risk.js", "Arrow.es2015.min.js", "drishti_table.js", "drishti_var-basket-sheet.js", "drishti_var-basket.js", "drishti_scope-delete.js",
           "drishti_var-explorer.js"]

#: what the scripts draw (drishti_var-explorer.js): the six tiles, the tree's columns, the actions, the Explain requests
EXPLORER_INVENTORY = [
    '(stressed ? "Stressed VaR " : "VaR ")', '(stressed ? "Stressed ES " : "ES ")', "money(d.es)", '"mean of worst " + d.esCount',
    '"1-day ES × √10"', '"Sum of standalone VaR"', '"Diversification"', '"Limit utilisation"', '"Backtest exceptions ("',
    '"-week average"', 'header: "Scope"', '"VaR " + S.ccy', 'R.figureColumn("es", "ES"', 'label: "Limit"', "n.es", "r.standaloneEs",
    '"ES " + esc + "% (mean of worst "', "doCapture", "csvOfPath", "jsonlOfPath", "B.openWhatIf()", "Drishti.ScopeDelete.ask", "B.edit(scope)",
    '"Edit a copy"', "addButton(", "b.owner === S.user || S.admin", "switchTab", "setCcy", "setLens", "bindExplain", "explainReq",
]

#: every line of How this number was built, now the server's Inputs & lineage (VarNumberExplainer.java), as the box labelled them
EXPLAINER = Path(__file__).resolve().parents[2] / "drishti-banking/src/main/java/com/ash/drishti/banking/var/VarNumberExplainer.java"
EXPLAINER_INVENTORY = ['"Scope"', '"Reporting currency"', '"Input"', '"By source system"', '"Intraday"', '"Scenario FX"', '"Cross rates"',
                       '"Resolved"', '"Positions"', '"Trades"', '"Outside your view"', '"Unknown ids"', '"Warnings"', '"Rows left out"',
                       '"Vectors"', '"Scenario set"', '"Method"', '"ES"', '"Components"', '"Positions by"', '"Engine"', '"View"', '"Members"',
                       '"Computed"', '" trades not included"', '"none: the morning run"', '", chosen in the header"']


def _page(client, path="/var?scope=BANK"):
    r = client.get(path)
    assert r.status_code == 200
    return r.text


def test_every_part_of_the_var_screen_is_still_served(client):
    html = _page(client)
    js = client.get("/static/js/drishti_var-explorer.js").text                 # the tables' headings are the grids' columns (drishti-grid)
    missing = [what for what, needle in PAGE_INVENTORY if needle not in html and needle not in js]
    assert not missing, "gone from the VaR page: " + ", ".join(missing)


def test_the_five_tabs_stay_in_their_order(client):
    html = _page(client)
    tabs = re.findall(r'class="vx-tab" role="tab" data-tab="(\w+)"', html)
    assert tabs == ["path", "hist", "contrib", "captures", "ccy"]          # Why it moved is the Explain drawer's tab (2.9.11.e)


def test_the_scripts_and_styles_stay(client):
    html = _page(client)
    at = [html.index(s) for s in SCRIPTS]
    assert at == sorted(at), "the scripts' order changed"
    assert "/static/css/drishti_var.css" in html and "/static/css/drishti_var-basket.css" in html


def test_the_explorer_still_draws_every_tile_es_figure_and_explanation_line(client):
    js = client.get("/static/js/drishti_var-explorer.js").text
    missing = [needle for needle in EXPLORER_INVENTORY if needle not in js]
    assert not missing, "gone from drishti_var-explorer.js: " + ", ".join(missing)
    java = EXPLAINER.read_text(encoding="utf-8")
    missing = [needle for needle in EXPLAINER_INVENTORY if needle not in java]
    assert not missing, "gone from the explanation (VarNumberExplainer.java): " + ", ".join(missing)


def test_the_new_layout_baskets_in_two_drop_downs_and_the_drawer(client):
    """The two changes themselves: the drop-downs (combobox, listbox, keys B and M), + New basket; and Explain › (E), the one Explain's."""
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
    assert "data-vx-how-close" not in html and "data-vx-drawer" not in html and "how=1" not in html
    assert re.search(r'data-vx-explain data-explain-primary aria-keyshortcuts="E"', html)
    js = client.get("/static/js/drishti_var-layout.js").text
    assert "VarLayout" in js and "aria-activedescendant" in js and "openHow" not in js


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

    engine = engine_fakes.method

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

    async def explain_number(self, method, path, ident, body=None, **params):
        """The one Explain, as the server answers it (shapes of VarNumberExplainer), from this engine's own figures."""
        self.explained = getattr(self, "explained", []) + [dict(params)]
        if path == "/kinds":
            return {"kinds": [{"kind": "var", "title": "VaR", "measures": ["var", "es"]}], "underConstruction": []}
        h, m, part = self.head(params.get("scope", "BANK")), params.get("measure", "var"), params.get("part", "all")
        value = VAR if m == "var" else ES
        out = {"kind": "var", "measure": m, "subject": h["canonical"], "title": ("VaR 99%" if m == "var" else "ES 97.5%") + ", 1 day, " + h["label"],
               "stored": False, "value": value, "text": f"{value:,.0f}", "unit": "USD", "asOf": "2026-10-28",
               "origin": "computed by the VaR engine in the server for you (" + PROV["engine"] + ")", "part": part,
               "preview": "the 6th worst of 509 scenario P&Ls (2026-03-17)" if m == "var" else "the mean of the worst 13 of 509 scenario P&Ls",
               "facts": ["40 positions"]}
        if part in ("all", "formula"):
            out["formula"] = {"lines": ["VaR = −(k-th worst of 509 scenario P&Ls)", "k = 6   (" + PROV["method"] + ")", f"VaR = {VAR:,.0f} USD"],
                              "rachana": "var()"}
        if part in ("all", "inputs"):
            out["inputs"] = {"rows": [{"label": k, "value": v} for k, v in (("Scope", "node " + h["label"]), ("Reporting currency", "USD, chosen in the header"),
                                                                           ("Scenario set", PROV["scenarioSet"]), ("Method", PROV["method"]), ("ES", PROV["es"]),
                                                                           ("Components", PROV["components"]), ("Engine", PROV["engine"]),
                                                                           ("Computed", "2026-10-28 08:00:00 · 12 ms · cache miss"))],
                             "lineage": [{"source": "pnl-vector", "read": "business date 2026-10-28", "version": "42"}]}
        if part in ("all", "why"):
            view = params.get("view") or "waterfall"
            out["why"] = {"available": True, "from": params.get("from") or "2026-10-27", "to": params.get("to") or "2026-10-28", "change": 1000.0,
                          "addsUp": True, "views": ["waterfall", "matrix", "sentences"], "view": view,
                          "panels": [{"id": "answer", "kind": "markdown", "title": "The answer", "area": "main", "span": 12, "inferred": False,
                                      "empty": False, "data": {"text": "VaR rose by 1,000 (" + view + ")."}}]}
        if part in ("all", "workbook"):
            out["workbook"] = {"available": True, "links": [
                {"label": "Why it moved: every state and step as an Excel formula (.xlsx)", "href": "/api/var/explain.xlsx?scope=" + h["canonical"], "note": "checked"},
                {"label": "Worked in the playground: the grid and its formula workbook", "href": "/play?scope=" + h["canonical"], "note": ""}]}
        if part != "preview":
            out["checks"] = [{"ok": True, "text": "inputs complete (120 of 120 trades with a vector)"}]
            out["links"] = [{"label": "Guide: VaR", "href": "/help/context/var"}]
            out["underConstruction"] = [{"id": "explain-official-check", "text": "Under construction", "about": "the official check", "release": "2.9.11.j"}]
        return out


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
def test_the_drawn_screen_keeps_every_figure_and_the_explanation_is_the_one_explain(served, browser, theme):
    url, _ = served
    ctx, pg = _open(browser, url + "/var?scope=BANK", theme=theme)
    try:
        # the six tiles, ES the second, with the server's figure
        assert pg.locator(".vx-tile").count() == 6
        es_tile = pg.locator('.vx-tile[data-tile="1"]')
        assert es_tile.locator(".l").inner_text().startswith("ES 97.5%")
        assert es_tile.locator(".v").inner_text() == pg.evaluate(f"window.Drishti.elements.risk.riskMoney({ES})")
        assert "mean of worst 13" in es_tile.inner_text()
        assert pg.locator('.vx-tile[data-tile="0"] .l').inner_text().startswith("VaR 99%")
        # the tree stays on the left, with its VaR and ES columns; a click on a node moves the page
        hdr = pg.locator(".vx-tree .dg-hrow.dg-hleaf").first.inner_text()
        assert "VaR USD" in hdr and "ES" in hdr and "Limit" in hdr
        pg.locator('.vx-tree .dg-row[data-scope="NODE:MGMT~DESK-FX"]').wait_for(state="visible")    # the tree opens to the desks once its rows arrive
        tree_box = pg.locator(".vx-tree-panel").bounding_box()
        main_box = pg.locator(".vx-main").bounding_box()
        assert tree_box["x"] < main_box["x"]
        # the tree fills the height beside the scope: no empty band down the left (its bottom at the window's, or at the scope's)
        for width in (1500, 1000):
            pg.set_viewport_size({"width": width, "height": 1000})
            pg.wait_for_timeout(200)
            tb, mb = pg.locator(".vx-tree-panel").bounding_box(), pg.locator(".vx-main").bounding_box()
            bottom, vh = tb["y"] + tb["height"], pg.evaluate("innerHeight")
            assert abs(bottom - (vh - 10)) <= 4 or abs(bottom - (mb["y"] + mb["height"])) <= 4, (width, tb, mb, vh)
        pg.set_viewport_size({"width": 1500, "height": 1000})
        pg.locator('.vx-tree .dg-row[data-scope="NODE:MGMT~DESK-FX"]').click()
        _wait(pg, "document.querySelector('[data-var]').dataset.painted === 'NODE:MGMT~DESK-FX'")
        # the five tabs, each with its pane
        for tab in ("path", "hist", "contrib", "captures", "ccy"):
            pg.locator(f'.vx-tab[data-tab="{tab}"]').click()
            assert pg.locator(f'[data-pane="{tab}"]').is_visible(), tab
        pg.locator('.vx-tab[data-tab="path"]').click()
        # no right-hand column: the scope's panel takes the width; no Explain drawer until asked
        assert not pg.locator("[data-explain-drawer]").is_visible()
        assert main_box["width"] > 1000
        # Explain (E): the one drawer, the server's lines, Esc closes, the focus goes back
        pg.locator("body").click(position={"x": 5, "y": 5})
        pg.keyboard.press("e")
        _wait(pg, "document.querySelector('[data-explain-drawer]') && document.querySelector('[data-explain-drawer]').dataset.state === 'ready'")
        drawer = pg.locator("[data-explain-drawer]")
        assert drawer.is_visible() and "k = 6" in drawer.inner_text() and PROV["method"] in drawer.inner_text()
        pg.locator('[data-dx-tab="inputs"]').click()
        _wait(pg, "document.querySelector('[data-explain-drawer]').dataset.tab === 'inputs'")
        text = drawer.inner_text()
        for line in ("Scope", "Reporting currency", "Scenario set", "Method", "ES", "Components", "Engine", "Computed"):
            assert line in text, line
        assert PROV["es"] in text and PROV["method"] in text and PROV["engine"] in text   # the server's text, as sent
        assert pg.locator("[data-vx-whatif]").is_visible()
        box = drawer.bounding_box()
        assert box["x"] + box["width"] >= 1499 and box["width"] < 700                  # over the right of the screen
        # below the console's top bar: the bar (command line, date, its icons) stays visible and clickable
        head = pg.locator("header.tbar").bounding_box()
        assert abs(box["y"] - (head["y"] + head["height"])) <= 2, (box, head)
        pg.locator("#cmdInput").click()                                             # the command line takes a click
        assert pg.evaluate("document.activeElement.id") == "cmdInput" and drawer.is_visible()
        _shot(pg, f"var-layout-explain-{theme}")
        pg.locator("[data-dx-close]").focus()
        pg.keyboard.press("Escape")
        _wait(pg, "document.querySelector('[data-explain-drawer]').hidden")
        # a tile opens it at its own figure: ES
        pg.locator('.vx-tile[data-tile="1"] .v').click()
        _wait(pg, "document.querySelector('[data-explain-drawer]').dataset.state === 'ready'")
        assert "ES 97.5%" in drawer.inner_text()
        pg.locator("[data-dx-close]").click()
        assert not drawer.is_visible()
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
        pg.evaluate("window.Drishti.VarBasket.add('TRD:C', {name: 'C'})")
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
def test_on_a_phone_the_drop_downs_are_full_width_and_explain_a_bottom_sheet(served, browser, theme):
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
        pg.locator("[data-vx-explain]").click()
        _wait(pg, "document.querySelector('[data-explain-drawer]') && document.querySelector('[data-explain-drawer]').dataset.state === 'ready'")
        pg.wait_for_timeout(400)                                                          # the sheet has risen
        d = pg.locator("[data-explain-drawer]").bounding_box()
        assert d["width"] >= 389 and d["y"] > 100 and abs(d["y"] + d["height"] - 844) <= 2      # the bottom sheet
        assert pg.evaluate(audit)
        _shot(pg, f"var-layout-phone-drawer-{theme}")
    finally:
        ctx.close()
