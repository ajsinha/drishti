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

"""Makes the pictures and the captured output of docs/tutorials/10-first-bi-report.md, BI_GUIDE.md and REPORTS.md from the real stack (not a test: run it by hand).

    drishti-console/.venv/bin/python drishti-console/tests/bi_report_shots.py <pictures dir> <captures.json>

Ports 18990 (server) and 17990 (console), a scratch directory, the three sample datasets and the three sample reports over a small Delta lake (made with
tools/packgen/banking/make_data.py --lake, or DRISHTI_BI_DELTA_ROOT). Pictures are desktop (1600 px: the top bar needs it) and the phone (390 px). It builds the tutorial's report
from scratch with the designer, so the pictures show the real steps."""
import json
import sys
import tempfile
from pathlib import Path

from playwright.sync_api import sync_playwright

import bi_report_stack
from bi_report_helpers import chart, click_mark, open_report, settled, sign_in
from bi_report_stack import CONSOLE_URL, api

OVERVIEW = "file:report:trades-overview"
COUNTERPARTY = "file:report:counterparty-risk"
LADDER = "file:report:cashflow-ladder"
RISK = "file:dataset:trading-risk"


def settled_design(page):
    page.wait_for_function("() => !document.querySelector('.bi-dvis.bi-vis-busy') && [...document.querySelectorAll('.bi-dvis')].every(v => v.querySelector('.bi-vis-ms') || v.querySelector('.bi-dropnote') || v.querySelector('.bi-vis-error') || v.classList.contains('bi-vis-failed') || v.querySelector('.bi-text,.bi-button,.bi-picture'))", timeout=30000)


def shot(page, selector, path):
    page.locator(selector).first.screenshot(path=str(path))


def shot_full(page, selector, path):
    """A region of the whole page at its natural layout (the panes are sticky: an element screenshot would scroll them out of place)."""
    page.evaluate("window.scrollTo(0, 0)")
    bb = page.locator(selector).first.bounding_box()
    page.screenshot(path=str(path), full_page=True, clip={"x": bb["x"], "y": bb["y"], "width": bb["width"], "height": bb["height"]})


def cell(page, cx, cy):
    """A point in the canvas that falls in grid cell (cx, cy): where to drop a field."""
    w = page.evaluate("document.querySelector('[data-canvas-grid]').clientWidth")
    return {"x": cx * ((w - 110) / 12 + 10) + 20, "y": cy * 66 + 20}


