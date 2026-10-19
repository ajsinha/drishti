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
trade documents, drilled from year to quarter; and the Model view of the cross-pack 2.1 dataset (a version 1 dataset still has it): five boxes, five relationships, remove one, drag a column to bring it
back, accept it from the proposals, drag a box.

Skipped when Playwright, Chromium, uv (to make the lake; or DRISHTI_BI_DELTA_ROOT) or the built server jar is missing, or a scratch port is in use. Never run with pytest -n."""
import json
import re
import urllib.parse

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import card_value, chart, clean, click_mark, open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, api  # noqa: E402

COUNTERPARTY = "file:report:counterparty-risk"
LADDER = "file:report:cashflow-ladder"
RISK = "file:dataset:trading-risk-v1"           # the 2.1 sample, kept as a version 1 dataset: the Model view is for those (a model has its designer)


class CrossStack(bi_report_stack.ReportStack):
    def prepare(self, work):
        old = (bi_report_stack.ROOT / "drishti-server/src/test/resources/bi/samples-2.1/datasets/trading-risk.dataset.yaml").read_text(encoding="utf-8")
        (work / "config/bi/datasets/trading-risk-v1.dataset.yaml").write_text(old.replace("dataset: trading-risk", "dataset: trading-risk-v1", 1), encoding="utf-8")


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
        s = CrossStack(tmp, delta_root=lake).start()
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
    expect(page.locator('[data-visual="mtm-by-counterparty"] tbody tr').first).to_be_visible()
    clean(page)


def test_a_report_scope_slicer_filters_every_visual_through_the_relationships(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, COUNTERPARTY)
    settled(page)
    page.click('[data-visual="sector"] summary')
    page.check('[data-visual="sector"] [data-slicer-value="Banking"]')
    settled(page)
    want = engine("rita", ["risk"], {"measures": ["Exposure (EE peak)"], "filters": [{"field": "counterparties.SECTOR", "op": "in", "values": ["Banking"]}]})["rows"][0][0]
    assert card_value(page, "ee") == int(want)
    mtm_sectors = [r.inner_text().split("\t")[0] for r in page.locator('[data-visual="mtm-by-counterparty"] tbody tr').all()]
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
    rows = page.locator('[data-visual="c-trades-table"] tbody tr')
    assert rows.count() == min(int(trades), 200)                                                         # one row per trade of that counterparty (the visual's limit is 200)
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
    first = page.locator('[data-visual="c-trades-table"] tbody tr').first
    trade_id = first.locator("a[data-open-terminal]").inner_text()
    first.focus()
    page.keyboard.press("Enter")                                                                         # a table row is selectable from the keyboard
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
    rows = page.locator('[data-visual="mtm-by-counterparty"] tbody tr')
    total = sum(int(r.inner_text().split("\t")[1].replace(",", "")) for r in rows.all())
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
    total = engine("rita", ["risk"], {"measures": ["Cashflow amount"], "filters": [{"field": "cashflows.PAYMENT_DATE", "op": "not-null"}]}, "trading-cashflows")["rows"][0][0]
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


# ---- the Model view -----------------------------------------------------------------------------------------------------------

def yaml_text(page):
    return page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()")


def test_the_model_view_draws_five_boxes_and_five_lines_and_edits_the_yaml_both_ways(stack, browser):
    page = sign_in(browser, "wes", viewport={"width": 1500, "height": 1500})                            # tall: a box that no relationship holds goes in a band below
    page.goto(f"{CONSOLE_URL}/bi/m/{RISK}")
    page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
    expect(page.locator(".bi-tbox")).to_have_count(5)
    expect(page.locator("svg.bi-lines g[data-rel]")).to_have_count(5)
    expect(page.locator("[data-readonly]")).to_be_visible()
    expect(page.locator("[data-save]")).to_be_disabled()
    assert "many-to-one" in page.locator("svg.bi-lines g[data-rel] title").first.text_content()               # the cardinality is on the line (and its ends say one and many)
    assert page.locator("svg.bi-lines text").filter(has_text="*").count() >= 5
    page.click("[data-save-copy]")
    page.wait_for_url("**/bi/m/bi-**")
    page.wait_for_selector("[data-model-root][data-ready=yes]")
    expect(page.locator("[data-readonly]")).to_be_hidden()
    base = yaml_text(page)
    assert "from: trades.PORTFOLIO" in base
    # remove one relationship in the list: the YAML loses exactly that line
    page.locator('[data-rel-row]').filter(has_text="trades.PORTFOLIO").locator("[data-remove-rel]").click()
    expect(page.locator("svg.bi-lines g[data-rel]")).to_have_count(4)
    after = yaml_text(page)
    assert "trades.PORTFOLIO" not in after.split("relationships:")[1].split("measures:")[0] and after.count("cardinality:") == base.count("cardinality:") - 1
    assert "# STORED SAMPLE 2 of 3" in after and "rollups:" in after                                           # the rest of the file, comments included, is as it was
    # drag trades.PORTFOLIO onto books.id: the form is filled in with a many-to-one, and Add writes it
    page.drag_and_drop('[data-column="trades.PORTFOLIO"]', '[data-column="books.id"]')
    assert page.input_value("[data-nr-from-t]") == "trades" and page.input_value("[data-nr-from-c]") == "PORTFOLIO"
    assert page.input_value("[data-nr-to-t]") == "books" and page.input_value("[data-nr-to-c]") == "id"
    assert page.input_value("[data-nr-card]") == "many-to-one"
    page.click("[data-nr-add]")
    expect(page.locator("svg.bi-lines g[data-rel]")).to_have_count(5)
    assert "{ from: trades.PORTFOLIO, to: books.id, cardinality: many-to-one }" in yaml_text(page)
    page.wait_for_function("() => document.querySelector('[data-valid]').textContent === 'Valid'")
    # change the cardinality on the list and the direction: both go to the YAML
    page.locator('[data-rel-row]').filter(has_text="trades.PORTFOLIO").locator("[data-direction]").select_option("both")
    assert "direction: both" in yaml_text(page)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved")
    clean(page)


def test_a_box_is_moved_by_its_header_and_the_layout_is_kept_in_the_yaml(stack, browser):
    page = sign_in(browser, "wes", viewport={"width": 1500, "height": 1500})
    page.goto(f"{CONSOLE_URL}/bi/m/{RISK}")
    page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
    page.click("[data-save-copy]")
    page.wait_for_url("**/bi/m/bi-**")
    page.wait_for_selector("[data-model-root][data-ready=yes]")
    head = page.locator('.bi-tbox[data-table="desks"] header')
    box = head.bounding_box()
    page.mouse.move(box["x"] + 30, box["y"] + 8)
    page.mouse.down()
    page.mouse.move(box["x"] + 130, box["y"] + 58, steps=5)
    page.mouse.up()
    assert re.search(r"layout:.*\n(?:\s+\w+: \{ x: \d+, y: \d+ \}\n)+", yaml_text(page)), yaml_text(page)[-300:]
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved")
    page.reload()
    page.wait_for_selector("[data-model-root][data-ready=yes]")
    moved = page.evaluate("[...document.querySelectorAll('.bi-tbox')].map(b => [b.getAttribute('data-table'), b.style.left, b.style.top])")
    assert any(m[0] == "desks" and m[1] != "" for m in moved)
    clean(page)


PROPOSAL_DEMO = """rachana: 1
dataset: proposal-demo
title: Proposal demo
tables:
  trades:
    source: { kind: trade }
    mode: cached
    columns: { DESK: string, MTM: decimal }
  desk_pnl:
    source: { kind: desk-pnl }
    mode: cached
    columns: { DESK: string }
