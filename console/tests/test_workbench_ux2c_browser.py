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

"""QA round 2 (UX), part three: the lows and infos. Real browser, real server. Fixtures are test_workbench_browser.py's."""
import json
import re
import time

from conftest import CONSOLE
from test_workbench_browser import browser, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console, post, showcase_json, showcase_sutra  # noqa: F401 - the fixture

SHIP = json.dumps({"shipmentId": "S-1", "carrier": "Maersk", "valueUsd": 1200, "legs": [{"from": "SIN", "to": "RTM"}]})
SUTRA = ("rachana: 1\nsutra: ux\nversion: 1\nmatch: { kind: shipment }\ntitle: { id: $.shipmentId }\npanels:\n"
         "  - { id: facts, kind: kv, title: Facts, columns: [ { label: Carrier, bind: $.carrier } ] }\n  - { id: legs, kind: table, title: Legs, rows: $.legs, columns: [ { label: From, bind: \"@.from\" } ] }\n")


def say(page):
    return page.locator("[data-say]").inner_text()


def test_unknown_example_design_and_sutra_addresses_say_so(live_console, page):
    page.goto(live_console + "/studio?example=nope")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    wait(page, "document.querySelector('[data-say]').textContent.indexOf(\"no example 'nope'\") >= 0")
    page.goto(live_console + "/studio?design=zzzz")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    wait(page, "document.querySelector('[data-say]').textContent.indexOf(\"design you asked for is not one of yours\") >= 0")
    page.goto(live_console + "/studio?sutra=nope@9")
    text = page.locator("[role=alert]").first.inner_text()
    assert "no Sutra 'nope@9' is loaded" in text and text.count("DRS-2003") == 1


def test_a_file_that_is_not_json_is_named_when_it_is_chosen(live_console, page):
    page.goto(live_console + "/build/new")
    page.locator("[data-files]").set_input_files([{"name": "bad.json", "mimeType": "application/json", "buffer": b"not json {"},
                                                  {"name": "good.json", "mimeType": "application/json", "buffer": b"{}"}])
    wait(page, "document.querySelector('[data-files-status], .bs-status') && /bad.json/.test(document.body.innerText)")
    assert "bad.json is not valid JSON" in page.locator("body").inner_text()


def test_yaml_syntax_text_is_short_and_a_number_field_refuses_a_fraction(live_console, page):
    open_design(page, live_console, "nits", sutra=SUTRA, files={"s.json": SHIP}, kind="shipment")
    raw = "while parsing a flow sequence\n in 'reader', line 1, column 8:\n    title: [unclosed\n           ^\nexpected ',' or ']', but got <stream end>\n in 'reader', line 1, column 8:\n"
    out = page.evaluate("(r) => window.DrishtiWB.problemText({message: r})", raw)
    assert out.startswith("The YAML does not read at line 1, column 8") and "reader" not in out and out.count("line 1") == 1
    page.locator('[data-preview] [data-panel="facts"] .pnl-h').click()
    span = page.get_by_label("span", exact=True)
    span.fill("6")
    span.blur()
    page.wait_for_timeout(1200)
    span.fill("3.5")
    span.blur()
    page.wait_for_timeout(600)
    assert span.input_value() == "6"                                      # the design's value, not the refused one


def test_the_workbench_asks_in_the_page_and_never_in_a_browser_dialog(live_console, page):
    seen = []
    page.on("dialog", lambda d: (seen.append(d.message), d.dismiss()))
    open_design(page, live_console, "asks", sutra=SUTRA, files={"s.json": SHIP}, kind="shipment")
    page.locator("[data-autodesign]").click()
    dialog = page.get_by_role("dialog", name="Replace the Sutra?")
    dialog.wait_for()
    page.keyboard.press("Escape")
    assert dialog.count() == 0 and not seen


def test_first_guesses_say_when_they_are_placeholders(live_console, page):
    open_design(page, live_console, "guess", sutra=SUTRA, files={"s.json": SHIP}, kind="shipment")
    rev = state(page, "rev")
    page.get_by_role("button", name="Add a gauge panel").click()
    settle(page, rev)
    assert "set max to your limit" in say(page)
    rev = state(page, "rev")
    page.get_by_role("button", name="Add a histogram panel").click()
    settle(page, rev)
    assert "placeholder" in say(page)
    rev = state(page, "rev")
    page.get_by_role("button", name="Add an area panel").click()
    settle(page, rev)
    assert "Added an area panel" in say(page)


def test_an_old_complaint_does_not_stay_on_the_status_line(live_console, page):
    open_design(page, live_console, "stale", sutra=SUTRA, files={"s.json": SHIP}, kind="shipment")
    page.evaluate("window.drishtiWorkbench.staleMs = 400; window.drishtiWorkbench.store.emit('say', 'note.txt is not valid JSON', true)")
    assert "note.txt" in say(page)
    page.wait_for_timeout(900)
    assert say(page) == ""


def test_the_help_examples_page_has_one_link_per_example_and_it_opens_a_copy(live_console, page):
    page.goto(live_console + "/help/examples")
    links = page.locator(".help-example-actions a")
    assert links.count() == page.locator(".help-example-item").count()
    assert links.first.get_attribute("href").startswith("/studio?example=")


def test_the_designs_list_shows_sortable_iso_dates(live_console, page):
    open_design(page, live_console, "dates", sutra=SUTRA, files={"s.json": SHIP}, kind="shipment")
    page.goto(live_console + "/build")
    stamp = page.locator("time[data-ms]").first.inner_text()
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}", stamp), stamp


def test_small_targets_are_at_least_24_px_and_the_status_bar_sticks_on_a_phone(live_console, page):
    open_design(page, live_console, "targets", sutra=showcase_sutra(), files={"showcase.json": showcase_json()})
    small = page.evaluate("""() => [...document.querySelectorAll('[data-preview] .pnl-help, [data-preview] .tbl-pg-b')].map(e => e.getBoundingClientRect()).filter(r => r.width && (r.width < 24 || r.height < 24)).length""")
    assert small == 0
    page.set_viewport_size({"width": 390, "height": 800})
    assert page.evaluate("getComputedStyle(document.querySelector('.wb-bar')).position") == "sticky"
    page.locator('[data-preview] [data-panel="terms"] .pnl-h').click()
    assert "inspector is below" in say(page)


def test_a_check_of_many_samples_answers_quickly(live_console, page):
    files = {f"s{i}.json": json.dumps({"shipmentId": f"S-{i}", "carrier": "Maersk", "legs": [{"from": "A", "to": "B"}]}) for i in range(30)}
    id_ = open_design(page, live_console, "perf", sutra=SUTRA, files=files, kind="shipment")
    t = time.monotonic()
    r = page.request.post(f"{live_console}/build/designs/{id_}/check", data="{}", headers={"Content-Type": "application/json"})
    assert r.status == 200 and time.monotonic() - t < 5
    assert len(r.json()["samples"]) == 30


def test_the_stored_entity_message_uses_the_pages_words_not_api_field_names():
    js = (CONSOLE / "web" / "static" / "js" / "build-new.js").read_text()
    assert "the kind of the stored entity is required" in js
