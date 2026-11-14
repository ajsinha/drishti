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

"""The grid's live mode on the trade blotter page (/live/trade) in a real browser against a stand-in server: a live update arrives over the
console's one channel and the cell ticks; a row opens the record panel; an edit goes with its reason and its version, and a record that moved
since it was opened is refused with who changed it; a person without the edit power gets no edit control at all; the Raw JSON tab shows the
whole stored record (fields the grid has no column for), collapsible, with a Copy button, its version, a note when it changed since the panel
opened it and masked fields as masked; grouping keeps live subtotals; resync starts again from a snapshot; Pivot › opens the Pivot with the same
kind, filters, grouping and numbers; the layout holds on a desktop and a phone, light and dark."""
import json
from urllib.parse import parse_qs, urlparse

import pytest

pytest.importorskip("playwright")
from playwright import sync_api  # noqa: E402

from live_backend import LiveBackend  # noqa: E402
from table_chart_backend import serve  # noqa: E402

READY = "[data-live-ready] .dg-row[data-key]"
CELL = ".dg-live-grid .dg-row[data-key='{key}'] [data-col='{col}'] .dg-text"


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture()
def stack():
    backend = LiveBackend()
    url, _, stop = serve(backend)
    yield url, backend
    stop()


def opened(browser, url, width=1440, height=900, scheme="light", mobile=False):
    ctx = browser.new_context(viewport={"width": width, "height": height}, bypass_csp=True, color_scheme=scheme, is_mobile=mobile, has_touch=mobile,
                              permissions=["clipboard-read", "clipboard-write"])
    pg = ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(url + "/live/trade", wait_until="load")
    pg.wait_for_selector("[data-live-ready]")
    pg.wait_for_selector(".dg-live-grid .dg-row")
    pg.wait_for_function("() => document.querySelector('.dg-live-chip').dataset.state === 'live'")      # the stream said hello
    return pg


def row_text(pg, key):
    return pg.locator(f".dg-live-grid .dg-row[data-key='{key}']").inner_text()


def open_row(pg, key):
    pg.locator(f".dg-live-grid .dg-row[data-key='{key}'] .dg-cell").nth(2).click()
    pg.wait_for_selector(f".dg-live-panel[data-record='{key}']")


