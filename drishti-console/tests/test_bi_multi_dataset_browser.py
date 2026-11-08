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

"""BI over several facts (2.2: docs/guides/BI_GUIDE.md, docs/guides/BI_GUIDE.md, docs/guides/USER_GUIDE.md › Analyse: slice, SQL and Python, section 2), in Chromium over the real server and console
(tests/bi_report_stack.py on ports 18944 and 17944, security on, a small Delta lake: trades, counterparties and netting sets across three packs). The sample
"Desk P&L vs counterparty exposure" is answered three ways that must agree with an independent join of two model queries: the model trading-risk (two facts,
each aggregated alone and joined on the counterparty), the Python dataset desk-pnl-vs-exposure-py-result (a Transform over two model queries) and an Analyse
notebook that joins two extracts in pandas. Each input is asked as the reader (a desk head sees fewer counterparties, an analyst sees the name masked); the
sample report's sector slicer and donut filter every visual of the one model.

Skipped when Playwright, Chromium, uv (to make the lake; or DRISHTI_BI_DELTA_ROOT), the built server jar or pandas and pyarrow are missing, or a scratch port is in use. Never run
with pytest -n (the ports are fixed)."""
import os
import sys

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18944")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17944")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
pytest.importorskip("pandas")
pytest.importorskip("pyarrow")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import card_value, chart, clean, click_mark, grid_rows, open_report, pick_in_dropdown_slicer, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, ROOT, api  # noqa: E402

REPORT = "file:report:pnl-vs-exposure"
MODEL = "trading-risk@1"
PY_MODEL = "desk-pnl-vs-exposure-py@1"
PY_DATASET = "file:table:banking.desk_pnl_vs_exposure_py_result"   # the table the Python Transform makes (the table layer's, shipped in config/tables)


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        from bi_samples import copy_samples, shipped
        copy_samples(work / "config/bi", ["trading-risk", "counterparty-exposure", "desk-pnl-vs-exposure-py"])   # the models, their datasets, the Transform
        for folder, suffix, name in (("reports", "report", "pnl-vs-exposure"), ("notebooks", "notebook", "pnl-vs-exposure-pandas")):
            target = work / "config/bi" / folder
            target.mkdir(parents=True, exist_ok=True)
            (target / f"{name}.{suffix}.yaml").write_text(shipped(folder, f"{name}.{suffix}.yaml").read_text(encoding="utf-8"), encoding="utf-8")
        # the server runs Python on its compute pool python, in the interpreter that runs this test (it carries the pinned libraries)
        self.server_args += ["--DRISHTI_COMPUTE_ENABLED=true", f"--DRISHTI_PYTHON={sys.executable}", f"--drishti.compute.file={ROOT}/config/compute/compute.yaml"]


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    tmp = tmp_path_factory.mktemp("bimulti")
    lake = bi_report_stack.make_lake(tmp / "lake")
    if not lake:
        pytest.skip("needs a Delta lake: DRISHTI_BI_DELTA_ROOT, or uv with deltalake, pyarrow and pyyaml (tools/packgen/banking/make_data.py --lake)")
    try:
        s = Stack(tmp, delta_root=lake).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    st, r = bi_report_stack.admin("POST", "/admin/users", {"username": "rex", "displayName": "Rex", "email": "rex@desk.test", "desk": "", "roles": ["risk", "bi-writer"],
                                                           "packs": ["trading", "counterparty-risk", "market-risk"], "password": bi_report_stack.PASSWORD, "mustChangePassword": False})
    assert st in (200, 201), r      # rex reads every row (risk; the valuations are market-risk's kind since 2.3.0) and may run Python cells (bi-author)
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


def ask(user, roles, model, select, **more):
    """One model query as ``user``: ``select`` is {fields, measures}; ``more`` the other keys (where, shape, sort, limit)."""
    s, r = api("POST", "/bi/mq", user, roles, {"mq": 1, "model": model, "select": {"fields": [], "measures": [], **select}, **more})
    assert s == 200, r
    return r


RISK = ("rita", ["risk"])
HEAD = ("hana", ["desk-head"])
ANALYST = ("ann", ["analyst"])


