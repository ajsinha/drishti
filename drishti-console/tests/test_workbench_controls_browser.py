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

"""The workbench's Controls tab in a real browser over a real server: reached by keyboard alone, a control added and set from its card, the YAML
following the cards and the cards following typed YAML, reordered by Alt+Down, the controls kept as one step of the log (undo brings the old
ones back), a mistake located, and the small preview working the controls (the console's own controls.js) over the sample.

Skipped when Playwright, its Chromium, the server jar or a JDK 21 is missing."""
import json

from test_workbench_browser import browser, open_design, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console  # noqa: F401 - the fixture

SAMPLE = json.dumps({"tradeId": "T-1", "cashflows": [{"leg": 1, "amount": 100}, {"leg": 2, "amount": 200}, {"leg": 1, "amount": 300}]})
SUTRA = ("rachana: 1\nsutra: ctl-e2e\nversion: 1\ndescription: Cashflows by leg.\nmatch: { kind: ctl-trade }\ntitle: { id: $.tradeId }\n"
         "panels:\n  - id: cf\n    kind: table\n    title: Cashflows\n    rows: $.cashflows\n    columns:\n      - { label: Leg, bind: \"@.leg\" }\n      - { label: Amount, bind: \"@.amount\" }\n")


def _ids(page):
    return page.evaluate("() => window.drishtiWorkbench.controls.model().map(c => c.id)")


def _saved(page, text):
    wait(page, f"window.drishtiWorkbench.store.state.yaml.indexOf({json.dumps(text)}) >= 0")


def test_add_control_from_a_card_see_the_yaml_and_try_it_on_the_sample(live_console, page):
    id_ = open_design(page, live_console, "ctl", sutra=SUTRA, files={"s.json": SAMPLE}, kind="ctl-trade")

    page.locator("#wbTabCtl").focus()
    page.keyboard.press("Enter")                                         # a tab, opened by the keyboard
    assert page.locator("#wbPaneCtl").is_visible()
    page.locator("[data-ctl-new-kind]").select_option("multi-select")
    page.locator("[data-ctl-add]").focus()
    page.keyboard.press("Enter")
    assert _ids(page) == ["control1"]
    card = page.locator("[data-ctl-list] li").first
    assert card.get_attribute("aria-label").startswith("new control") is False and "control 1 of 1" in card.get_attribute("aria-label")
    card.locator("input[aria-label='control1 id']").fill("legs")
    card.locator("input[aria-label='legs options']").fill("$.cashflows[*].leg")
    card.locator("select[aria-label='legs placement']").select_option("header")
    card.locator("select[aria-label='legs panel']").select_option("cf")
    card.locator("input[aria-label='legs label']").fill("Legs")
    text = page.input_value("[data-ctl-src]")
    assert text.startswith("- { id: \"legs\", kind: \"multi-select\", label: \"Legs\", options: \"$.cashflows[*].leg\"") and 'panel: "cf"' in text    # the YAML follows the cards

    # kept as ONE step of the log, validated as the Sutra is
    _saved(page, "controls:")
    yaml = page.evaluate("window.drishtiWorkbench.store.state.yaml")
    assert page.evaluate("window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).controls.map(c => [c.id, c.kind, c.panel, c.options].join('|'))") == [
        "legs|multi-select|cf|$.cashflows[*].leg"] and yaml.index("controls:") < yaml.index("panels:")
    assert page.locator("[data-ctl-problems]").inner_text().strip() == "The controls read without problems."

    # a panel reads it; the small preview works the control over the sample
    page.evaluate("""() => window.drishtiWorkbench.store.send([{ op: 'setOption', panel: 'cf', option: 'where', value: 'oneOf(@.leg, ctl.legs)' }], 'Condition set')""")
    wait(page, "window.drishtiWorkbench.store.state.yaml.indexOf('ctl.legs') >= 0")
    page.locator("#wbTabProb").click()                                                              # a Sutra that reads its controls has no problems
    assert page.locator("[data-problem-count]").is_hidden(), page.locator("[data-problems]").inner_text()
    page.locator("#wbTabCtl").click()
    page.evaluate("window.drishtiWorkbench.controls.preview()")
    page.locator("[data-ctl-preview] [data-ctl='legs'] summary").wait_for(timeout=20000)
    assert page.locator("[data-ctl-preview] tbody tr").count() == 3
    page.locator("[data-ctl-preview] [data-ctl='legs'] summary").click()
    page.locator("[data-ctl-preview] [data-ctl='legs'] input[value='2']").check()
    page.locator("[data-ctl-preview] [data-ctl='legs'] input[value='1']").uncheck() if False else None
    wait(page, "document.querySelectorAll('[data-ctl-preview] tbody tr').length === 1")
    assert page.locator("[data-ctl-preview] tbody tr").inner_text().startswith("2")
    assert page.locator("[data-ctl-preview] [id^='wbc-p-cf']").count() == 1                      # the preview's ids do not clash with the canvas's
    assert page.locator("#p-cf").count() == 1

    # undo brings the old controls back (focus outside the boxes: the design's own Ctrl+Z)
    page.locator("#wbTabIns").focus()
    page.keyboard.press("Control+z")
    wait(page, "window.drishtiWorkbench.store.state.yaml.indexOf('ctl.legs') < 0")
    page.keyboard.press("Control+z")
    wait(page, "window.drishtiWorkbench.store.state.yaml.indexOf('controls:') < 0")
    wait(page, "window.drishtiWorkbench.controls.model().length === 0")
    page.keyboard.press("Control+Shift+z")
    wait(page, "window.drishtiWorkbench.store.state.yaml.indexOf('controls:') >= 0")
    wait(page, "window.drishtiWorkbench.controls.model().length === 1")
    assert page.request.get(f"{live_console}/build/designs/{id_}").json()["sutra"].count("controls:") == 1


