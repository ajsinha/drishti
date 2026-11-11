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

"""The VaR explorer in another reporting currency, in a real browser (docs/guides/VAR_GUIDE.md section 15.11; the product owner's mock-up
A with C's table as a tab), over the market-risk pack's committed VaR data (the Endur trades as scenario PVs in their own currency) built
into a scratch lake, the demo connector off:

- the header's Reporting currency lists the configured currencies and More…; choosing EUR moves every panel to EUR (the tiles say it,
  the command says ``IN EUR``, the link carries ``ccy=EUR``, the provenance names the reporting currency, the input form, the scenario FX
  and the cross rates via USD); the choice stays for the session (another VaR page in the tab opens in EUR);
- the tab "In other currencies" is loaded only when opened (no request before), one row per configured currency, the shown one marked;
  a click on a row switches the page to that currency;
- ``VAR DESK DESK-RATES IN EUR`` opens the page in EUR;
- at phone width the picker and the tab fit without scrolling sideways;
- a trade page's strip says its Trade currency (an Endur trade sent as scenario PVs: as priced) and its Risk group its base PV in it.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
import urllib.request

import pytest

from test_var_explorer_browser import open_var, painted, shot
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from var_stack import VAR_TRADES, trade_grain_copy, var_data, var_lake, var_server
from select_helpers import choose, options, value, values  # <drishti-select>: drishti-inputs' Select

@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from var_server(tmp_path_factory, var_lake(tmp_path_factory, var_data(*VAR_TRADES)), shared=True)   # reads only: the pool guards it


@pytest.fixture(scope="module")
def trade_stack(tmp_path_factory):
    """The same data with a small trade-grain morning on its newest date (the demo is position grain): a trade page and its currency."""
    lake = trade_grain_copy(var_lake(tmp_path_factory, var_data(*VAR_TRADES)), tmp_path_factory.mktemp("var-trade-lake"))
    yield from var_server(tmp_path_factory, lake)


def requests_to(pg, part):
    return [u for u in pg.seen if part in u]


def watch(pg):
    pg.seen = []
    pg.on("request", lambda req: pg.seen.append(req.url))
    return pg


def tile_label(pg):
    return pg.locator('.vx-tile[data-tile="0"] .l').inner_text()


def test_the_picker_moves_every_panel_and_the_tab_loads_only_when_opened(stack, browser):
    pg = watch(open_var(browser, str(stack), "/var?scope=DESK:DESK-RATES"))
    painted(pg, "DESK:DESK-RATES")
    wait(pg, "document.querySelector('[data-var]').dataset.ccyReady === 'true'", 30)
    ccys = values(pg, "[data-vx-ccy]")
    assert ccys[:5] == ["USD", "EUR", "CAD", "JPY", "GBP"] and ccys[-1] == "__more", ccys
    assert tile_label(pg).endswith("· USD")
    usd_var = pg.locator('.vx-tile[data-tile="0"] .v').inner_text()
    for t in ("hist", "contrib", "captures"):                                # every other tab: the currencies are not asked
        pg.locator(f'.vx-tab[data-tab="{t}"]').click()
        wait(pg, "document.querySelector('[data-var]').dataset.tabPainted === %r" % t, 60)
    pg.locator('.vx-tab[data-tab="path"]').click()
    assert not requests_to(pg, "/api/var/scope/currencies"), pg.seen

    choose(pg, "[data-vx-ccy]", "EUR")
    wait(pg, "document.querySelector('[data-var]').dataset.painted === 'DESK:DESK-RATES' && "
             "document.querySelector('.vx-tile[data-tile=\"0\"] .l').innerText.indexOf('EUR') >= 0", 60)
    assert "ccy=EUR" in pg.url
    assert pg.locator("[data-vx-cmd]").inner_text().endswith("IN EUR")
    assert pg.locator('.vx-tile[data-tile="0"] .v').inner_text() != usd_var
    pg.locator("[data-vx-explain]").click()                                        # how it was built: the Explain drawer's Inputs & lineage
    pg.locator('[data-dx-tab="inputs"]').click()
    wait(pg, "(() => { const d = document.querySelector('[data-explain-drawer]'); return d && !d.hidden && d.dataset.state === 'ready' && d.dataset.tab === 'inputs'; })()", 120)
    prov = pg.locator("[data-explain-drawer]").inner_text()
    assert "Reporting currency" in prov and "EUR, chosen in the header" in prov, prov
    assert "scenario PVs" in prov and "supplied" in prov and "via USD" in prov, prov
    wait(pg, "document.querySelector('.vx-tree .dg-hrow.dg-hleaf') && document.querySelector('.vx-tree .dg-hrow.dg-hleaf').innerText.indexOf('EUR') >= 0", 60)
    assert all("ccy=EUR" in u for u in requests_to(pg, "/api/var/tree?")[-1:]), pg.seen
    shot(pg, "ccy-eur")
    assert not requests_to(pg, "/api/var/scope/currencies"), "the tab is loaded only when opened"

    pg.locator('.vx-tab[data-tab="ccy"]').click()
    wait(pg, "((document.querySelector('[data-vx-ccytable] drishti-grid') || {}).grid ? document.querySelector('[data-vx-ccytable] drishti-grid').grid.model.rowCount() : 0) >= 5", 60)
    assert len(requests_to(pg, "/api/var/scope/currencies")) == 1
    rows = pg.evaluate("((document.querySelector('[data-vx-ccytable] drishti-grid') || {}).grid || null).model.rowModel.rows.map((r) => [r.currency, r.shown ? 'shown' : ''])")
    assert [r[0] for r in rows] == ["USD", "EUR", "CAD", "JPY", "GBP"], rows
    assert dict(rows)["EUR"] == "shown"
    assert "VaR in EUR at today's rate" in pg.evaluate("((document.querySelector('[data-vx-ccytable] drishti-grid') || {}).grid || null).model.shownColumns().map((c) => c.header)")    # the grid's heading
    shot(pg, "ccy-tab")
    pg.locator('[data-vx-ccytable] .dg-row[data-ccy="CAD"] button').click()      # a row switches the page
    wait(pg, "document.querySelector('.vx-tile[data-tile=\"0\"] .l').innerText.indexOf('CAD') >= 0", 60)
    assert "ccy=CAD" in pg.url and value(pg, "[data-vx-ccy]") == "CAD"
    wait(pg, "!!document.querySelector('[data-vx-ccytable] .dg-row.vx-shown[data-ccy=\"CAD\"]')", 60)

    # the session keeps it: another VaR page in this tab opens in CAD
    pg.goto(str(stack) + "/var?scope=BANK")
    painted(pg, "BANK")
    assert "ccy=CAD" in pg.url and tile_label(pg).endswith("· CAD")
    # More…: every other currency with FX on the date
    wait(pg, "document.querySelector('[data-var]').dataset.ccyReady === 'true'", 30)
    choose(pg, "[data-vx-ccy]", "USD")
    wait(pg, "document.querySelector('.vx-tile[data-tile=\"0\"] .l').innerText.indexOf('USD') >= 0", 60)
    choose(pg, "[data-vx-ccy]", "__more")
    wait(pg, "document.querySelector('[data-vx-ccy]').component.options.some(o => o.group)", 30)
    more = [o["value"] for o in options(pg, "[data-vx-ccy]") if o["group"]]          # the group "More currencies with FX on <date>"
    assert "CHF" in more and "AUD" in more, more
    pg.close()


def test_the_command_in_a_currency_opens_the_page_in_it(stack, browser):
    pg = open_var(browser, str(stack), "/go?q=" + "VAR DESK DESK-RATES IN EUR <GO>".replace(" ", "+"))
    assert "ccy=EUR" in pg.url
    painted(pg, "DESK:DESK-RATES")
    assert tile_label(pg).endswith("· EUR")
    assert value(pg, "[data-vx-ccy]") == "EUR"
    pg.close()


def test_on_a_phone_the_picker_and_the_tab_fit(stack, browser):
    pg = open_var(browser, str(stack), "/var?scope=DESK:DESK-RATES&ccy=EUR", {"width": 390, "height": 844})
    painted(pg, "DESK:DESK-RATES")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    box = pg.locator("[data-vx-ccy] .di-select-control").bounding_box()
    assert box and box["height"] >= 36 and box["x"] + box["width"] <= 391, box          # a touch target, inside the screen
    pg.locator('.vx-tab[data-tab="ccy"]').click()
    wait(pg, "((document.querySelector('[data-vx-ccytable] drishti-grid') || {}).grid ? document.querySelector('[data-vx-ccytable] drishti-grid').grid.model.rowCount() : 0) >= 5", 60)
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    shot(pg, "ccy-phone")
    pg.close()


def test_a_trade_page_says_its_trade_currency(trade_stack, browser):
    """An Endur trade sent as scenario PVs in EUR: the strip's Trade currency is EUR, as priced; its Risk group says where it comes from and
    its base PV in it; the VaR contributions by trade list it too."""
    rows = []
    for scope in ("NODE:MGMT~DESK-COMM", "BANK"):
        with urllib.request.urlopen(str(trade_stack) + f"/api/var/scope/contributions?scope={scope}&by=trade", timeout=60) as r:
            rows = [x for x in json.loads(r.read())["rows"] if x.get("tradeCurrency") == "EUR" and x.get("tradeCurrencyFrom") == "scenario PVs"]
        if rows:
            break
    assert rows, "no EUR trade sent as scenario PVs in the demo data"
    tid = rows[0]["key"]
    pg = open_var(browser, str(trade_stack), "/go?q=" + f"TRD {tid} <GO>".replace(" ", "+"))
    wait(pg, "!!document.querySelector('#p-risk')", 60)
    wait(pg, "document.querySelector('#p-risk').innerText.indexOf('HS-509') >= 0 || document.querySelector('#p-risk').innerText.indexOf('not loaded') >= 0", 30)
    if "not loaded" in pg.locator("#p-risk").inner_text():
        pg.reload()
        wait(pg, "!!document.querySelector('#p-risk') && document.querySelector('#p-risk').innerText.indexOf('HS-509') >= 0", 60)
    strip = pg.locator("dl.strip").inner_text()
    assert "Trade currency" in strip and "EUR · as priced" in strip, strip
    risk = pg.locator("#p-risk").inner_text()
    assert "EUR, from its scenario PVs" in risk and "Base PV" in risk and "EUR " in risk, risk
    shot(pg, "trade-currency")
    pg.close()