def hand_join(who):
    """The truth: P&L and exposure asked as two separate model queries as the same reader, joined here by counterparty."""
    pnl = {r[0]: r[1] for r in ask(*who, MODEL, {"fields": ["counterparties.id"], "measures": ["P&L 1d", "Trades"]})["rows"] if r[2]}
    ee = {r[0]: r[1] for r in ask(*who, "counterparty-exposure@1", {"fields": ["counterparties.id"], "measures": ["Exposure (EE peak)"]})["rows"]}
    return pnl, ee


def py_rows(who):
    return ask(*who, PY_MODEL, {"fields": ["result.counterparty", "result.desk_pnl", "result.ee_peak"]}, shape="rows")["rows"]


# ---- three ways to the same table: the model's two facts, the Python dataset, and the join done by hand -------------------------------

def test_the_two_facts_of_the_model_and_the_python_dataset_equal_an_independent_join(stack):
    pnl, ee = hand_join(RISK)
    both = ask(*RISK, MODEL, {"fields": ["counterparties.id"], "measures": ["P&L 1d", "Exposure (EE peak)", "Trades"]})["rows"]
    seen = {r[0]: r for r in both if r[3]}
    assert set(seen) == set(pnl)                                                                          # each fact aggregated alone, joined on the counterparty
    for cp, row in seen.items():
        assert row[1] == pytest.approx(pnl[cp]) and (row[2] == pytest.approx(ee[cp]) if cp in ee else row[2] in (None, 0))
    made = py_rows(RISK)
    assert {r[0] for r in made} == set(pnl)                                                               # the Transform keeps the counterparties with P&L
    for cp, p, e in made:
        assert p == pytest.approx(pnl[cp]) and (e == pytest.approx(ee[cp]) if cp in ee else e in (None, 0))
    total = ask(*RISK, MODEL, {"measures": ["P&L 1d", "Exposure (EE peak)", "P&L per exposure"]})["rows"][0]
    assert total[2] == pytest.approx(total[0] / total[1])                                                 # a ratio of the two sums, after each sum


def test_each_input_is_asked_as_the_reader(stack):
    everyone = ask(*RISK, MODEL, {"fields": ["counterparties.id"], "measures": ["P&L 1d", "Trades"]})["rows"]
    mine = ask(*HEAD, MODEL, {"fields": ["counterparties.id"], "measures": ["P&L 1d", "Trades"]})["rows"]
    assert 0 < len([r for r in mine if r[2]]) < len([r for r in everyone if r[2]])                       # the desk head's row rule cut the trades
    assert len(py_rows(HEAD)) < len(py_rows(RISK))                                                        # and the Python table is made from her own answers
    masked = ask(*ANALYST, MODEL, {"fields": ["counterparties.id", "counterparties.NAME"], "measures": ["P&L 1d"]})
    assert {r[1] for r in masked["rows"]} == {"•••"} and "counterparties.NAME" in masked["meta"]["masked"]
    assert {r[0] for r in masked["rows"]} == {r[0] for r in everyone}                                     # the key (the counterparty id) is not masked: same rows


# ---- the sample report: the sector slicer and the donut filter every visual of the one model ----------------------------------------------

