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

"""Rupaka phase 3 in Chromium over the cross-pack samples (tests/bi_report_stack.py: ports 18990 and 17990, security on, a small Delta lake made by
tools/packgen/banking/make_data.py: trades, counterparties, desks, books and netting sets across three packs): the "Counterparty risk" report with its slicers,
its top-10 bar and its drill-through to a page about one counterparty; the same report for a desk head (her row rule shows only her desk's trades; the netting sets, a fact of
their own that the model lets her read, are not cut by it) and for an analyst (the counterparty name is masked); the "Cashflow ladder" report over legs and cashflows that are arrays inside the
trade documents, drilled from year to quarter.

Skipped when Playwright, Chromium, uv (to make the lake; or DRISHTI_BI_DELTA_ROOT) or the built server jar is missing, or a scratch port is in use. Never run with pytest -n."""
import json
import re
import urllib.parse

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import card_value, chart, clean, click_mark, grid_rows, open_report, pick_in_dropdown_slicer, settled, sign_in  # noqa: E402
from bi_report_stack import api  # noqa: E402

COUNTERPARTY = "file:report:counterparty-risk"
LADDER = "file:report:cashflow-ladder"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    tmp = tmp_path_factory.mktemp("bicross")
    lake = bi_report_stack.make_lake(tmp / "lake")
    if not lake:
        pytest.skip("needs a Delta lake: DRISHTI_BI_DELTA_ROOT, or uv with deltalake, pyarrow and pyyaml (tools/packgen/banking/make_data.py --lake)")
    try:
        s = bi_report_stack.ReportStack(tmp, delta_root=lake).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def engine(user, roles, body, dataset="trading-risk"):
    """The engine's answer as ``user``: the 2.1 query body asked as one model query on the sample's model (2.2)."""
    from bi_samples import as_mq
    s, r = api("POST", "/bi/mq", user, roles, as_mq(f"{dataset}@1", body))
    assert s == 200, r
    return r


# ---- counterparty risk ------------------------------------------------------------------------------------------------------

def test_the_counterparty_report_shows_what_the_engine_answers(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, COUNTERPARTY)
    settled(page)
    ee = engine("rita", ["risk"], {"measures": ["Exposure (EE peak)", "Exposure (PFE peak)", "Netting sets"]})["rows"][0]
    assert card_value(page, "ee") == int(ee[0]) and card_value(page, "pfe") == int(ee[1]) and card_value(page, "sets") == int(ee[2])
    top = engine("rita", ["risk"], {"fields": ["counterparties.NAME"], "measures": ["Exposure (EE peak)"], "orderBy": [{"by": "Exposure (EE peak)", "desc": True}], "limit": 10})["rows"]
    bar = chart(page, "by-counterparty")
    assert len(bar["categories"]) == 10 and set(bar["categories"]) == {r[0] for r in top}              # the top-10 filter kept the ten largest
    assert [s["name"] for s in bar["series"]] == ["Exposure (EE peak)", "Exposure (PFE peak)"]
    donut = chart(page, "by-sector")
    assert round(sum(donut["series"][0]["values"])) == int(ee[0])
    expect(page.locator('[data-visual="mtm-by-counterparty"] .dg-row').first).to_be_visible()           # the table, drawn by drishti-grid
    clean(page)


def test_a_report_scope_slicer_filters_every_visual_through_the_relationships(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, COUNTERPARTY)
    settled(page)
    pick_in_dropdown_slicer(page, "sector", "Banking")                    # the dropdown slicer: drishti-inputs' MultiSelect
    settled(page)
    want = engine("rita", ["risk"], {"measures": ["Exposure (EE peak)"], "filters": [{"field": "counterparties.SECTOR", "op": "in", "values": ["Banking"]}]})["rows"][0][0]
    assert card_value(page, "ee") == int(want)
    mtm_sectors = [row[0] for row in grid_rows(page, "mtm-by-counterparty")]
    assert mtm_sectors == ["Banking"]                                                                   # the trades table was filtered by the counterparties' sector too
    expect(page.locator('[data-chip="slicer"]')).to_contain_text("Banking")
    clean(page)


def test_drill_through_carries_the_counterparty_to_its_own_page_and_back(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, COUNTERPARTY)
    settled(page)
    name = chart(page, "by-counterparty")["categories"][0]
    click_mark(page, "by-counterparty", name)
    page.click('[data-visual="by-counterparty"] [data-act="drill-through"]')
    page.wait_for_url("**/counterparty?carry=**")
    settled(page)
    expect(page.locator('[data-chip="carry"]')).to_contain_text(name)
    assert json.loads(urllib.parse.unquote(page.url.split("carry=")[1]))[0]["field"] == "counterparties.NAME"
    one = [{"field": "counterparties.NAME", "op": "eq", "values": [name]}]
    trades = engine("rita", ["risk"], {"measures": ["Trades"], "filters": one})["rows"][0][0]
    ee = engine("rita", ["risk"], {"measures": ["Exposure (EE peak)"], "filters": one})["rows"][0][0]
    assert card_value(page, "c-trades") == int(trades) and card_value(page, "c-ee") == int(ee)
    rows = grid_rows(page, "c-trades-table")
    assert len(rows) == min(int(trades), 200)                                                         # one row per trade of that counterparty (the visual's limit is 200)
    link = page.locator('[data-visual="c-trades-table"] a[data-open-terminal="trade"]').first          # a trade is a Drishti kind: open it in the terminal
    assert re.match(r"/v/trade/[^/]+$", link.get_attribute("href"))
    assert page.locator('[data-pages] [data-page="exposure"]').count() == 1 and page.locator('[data-pages] [data-page="counterparty"]').count() == 1
    page.click("[data-back]")
    page.wait_for_selector('[data-visual="by-counterparty"] .bi-chart')
    expect(page.locator('[data-chip="carry"]')).to_have_count(0)
    clean(page)


