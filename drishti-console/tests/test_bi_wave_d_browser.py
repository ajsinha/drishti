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

"""Wave D (BI_DESIGN.md section 12.2, 2.6.0), console lane, in Chromium over the real server and console (tests/bi_report_stack.py, security on, the small
lake of trade valuations, plus a model with two field parameters written in the stack's BI folder):

* field parameters in the Studio's wells: Build offers the model's field parameters (a parameter of fields in the axis well, one of measures in the values
  well), the canvas draws the default choice, and in the viewer the parameter slicer switches the column chart from DESK to PORTFOLIO and from MTM to
  NOTIONAL, each picture equal to an independent sum of the lake's rows;
* the statement designer: a page made a statement in Format gets the Paper tab; the paper is set with plain controls (written to `statement:`), and the
  statement is previewed page by page as the server lays it out (Page k of N on each sheet, the column titles on every page, the next page by button).

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os
from collections import defaultdict

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18798")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17798")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import chart, clean, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, trade  # noqa: E402

DAY = "2026-10-08"
STATE = {}
SHOTS = os.environ.get("DRISHTI_SHOTS", "")                       # a folder: the guides' pictures are taken there too (docs/guides/img/bi-wave-d)


def shoot(page, selector, name, max_height=760):
    """A picture of one region for the guides and the tutorial (only with DRISHTI_SHOTS set)."""
    if SHOTS:
        el = page.locator(selector).first
        el.scroll_into_view_if_needed()
        bb = el.bounding_box()
        if bb["height"] <= max_height:
            el.screenshot(path=os.path.join(SHOTS, name + ".jpg"), quality=86)
        else:
            page.screenshot(path=os.path.join(SHOTS, name + ".jpg"), quality=86, clip={"x": bb["x"], "y": bb["y"], "width": bb["width"], "height": max_height})

# a model over the sample's valuations with a field parameter of fields and one of measures (docs/guides/REPORTS.md section 21)
MODEL = """rachana: 2
expressions: 2
model: fp-desk
version: 1.0.0
title: Desk with field parameters
datasets:
  trade: { dataset: trades-basic-trade, role: fact }
measures:
  MTM:      { expr: "sum(trade.MTM)" }
  NOTIONAL: { expr: "sum(trade.NOTIONAL)" }
  Trades:   { expr: "count(trade)" }
parameters:
  breakdown: { kind: field, fields: [trade.DESK, trade.PORTFOLIO], default: trade.DESK, title: Break down by }
  metric:    { kind: field, fields: [MTM, NOTIONAL], default: MTM, title: Show }
"""


class WaveDStack(bi_report_stack.ReportStack):
    def prepare(self, work):
        models = work / "config" / "bi" / "models"
        models.mkdir(parents=True, exist_ok=True)
        (models / "fp-desk.model.yaml").write_text(MODEL)


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = WaveDStack(tmp_path_factory.mktemp("waved"), delta_root="").start()
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


def fetch(page, method, path, body=None):
    return page.evaluate("""([m, p, b]) => fetch(p, {method: m, credentials: 'same-origin', headers: {'Content-Type': 'application/json', Accept: 'application/json'},
                              body: b === null ? undefined : JSON.stringify(b)}).then(r => r.json().then(j => ({status: r.status, body: j})))""", [method, path, body])


def sums(key, value):
    """An independent sum of the lake's rows of the day (60 valuations): key -> total."""
    out = defaultdict(float)
    for i in range(60):
        t = trade(DAY, i)
        out[t[key]] += t[value]
    return dict(out)


def drawn(page, vis):
    c = chart(page, vis)
    return dict(zip(c["categories"], [float(v) for v in c["series"][0]["values"]])) if c and c["categories"] else {}


def model(page):
    return page.evaluate("window.drishtiBi.design.model")


def designed(page):
    page.wait_for_function("() => !document.querySelector('.bi-dvis.bi-vis-busy') && [...document.querySelectorAll('.bi-dvis')].every(v => v.querySelector('.bi-vis-ms') || v.querySelector('.bi-dropnote') "
                           "|| v.querySelector('.bi-vis-error') || v.classList.contains('bi-vis-failed'))", timeout=30000)