def test_typed_yaml_makes_cards_reorder_by_keyboard_and_a_mistake_is_located(live_console, page):
    open_design(page, live_console, "ctl2", sutra=SUTRA, files={"s.json": SAMPLE}, kind="ctl-trade")
    page.locator("#wbTabCtl").click()
    page.locator("[data-ctl-src]").evaluate("e => e.closest('details').open = true")
    page.locator("[data-ctl-src]").fill("- { id: a, kind: search }\n- { id: b, kind: top-n, options: [5, 10], default: 10 }\n- { id: c, kind: date-range, default: next-90d }\n")
    wait(page, "window.drishtiWorkbench.controls.model().length === 3")                          # the cards follow typed YAML
    assert _ids(page) == ["a", "b", "c"]
    wait(page, "window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).controls.length === 3")
    assert page.locator("[data-ctl-list] li").nth(1).locator("input[aria-label='b options']").input_value() == "5, 10"

    page.locator("[data-ctl-list] li").first.focus()
    page.keyboard.press("Alt+ArrowDown")                                                          # reorder by keyboard
    assert _ids(page) == ["b", "a", "c"]
    assert page.evaluate("document.activeElement.getAttribute('data-i')") == "1"
    page.locator("[data-ctl-list] button[aria-label='Move c up']").click()
    assert _ids(page) == ["b", "c", "a"]
    wait(page, "window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).controls.map(c => c.id).join() === 'b,c,a'")
    yaml = page.evaluate("window.drishtiWorkbench.store.state.yaml")
    assert page.evaluate("window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).controls.map(c => c.id)") == ["b", "c", "a"]

    # a mistake is located: a date default that is not a date is DRS-2039, and the Sutra keeps its last good controls
    page.locator("[data-ctl-list] li").nth(1).locator("input[aria-label='c default']").fill("soon")
    page.locator("[data-ctl-problems]").get_by_text("DRS-2039").wait_for(timeout=20000)
    assert "next-90d" in page.locator("[data-ctl-problems]").inner_text()
    assert "soon" not in page.evaluate("window.drishtiWorkbench.store.state.yaml")
    page.locator("[data-ctl-list] li").nth(1).locator("input[aria-label='c default']").fill("this-month")
    page.locator("[data-ctl-problems]").get_by_text("read without problems").wait_for(timeout=20000)

    page.locator("[data-ctl-list] li").first.focus()
    page.keyboard.press("Delete")                                                                 # remove by keyboard
    assert _ids(page) == ["c", "a"]