def main(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got = {}
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        lake = bi_report_stack.make_lake(tmp / "lake")
        assert lake, "needs a Delta lake: DRISHTI_BI_DELTA_ROOT, or uv with deltalake"
        stack = bi_report_stack.ReportStack(tmp, delta_root=lake).start()
        try:
            with sync_playwright() as p:
                browser = p.chromium.launch()

                # ---- the viewer: Trades overview ----------------------------------------------------------------------------------------
                page = sign_in(browser, "rita")
                open_report(page, OVERVIEW)
                settled(page)
                page.wait_for_timeout(600)
                shot(page, ".bi-canvas", out / "viewer-overview.png")
                bodies = []
                page.on("request", lambda r: bodies.append(json.loads(r.post_data)) if "/reports/" in r.url and r.method == "POST" and r.post_data else None)
                click_mark(page, "by-desk", "DESK-COMM")
                settled(page)
                page.wait_for_timeout(600)
                shot(page, ".bi-canvas", out / "viewer-select.png")
                got["select_requests"] = [b for b in bodies if b["visual"] in ("mtm", "by-date")][:2]
                page.click('[data-visual="by-desk"] [data-act="drill-down"]')
                settled(page)
                page.wait_for_timeout(600)
                shot(page, '[data-visual="by-desk"]', out / "viewer-drilldown.png")
                page.click('[data-visual="by-desk"] [data-act="rows"]')
                page.wait_for_selector('[data-visual="by-desk"] table tbody tr')
                shot(page, '[data-visual="by-desk"]', out / "viewer-rows.png")
                got["rows_request"] = [b for b in bodies if b["visual"] == "by-desk"][-1]
                got["overview_values"] = {v: page.locator(f'[data-visual="{v}"] .bi-card-value').inner_text() for v in ("trades", "notional", "mtm", "average")}
                page.context.close()

                # ---- Counterparty risk: slicers, drill-through, rows and masks -----------------------------------------------------------------
                page = sign_in(browser, "rita")
                open_report(page, COUNTERPARTY)
                settled(page)
                page.wait_for_timeout(600)
                shot(page, ".bi-canvas", out / "viewer-counterparty.png")
                name = chart(page, "by-counterparty")["categories"][0]
                click_mark(page, "by-counterparty", name)
                page.wait_for_timeout(500)
                shot(page, '[data-visual="by-counterparty"]', out / "viewer-mark.png")
                page.click('[data-visual="by-counterparty"] [data-act="drill-through"]')
                page.wait_for_url("**/counterparty?carry=**")
                settled(page)
                page.wait_for_timeout(600)
                shot(page, ".bi-canvas", out / "viewer-drillthrough.png")
                got["drillthrough_url"] = page.url.replace(CONSOLE_URL, "")
                got["counterparty"] = name
                page.context.close()
                for user, label in (("hana", "deskhead"), ("ann", "analyst")):
                    page = sign_in(browser, user)
                    open_report(page, COUNTERPARTY)
                    settled(page)
                    page.wait_for_timeout(600)
                    shot(page, ".bi-canvas", out / f"viewer-counterparty-{label}.png")
                    got[f"failed_{label}"] = page.evaluate("[...document.querySelectorAll('.bi-vis-failed')].map(v => v.getAttribute('data-visual') + ': ' + v.querySelector('.bi-vis-error').textContent.slice(0, 220))")
                    if label == "analyst":
                        got["analyst_bars"] = chart(page, "by-counterparty")["categories"]
                    page.context.close()

                # ---- Cashflow ladder ------------------------------------------------------------------------------------------------------
                page = sign_in(browser, "rita")
                open_report(page, LADDER)
                settled(page)
                year = chart(page, "by-pay-date")["categories"][1]
                click_mark(page, "by-pay-date", year)
                page.click('[data-visual="by-pay-date"] [data-act="drill-down"]')
                settled(page)
                page.wait_for_timeout(600)
                shot(page, ".bi-canvas", out / "viewer-ladder.png")
                page.click('[data-pages] [data-page="legs"]')
                settled(page)
                page.wait_for_timeout(500)
                shot(page, '[data-visual="leg-matrix"]', out / "viewer-matrix.png")
                page.context.close()

                # ---- the phone --------------------------------------------------------------------------------------------------------------
                page = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
                open_report(page, OVERVIEW)
                settled(page)
                page.wait_for_timeout(600)
                bb = page.locator("[data-title]").bounding_box()
                page.screenshot(path=str(out / "viewer-phone-390.png"), full_page=True, clip={"x": 0, "y": max(0, bb["y"] - 20), "width": 390, "height": 1700})
                page.context.close()

                # ---- the designer: a report from scratch ---------------------------------------------------------------------------------------
                page = sign_in(browser, "wes", viewport={"width": 1600, "height": 1000})
                page.goto(CONSOLE_URL + "/bi/designer")
                page.wait_for_selector("[data-new-dataset] option[value='file:dataset:trades-basic']", state="attached")
                page.select_option("[data-new-dataset]", "file:dataset:trades-basic")
                page.fill("[data-new-title]", "Desk overview")
                shot_full(page, ".bi-canvas", out / "design-start.png")
                page.click("[data-new] button[type=submit]")
                page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
                page.wait_for_timeout(500)
                shot_full(page, ".bi-design", out / "design-empty.png")
                page.drag_and_drop('[data-field="MTM"]', "[data-canvas-grid]", target_position=cell(page, 0, 0))
                page.wait_for_selector(".bi-card-value")
                page.wait_for_timeout(500)
                shot_full(page, ".bi-design", out / "design-card.png")
                page.drag_and_drop('[data-field="Notional"]', "[data-canvas-grid]", target_position=cell(page, 3, 0))
                page.wait_for_selector('.bi-dvis[data-visual="card-2"] .bi-card-value')
                page.drag_and_drop('[data-field="trade.desk"]', "[data-canvas-grid]", target_position=cell(page, 0, 2))
                page.wait_for_selector('.bi-dvis[data-visual="table-1"]')
                page.drag_and_drop('[data-field="MTM"]', '.bi-dvis[data-visual="table-1"]')
                page.wait_for_selector('.bi-dvis[data-visual="bar-1"]')
                page.select_option('[data-add="values"]', "Notional")
                settled_design(page)
                page.wait_for_timeout(500)
                shot_full(page, ".bi-design", out / "design-bar.png")
                page.drag_and_drop('[data-field="trade.tradeDate"]', "[data-canvas-grid]", target_position=cell(page, 6, 2))
                page.wait_for_selector('.bi-dvis[data-visual="table-1"]')
                page.drag_and_drop('[data-field="Notional"]', '.bi-dvis[data-visual="table-1"]')
                page.wait_for_selector('.bi-dvis[data-visual="line-1"]')
                settled_design(page)
                page.click("[data-canvas-grid]", position=cell(page, 9, 0))                                                 # an empty corner: let go of the selection
                page.click(".bi-vtype[data-type='slicer']")
                page.wait_for_selector('.bi-dvis[data-type="slicer"]')
                page.select_option('[data-add="field"]', "trade.currency")
                page.click("[data-ptab='format']")
                page.select_option("[data-format-mode]", "dropdown")
                settled_design(page)
                handle = page.locator('.bi-dvis[data-type="slicer"] .bi-dhandle')
                bb = handle.bounding_box()
                target = page.locator("[data-canvas-grid]").bounding_box()
                dest = cell(page, 9, 0)
                page.mouse.move(bb["x"] + 40, bb["y"] + 10)
                page.mouse.down()
                page.mouse.move(target["x"] + dest["x"] + 40, target["y"] + dest["y"] - 10, steps=10)
                page.mouse.up()
                settled_design(page)
                page.wait_for_timeout(500)
                shot_full(page, ".bi-design", out / "design-format.png")
                page.click("[data-check]")
                page.wait_for_selector("[data-check-list] li")
                page.wait_for_timeout(300)
                shot_full(page, ".bi-design", out / "design-check.png")
                got["check"] = page.locator("[data-check-sum]").inner_text() + " | " + " || ".join(page.locator("[data-check-list] li").all_inner_texts())
                page.click("[data-check-close]")
                for vid, title in (("card-1", "Mark to market"), ("card-2", "Notional"), ("bar-1", "MTM and notional by desk"), ("line-1", "Notional by trade date"), ("slicer-1", "Currency")):
                    page.click(f'.bi-dvis[data-visual="{vid}"] .bi-vis-head')
                    page.fill("[data-format-title]", title)
                    page.press("[data-format-title]", "Enter")
                page.click('.bi-dvis[data-visual="bar-1"] .bi-vis-head')
                page.check("[data-format-labels]")
                page.click('[data-scope="visual"] [data-add-filter]')
                page.select_option('[data-filter-editor] [name="field"]', "trade.desk")
                page.select_option('[data-filter-editor] [name="mode"]', "top")
                page.fill('[data-filter-editor] [name="n"]', "5")
                page.wait_for_timeout(300)
                shot_full(page, ".bi-design", out / "design-filter.png")
                page.click('[data-filter-editor] button[type=submit]')
                settled_design(page)
                page.click("[data-ptab='format']")
                page.click('.bi-dvis[data-visual="line-1"] .bi-vis-head')
                page.wait_for_timeout(600)
                shot_full(page, ".bi-design", out / "design-two-visuals.png")
                page.click('[data-tab="yaml"]')
                page.wait_for_timeout(900)
                shot_full(page, ".bi-design", out / "design-yaml.png")
                got["designed_yaml"] = page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()")
                page.click('[data-tab="canvas"]')
                page.click("[data-check]")
                page.wait_for_selector("[data-check-sum]")
                got["check_after"] = page.locator("[data-check-sum]").inner_text() + " | " + " || ".join(page.locator("[data-check-list] li").all_inner_texts())
                page.click("[data-save]")
                page.wait_for_timeout(500)
                got["design_id"] = page.url.rsplit("/", 1)[1]
                page.goto(f"{CONSOLE_URL}/bi/r/{got['design_id']}")
                page.wait_for_selector("[data-ready=yes]")
                settled(page)
                page.wait_for_timeout(600)
                shot(page, ".bi-canvas", out / "design-viewer.png")
                page.context.close()

                # ---- the Model view -----------------------------------------------------------------------------------------------------------
                page = sign_in(browser, "wes", viewport={"width": 1500, "height": 1250})
                page.goto(f"{CONSOLE_URL}/bi/m/{RISK}")
                page.wait_for_selector("[data-model-root][data-ready=yes]", timeout=30000)
                page.wait_for_timeout(600)
                shot_full(page, ".bi-model", out / "model-shipped.png")
                page.click("[data-save-copy]")
                page.wait_for_url("**/bi/m/bi-**")
                page.wait_for_selector("[data-model-root][data-ready=yes]")
                page.locator("[data-rel-row]").filter(has_text="trades.book").locator("[data-remove-rel]").click()
                page.wait_for_timeout(700)
                page.drag_and_drop('[data-column="trades.book"]', '[data-column="books.id"]')
                page.wait_for_timeout(300)
                shot_full(page, ".bi-model", out / "model-relate.png")
                page.click("[data-nr-add]")
                page.locator("[data-rel-row]").filter(has_text="trades.book").first.click()
                page.wait_for_timeout(500)
                shot_full(page, ".bi-model", out / "model-related.png")
                page.context.close()
                page = sign_in(browser, "wes", viewport={"width": 1500, "height": 1000})
                page.goto(CONSOLE_URL + "/bi/designer")
                made = page.evaluate("""async (body) => { const r = await fetch('/bi/api/workspaces/me/items', {method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({kind: 'dataset', title: 'Desk and P&L', description: '', body})}); return await r.json(); }""",
                                     "rachana: 1\ndataset: desk-and-pnl\ntitle: Desk and P&L\ntables:\n  trades:\n    source: { kind: trade }\n    mode: cached\n    columns: { desk: string, mtm: decimal }\n"
                                     "  desk_pnl:\n    source: { kind: desk-pnl }\n    mode: cached\n    columns: { desk: string }\nmeasures:\n  MTM: { expr: \"sum(trades.mtm)\" }\n")
                page.goto(f"{CONSOLE_URL}/bi/m/{made['id']}")
                page.wait_for_selector("[data-model-root][data-ready=yes]")
                page.click("[data-infer]")
                page.wait_for_selector("[data-proposals] [data-proposal]", timeout=90000)
                page.wait_for_timeout(400)
                shot_full(page, ".bi-model", out / "model-proposals.png")
                got["proposal"] = page.locator("[data-proposals] [data-proposal]").first.inner_text()
                page.locator("[data-proposals] [data-accept]").first.click()
                page.wait_for_timeout(500)
                got["accepted_yaml_tail"] = page.evaluate("document.querySelector('.CodeMirror').CodeMirror.getValue()")
                page.context.close()
                browser.close()
            s, got["catalogue_reports"] = api("GET", "/bi/catalogue?kind=report", "rita", ["risk"])
            s, got["report_get"] = api("GET", "/bi/reports/" + OVERVIEW, "rita", ["risk"])
            got["report_get"] = {k: got["report_get"][k] for k in ("id", "title", "valid", "problems", "mayEdit")} | {"pages": [p["id"] for p in got["report_get"]["report"]["pages"]]}
            s, got["visual_query"] = api("POST", f"/bi/reports/{OVERVIEW}/query", "rita", ["risk"], {"page": "overview", "visual": "by-desk", "state": {"selections": [], "drill": []}})
            s, got["validate_bad"] = api("POST", "/bi/reports/validate", "wes", ["bi-writer"], {"yaml": "report: x\ndataset: trades-basic\npages:\n  - id: p\n    visuals:\n      - { id: a, type: bar, at: {x: 0, y: 0, w: 14, h: 4}, wells: { axis: [trade.nope], values: [trade.mtm] } }\n      - { id: b, type: map, at: {x: 0, y: 4, w: 4, h: 4} }\n"})
            s, got["catalogue"] = api("GET", "/bi/reports/catalogue", "rita", ["risk"])
        finally:
            stack.close()
    captures.write_text(json.dumps(got, indent=2, default=str))
    print("wrote", len(list(out.glob("*.png"))), "pictures and", captures)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