def deselect(page):
    page.click("[data-design-root] [data-title]")
    page.keyboard.press("Escape")
    page.wait_for_function("() => window.drishtiBi.design.selected === null")


def new_report(page, body, title):
    r = fetch(page, "POST", "/bi/api/workspaces/me/items", {"kind": "report", "title": title, "body": body})
    assert r["status"] in (200, 201), r
    return r["body"]["id"]


def open_designer(page, rid):
    page.goto(CONSOLE_URL + "/bi/design/" + rid)
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)


def test_the_studio_wells_offer_field_parameters_and_the_slicer_switches_the_chart(stack, browser):
    page = sign_in(browser, "wes")
    rid = new_report(page, "rachana: 1\nreport: fp-desk-report\ntitle: By the reader's choice\nmodel: fp-desk@1\npages:\n  - id: main\n    title: Main\n    visuals: []\n",
                     "By the reader's choice")
    open_designer(page, rid)
    deselect(page)
    page.click('.bi-vtype[data-type="column"]')
    page.wait_for_function("() => { const v = window.drishtiBi.design.sel(); return v && v.type === 'column'; }")
    page.click("[data-ptab='build']")
    # the axis well offers the field parameter of fields only; the values well the one of measures only
    axis_params = page.eval_on_selector_all('[data-add="axis"] optgroup[data-field-params] option', "os => os.map(o => o.value)")
    value_params = page.eval_on_selector_all('[data-add="values"] optgroup[data-field-params] option', "os => os.map(o => o.value)")
    assert axis_params == ["param.breakdown"] and value_params == ["param.metric"], (axis_params, value_params)
    page.select_option('[data-add="axis"]', "param.breakdown")
    page.select_option('[data-add="values"]', "param.metric")
    v = model(page)["pages"][0]["visuals"][0]
    assert v["wells"] == {"axis": ["param.breakdown"], "values": ["param.metric"]}, v
    expect(page.locator('[data-build] .bi-wchip-param')).to_have_count(2)
    designed(page)
    shoot(page, "[data-build]", "field-parameter-wells", 520)
    page.wait_for_function("() => { const el = document.querySelector('.bi-dvis .bi-chart'); return el && echarts.getInstanceByDom(el); }", timeout=20000)
    vid = v["id"]
    assert drawn(page, vid) == sums("desk", "mtm"), "the canvas draws the default choice: MTM by desk"
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved", timeout=20000)
    # the viewer (the author's own draft): the parameter slicer switches the field and the measure the chart draws
    page.goto(CONSOLE_URL + "/bi/r/" + rid)
    page.wait_for_selector("[data-ready=yes]", state="attached", timeout=30000)
    settled(page)
    assert drawn(page, vid) == sums("desk", "mtm")
    expect(page.locator('[data-param-bar] [data-param="breakdown"]')).to_be_visible(timeout=20000)
    page.select_option('[data-param-bar] [data-param="breakdown"]', "trade.PORTFOLIO")
    expect(page.locator('[data-chip="param"]')).to_contain_text("breakdown: trade.PORTFOLIO", timeout=15000)
    page.wait_for_function("(vis) => { const el = document.querySelector('[data-visual=\"' + vis + '\"] .bi-chart'); const c = el && echarts.getInstanceByDom(el);"
                           " return c && (c.getOption().xAxis[0].data || []).every(x => x.indexOf('DESK-') !== 0); }", arg=vid, timeout=30000)
    settled(page)
    assert drawn(page, vid) == sums("book", "mtm"), "DESK to PORTFOLIO"
    page.select_option('[data-param-bar] [data-param="metric"]', "NOTIONAL")
    expect(page.locator('[data-chip="param"]')).to_have_count(2, timeout=15000)
    target = sums("book", "notional")
    page.wait_for_function("([vis, total]) => { const el = document.querySelector('[data-visual=\"' + vis + '\"] .bi-chart'); const c = el && echarts.getInstanceByDom(el);"
                           " if (!c) return false; const s = c.getOption().series[0]; return s && s.data.map(d => d && typeof d === 'object' ? d.value : d).reduce((a, b) => a + Number(b), 0) === total; }",
                           arg=[vid, sum(target.values())], timeout=30000)
    assert drawn(page, vid) == target, "MTM to NOTIONAL"
    shoot(page, "[data-report]", "field-parameter-slicer", 620)
    assert page.locator(f'[data-visual="{vid}"] .bi-chart').count() == 1
    STATE["report"] = rid
    clean(page)
    page.context.close()


