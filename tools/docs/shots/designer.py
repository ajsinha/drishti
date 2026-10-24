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

"""The pictures of the screen designer guide (docs/guides/SCREEN_DESIGNER.md), written to docs/guides/img/designer/. Run through
tools/docs/screenshots.py --guide designer."""
from __future__ import annotations

import json
import re
import tempfile
import time
from pathlib import Path

from shotlib import EXAMPLES, ROOT, Ctx, register, showcase  # noqa: F401

shot = register("designer")


def before(c: Ctx, name: str) -> None:
    """The first pictures build on one open copy of the showcase; later ones open their own."""
    if "03" <= name[:2] <= "14" and not c.showcase_open:
        c.example("all-panels-showcase")


# ---- the pictures -----------------------------------------------------------------------------------------------------------------------

@shot("01-new-flow.jpg")
def new_flow(c: Ctx):
    c.page.goto(c.base + "/build/new")
    c.page.wait_for_timeout(800)
    c.save("01-new-flow.jpg")


@shot("02-workbench.jpg")
def workbench(c: Ctx):
    c.example("all-panels-showcase")
    c.save("02-workbench.jpg")


@shot("03-data-pane.jpg")
def data_pane(c: Ctx):
    c.page.locator(".wb-field-row", has_text="productName").first.click()
    c.page.wait_for_timeout(300)
    c.save("03-data-pane.jpg", ".wb-left")


@shot("04-palette.jpg")
def palette(c: Ctx):
    c.page.locator('[data-palette] [data-kind="scatter"]').scroll_into_view_if_needed()
    c.save("04-palette.jpg", "[data-palette-box]")


@shot("05-select-panel.jpg")
def select_panel(c: Ctx):
    c.panel("terms").locator(".pnl-h").click()
    c.page.wait_for_timeout(300)
    c.panel("terms").hover()
    c.save("05-select-panel.jpg")


@shot("06-drop-panel.jpg", also=("06b-panel-added.jpg",))
def drop_panel(c: Ctx):
    src = c.page.locator('[data-palette] [data-kind="gauge"]')
    src.scroll_into_view_if_needed()
    s = c.box(src)
    t = c.box(c.panel("ops"))
    c.page.mouse.move(s["x"] + 30, s["y"] + 10)
    c.page.mouse.down()
    c.page.mouse.move(s["x"] + 70, s["y"] + 40, steps=3)
    c.page.mouse.move(t["x"] + 14, t["y"] + 90, steps=10)             # the left half of the status panel: before it, beside the key/value panel
    c.page.wait_for_timeout(200)
    c.save("06-drop-panel.jpg")
    rev = c.rev()
    c.page.mouse.up()
    c.settle(rev)
    c.save("06b-panel-added.jpg")


@shot("06c-add-panel-chooser.jpg")
def add_chooser(c: Ctx):
    c.example("pnl-explain")
    c.page.get_by_role("button", name="Add panel", exact=True).click()
    c.page.keyboard.type("li")
    c.page.wait_for_timeout(300)
    c.save("06c-add-panel-chooser.jpg")
    c.page.keyboard.press("Escape")


@shot("06d-remove-panel.jpg")
def remove_panel(c: Ctx):
    c.example("pnl-explain")
    ids = c.page.evaluate("window.drishtiWorkbench.canvas.ids()")
    c.page.locator(f'[data-preview] [data-panel="{ids[-1]}"]').hover()
    c.page.get_by_role("button", name=re.compile("^Remove panel")).first.click()
    c.page.locator("[data-toast]").wait_for()
    c.page.wait_for_timeout(400)
    c.save("06d-remove-panel.jpg")


@shot("26-notes.jpg")
def notes_tab(c: Ctx):
    c.example("all-panels-showcase")
    c.page.get_by_role("tab", name="Notes").click()
    c.page.wait_for_timeout(300)
    c.save("26-notes.jpg")


