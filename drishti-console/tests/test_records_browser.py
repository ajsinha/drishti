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
"""Records everywhere, in a real browser against stand-in servers: a bare mnemonic (TRD <GO>) is a paged list in the records grid with the record panel
(Record, History, Raw JSON) and Pivot ›; a search result is the same grid with a Chart button that opens the one chart dialog; a monitor is the grid in its
static mode; a result without a record key (an SQL answer, a preview) is the same grid, static: no Pause, no Pivot, the panel shows the row's JSON; the
layout holds on a desktop and a phone, light and dark."""
import pytest

pytest.importorskip("playwright")
from playwright import sync_api  # noqa: E402

from table_chart_backend import serve  # noqa: E402

READY = ".rc-host[data-records-ready] .dg-row[data-key]"


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
def stack():
    url, backend, stop = serve()
    yield url, backend
    stop()


def opened(browser, url, path, wait=READY, width=1440, height=900, scheme="light", mobile=False):
    ctx = browser.new_context(viewport={"width": width, "height": height}, bypass_csp=True, color_scheme=scheme, is_mobile=mobile, has_touch=mobile)
    pg = ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(url + path, wait_until="load")
    pg.wait_for_selector(wait, timeout=20000)
    return pg


def test_a_bare_mnemonic_is_a_paged_list_in_the_records_grid_with_the_panel(browser, stack):
    url, _ = stack
    pg = opened(browser, url, "/s?q=TRD")
    assert pg.locator(".rc-host").get_attribute("data-records-mode") == "engine"
    assert pg.locator("[data-pager]").count() == 1 and pg.locator(".rc-host table").count() == 0
    assert pg.locator(".rc-host .dg-live-btn[data-act=pivot]").count() == 1
    pg.locator(".rc-host .dg-row[data-key]").first.locator(".dg-cell").nth(1).click()
    pg.wait_for_selector(".dg-live-panel[data-record]")
    assert [t.inner_text() for t in pg.locator(".dg-live-tab").all()] == ["Record", "History", "Raw JSON"]
    assert "Read-only" in pg.locator(".dg-live-panel").inner_text()           # read-only unless the server allows an edit
    assert pg.locator(".dg-live-panel a[data-link=Open]").count() == 1
    assert not pg.errors


def test_a_search_result_is_the_same_grid_and_charts_the_rows_it_holds(browser, stack):
    url, _ = stack
    pg = opened(browser, url, "/s?q=TRD%20where%20notional%20%3E%200")
    assert pg.locator(".rc-host .dg-row[data-key]").count() >= 5
    pg.click(".rc-host .dg-live-btn[data-act=chart]")
    pg.wait_for_selector("dialog.tc-dlg[open]")
    assert not pg.errors


def test_a_monitor_is_the_grid_in_its_static_mode(browser, stack):
    url, _ = stack
    pg = opened(browser, url, "/m/Watch?template=Credit%20watch")
    assert pg.locator("[data-monitor-grid][data-records-mode=static]").count() == 1
    assert pg.locator(".rc-host .dg-live-btn[data-act=pause]").count() == 0       # static: nothing to pause
    pg.locator(".rc-host .dg-row[data-key]").first.locator(".dg-cell").nth(1).click()
    pg.wait_for_selector(".dg-live-panel[data-record]")
    assert pg.locator(".dg-live-panel button[data-action]").count() == 1          # Remove from the monitor
    assert [t.inner_text() for t in pg.locator(".dg-live-tab").all()] == ["Record", "JSON"]


def test_a_result_without_a_key_is_the_static_grid_and_its_panel_shows_the_rows_json(browser, stack):
    url, _ = stack
    pg = opened(browser, url, "/s", wait="body")
    pg.evaluate("""() => { const h = window.Drishti.records.table([{name: 'Desk', type: 'string'}, {name: 'Notional', type: 'number'}],
        [['Rates', 1500000], ['FX', 250000.5], ['Credit', null]], {title: 'An SQL answer'}); h.id = 'sqlAnswer'; document.body.prepend(h); }""")
    pg.wait_for_selector("#sqlAnswer[data-records-ready] .dg-row[data-key]")
    assert pg.locator("#sqlAnswer .dg-row[data-key]").count() == 3
    assert pg.locator("#sqlAnswer .dg-live-btn[data-act=pivot], #sqlAnswer .dg-live-btn[data-act=pause]").count() == 0
    assert pg.locator("#sqlAnswer .dg-live-chip").inner_text().startswith("STATIC")
    pg.locator("#sqlAnswer .dg-row[data-key]").first.locator(".dg-cell").nth(0).click()
    pg.wait_for_selector("#sqlAnswer .dg-live-panel[data-record]")
    pg.click("#sqlAnswer .dg-live-tab[data-tab=raw]")
    pg.wait_for_selector("#sqlAnswer [data-raw] .dg-live-jl")
    assert "Rates" in pg.locator("#sqlAnswer [data-raw]").inner_text()
    assert not pg.errors


@pytest.mark.parametrize("scheme", ["light", "dark"])
@pytest.mark.parametrize("phone", [False, True])
def test_the_layout_holds_on_a_desktop_and_a_phone_light_and_dark(browser, stack, scheme, phone):
    url, _ = stack
    pg = opened(browser, url, "/s?q=TRD", width=390 if phone else 1440, height=800 if phone else 900, scheme=scheme, mobile=phone)
    scroll, inner = pg.evaluate("document.documentElement.scrollWidth"), pg.evaluate("window.innerWidth")
    assert scroll <= inner + 1, f"the page scrolls sideways: {scroll} > {inner}"
    box = pg.locator(".rc-host").bounding_box()
    assert box and box["width"] <= inner and box["height"] > 100
    assert not pg.errors