STATEMENT = """rachana: 1
report: desk-statement
title: Desk statement
model: fp-desk@1
statement:
  header: { left: "{title}", right: "For {reader}" }
pages:
  - id: summary
    title: Summary
    visuals:
      - { id: total, type: card, title: Notional, at: { x: 0, y: 0, w: 4, h: 2 }, wells: { values: [NOTIONAL] } }
  - id: positions
    title: Positions
    visuals:
      - { id: intro, type: text, at: { x: 0, y: 0, w: 12, h: 1 }, text: "Positions for {reader}", format: { size: heading } }
      - { id: rows, type: table, title: Positions, at: { x: 0, y: 1, w: 12, h: 8 }, wells: { columns: [trade.TRADE_ID, trade.DESK, trade.PORTFOLIO, NOTIONAL, MTM] } }
"""


def test_the_statement_designer_sets_the_paper_and_previews_page_by_page(stack, browser):
    page = sign_in(browser, "wes")
    rid = new_report(page, STATEMENT, "Desk statement")
    open_designer(page, rid)
    expect(page.locator('[data-tab="paper"]')).to_be_hidden()
    # the Positions page becomes a statement in Format (no visual selected): the Paper tab appears
    page.click('[data-ptabs] [data-page="positions"]')
    deselect(page)
    page.click("[data-ptab='format']")
    page.select_option("[data-page-layout]", "statement")
    assert next(p for p in model(page)["pages"] if p["id"] == "positions")["layout"] == "statement"
    expect(page.locator('[data-tab="paper"]')).to_be_visible()
    page.click('[data-tab="paper"]')
    area = page.locator("[data-st-preview-area]")
    expect(area).to_have_attribute("data-st-state", "ready", timeout=60000)
    pages = int(area.get_attribute("data-st-pages"))
    assert pages >= 2, "the 60 rows run over pages"
    sheet = page.locator("[data-st-sheet]")
    expect(sheet).to_have_attribute("data-st-page", "1")
    texts = page.eval_on_selector_all("[data-st-sheet] .bi-st-text", "els => els.map(e => e.textContent)")
    assert "Desk statement" in texts and "For wes" in texts and f"Page 1 of {pages}" in texts and "Positions for wes" in texts, texts
    page.click("[data-st-next]")
    expect(sheet).to_have_attribute("data-st-page", "2")
    texts = page.eval_on_selector_all("[data-st-sheet] .bi-st-text", "els => els.map(e => e.textContent)")
    assert f"Page 2 of {pages}" in texts and "TRADE_ID" in texts, "the column titles again on the next page"
    # the paper, with plain controls: landscape Letter, a footer of our own; the preview follows
    page.select_option("[data-st-paper]", "Letter")
    page.select_option("[data-st-orientation]", "landscape")
    page.fill('[data-st-footer="left"]', "Desk statement · {period}")
    page.locator('[data-st-footer="left"]').press("Tab")
    st = model(page)["statement"]
    assert st["paper"] == "Letter" and st["orientation"] == "landscape" and st["footer"]["left"] == "Desk statement · {period}", st
    page.click("[data-st-preview]")
    expect(area).to_contain_text("Letter landscape", timeout=60000)
    landscape = int(area.get_attribute("data-st-pages"))
    assert landscape > pages, "fewer rows to a landscape page"
    box = page.locator("[data-st-sheet]").bounding_box()
    assert box["width"] > box["height"], "a landscape sheet"
    shoot(page, '[data-view="paper"]', "statement-designer", 900)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved", timeout=20000)
    saved = fetch(page, "GET", "/bi/api/items/" + rid)
    assert "layout: statement" in saved["body"]["body"] and "orientation: landscape" in saved["body"]["body"], saved["body"]["body"]
    clean(page)
    page.context.close()