@shot("07-drop-field.jpg")
def drop_field(c: Ctx):
    f = c.page.locator(".wb-field-row", has_text="productName").first
    f.scroll_into_view_if_needed()
    s = c.box(f)
    t = c.box(c.panel("terms"))
    c.page.mouse.move(s["x"] + 30, s["y"] + 5)
    c.page.mouse.down()
    c.page.mouse.move(s["x"] + 80, s["y"] + 30, steps=3)
    c.page.mouse.move(t["x"] + t["width"] / 2, t["y"] + t["height"] - 6, steps=10)
    c.page.mouse.up()
    c.page.locator(".wb-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("07-drop-field.jpg")
    c.page.keyboard.press("Escape")


@shot("08-bind-field.jpg")
def bind_field(c: Ctx):
    f = c.page.locator(".wb-field-row", has_text="currency").first
    f.scroll_into_view_if_needed()
    s = c.box(f)
    t = c.box(c.panel("terms"))
    c.page.mouse.move(s["x"] + 30, s["y"] + 5)
    c.page.mouse.down()
    c.page.mouse.move(s["x"] + 80, s["y"] + 30, steps=3)
    c.page.mouse.move(t["x"] + t["width"] / 2, t["y"] + t["height"] / 2, steps=10)
    c.page.wait_for_timeout(200)
    c.save("08-bind-field.jpg")
    c.page.keyboard.press("Escape")
    c.page.mouse.up()
    c.page.wait_for_timeout(500)


@shot("09-resize.jpg")
def resize(c: Ctx):
    p = c.panel("terms")
    p.hover()
    e = c.box(p.locator(".lh-e"))
    c.page.mouse.move(e["x"] + 5, e["y"] + 30)
    c.page.mouse.down()
    c.page.mouse.move(e["x"] - 70, e["y"] + 30, steps=6)
    c.page.wait_for_timeout(200)
    c.save("09-resize.jpg")
    c.page.keyboard.press("Escape")
    c.page.mouse.up()
    c.page.wait_for_timeout(500)


@shot("10-inspector.jpg")
def inspector(c: Ctx):
    c.panel("coupons").locator(".pnl-h").click()
    c.page.wait_for_timeout(400)
    c.save("10-inspector.jpg", ".wb-right")


@shot("11-expression.jpg")
def expression(c: Ctx):
    rows = c.page.get_by_label("rows *")
    rows.fill("$.")
    c.page.wait_for_timeout(300)
    c.save("11-expression.jpg", ".wb-right")
    rows.fill("$.SCHEDULE")
    rows.blur()
    c.page.wait_for_timeout(500)


@shot("12-yaml-tab.jpg")
def yaml_tab(c: Ctx):
    c.page.get_by_role("tab", name="YAML").click()
    c.page.wait_for_timeout(500)
    c.page.evaluate("document.querySelector('.CodeMirror').CodeMirror.scrollTo(0, 560)")
    c.save("12-yaml-tab.jpg")


@shot("13-split.jpg")
def split(c: Ctx):
    c.page.locator("[data-split]").click()
    c.page.wait_for_timeout(700)
    c.save("13-split.jpg")
    c.page.locator("[data-split]").click()
    c.page.get_by_role("tab", name="Design").click()


@shot("14-problems.jpg")
def problems(c: Ctx):
    c.page.get_by_role("tab", name="YAML").click()
    c.page.evaluate("document.querySelector('.CodeMirror').CodeMirror.replaceRange('  - id: oops\\n    kind: nonsense\\n', {line: 40, ch: 0})")
    c.page.get_by_role("tab", name="Problems").click()
    c.page.locator(".wb-problem-b", has_text="nonsense").first.wait_for(timeout=20000)
    c.save("14-problems.jpg")


@shot("15-tests.jpg")
def tests(c: Ctx):
    json_text, sutra = showcase()
    doc = json.loads(json_text)
    thin = dict(doc)
    for k in ("LEGS", "SCHEDULE", "PNL_HISTORY"):
        thin.pop(k, None)
    thin["TRADE_ID"] = "DEMO-BOND-THIN"
    other = dict(doc)
    other["TRADE_ID"] = "DEMO-BOND-2"
    c.open(c.design("Screenshots: tests", files={"full.json": json_text, "thin.json": json.dumps(thin), "second.json": json.dumps(other)}, sutra=sutra))
    c.page.get_by_role("tab", name="Tests").click()
    c.page.locator(".wb-matrix").wait_for(timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("15-tests.jpg")


@shot("16-preview-file.jpg")
def preview_file(c: Ctx):
    json_text, sutra = showcase()
    doc = json.loads(json_text)
    doc["TRADE_ID"] = "TRY-IT-ON-THE-SPOT"
    doc["PRODUCT_NAME"] = "A file that is not in the design"
    tmp = Path(tempfile.mkdtemp()) / "try-this.json"
    tmp.write_text(json.dumps(doc))
    c.open(c.design("Screenshots: preview", files={"full.json": json_text}, sutra=sutra))
    c.page.locator("[data-preview-file]").set_input_files(str(tmp))
    c.page.get_by_text("TRY-IT-ON-THE-SPOT").first.wait_for(timeout=20000)
    c.page.wait_for_timeout(600)
    c.save("16-preview-file.jpg")


@shot("17-keyboard-add.jpg", also=("17b-keyboard-bind.jpg",))
def keyboard_add(c: Ctx):
    json_text, sutra = showcase()
    c.open(c.design("Screenshots: keyboard", files={"full.json": json_text}, sutra=sutra))
    c.panel("terms").focus()
    c.page.keyboard.press("n")
    c.page.locator(".wb-menu input").wait_for()
    c.page.keyboard.type("g")
    c.page.wait_for_timeout(300)
    c.save("17-keyboard-add.jpg")
    c.page.keyboard.press("Escape")
    c.panel("terms").focus()
    c.page.keyboard.press("b")
    c.page.locator(".wb-menu input").wait_for()
    c.page.keyboard.type("curr")
    c.page.wait_for_timeout(300)
    c.save("17b-keyboard-bind.jpg")
    c.page.keyboard.press("Escape")


@shot("18-undo.jpg")
def undo(c: Ctx):
    c.panel("terms").focus()
    for _ in range(2):
        rev = c.rev()
        c.page.keyboard.press("Shift+ArrowLeft")
        c.settle(rev)
    c.save("18-undo.jpg", ".wb-bar")


@shot("19-phone.jpg")
def phone(c: Ctx):
    c.page.locator('[data-width="phone"]').click()
    c.page.wait_for_timeout(900)
    c.save("19-phone.jpg")
    c.page.locator('[data-width="desktop"]').click()


@shot("20-autodesign.jpg")
def autodesign(c: Ctx):
    docs = {f"trade-{i}.json": json.dumps({"TRADE_ID": f"T-{i}", "BOOK": ["rates", "fx", "credit"][i % 3], "NOTIONAL": 1000000 * (i + 1), "MTM": 12345 * i - 20000,
                                           "LEGS": [{"LEG": 1, "RATE": 0.01 * i, "AMOUNT": 100 * i}, {"LEG": 2, "RATE": 0.02, "AMOUNT": 200}]}) for i in range(1, 6)}
    c.open(c.design("Screenshots: auto-design", files=docs))
    c.page.on("dialog", lambda d: d.accept())
    rev = c.rev()
    c.page.locator("[data-autodesign]").click()
    c.settle(rev)
    c.page.wait_for_timeout(1000)
    c.save("20-autodesign.jpg")


@shot("21-file-menu.jpg")
def file_menu(c: Ctx):
    c.example("all-panels-showcase")
    c.page.locator("[data-file-menu]").click()
    c.page.locator(".wb-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("21-file-menu.jpg")
    c.page.keyboard.press("Escape")


@shot("22-palette.jpg")
def command_palette(c: Ctx):
    c.example("all-panels-showcase")
    c.panel("terms").locator(".pnl-h").click()
    c.page.keyboard.press("Control+k")
    c.page.locator(".wb-menu input").wait_for()
    c.page.keyboard.type("go to")
    c.page.keyboard.press("ArrowDown")
    c.page.wait_for_timeout(300)
    c.save("22-palette.jpg")
    c.page.keyboard.press("Escape")


@shot("23-versions.jpg")
def versions(c: Ctx):
    c.page.goto(c.base + "/build/new")
    base = c.page.locator("select option", has_text="@").first.get_attribute("value")
    made = c.api("/build/designs", {"name": "Screenshots: versions", "base": base})
    c.api(f"/build/designs/{made['id']}/files", {"files": [{"name": "s.json", "text": json.dumps({"id": "X-1", "value": 1})}]})
    c.page.goto(f"{c.base}/build/d/{made['id']}?tab=yaml")
    c.made.append(made["id"])
    c.page.wait_for_timeout(2500)
    for note in ("a first change", "a second change"):
        rev = c.rev()
        c.page.evaluate("(note) => window.drishtiWorkbench.store.send([{op: 'text', yaml: window.drishtiWorkbench.store.state.yaml + '# ' + note + '\\n'}])", note)
        c.settle(rev)
    c.page.locator("#wbTabVer").click()
    c.page.wait_for_timeout(1200)
    c.save("23-versions.jpg")


@shot("24-save-bar.jpg")
def save_bar(c: Ctx):
    c.example("all-panels-showcase")
    c.save("24-save-bar.jpg", ".wb-head")


@shot("25-build-menu.jpg")
def build_menu(c: Ctx):
    c.example("all-panels-showcase")
    c.page.locator('.tbar-menu-btn[title="Build"]').click()
    c.page.locator(".mega-panel.show").wait_for()
    c.page.wait_for_timeout(1200)                      # the pending-review count is asked when the menu opens
    c.save("25-build-menu.jpg", clip={"x": 0, "y": 0, "width": 1440, "height": 400})
    c.page.keyboard.press("Escape")


FAMILIES = [("30-family-facts.jpg", "all-panels-showcase", "ops", "key figures, key/value and status"),
            ("31-family-tables.jpg", "tree-table", None, "tables and the tree table"),
            ("32-family-pivot.jpg", "pivot-row-groups", None, "pivot row groups"),
            ("33-family-series.jpg", "exposure-profile", None, "time series"),
            ("34-family-market.jpg", "market-charts", None, "market"),
            ("35-family-risk.jpg", "risk-distribution", None, "risk"),
            ("36-family-pnl.jpg", "pnl-explain", None, "P&L"),
            ("37-family-relationships.jpg", "relationships", None, "relationships"),
            ("38-family-operations.jpg", "operations-status", None, "operations")]


def family(file, example, select):
    def run(c: Ctx):
        c.example(example)
        first = select or c.page.evaluate("window.drishtiWorkbench.canvas.ids()[0]")
        c.panel(first).locator(".pnl-h").click()
        c.page.wait_for_timeout(500)
        c.save(file)
    return run


for _f, _e, _s, _t in FAMILIES:
    shot(_f)(family(_f, _e, _s))


# ---- step 8: ship and scale ------------------------------------------------------------------------------------------------------------------

def ship_design(c: Ctx, label: str) -> str:
    """A design of the showcase under a Sutra name of its own (so a proposal is never 'already live'), opened in the workbench."""
    data, sutra = showcase()
    unique = f"shots-{label}-{int(time.time())}"
    id_ = c.design("Quarterly credit review", files={"showcase.json": data, "second-bond.json": data.replace("GOVT_BOND", "GOVT_BOND")},
                   sutra=sutra.replace("sutra: all-panels-showcase", f"sutra: {unique}", 1))
    c.open(id_)
    return id_


def submit(c: Ctx) -> str:
    c.page.fill("[data-note]", "Adds the credit panels; checked on both samples")
    c.page.locator("[data-save]").click()
    c.until("document.querySelector('[data-say]').textContent.indexOf('Submitted for review as P-') >= 0")
    return c.page.locator("[data-say]").inner_text().split("as ")[1].split(":")[0]


@shot("40-ship-menu.jpg")
def ship_menu(c: Ctx):
    ship_design(c, "menu")
    c.page.locator("[data-ship-menu]").click()
    c.page.locator(".wb-menu").wait_for()
    c.save("40-ship-menu.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 470})
    c.page.keyboard.press("Escape")


@shot("41-submit-for-review.jpg")
def submit_for_review(c: Ctx):
    ship_design(c, "submit")
    submit(c)
    c.page.wait_for_timeout(600)
    c.save("41-submit-for-review.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 190})


@shot("42-review-evidence.jpg")
def review_evidence(c: Ctx):
    ship_design(c, "review")
    pid = submit(c)
    c.page.goto(f"{c.base}/build/reviews/{pid}")
    c.page.locator("[data-evidence]").wait_for()
    c.page.locator("[data-evidence]").scroll_into_view_if_needed()
    c.save("42-review-evidence.jpg", "[data-evidence]")


@shot("43-live-status.jpg")
def live_status(c: Ctx):
    id_ = ship_design(c, "live")
    pid = submit(c)
    c.page.goto(f"{c.base}/build/reviews/{pid}")
    c.page.get_by_role("button", name="Approve and publish").click()
    c.page.wait_for_timeout(800)
    c.page.goto(f"{c.base}/build")
    c.page.locator(f"[data-design='{id_}']").wait_for()
    c.save("43-live-status.jpg", f"[data-design='{id_}']")


@shot("44-export-import.jpg")
def export_import(c: Ctx):
    c.page.goto(c.base + "/build/new#import")
    c.page.locator("#import").wait_for()
    c.page.locator("#import").scroll_into_view_if_needed()
    c.page.wait_for_timeout(400)
    c.save("44-export-import.jpg", "#import")


@shot("45-share-link.jpg")
def share_link(c: Ctx):
    ship_design(c, "share")
    c.page.locator("[data-ship-menu]").click()
    c.page.get_by_role("option", name="Create a read-only link").click()
    c.page.locator("[data-share-box]:not([hidden])").wait_for()
    c.page.wait_for_timeout(500)
    c.save("45-share-link.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 200})


@shot("46-shared-view.jpg")
def shared_view(c: Ctx):
    ship_design(c, "shared")
    c.page.locator("[data-ship-menu]").click()
    c.page.get_by_role("option", name="Create a read-only link").click()
    c.page.locator("[data-share-box]:not([hidden])").wait_for()
    c.until("document.querySelector('[data-share-url]').value.indexOf('?share=') > 0")
    link = c.page.locator("[data-share-url]").input_value()
    c.page.goto(link)
    c.page.locator("[data-shared]").wait_for()
    c.page.wait_for_timeout(500)
    c.save("46-shared-view.jpg", clip={"x": 0, "y": 0, "width": 1440, "height": 760})


@shot("47-file-binding.jpg")
def file_binding(c: Ctx):
    ship_design(c, "bound")
    c.page.locator("[data-ship-menu]").click()
    c.page.get_by_role("option", name="Bind to a file").click()
    c.page.get_by_role("dialog", name="Bind to a file").get_by_label("File").fill("shots/credit-review.v1.sutra.yaml")
    c.page.get_by_role("button", name="Bind", exact=True).click()
    c.until("!document.querySelector('[data-bound-chip]').hidden")
    c.page.wait_for_timeout(600)
    c.save("47-file-binding.jpg", clip={"x": 0, "y": 60, "width": 1440, "height": 190})



# ---- the Sutra designer (docs/guides/SCREEN_DESIGNER.md, The Sutra designer): ui.designer must be on (DRISHTI_UI_DESIGNER=true) ----------

def _designer_on(c: Ctx) -> None:
    if not c.page.locator("[data-customize-open]").count():
        raise RuntimeError("the Sutra designer is off: run with DRISHTI_UI_DESIGNER=true (tests/test_designer_browser.py makes the same pictures)")


@shot("customize.jpg")
def sutra_customize(c: Ctx):
    c.page.goto(c.base + "/v/trade/MX-20000001")
    _designer_on(c)
    c.page.locator("[data-customize-open]").click()
    c.until("document.querySelector('[data-dz-ready]')")
    c.save("customize.jpg")
    c.page.locator("[data-dz-cancel]").click()


@shot("describe.jpg", also=("describe-light.jpg",))
def sutra_describe(c: Ctx):
    from urllib.parse import quote
    c.page.goto(c.base + "/designer/new?q=" + quote("TRD BOOK-RATES-2 · click a trade → cash flows + CPTY limits · refresh 30s"))
    c.until("document.querySelector('[data-result]') && !document.querySelector('[data-result]').hidden")
    frame = c.page.frame_locator("[data-preview]")
    frame.locator("#p-trades tbody tr[data-ev]").first.locator("td").nth(1).click()
    frame.locator("#p-limit dd").first.wait_for()
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("describe.jpg")
    c.page.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
    c.page.fill("#describe", "TRD BOOK-RATES-2 · click a trade → limits · pizza")
    c.page.locator("[data-describe-form] button[type=submit]").click()
    c.until("document.querySelectorAll('[data-token]').length === 3")
    c.save("describe-light.jpg")



# ---- forms (docs/guides/SUTRA_FORMS_GUIDE.md; tests/test_record_forms_browser.py makes the same pictures) --------------------------

@shot("form-beside.jpg")
def form_beside(c: Ctx):
    c.page.goto(c.base + "/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk")
    c.until("window.drishtiForms && document.querySelector('#p-trades tbody tr[data-frow]')")
    c.page.locator("#p-trades tbody tr[data-frow='1'] td").nth(1).click()
    c.page.fill("[data-fm-side] [data-field='notional']", "40,000,000")
    c.page.fill("[data-fm-side] [data-field='comment']", "Client hedge for the Q4 roll")
    c.page.evaluate("document.querySelectorAll('#p-trades tr.wire-kids').forEach(n => n.remove());"
                    "document.querySelector('#p-trades').scrollIntoView({ block: 'start' }); window.scrollBy(0, -70)")
    c.save("form-beside.jpg", clip={"x": 0, "y": 0, "width": 1500, "height": 760})


@shot("form-cells.jpg")
def form_cells(c: Ctx):
    c.page.goto(c.base + "/v/credit-limit/LIM-ALDERSHOT")
    c.until("window.drishtiForms && document.querySelector('#p-buckets td.fm-editable')")
    for row, value in (("0", "100"), ("1", "400m")):
        c.page.locator(f"#p-buckets tr[data-frow='{row}'] td[data-fcell='limit']").click()
        c.page.keyboard.press("Control+A")
        c.page.keyboard.type(value)
        c.page.keyboard.press("Enter")
    c.save("form-cells.jpg", clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    c.page.locator(".fm-undo").click()


@shot("form-drawer.jpg")
def form_drawer(c: Ctx):
    c.page.goto(c.base + "/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk")
    c.until("window.drishtiForms && document.querySelector('[data-fm-new]')")
    c.page.locator("[data-fm-new]").click()
    c.page.select_option("[data-fm-drawer] [data-field='counterparty']", index=1)
    c.page.locator("[data-fm-drawer] .fm-next").click()
    c.page.select_option("[data-fm-drawer] [data-field='product_type']", "IRS")
    c.page.fill("[data-fm-drawer] [data-field='notional']", "25m")
    c.page.select_option("[data-fm-drawer] [data-field='currency']", "USD")
    c.page.fill("[data-fm-drawer] [data-field='maturity_date']", "2031-06-30")
    c.save("form-drawer.jpg", clip={"x": 0, "y": 0, "width": 1500, "height": 860})


# ---- the designer's later waves: linked records, refresh and freshness, layout, Outline, Grid (the browser tests make the same pictures:
# test_designer_browser.py, test_refresh_browser.py, test_screen_grid_browser.py, test_designer_grid_browser.py) ---------------------

_GRID_BOOK = """rachana: 1
sutra: qa-book-grid
version: 1
match: { kind: portfolio-trades }
title: { pill: Book, id: $.PORTFOLIO }
layout:
  areas: ["head head", "trades detail"]
  columns: [7, 5]
  split: { between: [trades, detail] }
  tabs: [detail]
  sticky: head
panels:
  - { id: pay7, kind: metric, title: "Payments, next 7 days", value: $.PAY_7D, fmt: signed0, area: head, span: 4 }
  - { id: pay30, kind: metric, title: "Payments, next 30 days", value: $.PAY_30D, fmt: signed0, area: head, span: 4 }
  - { id: pay90, kind: metric, title: "Payments, next 90 days", value: $.PAY_90D, fmt: signed0, area: head, span: 4 }
  - id: trades
    kind: table
    title: Trades
    area: trades
    rows: $.TRADES
    columns: [ { label: Trade, bind: "@.TRADE_ID" }, { label: Product, bind: "@.PRODUCT_TYPE" }, { label: Notional, bind: "@.NOTIONAL", fmt: amount0 } ]
  - { id: book, kind: kv, title: The book, area: detail, columns: [ { label: Trades, bind: $.TRADE_COUNT }, { label: Notional, bind: $.NOTIONAL, fmt: compact } ] }
  - { id: spread, kind: kv, title: Its spread, area: detail, collapsible: true, columns: [ { label: Currencies, bind: "size($.CURRENCIES)" } ] }
"""


def _book_design(c: Ctx, sutra: str) -> str:
    return c.base + "/v/portfolio-trades/BOOK-RATES-2?design=" + c.design("", sutra=sutra, kind="portfolio-trades")


def _customize_step(c: Ctx, button: str) -> None:
    rev = c.page.get_attribute("[data-view]", "data-dz-rev")
    c.page.locator(button).click()
    c.until("document.querySelector('[data-view]').getAttribute('data-dz-rev') !== %s" % json.dumps(rev))


@shot("customize-linked.jpg")
def customize_linked(c: Ctx):
    c.page.goto(c.base + "/v/portfolio-trades/BOOK-RATES-2")
    _designer_on(c)
    c.page.locator("[data-customize-open]").click()
    c.until("document.querySelector('[data-dz-ready]')")
    pick = c.page.locator("[data-dz-click-from] option").first.get_attribute("value")
    c.page.select_option("[data-dz-click-from]", pick)
    if pick.startswith("u:"):                                                     # a used table: opened up by the first sentence
        _customize_step(c, "[data-dz-click-apply]")
    pid = c.page.locator("[data-dz-click-from] option").first.get_attribute("value")[2:]
    limits = next(x for x in c.page.locator("[data-dz-click-show] option").all_inner_texts() if "credit limits" in x)
    c.page.select_option("[data-dz-click-show]", label=limits)
    c.page.select_option("[data-dz-click-place]", "detail-right")
    _customize_step(c, "[data-dz-click-apply]")
    c.page.locator(f"#p-{pid} tbody tr[data-ev]").first.locator("td").nth(1).click()
    c.until(f"document.querySelector('[data-wire-kids=\"{pid}\"] #p-{pid}-credit-limit tbody tr')")
    c.save("customize-linked.jpg")
    c.page.locator("[data-dz-cancel]").click()


@shot("refresh-controls.jpg", also=("freshness.jpg",))
def refresh_controls(c: Ctx):
    c.page.goto(c.base + "/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk")
    c.until("window.drishtiRefresh && document.querySelector('[data-fresh] [data-refresh-toggle]')")
    c.page.locator("[data-fresh] [data-refresh-toggle]").first.click()
    c.page.locator(".fresh-bar [data-refresh-all]").click()
    c.page.wait_for_timeout(800)
    c.save("refresh-controls.jpg", clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    c.page.locator(".fresh-bar [data-refresh-all]").click()                     # resumed, as the reader had it
    c.page.locator("[data-fresh] [data-refresh-toggle]").first.click()

    def stale(route):
        r = route.fetch()
        body = r.json()
        body["provenance"].update(stale=True, updatedAt="2026-10-23T10:42:00Z", staleAfter="PT2M")
        route.fulfill(response=r, body=json.dumps(body))
    c.page.route("**/panels?*", stale)
    c.until("document.querySelector('[data-fresh][data-state=\"stale\"]')")
    c.save("freshness.jpg", clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    c.page.unroute("**/panels?*")


@shot("layout-grid.jpg")
def layout_grid(c: Ctx):
    c.page.goto(_book_design(c, _GRID_BOOK))
    c.until("document.querySelector('[data-sgrid][data-sg-ready] .sg-split')")
    c.save("layout-grid.jpg")


@shot("outline.jpg")
def outline(c: Ctx):
    c.page.goto(c.base + "/designer/outline?kind=portfolio-trades&id=BOOK-RATES-2")
    c.until("document.querySelector('[data-ol-rev]')")
    c.page.locator('[data-ol-panel="trades"]').first.click()
    c.save("outline.jpg")
    c.page.locator("[data-ol-cancel]").click()


@shot("grid-mode.jpg")
def grid_mode(c: Ctx):
    c.page.goto(c.base + "/designer/grid?kind=portfolio-trades&id=BOOK-RATES-2")
    c.until("document.querySelector('[data-gd-rev]')")
    c.page.locator('[data-gd-cell="A4"]').click()
    c.save("grid-mode.jpg")
    c.page.locator("[data-gd-cancel]").click()
