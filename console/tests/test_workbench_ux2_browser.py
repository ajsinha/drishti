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

"""QA round 2 (UX), in a real browser over a real server: a design of your own kind is not a problem (UX-01), a mistyped expression is
a located problem and the other panels are still drawn (UX-02), a DRS code appears once (UX-04). Fixtures are test_workbench_browser.py's."""
import json
import re

from test_workbench_browser import browser, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console, post, showcase_json, showcase_sutra  # noqa: F401 - the fixture

SHIPMENT = json.dumps({"shipmentId": "S-1", "carrier": "Maersk", "legs": [{"from": "SIN", "to": "RTM"}], "valueUsd": 1200})
SHIPMENT_SUTRA = """rachana: 1
sutra: shipments
version: 1
match: { kind: shipment }
title: { id: $.shipmentId }
panels:
  - id: facts
    kind: kv
    title: Facts
    columns:
      - { label: Carrier, bind: $.carrier }
  - id: legs
    kind: table
    title: Legs
    rows: $.legs
    columns:
      - { label: From, bind: "@.from" }
"""


def retype(page, old, new):
    """Types new in place of old in the YAML editor (a change the editor sends after its pause)."""
    page.evaluate("([a, b]) => { const cm = document.querySelector('.CodeMirror').CodeMirror; cm.replaceRange(cm.getValue().replace(a, b), {line: 0, ch: 0}, {line: cm.lineCount(), ch: 0}); }", [old, new])


def problems(page):
    page.get_by_role("tab", name="Problems").click()
    return [t.strip() for t in page.locator(".wb-problem-b").all_inner_texts()]


def test_a_design_of_your_own_kind_has_no_false_kind_problem(live_console, page):
    open_design(page, live_console, "own kind", sutra=SHIPMENT_SUTRA, files={"s1.json": SHIPMENT}, kind="shipment")
    page.get_by_role("tab", name="YAML").click()
    page.wait_for_timeout(1500)
    assert not any("valid kind" in t for t in problems(page)), problems(page)
    # a typo in the kind is still said
    page.get_by_role("tab", name="YAML").click()
    retype(page, "kind: shipment", "kind: shipmnt")
    page.wait_for_timeout(1200)
    assert any("shipmnt" in t for t in problems(page))


def test_a_mistyped_expression_is_a_located_problem_and_the_other_panels_are_drawn(live_console, page):
    id_ = open_design(page, live_console, "bad expr", sutra=SHIPMENT_SUTRA, files={"s1.json": SHIPMENT}, kind="shipment")
    page.get_by_role("tab", name="YAML").click()
    rev = state(page, "rev")
    retype(page, "rows: $.legs", "rows: '$.legs[?'")
    settle(page, rev)
    page.locator('[data-preview] [data-panel="facts"]').wait_for(state="attached", timeout=15000)          # the other panel is still drawn
    assert page.locator('[data-preview] [data-panel="legs"]').count() == 0
    assert "legs" in page.locator("[data-preview] .wb-dropped").text_content()
    found = problems(page)
    mine = [t for t in found if "legs" in t and "rows" in t]
    assert mine, found
    assert mine[0].count("DRS-2101") == 1 and "line " in mine[0]                           # located, and the code once
    page.get_by_role("tab", name="Tests").click()
    page.wait_for_timeout(2500)
    assert page.locator("[data-tab-panel=tests], .wb-sum").first.inner_text().count("DRS-2101") == 0
    assert page.locator(".wb-sum").first.inner_text().count("DRS-") <= 1
    # mending it clears the problem and brings the panel back
    rev = state(page, "rev")
    page.get_by_role("tab", name="YAML").click()
    retype(page, "rows: '$.legs[?'", "rows: $.legs")
    settle(page, rev)
    page.locator('[data-preview] [data-panel="legs"]').wait_for(state="attached", timeout=15000)
    assert not [t for t in problems(page) if "legs" in t and "rows" in t]
    assert not page.errors


def test_the_expression_error_has_the_same_shape_through_the_preview_route(live_console, page):
    id_ = open_design(page, live_console, "bad expr route", sutra=SHIPMENT_SUTRA, files={"s1.json": SHIPMENT}, kind="shipment")
    bad = SHIPMENT_SUTRA.replace("rows: $.legs", "rows: '$.legs[?'")
    sent = post(page, f"{live_console}/build/designs/{id_}/ops", {"baseRev": state(page, "rev"), "ops": [{"op": "text", "yaml": bad}]})
    assert sent["http"] == 200 and not sent.get("previewError"), sent
    assert sent["checkProblems"][0]["panel"] == "legs" and sent["checkProblems"][0]["option"] == "rows" and sent["checkProblems"][0]["code"] == "DRS-2101"
    assert "data-panel=\"facts\"" in sent["previewHtml"] and "DRS-2101" not in sent["checkProblems"][0]["message"]
    got = page.request.get(f"{live_console}/build/designs/{id_}/preview?sample=s1.json").json()
    assert got["checkProblems"][0]["panel"] == "legs" and "data-panel=\"facts\"" in got["previewHtml"]
    checked = page.request.post(f"{live_console}/build/designs/{id_}/check", data="{}", headers={"Content-Type": "application/json"})
    assert checked.status == 422 and checked.json()["checkProblems"][0]["option"] == "rows"


def test_a_failed_request_says_its_code_once_on_every_build_page(live_console, page):
    page.goto(live_console + "/build/d/doesnotexist")
    text = page.locator("[role=alert]").first.text_content()
    assert text.count("DRS-5006") == 1, text
    page.goto(live_console + "/build/reviews/P-999999")
    text = page.locator("[role=alert]").first.text_content()
    assert len(re.findall(r"DRS-\d+", text)) == 1, text
    page.goto(live_console + "/build/new")
    page.locator("[data-kind]").fill("bad kind!")
    page.locator("[data-name]").fill("x")
    page.locator("[data-folder], input[type=file]").first.wait_for(state="attached")
    page.evaluate("document.querySelector('[data-create]').click()")
    page.wait_for_timeout(1500)
    shown = page.locator("[data-create-status], [role=alert], .bs-status.bad").all_inner_texts()
    assert all(t.count("DRS-5001") <= 1 for t in shown), shown
