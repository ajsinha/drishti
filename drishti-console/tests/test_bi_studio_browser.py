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

"""Wave C acceptance (BI_DESIGN.md section 12.2, 2.4.0): a Power BI author builds the parity task list (docs/archive/BI_PARITY_PLAN.md section 4, items 6 to 8 and 13) in BI
Studio, in Chromium over the real server and console (tests/bi_report_stack.py, security on, the small lake of trade valuations), and never opens the YAML tab:

* a P&L matrix by desk and portfolio across currencies, with subtotals at every level, opening at the first level, a colour scale on MTM and icons on
  Notional (conditional formatting depth, matrix depth);
* an MTM chart in small multiples by currency, with a subtitle, a border and no gridlines (format pane depth), a tooltip page over its bars and, through Edit
  interactions, no effect on the matrix;
* a KPI card, a currency slicer in a sync group with the one on a second page, and the shipped Corporate theme (themes as items);
* then the report is viewed: the matrix opens and closes level by level and is coloured, the small multiples are drawn, a selection filters the card but not
  the matrix, the tooltip page opens over a bar filtered to it, and a choice in one slicer of the sync group holds on the other page;
* and a theme is made in the theme designer, saved and published, and offered by the report designer's theme picker.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import os

import pytest

os.environ.setdefault("DRISHTI_BI_REPORT_SERVER_PORT", "18958")
os.environ.setdefault("DRISHTI_BI_REPORT_CONSOLE_PORT", "17958")
pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_report_stack  # noqa: E402
from bi_report_helpers import CHART, card_value, clean, settled, sign_in  # noqa: E402
from bi_report_stack import CONSOLE_URL, trade  # noqa: E402

DAY = "2026-10-08"
STATE = {}

# Where the bar of a category is, in whichever small chart has one (a small multiple without the category has no bar there).
MARK = """([vis, category]) => {
  const el = document.querySelector('[data-visual="' + vis + '"] .bi-chart');
  const c = echarts.getInstanceByDom(el), o = c.getOption();
  for (let k = 0; k < o.series.length; k++) {
    const axis = o.xAxis[o.series[k].xAxisIndex || 0];
    const i = (axis.data || []).indexOf(category);
    if (i < 0) { continue; }
    const lay = c.getModel().getSeriesByIndex(k).getData().getItemLayout(i);
    if (!lay || !isFinite(lay.x) || !isFinite(lay.y) || !lay.height) { continue; }
    const r = el.getBoundingClientRect();
    return { x: r.left + lay.x + lay.width / 2, y: r.top + lay.y + lay.height / 2 };
  }
  return null;
}"""


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_report_stack.SERVER_PORT, bi_report_stack.CONSOLE_PORT):
        if bi_report_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_report_stack.ReportStack(tmp_path_factory.mktemp("bistudio"), delta_root="").start()
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


def day_trades():
    return [trade(DAY, i) for i in range(60)]


def model(page):
    return page.evaluate("window.drishtiBi.design.model")


def designed(page):
    """Every visual on the designer's canvas has answered (or says what it still needs)."""
    page.wait_for_function("() => !document.querySelector('.bi-dvis.bi-vis-busy') && [...document.querySelectorAll('.bi-dvis')].every(v => v.querySelector('.bi-vis-ms') || v.querySelector('.bi-dropnote') "
                           "|| v.querySelector('.bi-vis-error') || v.classList.contains('bi-vis-failed'))", timeout=30000)


def deselect(page):
    """Nothing selected (Escape, with the focus off any field): the next type chosen in Visualizations adds a visual, and Format shows the page and the report."""
    page.click("[data-design-root] [data-title]")
    page.keyboard.press("Escape")
    page.wait_for_function("() => window.drishtiBi.design.selected === null")


def add(page, vtype):
    """A new visual of a type from the Visualizations pane, then its Build tab."""
    deselect(page)
    before = len(model(page)["pages"])
    page.click(f'.bi-vtype[data-type="{vtype}"]')
    page.wait_for_function("(t) => { const v = window.drishtiBi.design.sel(); return v && v.type === t; }", arg=vtype)
    assert len(model(page)["pages"]) == before
    page.click("[data-ptab='build']")


def fill(page, sel, value):
    page.fill(sel, value)
    page.locator(sel).press("Tab")