def carry(field, *values):
    return "?carry=" + urllib.parse.quote(json.dumps([{"field": field, "values": list(values)}], separators=(",", ":")))


def test_a_trade_row_opens_in_the_terminal_and_an_unknown_counterparty_is_an_empty_page_not_an_error(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, COUNTERPARTY, "/counterparty" + carry("counterparties.NAME", "No such counterparty plc"))
    settled(page)
    expect(page.locator('[data-visual="c-trades-table"] .bi-vis-empty')).to_be_visible()
    assert card_value(page, "c-trades") == 0
    open_report(page, COUNTERPARTY)
    settled(page)
    name = chart(page, "by-counterparty")["categories"][0]
    open_report(page, COUNTERPARTY, "/counterparty" + carry("counterparties.NAME", name))
    settled(page)
    first = page.locator('[data-visual="c-trades-table"] .dg-row').first
    trade_id = first.locator("a[data-open-terminal=trade]").inner_text()                                # the desk opens too, as a desk
    first.locator(".dg-cell:not(:has(a))").first.click()                                                 # a click on a row (not on a link): its actions, the row selected
    expect(page.locator('[data-chip="selection"]')).to_contain_text("trades.TRADE_ID")
    page.locator('[data-visual="c-trades-table"] [data-act="open-terminal"]').click()
    page.wait_for_url(f"**/v/trade/{trade_id}")
    clean(page)


def test_a_desk_head_sees_her_desk_and_the_exposure_of_every_counterparty(stack, browser):
    page = sign_in(browser, "hana")
    open_report(page, COUNTERPARTY)
    settled(page)
    mine = engine("hana", ["desk-head"], {"measures": ["Trades"]})["rows"][0][0]
    everyone = engine("rita", ["risk"], {"measures": ["Trades"]})["rows"][0][0]
    assert mine < everyone
    total = sum(int(row[1].replace(",", "")) for row in grid_rows(page, "mtm-by-counterparty"))
    assert total == mine                                                                                 # the table counts only her desk's trades, whatever she clicks
    assert page.locator(".bi-vis-failed").count() == 0                                                # 2.2: the netting sets are a fact of their own, not cut by her trades' rule
    ee = engine("hana", ["desk-head"], {"measures": ["Exposure (EE peak)"]})["rows"][0][0]
    assert ee == engine("rita", ["risk"], {"measures": ["Exposure (EE peak)"]})["rows"][0][0]          # may-see-all: the model says she reads every netting set
    clean(page)


def test_an_analyst_sees_the_masked_name_as_dots_and_the_report_says_it_is_masked(stack, browser):
    page = sign_in(browser, "ann")
    open_report(page, COUNTERPARTY)
    settled(page)
    bar = chart(page, "by-counterparty")
    assert bar["categories"] == ["•••"]                                                   # every counterparty is the same mask: one bar
    assert "masked" in page.locator('[data-visual="by-counterparty"] .bi-vis-foot').inner_text()
    clean(page)


# ---- the cashflow ladder ------------------------------------------------------------------------------------------------------

def test_the_ladder_drills_from_year_to_quarter_and_the_legs_matrix_has_totals(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, LADDER)
    settled(page)
    years = chart(page, "by-pay-date")
    assert len(years["categories"]) >= 2 and all(re.fullmatch(r"20\d\d", c) for c in years["categories"])
    total = engine("rita", ["risk"], {"measures": ["Cashflow amount"], "filters": [{"field": "payments.EVENT_DATE", "op": "not-null"}]}, "trading-cashflows")["rows"][0][0]   # the visual's own filter
    assert round(sum(years["series"][0]["values"])) == round(total)
    year = years["categories"][0]
    click_mark(page, "by-pay-date", year)
    page.click('[data-visual="by-pay-date"] [data-act="drill-down"]')
    settled(page)
    q = chart(page, "by-pay-date")
    assert all(c in {"1", "2", "3", "4"} for c in q["categories"])                                        # the quarters of that year
    assert year in page.locator('[data-visual="by-pay-date"] .bi-vis-title').inner_text()
    page.click('[data-visual="by-pay-date"] [data-act="drill-up"]')
    settled(page)
    assert chart(page, "by-pay-date")["categories"] == years["categories"]
    page.click('[data-pages] [data-page="legs"]')
    settled(page)
    matrix = page.locator('[data-visual="leg-matrix"]')
    expect(matrix.locator("tr[data-grandtotal]")).to_have_count(1)
    legs = engine("rita", ["risk"], {"measures": ["Legs"]}, "trading-cashflows")["rows"][0][0]
    assert int(matrix.locator('tr[data-grandtotal] td[data-measure="Legs"]').last.inner_text().replace(",", "")) == legs
    clean(page)


def test_a_phone_reads_the_counterparty_page_in_one_column(stack, browser):
    page = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    open_report(page, COUNTERPARTY)
    settled(page)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    assert len(set(page.evaluate("[...document.querySelectorAll('.bi-vis')].map(v => Math.round(v.getBoundingClientRect().left))"))) == 1
    for el in page.locator(".bi-slicer summary, [data-pages] a, .bi-vis-btn").all():
        if el.is_visible():
            assert el.bounding_box()["height"] >= 43, (el.evaluate("e => e.outerHTML.slice(0, 160)"), el.bounding_box())
    clean(page)
