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


@pytest.mark.parametrize("width", [1366, 1920, 2560])
@pytest.mark.parametrize("scheme", ["light", "dark"])
def test_a_list_of_records_spans_the_content_and_fills_the_window_under_one_toolbar(browser, stack, width, scheme):
    url, _ = stack
    pg = opened(browser, url, "/s?q=TRD", width=width, height=900, scheme=scheme)
    pg.wait_for_function("() => document.querySelector('.rc-host').style.height !== ''")
    geo = pg.evaluate("""() => { const h = document.querySelector('.rc-host').getBoundingClientRect(), m = document.querySelector('main').getBoundingClientRect();
      return { w: h.width, main: m.width, bottom: h.bottom, vh: window.innerHeight, sw: document.documentElement.scrollWidth, vw: document.documentElement.clientWidth }; }""")
    assert geo["w"] >= 0.9 * geo["main"] - 2, geo                                        # not a narrow centred column
    assert geo["bottom"] <= geo["vh"] + 1, geo                                           # down to the bottom of the window, not past it
    assert geo["sw"] <= geo["vw"] + 1, geo
    assert pg.locator(".rc-host .dg-toolbar").count() == 1 and pg.locator(".dg-live-bar").count() == 0      # one toolbar
    assert pg.locator(".rc-host [data-act=toggle-pivot]").count() == 0 and pg.locator(".rc-host [data-act=pivot]").count() == 1   # one Pivot
    assert pg.locator(".rc-host .dg-wells").first.is_hidden()                            # ROWS / COLUMNS / FILTERS / VALUES wait behind Customize
    pg.click(".rc-host [data-act=customize]")
    pg.wait_for_selector(".rc-host .dg-layout-panel .dg-side")
    pg.set_viewport_size({"width": width, "height": 700})
    pg.wait_for_function("h => document.querySelector('.rc-host').getBoundingClientRect().bottom <= h + 1", arg=700)   # it follows the window
    assert not pg.errors


def test_the_records_list_has_one_count(browser, stack):
    url, _ = stack
    pg = opened(browser, url, "/s?q=TRD", width=1440)
    assert "rows ×" not in pg.locator(".rc-host .dg-status").inner_text()                # the grid's own count is not repeated in its status bar
    assert "30 trades" in pg.locator(".rc-host .dg-live-chip").inner_text()
    # live or not, the chip leaves CONNECTING: a set with no stream says STATIC once its snapshot is drawn (no hello ever comes)
    pg.wait_for_function("() => document.querySelector('.rc-host .dg-live-chip').dataset.state !== 'connecting'", timeout=30000)
    assert pg.locator(".cmp-sum span:has(b)").count() == 0 or "match" in pg.locator(".cmp-sum").inner_text()
    assert not pg.errors


@pytest.mark.parametrize("phone", [False, True])
def test_a_nested_value_in_the_record_panel_is_never_object_object(browser, stack, phone):
    """A NEWS record holds its items and sources as lists of records (the owner's phone, 2026-11-15: tapping a row showed
    "[object Object], [object Object] …"). The panel's Record tab joins a list of plain values, draws a record or a list of records as
    the Raw JSON tab's tree (closed, opening whole), and the grid's own cell counts the records."""
    url, _ = stack
    pg = opened(browser, url, "/s", wait="body", width=390 if phone else 1440, height=844 if phone else 900, mobile=phone)
    pg.evaluate("""() => { const h = window.Drishti.records.table(
        [{name: 'News', type: 'string'}, {name: 'Aliases'}, {name: 'Items'}, {name: 'Source'}],
        [['NEWS-bitcoin', ['crypto', 'cryptocurrency'], [{title: 'Bitcoin rallies', source: 'Wire'}, {title: 'ETF flows', source: 'Desk'}],
          {name: 'Wire', items: 2}]], {title: 'News'}); h.id = 'nested'; document.body.prepend(h); }""")
    pg.wait_for_selector("#nested[data-records-ready] .dg-row[data-key]")
    row = pg.locator("#nested .dg-row[data-key]").first
    assert "2 items" in row.inner_text() and "[object Object]" not in row.inner_text()
    row.locator(".dg-cell").nth(0).click()
    pg.wait_for_selector("#nested .dg-live-panel[data-record] .dg-live-kv")
    kv = pg.locator("#nested .dg-live-kv")
    assert "[object Object]" not in kv.inner_text()
    assert "crypto, cryptocurrency" in kv.inner_text()
    assert kv.locator("dd[data-nested] > details").count() == 2             # Items and Source: the tree, closed
    kv.locator("dd[data-nested] > details > summary").first.dispatch_event("click")
    assert "Bitcoin rallies" in kv.inner_text() and "ETF flows" in kv.inner_text()   # opened: each record's fields
    assert not pg.errors