def test_the_sample_report_answers_as_the_model_and_its_slicer_filters_every_visual(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    total = ask(*RISK, MODEL, {"measures": ["P&L 1d", "Exposure (EE peak)"]})["rows"][0]
    assert card_value(page, "pnl") == int(total[0]) and card_value(page, "ee") == int(round(total[1]))
    sector = "Banking"
    pick_in_dropdown_slicer(page, "sector", sector)                       # the dropdown slicer: drishti-inputs' MultiSelect
    settled(page)
    want = ask(*RISK, MODEL, {"measures": ["P&L 1d", "Exposure (EE peak)"]}, where=[{"field": "counterparties.SECTOR", "op": "in", "values": [sector]}])["rows"][0]
    assert card_value(page, "pnl") == int(want[0]) and card_value(page, "ee") == int(round(want[1]))       # one choice reached both facts, through the counterparty
    assert round(sum(chart(page, "exposure-by-sector")["series"][0]["values"])) == int(round(want[1]))
    rows = grid_rows(page, "table")
    assert rows and all(row[1] == sector for row in rows)
    expect(page.locator('[data-chip="slicer"]')).to_contain_text(sector)
    clean(page)


SLICE = """(name) => {
  const el = document.querySelector('[data-visual="exposure-by-sector"] .bi-chart');
  const c = echarts.getInstanceByDom(el), data = c.getOption().series[0].data;
  const i = data.findIndex(d => d.name === name);
  const lay = c.getModel().getSeriesByIndex(0).getData().getItemLayout(i);                  // the slice, in the chart's own pixels
  const a = (lay.startAngle + lay.endAngle) / 2, rad = (lay.r0 + lay.r) / 2, r = el.getBoundingClientRect();
  return { x: r.left + lay.cx + rad * Math.cos(a), y: r.top + lay.cy + rad * Math.sin(a) };
}"""


def test_a_click_on_the_donut_cross_filters_the_other_fact(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    sectors = page.evaluate("() => echarts.getInstanceByDom(document.querySelector('[data-visual=\"exposure-by-sector\"] .bi-chart')).getOption().series[0].data.map(d => d.name)")
    assert len(sectors) >= 2
    sector = sectors[0]
    page.locator('[data-visual="exposure-by-sector"] .bi-chart').scroll_into_view_if_needed()
    at = page.evaluate(SLICE, sector)
    page.mouse.click(at["x"], at["y"])                                                                    # a click on the slice, as a person makes it
    settled(page)
    expect(page.locator('[data-chip="selection"]')).to_contain_text(sector)
    want = ask(*RISK, MODEL, {"measures": ["P&L 1d", "Exposure (EE peak)"]}, where=[{"field": "counterparties.SECTOR", "op": "in", "values": [sector]}])["rows"][0]
    assert card_value(page, "pnl") == int(want[0]) and card_value(page, "ee") == int(round(want[1]))       # the click on an exposure mark reached the trades' card
    bars = chart(page, "by-counterparty")
    assert 0 < len(bars["categories"]) <= 10
    clean(page)
    page.close()


# ---- the Python dataset's page says what makes it ----------------------------------------------------------------------------------------

def test_the_python_datasets_page_names_its_transform_and_its_inputs(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(f"{CONSOLE_URL}/bi/d/{PY_DATASET}")                                                         # the old link opens the dataset's own page (2.2)
    page.wait_for_url("**/bi/ds/**")
    page.wait_for_selector("[data-layer-dataset][data-ready=yes]", timeout=30000)
    expect(page.locator('[data-fact="source"]')).to_contain_text("the Python Transform pnl-vs-exposure")
    expect(page.locator("[data-yaml]")).to_contain_text('model: "trading-risk@1"')
    expect(page.locator("[data-yaml]")).to_contain_text('model: "counterparty-exposure@1"')
    clean(page)
    page.close()


# ---- the Notebook: two extracts in one notebook, a join cell for the server's compute pool ------------------------------------------------

def test_the_notebook_opens_two_extracts_and_its_join_cell_runs_on_the_compute_pool(stack, browser):
    page = sign_in(browser, "rex", "/bi/notebook")                                                         # rex reads every row and holds bi-author
    page.goto(f"{CONSOLE_URL}/bi/notebook?item=file:notebook:pnl-vs-exposure-pandas")
    page.wait_for_selector("[data-extra='exposure']", timeout=60000)
    page.wait_for_function("() => /rows/.test(document.querySelector('[data-info]').textContent)", timeout=60000)        # the first extract is open
    page.click('[data-tab="python"]')
    page.wait_for_selector('[data-cells=python] [data-run]')
    assert 'extract("exposure")' in page.locator('[data-cells=python] textarea').first.input_value()
    page.locator('[data-cells=python] [data-run]').first.click()                                             # the join cell runs on the server's compute pool
    expect(page.locator('[data-cells=python] [data-engine=python]').first).to_be_visible(timeout=120000)
    assert page.locator('[data-cells=python] [data-target=server]').count() >= 1
    expect(page.locator('[data-cells=python] [data-result] [role=grid]').first).to_be_visible()                # the result: drishti-grid
    assert page.locator('[data-promote="transform"]').count() == 1 and page.locator('[data-promote="transform"]').is_enabled()   # a cell that joins extracts can become a Transform
    clean(page)
    page.close()
