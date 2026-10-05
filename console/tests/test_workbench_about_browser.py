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

"""The workbench's About tab in a real browser over a real server (CONTEXT_HELP.md step 7): reached by keyboard alone, the starter
writes a skeleton, editing the text updates the About card of the previewed sample, the text is kept as a step of the log (undo brings
the old text back), the lint reaches the Problems tab, and the fragment carries config/about.yaml.

Skipped when Playwright, its Chromium, the server jar or JDK 25 is missing."""
import io
import json
import zipfile

from test_workbench_browser import browser, open_design, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console  # noqa: F401 - the fixture

SAMPLE = json.dumps({"shipmentId": "S-1", "carrier": "Maersk"})
SUTRA = ("rachana: 1\nsutra: about-e2e\nversion: 1\ndescription: Where a shipment is.\nmatch: { kind: shipment }\ntitle: { id: $.shipmentId }\n"
         "panels:\n  - { id: facts, kind: kv, title: Facts, columns: [ { label: Carrier, bind: $.carrier } ] }\n")
TEXT = "about: 1\nkinds:\n  shipment:\n    about: \"Shipment ${$.shipmentId} sails with ${$.carrier}.\"\n"
GLOSS = TEXT + "    glossary:\n      carrier: { term: Carrier, means: The line that moves the box. }\n"


def _set(page, text):
    page.evaluate("t => window.drishtiWorkbench.about.editor.setValue(t)", text)


def _value(page):
    return page.evaluate("() => window.drishtiWorkbench.about.value()")


def test_edit_the_about_text_and_the_card_updates_by_keyboard_alone_and_it_exports(live_console, page):
    id_ = open_design(page, live_console, "about", sutra=SUTRA, files={"s.json": SAMPLE}, kind="shipment")

    # reached by keyboard: the tab is a tab, Enter opens it, Tab walks into the starter button and then the editor
    page.locator("#wbTabAbout").focus()
    page.keyboard.press("Enter")
    assert page.locator("#wbPaneAbout").is_visible()
    for _ in range(3):                                                  # the guide link, then the starter button
        page.keyboard.press("Tab")
        if page.evaluate("document.activeElement.matches('[data-about-starter]')"):
            break
    assert page.evaluate("document.activeElement.matches('[data-about-starter]')")
    page.keyboard.press("Enter")                                        # the starter on the empty box
    page.keyboard.press("Tab")                                          # (the editor is next; it does not trap Tab)
    page.keyboard.press("Tab")
    assert page.evaluate("!document.activeElement.closest('.CodeMirror')")
    page.wait_for_function("() => window.drishtiWorkbench.about.value().indexOf('about: 1') === 0", timeout=10000)
    starter = _value(page)
    assert "shipment:" in starter and "facts:" in starter and "carrier:" in starter    # kind, panel and the field shown without an entry
    page.locator("[data-about-card]").get_by_text("Where a shipment is.").wait_for(timeout=10000)      # the Sutra's description, layer 1
    page.wait_for_timeout(2200)                                           # kept as a step of the log
    first = page.request.get(f"{live_console}/build/designs/{id_}").json()
    assert first["about"] == starter

    # a shorter text with the entity's own numbers: the card follows on idle
    _set(page, TEXT)
    page.locator("[data-about-card]").get_by_text("Shipment S-1 sails with Maersk.").wait_for(timeout=10000)
    assert "No field this preview shows has an entry yet" in page.locator("[data-about-card]").inner_text()
    page.wait_for_function("() => document.querySelector('[data-about-lint]').innerText.indexOf('DRS-2047') >= 0", timeout=10000)
    page.locator("#wbTabProb").click()
    page.locator("[data-problems]").get_by_text("DRS-2047").first.wait_for(timeout=10000)      # the same lint is in the Problems tab
    page.locator("#wbTabAbout").click()

    # the glossary entry: the card explains the field, the lint goes away
    _set(page, GLOSS)
    page.locator("[data-about-card]").get_by_text("The line that moves the box.").wait_for(timeout=10000)
    page.wait_for_function("() => document.querySelector('[data-about-lint]').innerText.indexOf('DRS-2047') < 0", timeout=10000)
    page.wait_for_timeout(2200)
    saved = page.request.get(f"{live_console}/build/designs/{id_}").json()
    assert saved["about"] == GLOSS and saved["rev"] > first["rev"]

    # a mistake is located: an unclosed expression is DRS-2042 with its line
    _set(page, TEXT.replace("${$.carrier}", "${$.carrier"))
    page.wait_for_function("() => document.querySelector('[data-about-lint]').innerText.indexOf('DRS-2042') >= 0", timeout=10000)
    assert "line 4" in page.locator("[data-about-lint]").inner_text()
    _set(page, GLOSS)
    page.wait_for_timeout(2200)

    # undo brings the earlier text back (focus outside the box: the design's own Ctrl+Z)
    page.locator("[data-about-card]").click()
    page.keyboard.press("Control+z")
    page.wait_for_function("() => window.drishtiWorkbench.about.value().indexOf('carrier: {') < 0", timeout=10000)
    assert page.request.get(f"{live_console}/build/designs/{id_}").json()["about"] != GLOSS

    # the fragment carries the text
    _set(page, GLOSS)
    page.wait_for_timeout(2200)
    zipped = page.request.get(f"{live_console}/build/designs/{id_}/export").body()
    with zipfile.ZipFile(io.BytesIO(zipped)) as z:
        names = z.namelist()
        about = [n for n in names if n.endswith("config/about.yaml")]
        assert about and z.read(about[0]).decode() == GLOSS
        assert "about: config/about.yaml" in z.read([n for n in names if n.endswith("pack.yaml")][0]).decode()
    imported = page.request.post(f"{live_console}/build/import", data=zipped, headers={"Content-Type": "application/zip"}).json()
    copy = imported["designs"][0]["id"]
    assert page.request.get(f"{live_console}/build/designs/{copy}").json()["about"] == GLOSS
