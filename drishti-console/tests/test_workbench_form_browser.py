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

"""The workbench's Form tab in a real browser over a real server: reached by keyboard alone, fields added from the samples' palette,
reordered by Alt+Down and by the buttons, typed and set from the cards, the YAML following the cards and the cards following typed YAML,
the live preview, problems listed, the text kept as a step of the log (undo brings the old text back).

Skipped when Playwright, its Chromium, the server jar or a JDK 21 is missing."""
import json

from test_workbench_browser import browser, open_design, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console  # noqa: F401 - the fixture

SAMPLE = json.dumps({"shipmentId": "S-1", "carrier": "Maersk", "weightKg": 1200.5, "departed": "2026-03-01", "reefer": True})
SUTRA = ("rachana: 1\nsutra: form-e2e\nversion: 1\ndescription: Where a shipment is.\nmatch: { kind: shipment }\ntitle: { id: $.shipmentId }\n"
         "panels:\n  - { id: facts, kind: kv, title: Facts, columns: [ { label: Carrier, bind: $.carrier } ] }\n")


def _paths(page):
    return page.evaluate("() => window.drishtiWorkbench.form.model().map(f => f.path)")


def test_build_a_form_from_the_samples_reorder_and_edit_it(live_console, page):
    id_ = open_design(page, live_console, "form", sutra=SUTRA, files={"s.json": SAMPLE}, kind="shipment")

    page.locator("#wbTabForm").focus()
    page.keyboard.press("Enter")                                        # a tab, opened by the keyboard
    assert page.locator("#wbPaneForm").is_visible()
    page.locator("[data-form-palette] button").first.wait_for(timeout=15000)
    chips = page.locator("[data-form-palette] button").all_inner_texts()
    assert any("weightKg" in c and "amount" not in c.split("weightKg")[0] for c in chips) and any("departed" in c and "date" in c for c in chips)
    assert any("reefer" in c and "bool" in c for c in chips)

    # add from the palette: a click, or Enter on the focused chip
    page.locator('[data-form-palette] button[aria-label^="Add shipmentId"]').click()
    page.locator('[data-form-palette] button[aria-label^="Add carrier"]').focus()
    page.keyboard.press("Enter")
    page.locator('[data-form-palette] button[aria-label^="Add weightKg"]').click()
    page.locator('[data-form-palette] button[aria-label^="Add departed"]').click()
    assert _paths(page) == ["shipmentId", "carrier", "weightKg", "departed"]
    assert page.locator('[data-form-palette] button[aria-label^="Add carrier"]').is_disabled()       # already in the form
    yaml = page.input_value("[data-form-src]")
    assert "shipmentId" in yaml.splitlines()[0]
    assert page.locator('[data-form-list] li').count() == 4

    # reorder by keyboard (Alt+Down on the focused card) and by the button
    page.locator("[data-form-list] li").first.focus()
    page.keyboard.press("Alt+ArrowDown")
    assert _paths(page) == ["carrier", "shipmentId", "weightKg", "departed"]
    assert page.evaluate("document.activeElement.getAttribute('data-i')") == "1"                       # the focus follows the card
    page.locator('[data-form-list] button[aria-label="Move departed up"]').click()
    assert _paths(page) == ["carrier", "shipmentId", "departed", "weightKg"]
    assert page.input_value("[data-form-src]").splitlines()[2].count("departed") == 1                  # the YAML follows the cards

    # a card's settings write the YAML: type, label, operators, default, required
    card = page.locator("[data-form-list] li").nth(3)                                                  # weightKg
    card.locator("select").select_option("number")
    card.locator("input[aria-label='weightKg label']").fill("Weight (kg)")
    card.locator("input[aria-label='weightKg default']").fill("1000")
    card.get_by_text("Required").click()
    card.locator("fieldset input").nth(1).uncheck()                                                    # drop "between"
    text = page.input_value("[data-form-src]")
    line = [l for l in text.splitlines() if "weightKg" in l][0]
    assert 'type: "number"' in line and 'label: "Weight (kg)"' in line and 'default: "1000"' in line and "required: true" in line
    assert '"="' in line and '"between"' not in line and '">="' in line                               # only the operators kept are written

    # the preview shows the form as people will see it
    page.locator("[data-form-preview] label", has_text="Weight (kg)").wait_for(timeout=10000)
    assert page.locator("[data-form-preview] .qf-field").count() == 4
    assert page.locator("[data-form-preview] select").count() >= 1
    assert "The form reads without problems." in page.locator("[data-form-problems]").inner_text()

    # typed YAML rebuilds the cards; a mistake is listed with its code
    page.locator("[data-form-yamlbox] summary").click()
    page.fill("[data-form-src]", "- {path: carrier, type: enum, values: [Maersk, MSC]}\n- {path: reefer, type: bool}\n")
    page.wait_for_function("() => window.drishtiWorkbench.form.model().length === 2", timeout=10000)
    assert _paths(page) == ["carrier", "reefer"]
    page.locator("[data-form-preview] label", has_text="Maersk").wait_for(timeout=10000)
    page.fill("[data-form-src]", "- {path: carrier, type: money}\n")
    page.locator("[data-form-problems]").get_by_text("DRS-2060").wait_for(timeout=10000)
    assert "type 'money'" in page.locator("[data-form-problems]").inner_text()
    assert _paths(page) == ["carrier", "reefer"]                                                       # the cards keep the last form that read

    # kept as a step of the log; undo brings the earlier text back
    page.fill("[data-form-src]", "- {path: carrier, type: text}\n")
    page.wait_for_timeout(2500)
    saved = page.request.get(f"{live_console}/build/designs/{id_}").json()
    assert saved["form"] == "- {path: carrier, type: text}\n"
    page.locator("[data-form-problems]").click()                                                       # focus outside any box: the design's own Ctrl+Z
    page.keyboard.press("Control+z")
    page.wait_for_function("() => window.drishtiWorkbench.form.value() !== '- {path: carrier, type: text}\\n'", timeout=10000)
    assert page.request.get(f"{live_console}/build/designs/{id_}").json()["form"] != "- {path: carrier, type: text}\n"


def test_the_starter_writes_a_form_from_the_samples(live_console, page):
    id_ = open_design(page, live_console, "form-starter", sutra=SUTRA.replace("form-e2e", "form-e2e-b"), files={"s.json": SAMPLE}, kind="shipment")
    page.locator("#wbTabForm").click()
    page.locator("[data-form-palette] button").first.wait_for(timeout=15000)
    page.locator("[data-form-starter]").click()
    page.wait_for_function("() => window.drishtiWorkbench.form.model().length >= 4", timeout=10000)
    types = page.evaluate("() => Object.fromEntries(window.drishtiWorkbench.form.model().map(f => [f.path, f.type]))")
    assert types["shipmentId"] == "id" and types["departed"] == "date" and types["reefer"] == "bool" and types["weightKg"] in ("amount", "number")
    page.wait_for_timeout(2500)
    assert "shipmentId" in page.request.get(f"{live_console}/build/designs/{id_}").json()["form"]