def test_a_power_bi_author_builds_the_desk_pack_without_yaml(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/designer")
    page.wait_for_selector("[data-new-dataset] option[value='mq:trades-basic@1']", state="attached")
    page.select_option("[data-new-dataset]", "mq:trades-basic@1")
    page.fill("[data-new-title]", "Desk pack")
    page.click("[data-new] button[type=submit]")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    STATE["report"] = page.url.rsplit("/", 1)[1]
    # BI Studio: the item is open in the tabs and listed in the Explorer
    expect(page.locator(f'[data-studio-tab="{STATE["report"]}"]')).to_have_count(1)
    expect(page.locator(f'[data-studio-item="{STATE["report"]}"]')).to_have_count(1)

    # 1. the P&L matrix: desk and portfolio down, currency across, MTM and Notional; subtotals at every level, opening at the first level
    add(page, "matrix")
    for well, item in (("rows", "trade.DESK"), ("rows", "trade.PORTFOLIO"), ("columns", "trade.CURRENCY"), ("values", "MTM"), ("values", "Notional")):
        page.select_option(f'[data-add="{well}"]', item)
    page.click("[data-ptab='format']")
    page.check("[data-format-subtotals]")
    fill(page, "[data-format-expand-to]", "0")                                   # the first level only: the desks
    page.select_option("[data-add-rule-kind]", "gradient")                         # a colour scale on MTM
    page.click("[data-add-rule]")
    page.select_option("[data-add-rule-kind]", "icons")                            # icons on Notional
    page.click("[data-add-rule]")
    page.select_option('[data-rule="1"] [data-rule-apply]', "Notional")
    m = model(page)["pages"][0]["visuals"][0]
    assert m["type"] == "matrix" and m["format"]["subtotals"] is True and m["format"]["expandTo"] == 0
    assert [r["type"] for r in m["format"]["rules"]] == ["gradient", "icons"] and m["format"]["rules"][1]["apply"] == "Notional"
    designed(page)
    matrix = page.locator('[data-visual="matrix-1"]')
    expect(matrix.locator("tr[data-subtotal]")).to_have_count(3)                    # a subtotal line per desk
    assert matrix.locator("td[data-cf-bg]").count() > 0 and matrix.locator("[data-cf-icon]").count() > 0

    # 2. the MTM chart in small multiples by currency, formatted, with a tooltip page, and leaving the matrix alone
    add(page, "column")
    for well, item in (("axis", "trade.DESK"), ("values", "MTM"), ("split", "trade.CURRENCY")):
        page.select_option(f'[data-add="{well}"]', item)
    page.click("[data-ptab='format']")
    fill(page, "[data-format-multiples-columns]", "3")
    fill(page, "[data-format-subtitle]", "By desk, one chart per currency")
    page.check("[data-format-border]")
    page.uncheck("[data-format-value-axis-gridlines]")
    page.click("[data-new-tooltip-page]")
    page.select_option('[data-interaction="matrix-1"]', "none")
    m = model(page)
    col = next(v for v in m["pages"][0]["visuals"] if v["id"] == "column-1")
    assert col["wells"]["split"] == ["trade.CURRENCY"] and col["format"]["multiples"] == {"columns": 3} and col["format"]["border"] is True
    assert col["format"]["valueAxis"] == {"gridlines": False} and col["tooltip"] == "tip1"
    assert m["pages"][0]["interactions"] == {"column-1": {"matrix-1": "none"}}
    assert any(p["id"] == "tip1" and p.get("tooltip") for p in m["pages"])
    designed(page)
    expect(page.locator('[data-visual="column-1"] .bi-chart[data-multiples="3"]')).to_have_count(1)
    expect(page.locator('[data-visual="column-1"] [data-subtitle]')).to_have_text("By desk, one chart per currency")

    # 3. a KPI card and a currency slicer in a sync group
    add(page, "card")
    page.select_option('[data-add="values"]', "Trades")
    add(page, "slicer")
    page.select_option('[data-add="field"]', "trade.CURRENCY")
    page.click("[data-ptab='format']")
    fill(page, "[data-format-sync-group]", "ccy")

    # 4. the report's theme: the shipped Corporate one
    deselect(page)
    page.click("[data-ptab='format']")
    page.wait_for_selector("[data-report-theme] option[value='corporate']", state="attached")
    page.select_option("[data-report-theme]", "corporate")
    page.wait_for_function("() => window.drishtiBi.design.theme && window.drishtiBi.design.theme.name === 'corporate'")
    assert model(page)["theme"] == {"use": "corporate"}

    # 5. the tooltip page gets a card; a second page gets a slicer of the same sync group and a card
    page.click('[data-ptabs] [data-page="tip1"]')
    add(page, "card")
    page.select_option('[data-add="values"]', "Trades")
    page.click("[data-add-page]")
    second = model(page)["pages"][-1]["id"]
    add(page, "slicer")
    page.select_option('[data-add="field"]', "trade.CURRENCY")
    page.click("[data-ptab='format']")
    fill(page, "[data-format-sync-group]", "ccy")
    add(page, "card")
    page.select_option('[data-add="values"]', "MTM")
    STATE["second"] = second

    # the server's Check in the Studio's Problems: no error; then Save. The YAML tab was never opened.
    page.click('[data-dock-tab="problems"]')
    page.wait_for_function("() => { const p = document.querySelector('[data-dock-pane=\"problems\"]'); return p && /no problems|0 errors/i.test(p.textContent); }", timeout=20000)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved", timeout=20000)
    assert page.evaluate("document.querySelector('[data-tab=\"yaml\"]').getAttribute('aria-selected')") == "false"
    clean(page)


def test_the_viewer_draws_the_desk_pack(stack, browser):
    page = sign_in(browser, "wes")
    page.goto(f"{CONSOLE_URL}/bi/r/{STATE['report']}")
    page.wait_for_selector("[data-ready=yes]", timeout=30000)
    settled(page)
    rows = day_trades()
    assert page.locator("[data-report]").get_attribute("data-theme-name") == "corporate"
    # the matrix: three desks, opening at the first level; Expand all opens the portfolios, Collapse all closes them; coloured and with icons
    matrix = page.locator('[data-visual="matrix-1"]')
    expect(matrix.locator("tr[data-subtotal]")).to_have_count(3)
    expect(matrix.locator("tr[data-row]")).to_have_count(0)
    matrix.locator("[data-expand-all]").click()
    expect(matrix.locator("tr[data-row]")).to_have_count(len({(t["desk"], t["book"]) for t in rows}))
    matrix.locator("[data-collapse-all]").click()
    expect(matrix.locator("tr[data-row]")).to_have_count(0)
    assert matrix.locator("td[data-cf-bg]").count() > 0 and matrix.locator("[data-cf-icon]").count() > 0
    # the small multiples: one small chart per currency
    expect(page.locator('[data-visual="column-1"] .bi-chart[data-multiples="3"]')).to_have_count(1)
    assert card_value(page, "card-1") == len(rows)
    # a selection on the chart filters the card, and (Edit interactions: none) not the matrix
    page.locator('[data-visual="column-1"] .bi-chart').scroll_into_view_if_needed()
    at = page.evaluate(MARK, ["column-1", "DESK-FX"])
    assert at, page.evaluate(CHART, "column-1")
    page.mouse.move(at["x"], at["y"])
    tip = page.locator('.bi-tip-pop[data-tooltip-page="tip1"]')                    # the tooltip page over the hovered bar, filtered to it
    expect(tip).to_be_visible(timeout=15000)
    page.wait_for_function("() => { const v = document.querySelector('.bi-tip-pop [data-visual=\"card-1\"] .bi-card-value'); return v && v.getAttribute('data-value') !== ''; }", timeout=20000)
    assert int(float(tip.locator('[data-visual="card-1"] .bi-card-value').get_attribute("data-value"))) == len([t for t in rows if t["desk"] == "DESK-FX"])
    page.mouse.click(at["x"], at["y"])
    expect(page.locator('[data-chip="selection"]')).to_have_count(1)
    page.mouse.move(5, 5)                                                          # the pointer leaves the bar: the tooltip page closes
    expect(tip).to_have_count(0, timeout=10000)
    settled(page)
    assert card_value(page, "card-1") == len([t for t in rows if t["desk"] == "DESK-FX"])
    expect(matrix.locator("tr[data-subtotal]")).to_have_count(3)
    # a slicer's choice holds on the other page through its sync group
    page.keyboard.press("Escape")
    page.click('[data-visual="slicer-1"] [data-slicer-value="USD"]')
    settled(page)
    page.click(f'[data-pages] [data-page="{STATE["second"]}"]')
    settled(page)
    expect(page.locator('[data-visual="slicer-1"] [data-slicer-value="USD"]')).to_be_checked()
    usd = sum(t["mtm"] for t in rows if t["currency"] == "USD")
    assert card_value(page, "card-1") == usd
    # the tooltip page is no tab
    expect(page.locator('[data-pages] [data-page="tip1"]')).to_have_count(0)
    clean(page)


def test_a_theme_is_made_published_and_offered_to_reports(stack, browser):
    # the desk's shared workspace (the setting up, not the authoring): a theme made in the Studio goes there, so it can be published for others
    s, ws = bi_report_stack.api("POST", "/bi/workspaces", "wes", ["bi-writer"], {"name": "Desk themes", "description": "the desk's look"})
    assert s in (200, 201), ws
    s, _ = bi_report_stack.api("PUT", "/bi/workspaces/" + ws["id"], "wes", ["bi-writer"], {"reviewRequired": False})
    assert s == 200
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/design/theme/new")
    page.wait_for_selector("[data-theme-root][data-ready=yes]", timeout=30000)
    fill(page, "[data-theme-key]", "desk-blue")
    fill(page, "[data-theme-title]", "Desk blue")
    page.locator('[data-theme-colour="0"]').evaluate("(el) => { el.value = '#0b3d91'; el.dispatchEvent(new Event('change')); }")
    page.click("[data-theme-add-colour]")
    page.click('[data-theme-family="matrix"] > summary')
    page.check('[data-theme-family="matrix"] [data-format-row-banding]')
    expect(page.locator("[data-theme-valid]")).to_have_text("The theme is valid.", timeout=15000)
    expect(page.locator('[data-theme-preview] [data-theme-sample="column"] .bi-chart')).to_have_count(1)
    page.click("[data-save]")
    expect(page.locator("[data-state]")).to_have_text("Saved", timeout=20000)
    page.click("[data-publish]")
    expect(page.locator("[data-theme-root] .bi-near").filter(has_text="Published: reports that name desk-blue")).to_have_count(1, timeout=20000)
    # the report designer's theme picker offers it
    page.goto(f"{CONSOLE_URL}/bi/design/{STATE['report']}")
    page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    deselect(page)
    page.click("[data-ptab='format']")
    page.wait_for_selector("[data-report-theme] option[value='desk-blue']", state="attached", timeout=15000)
    clean(page)
