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

"""BI over several datasets (docs/guides/DATASETS.md section 11, docs/guides/REPORTS.md section 16, docs/guides/ANALYSE.md section 2, RUPAKA.md section 20.10), in Chromium
over the real server and console (tests/bi_report_stack.py on ports 18944 and 17944, security on, a small Delta lake: trades, counterparties and netting sets across three
packs). The sample "Desk P&L vs counterparty exposure" is built both ways, a composed dataset in YAML and a derived dataset made by a Python Transform, and both give the
numbers an independent join of the two source datasets gives; each source is asked as the reader (a desk head sees fewer counterparties, an analyst sees the name masked); the
sample report has a slicer and a donut that read the second dataset and filter the visuals of the composed one through a link; the Model view draws the composition (two
sources, the join, the table they make) and the Transform; Analyse joins two extracts of one notebook in pandas.

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
from bi_report_helpers import card_value, chart, clean, click_mark, open_report, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, ROOT, api  # noqa: E402

REPORT = "file:report:pnl-vs-exposure"
COMPOSED = "desk-pnl-vs-exposure"
DERIVED = "desk-pnl-vs-exposure-py"
FILES = {"datasets": ("dataset", ["counterparty-exposure", COMPOSED, DERIVED]), "reports": ("report", ["pnl-vs-exposure"]), "python": ("python", ["pnl-vs-exposure"]),
         "notebooks": ("notebook", ["pnl-vs-exposure-pandas"])}


class Stack(bi_report_stack.ReportStack):
    def prepare(self, work):
        for folder, (suffix, names) in FILES.items():
            target = work / "config/bi" / folder
            target.mkdir(parents=True, exist_ok=True)
            for n in names:
                (target / f"{n}.{suffix}.yaml").write_text((ROOT / "config/bi" / folder / f"{n}.{suffix}.yaml").read_text(encoding="utf-8"), encoding="utf-8")
        # the server runs Transforms in the console's sandboxed runner, started with the Python that runs this test (it has pandas and pyarrow)
        self.server_args += [f"--drishti.bi.transform.python={sys.executable}", f"--drishti.bi.transform.runner={ROOT}/drishti-console/core/transform_cli.py"]


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
                                                           "packs": ["trading", "counterparty-risk"], "password": bi_report_stack.PASSWORD, "mustChangePassword": False})
    assert st in (200, 201), r                                                                              # rex reads every row (risk) and may run Python cells (bi-author)
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


def ask(user, roles, dataset, body):
    s, r = api("POST", f"/bi/datasets/file:dataset:{dataset}/query", user, roles, body)
    assert s == 200, r
    return r


RISK = ("rita", ["risk"])
HEAD = ("hana", ["desk-head"])
ANALYST = ("ann", ["analyst"])


def hand_join(who):
    """The truth: the two source datasets asked separately as the same reader, joined here by counterparty."""
    pnl = {r[0]: r[1] for r in ask(*who, "trading-risk", {"fields": ["counterparties.id"], "measures": ["P&L 1d"]})["rows"]}
    ee = {r[0]: r[1] for r in ask(*who, "counterparty-exposure", {"fields": ["counterparties.id"], "measures": ["Exposure (EE peak)"]})["rows"]}
    return pnl, ee


# ---- the two ways of building the sample give one table, and it is the join of the two sources ---------------------------------------

def test_the_composed_and_the_derived_dataset_equal_an_independent_join(stack):
    pnl, ee = hand_join(RISK)
    composed = ask(*RISK, COMPOSED, {"fields": ["joined.counterparty"], "measures": ["Desk P&L", "Exposure (EE peak)"]})["rows"]
    assert {r[0] for r in composed} == set(pnl)                                                           # a left join: every counterparty with P&L
    for cp, p, e in composed:
        assert p == pytest.approx(pnl[cp]) and (e == pytest.approx(ee[cp]) if cp in ee else e is None)
    derived = ask(*RISK, DERIVED, {"fields": ["result.counterparty", "result.desk_pnl", "result.ee_peak"]})["rows"]
    assert sorted(derived, key=lambda r: r[0]) == sorted([[cp, p, e] for cp, p, e in composed], key=lambda r: r[0])        # Python and YAML make the same table
    total = ask(*RISK, COMPOSED, {"measures": ["Desk P&L", "Exposure (EE peak)", "P&L per exposure"]})["rows"][0]
    assert total[0] == pytest.approx(sum(pnl.values())) and total[2] == pytest.approx(total[0] / total[1])
    assert ask(*RISK, COMPOSED, {"measures": ["Desk P&L"]})["meta"]["layer"] == "composed"
    assert ask(*RISK, DERIVED, {"measures": ["Counterparties"]})["meta"]["layer"] == "derived"


def test_each_source_is_asked_as_the_reader_before_the_join(stack):
    everyone = ask(*RISK, COMPOSED, {"fields": ["joined.counterparty"], "measures": ["Desk P&L"]})["rows"]
    mine = ask(*HEAD, COMPOSED, {"fields": ["joined.counterparty"], "measures": ["Desk P&L"]})["rows"]
    assert 0 < len(mine) < len(everyone)                                                                  # the desk head's row rule cut the trades before the join
    assert sum(r[1] for r in mine) == pytest.approx(ask(*HEAD, "trading-risk", {"measures": ["P&L 1d"]})["rows"][0][0])
    masked = ask(*ANALYST, COMPOSED, {"fields": ["joined.counterparty", "joined.name"], "measures": ["Desk P&L"]})
    assert {r[1] for r in masked["rows"]} == {"•••"} and "joined.name" in masked["meta"]["masked"]       # the name is masked by its source and shown as masked here
    assert {r[0] for r in masked["rows"]} == {r[0] for r in everyone}                                     # the join key (the counterparty id) is not masked: same rows


def test_a_derived_dataset_is_made_for_each_reader_and_kept(stack):
    stats = api("GET", "/bi/engine", "drishti-dev-admin", ["admin"])[1]["transforms"]
    ask(*HEAD, DERIVED, {"fields": ["result.counterparty"]})
    again = api("GET", "/bi/engine", "drishti-dev-admin", ["admin"])[1]["transforms"]
    ask(*HEAD, DERIVED, {"measures": ["Desk P&L"]})                                                        # same reader, same inputs: kept
    after = api("GET", "/bi/engine", "drishti-dev-admin", ["admin"])[1]["transforms"]
    assert after["runs"] == again["runs"] and after["cacheHits"] >= again["cacheHits"] and again["runs"] >= stats["runs"]
    assert len(ask(*HEAD, DERIVED, {"fields": ["result.counterparty"]})["rows"]) < len(ask(*RISK, DERIVED, {"fields": ["result.counterparty"]})["rows"])


# ---- the sample report: a slicer and a donut of the second dataset filter the first through a link -------------------------------------

def test_the_sample_report_filters_one_dataset_by_a_choice_made_on_another(stack, browser):
    page = sign_in(browser, "rita")
    open_report(page, REPORT)
    settled(page)
    pnl, ee = hand_join(RISK)
    assert card_value(page, "pnl") == int(sum(pnl.values())) and card_value(page, "ee") == int(round(sum(ee.values())))
    sector = "Banking"
    page.click('[data-visual="sector"] summary')
    page.check(f'[data-visual="sector"] [data-slicer-value="{sector}"]')
    settled(page)
    want = ask(*RISK, COMPOSED, {"measures": ["Desk P&L", "Exposure (EE peak)"], "filters": [{"field": "joined.sector", "op": "in", "values": [sector]}]})["rows"][0]
    assert card_value(page, "pnl") == int(want[0]) and card_value(page, "ee") == int(round(want[1]))       # the slicer reads counterparty-exposure; the cards read the composed dataset
    own = ask(*RISK, "counterparty-exposure", {"measures": ["Exposure (EE peak)"], "filters": [{"field": "counterparties.sector", "op": "in", "values": [sector]}]})["rows"][0][0]
    assert round(sum(chart(page, "exposure-by-sector")["series"][0]["values"])) == int(round(own))        # the donut (same dataset as the slicer) is filtered natively
    assert want[1] == pytest.approx(own)                                                                  # the link and the dataset's own field agree
    rows = page.locator('[data-visual="table"] tbody tr')
    assert rows.count() > 0 and all(r.inner_text().split("\t")[1] == sector for r in rows.all())
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


def test_a_click_on_the_donut_of_one_dataset_cross_filters_the_other(stack, browser):
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
    want = ask(*RISK, COMPOSED, {"measures": ["Desk P&L", "Exposure (EE peak)"], "filters": [{"field": "joined.sector", "op": "in", "values": [sector]}]})["rows"][0]
    assert card_value(page, "pnl") == int(want[0]) and card_value(page, "ee") == int(round(want[1]))       # the click on a counterparty-exposure mark reached the composed dataset's cards
    bars = chart(page, "by-counterparty")
    assert 0 < len(bars["categories"]) <= 10
    clean(page)
    page.close()


# ---- the Model view draws the composition and the Transform ----------------------------------------------------------------------------

def test_the_model_view_draws_the_sources_the_join_and_the_table_they_make(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(f"{CONSOLE_URL}/bi/m/file:dataset:{COMPOSED}")
    page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
    expect(page.locator(".bi-tbox")).to_have_count(3)
    assert [b.get_attribute("data-table") for b in page.locator(".bi-tbox").all()] == ["pnl", "exposure", "joined"]
    assert "trading-risk" in page.locator('.bi-tbox[data-table="pnl"] header').inner_text()
    cols = page.eval_on_selector_all('.bi-tbox[data-table="joined"] [data-column]', "els => els.map(e => e.getAttribute('data-column'))")
    assert "joined.counterparty" in cols and "joined.Desk_P_L" in cols and "joined.Exposure_EE_peak" in cols
    assert page.locator('.bi-lines g[data-rel] text', has_text="left").count() == 1                       # the join, labelled with its type
    assert page.locator(".bi-lines .bi-line-feed").count() == 2                                           # each source feeds the table
    assert "left join" in page.locator("[data-rels]").inner_text()
    expect(page.locator("[data-add-wrap]")).to_be_hidden()                                                 # the joins are written in the YAML, not drawn into it
    clean(page)
    page.goto(f"{CONSOLE_URL}/bi/m/file:dataset:{DERIVED}")
    page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
    assert [b.get_attribute("data-table") for b in page.locator(".bi-tbox").all()] == ["pnl", "exposure", "transform", "result"]
    assert "pnl-vs-exposure" in page.locator('.bi-tbox[data-table="transform"]').inner_text()
    clean(page)
    page.close()


def test_the_dataset_page_lists_the_sources_and_an_author_runs_the_transform(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(f"{CONSOLE_URL}/bi/d/file:dataset:{DERIVED}")
    page.wait_for_selector("[data-built-sec]:not([hidden])", timeout=30000)
    assert page.locator("[data-built] tbody tr").count() == 2 and "trading-risk" in page.locator("[data-built]").inner_text()
    page.click("[data-run-transform]")
    page.wait_for_function("() => /rows/.test(document.querySelector('[data-run-result]').textContent)", timeout=60000)
    assert "pnl_per_exposure" in page.locator("[data-run-result]").inner_text()
    clean(page)
    page.close()


# ---- Analyse: two extracts in one notebook, joined in pandas ----------------------------------------------------------------------------

def test_analyse_joins_two_extracts_of_a_notebook_in_pandas(stack, browser):
    page = sign_in(browser, "rex", "/bi/explore")                                                          # rex reads every row and holds bi-author: Python cells run for authors
    page.goto(f"{CONSOLE_URL}/bi/explore?item=file:notebook:pnl-vs-exposure-pandas")
    page.wait_for_selector("[data-extra='exposure']", timeout=60000)
    page.wait_for_function("() => /rows/.test(document.querySelector('[data-info]').textContent)", timeout=60000)        # the first extract is open
    page.click('[data-tab="python"]')
    page.wait_for_selector('[data-cells=python] [data-run]')
    page.click('[data-cells=python] [data-run]')
    page.wait_for_function("() => { const o = document.querySelector('[data-cells=python] .bx-out'); return o && o.textContent && !/Running/.test(o.textContent); }", timeout=90000)
    out = page.locator('[data-cells=python] .bx-out').inner_text()
    assert page.locator('[data-cells=python] .bx-out table').count() == 1, out
    head = page.locator('[data-cells=python] .bx-out thead th').all_inner_texts()
    assert head == ["counterparty", "desk_pnl", "ee_peak", "pnl_per_exposure"]
    first = page.locator('[data-cells=python] .bx-out tbody tr').first.inner_text().split("\t")
    pnl, ee = hand_join(("rex", ["risk", "bi-writer"]))
    assert first[0] in ee and float(first[1]) == pytest.approx(pnl[first[0]], rel=1e-6) and float(first[2]) == pytest.approx(ee[first[0]], rel=1e-6)
    assert page.locator('[data-promote="transform"]').count() == 1                                         # a cell that joins extracts can become a Transform
    clean(page)
    page.close()