def test_a_live_update_arrives_and_ticks(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    assert "T-003" in pg.locator(".dg-live-grid").inner_text() and "30 rows" in pg.locator(".dg-live-chip").inner_text()
    assert pg.locator(".dg-live-chip").inner_text().startswith("LIVE")
    backend.tick("T-003", PRICE=9.9999, TRADE_STATUS="AMENDED")
    pg.wait_for_function("() => document.querySelector(\"[data-key='T-003']\") && document.querySelector(\"[data-key='T-003']\").innerText.includes('9.9999')")
    text = row_text(pg, "T-003")
    assert "AMENDED" in text and "9.9999" in text and "\n2\n" in "\n" + text.replace("\t", "\n") + "\n"      # version 2 as the feed sent it
    assert pg.locator("[data-key='T-003'] [class*='dg-flash-']").count() >= 1, "the changed cell flashes"
    stale = pg.evaluate("window.Drishti.LivePage.grid.stats.stale")
    backend.push(["T-003"])                                                   # the same version again: harmless, and not applied twice
    pg.wait_for_function("(n) => window.Drishti.LivePage.grid.stats.stale > n", arg=stale)
    held = "(id) => window.Drishti.LivePage.grid.grid.model.rowModel.idIndex.has(id)"
    backend.docs["T-040"] = {**backend.docs["T-003"], "TRADE_ID": "T-040", "TRADE_VERSION": 1}
    backend.push(["T-040"])                                                   # a new trade arrives
    pg.wait_for_function(held, arg="T-040")
    backend.docs.pop("T-040")
    backend.push([], ["T-040"])                                               # and goes
    pg.wait_for_function("(id) => !(" + held[len("(id) => "):] + ")", arg="T-040")
    assert not pg.errors, pg.errors


def test_a_page_says_which_source_systems_it_excludes(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    assert pg.locator(".dg-live-banner").count() == 0                           # every source on: nothing to say
    pg.context.close()
    backend.excludes = ["ENDUR"]                                                 # the spec of the set says so: set.banner
    pg = opened(browser, url)
    banner = pg.locator(".dg-live-banner")
    assert banner.count() == 1 and banner.inner_text() == "excludes ENDUR (switched off)"
    assert banner.get_attribute("data-excludes") == "ENDUR" and banner.get_attribute("role") == "status"
    assert not pg.errors, pg.errors


def test_the_grid_tells_the_server_the_page_it_shows(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    pg.wait_for_function("() => true")
    for _ in range(50):
        if backend.windows:
            break
        pg.wait_for_timeout(100)
    assert backend.windows[0] == {"all": True}                                # the whole set fits: the viewer holds every row


def test_resync_starts_again_from_a_snapshot(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    backend.docs["T-050"] = {**backend.docs["T-003"], "TRADE_ID": "T-050"}
    backend.push(kind="resync")
    pg.wait_for_function("() => window.Drishti.LivePage.grid.grid.model.rowModel.idIndex.has('T-050')")
    assert pg.evaluate("window.Drishti.LivePage.grid.stats.resyncs") == 1
    assert "31 rows" in pg.locator(".dg-live-chip").inner_text()


def test_a_row_opens_the_record_panel_with_links_and_an_edit_with_a_reason(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    open_row(pg, "T-005")
    panel = pg.locator(".dg-live-panel")
    assert panel.locator(".dg-live-pkey").inner_text() == "T-005" and panel.locator(".dg-live-pver").inner_text() == "v1"
    hrefs = {a.get_attribute("data-link"): a.get_attribute("href") for a in panel.locator(".dg-live-link").all()}
    assert hrefs == {"Explain": "/explain/trade/T-005", "VaR": "/var?scope=book:BOOK-C", "Limits": "/limits?book=BOOK-C"}
    price = panel.locator("[data-live-field-price]").first
    price.fill("1.5")
    panel.locator("[data-live-reason]").first.fill("Booked at the wrong price: ticket 4411")
    panel.get_by_role("button", name="Submit changes").click()
    pg.wait_for_function("() => document.querySelector('.dg-live-status') && document.querySelector('.dg-live-status').textContent.startsWith('Submitted')")
    sent = backend.edits[-1]
    assert sent["entity"] == "T-005" and sent["basedOn"] == 1 and sent["reason"] == "Booked at the wrong price: ticket 4411"
    assert sent["items"] == [{"field": "PRICE", "from": 1.05, "to": 1.5}]
    assert "waiting for 1 approver" in panel.locator(".dg-live-status").inner_text()          # four eyes
    assert "My changes (1)" in pg.locator("[data-act=tray]").inner_text()                     # the tray
    pg.click("[data-act=tray]")
    assert "T-005" in pg.locator(".dg-live-tray").inner_text() and "PRICE 1.05 → 1.5" in pg.locator(".dg-live-tray").inner_text()
    panel.get_by_role("tab", name="History").click()
    pg.wait_for_selector(".dg-live-tl .dg-live-hrow[data-state='awaiting']")
    assert "Booked at the wrong price" in panel.inner_text() and "waiting for 1 approver" in panel.inner_text()
    assert not pg.errors, pg.errors


def test_a_record_that_moved_since_it_was_opened_says_so_and_the_server_refuses_the_old_edit(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    open_row(pg, "T-006")
    backend.tick("T-006", PRICE=7.0)
    pg.wait_for_selector(".dg-live-changed:not([hidden])")
    assert "Changed since you opened it: now version 2" in pg.locator(".dg-live-changed").inner_text()
    pg.locator("[data-live-field-quantity]").first.fill("42")
    pg.get_by_role("button", name="Submit changes").click()
    pg.wait_for_function("() => /changed by feed/.test(document.querySelector('.dg-live-panel').innerText)")
    assert len(backend.edits) == 1 and backend.edits[0]["basedOn"] == 1 and backend.changes == []        # refused: nothing was queued
    pg.click("[data-act=reload]")
    pg.wait_for_selector(".dg-live-changed", state="hidden")
    assert pg.locator(".dg-live-pver").inner_text() == "v2"


def test_without_the_edit_power_there_is_no_edit_control(browser):
    backend = LiveBackend(editable=False)
    url, _, stop = serve(backend)
    try:
        pg = opened(browser, url)
        open_row(pg, "T-005")
        panel = pg.locator(".dg-live-panel")
        assert panel.locator("input, select, textarea, [data-live-reason]").count() == 0
        assert panel.get_by_role("button", name="Submit changes").count() == 0
        assert "Read-only for you: your role holds no edit power" in panel.inner_text()
        assert panel.locator("[data-readonly]").inner_text().count("T-005") == 1
        assert not pg.errors, pg.errors
    finally:
        stop()


def test_raw_json_is_the_whole_stored_record_collapsible_copyable_and_masked(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    heads = pg.locator(".dg-live-grid [role=columnheader]").all_inner_texts()
    assert not any("BOOKING" in h or "LEGS" in h for h in heads)                # the grid has no column for them
    open_row(pg, "T-005")
    panel = pg.locator(".dg-live-panel")
    panel.get_by_role("tab", name="Raw JSON").click()
    pg.wait_for_selector("[data-raw] .dg-live-jl")
    raw = panel.locator("[data-raw]")
    panel.get_by_role("button", name="Expand all").click()
    text = raw.inner_text()
    assert "BOOKING_SYSTEM" in text and "MUREX" in text and "LEGS" in text and "NOTIONAL" in text      # fields the grid does not show
    assert "version 1 · seq" in panel.locator(".dg-live-rawmeta").inner_text()
    assert raw.locator("[data-masked]").count() == 1 and "Masked for you" in raw.locator("[data-masked]").get_attribute("title")
    legs = raw.locator("details", has_text="LEGS").first                         # collapsible
    assert legs.evaluate("e => e.open") is True
    legs.locator("summary").first.click()
    assert legs.evaluate("e => e.open") is False
    panel.get_by_role("button", name="Collapse all").click()
    assert raw.locator("details[open]").count() == 0
    panel.get_by_role("button", name="Expand all").click()
    assert raw.locator("details:not([open])").count() == 0
    panel.locator("[data-act=copy]").click()
    pg.wait_for_selector("[data-act=copy][data-copied]")
    copied = json.loads(pg.evaluate("navigator.clipboard.readText()"))
    assert copied["BOOKING_SYSTEM"] == "MUREX" and copied["TRADER_TAX_ID"] == "•••" and copied["LEGS"][0]["CCY"] == "USD"
    backend.tick("T-005", PRICE=3.5)                                              # changed since it was opened
    pg.wait_for_selector(".dg-live-changed:not([hidden])")
    pg.click("[data-act=reload]")                                                 # reopening reads the record again, whole
    pg.wait_for_selector("[data-raw] .dg-live-jl")
    assert backend.raw_reads >= 2 and "version 2" in panel.locator(".dg-live-rawmeta").inner_text() or pg.locator(".dg-live-pver").inner_text() == "v2"


def test_grouping_keeps_live_subtotals(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    pg.evaluate("window.Drishti.LivePage.grid.group('PORTFOLIO')")
    pg.wait_for_function("() => /BOOK-A/.test(document.querySelector('.dg-live-grid').innerText)")

    def subtotal():
        return pg.evaluate("""() => { const m = window.Drishti.LivePage.grid.grid.model; const out = {};
          for (let i = 0; i < m.rowCount(); i++) { const x = m.rowAt(i); if (x && x.kind === 'group') { out[x.keys ? x.keys[x.keys.length - 1] : x.path[x.path.length - 1]] = m.cellValue(i, m.shownColumns().findIndex((c) => c.id === 'QUANTITY')); } }
          return out; }""")
    before = subtotal()
    assert before.get("BOOK-A") and before["BOOK-A"] == sum(d["QUANTITY"] for d in backend.docs.values() if d["PORTFOLIO"] == "BOOK-A")
    backend.tick("T-000", QUANTITY=5_000_000)                                      # T-000 is in BOOK-A
    for _ in range(50):
        after = subtotal()
        if after["BOOK-A"] != before["BOOK-A"]:
            break
        pg.wait_for_timeout(100)
    assert after["BOOK-A"] == sum(d["QUANTITY"] for d in backend.docs.values() if d["PORTFOLIO"] == "BOOK-A") and after["BOOK-B"] == before["BOOK-B"]


def test_pivot_opens_with_the_same_kind_filters_grouping_and_numbers(browser, stack):
    url, backend = stack
    pg = opened(browser, url)
    pg.evaluate("() => { const g = window.Drishti.LivePage.grid; g.group('COUNTERPARTY'); g.grid.model.setFilterModel({PORTFOLIO: {type: 'set', values: ['BOOK-A']}}); }")
    address = pg.evaluate("window.Drishti.LivePage.grid.pivotAddress()")
    q = parse_qs(urlparse(address).query)
    assert urlparse(address).path == "/pivot" and q["source"] == ["trade"] and q["live"] == ["1"]
    lay = json.loads(q["lay"][0])
    assert lay["rows"] == ["COUNTERPARTY"] and lay["filters"] == {"PORTFOLIO": {"in": ["BOOK-A"]}}
    assert [v["measure"] for v in lay["values"]] == ["sum(QUANTITY)", "sum(PRICE)"]                  # the numbers, not the version
    with pg.expect_navigation():
        pg.click("[data-act=pivot]")
    assert pg.url.startswith(url + "/pivot?source=trade") and pg.locator("[data-pivot-screen]").count() == 1
    assert pg.locator("[data-pivot-screen]").get_attribute("data-source") == "trade"
    assert not pg.errors, pg.errors


@pytest.mark.parametrize("width,mobile", [(1440, False), (390, True)])
@pytest.mark.parametrize("scheme", ["light", "dark"])
def test_the_layout_holds_on_a_desktop_and_a_phone_light_and_dark(browser, stack, width, mobile, scheme):
    url, backend = stack
    pg = opened(browser, url, width=width, height=900 if not mobile else 800, scheme=scheme, mobile=mobile)
    pg.evaluate("t => document.documentElement.setAttribute('data-theme', t)", "terminal" if scheme == "dark" else "light")
    open_row(pg, "T-005")
    geo = pg.evaluate("""() => { const r = (s) => { const e = document.querySelector(s); return e ? e.getBoundingClientRect() : null; };
      return { sw: document.documentElement.scrollWidth, vw: document.documentElement.clientWidth, grid: r('.dg-live-grid'), panel: r('.dg-live-panel'), bar: r('.dg-live-bar'),
               bg: getComputedStyle(document.querySelector('.dg-liveset')).backgroundColor, ink: getComputedStyle(document.querySelector('.dg-live-title')).color }; }""")
    assert geo["sw"] <= geo["vw"], geo                                              # no sideways page scroll
    assert geo["grid"]["width"] > 150 and geo["panel"]["width"] > 150 and geo["panel"]["right"] <= geo["vw"] + 1, geo
    if mobile:
        assert geo["panel"]["top"] >= geo["grid"]["bottom"] - 2, "on a phone the panel goes under the grid"
    else:
        assert geo["panel"]["left"] >= geo["grid"]["right"] - 2, "on a desktop the panel is beside the grid"
    ink = [int(x) for x in geo["ink"][geo["ink"].index("(") + 1:geo["ink"].index(")")].split(",")[:3]]
    assert (sum(ink) > 380) == (scheme == "dark"), geo                              # the theme's ink: light text on dark, dark on light
    assert pg.locator(".dg-live-bar .dg-live-btn").first.is_visible()
    assert not pg.errors, pg.errors