measures:
  MTM: { expr: "sum(trades.MTM)" }
"""


def test_relationships_proposed_from_the_data_are_accepted_one_by_one(stack, browser):
    """Two tables with no declared link: the server finds that every value of trades.DESK is an id of desk-pnl (profiling, no AI) and proposes it with the match rate."""
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/designer")
    made = page.evaluate("""async (body) => { const r = await fetch('/bi/api/workspaces/me/items', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({kind: 'dataset', title: 'Proposal demo', description: '', body})}); return await r.json(); }""", PROPOSAL_DEMO)
    page.goto(f"{CONSOLE_URL}/bi/m/{made['id']}")
    page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
    expect(page.locator(".bi-tbox")).to_have_count(2)
    expect(page.locator("svg.bi-lines g[data-rel]")).to_have_count(0)
    expect(page.locator("[data-no-rels]")).to_be_visible()
    page.click("[data-infer]")
    page.wait_for_selector("[data-proposals] [data-proposal]", timeout=90000)
    mine = page.locator("[data-proposals] [data-proposal]").filter(has_text="trades.DESK")
    assert mine.count() == 1 and "100% of 10 values match" in mine.first.inner_text()                    # the match rate, from the data
    expect(page.locator("svg.bi-lines g[data-rel]")).to_have_count(0)                                    # proposing changes nothing
    assert "relationships:" not in yaml_text(page)
    mine.first.locator("[data-accept]").click()
    expect(page.locator("svg.bi-lines g[data-rel]")).to_have_count(1)
    assert "{ from: trades.DESK, to: desk_pnl.id, cardinality: many-to-one }" in yaml_text(page)
    page.wait_for_function("() => document.querySelector('[data-valid]').textContent === 'Valid'")
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved")
    clean(page)


def test_a_reader_cannot_use_the_model_view_but_sees_the_dataset_page(stack, browser):
    page = sign_in(browser, "rita")
    page.goto(f"{CONSOLE_URL}/bi/d/{RISK}")
    page.wait_for_selector("[data-relationships] tbody tr")
    expect(page.locator("[data-model-view]")).to_be_hidden()                                                # the link is for authors
    page = sign_in(browser, "wes")
    page.goto(f"{CONSOLE_URL}/bi/d/{RISK}")
    page.wait_for_selector("[data-relationships] tbody tr")
    expect(page.locator("[data-model-view]")).to_be_visible()
