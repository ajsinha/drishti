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

"""The FRTB-SA and SIMM playgrounds' choices on drishti-inputs' Select (docs/architecture/ELEMENTS.md section 15): a method switch, the
Compare with list (refilled from the server's switches, the choice kept) and SIMM's side are <drishti-select>s, and picking one asks
the server again with the choice. The engine is stood in for by the browser's routes; skipped when Playwright or its Chromium is
missing."""
import json
from urllib.parse import parse_qs, urlparse

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

import select_helpers as S  # noqa: E402
from test_admin_forms_browser import console  # noqa: E402,F401 - the console with a stand-in server
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

SWITCH = {"name": "curvature-floor", "value": "on", "default": "on", "options": ["on", "off"], "rule": "MAR21.5", "why": "the floor"}


def _answer(asked):
    def handle(route):
        q = parse_qs(urlparse(route.request.url).query)
        asked.append(q)
        value = (q.get("curvature-floor") or ["on"])[0]
        route.fulfill(content_type="application/json", body=json.dumps({
            "scope": "BANK", "currency": "USD", "binding": "medium", "regulatory": True, "side": (q.get("side") or ["collect"])[0],
            "choices": [dict(SWITCH, value=value)], "grid": [], "factors": 0, "rows": [], "total": 0}))
    return handle


@pytest.mark.parametrize("which,prefix", [("frtb", "fr"), ("simm", "sm")])
def test_the_switches_and_compare_are_selects_that_ask_again(browser, console, which, prefix):
    url, _ = console
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    asked = []
    page.route(f"**/api/{which}/playground**", _answer(asked))
    page.goto(url + f"/play/{which}?" + ("scope=BANK" if which == "frtb" else "nettingSet=NS-1"))
    page.wait_for_function(f"() => document.querySelector('[data-{prefix}-sw] .di-select-control')")
    assert page.locator(f"[data-{prefix}-choices] select").count() == 0                      # no native select left
    assert S.values(page, f"[data-{prefix}-compare]") == ["", "curvature-floor=off"]           # the switches the server offers, flipped
    S.choose(page, f"[data-{prefix}-sw='curvature-floor']", "off")
    page.wait_for_function("p => document.querySelector(p).component.options.some((o) => o.value === 'curvature-floor=on')", arg=f"[data-{prefix}-compare]")
    assert asked[-1].get("curvature-floor") == ["off"]
    assert S.values(page, f"[data-{prefix}-compare]") == ["", "curvature-floor=on"]            # refilled from the new answer
    with page.expect_response(lambda r: "/playground" in r.url):
        S.choose(page, f"[data-{prefix}-compare]", "curvature-floor=on")
    assert asked[-1].get("compare") == ["curvature-floor=on"]
    if which == "simm":
        with page.expect_response(lambda r: "/playground" in r.url):
            S.choose(page, "[data-sm-side]", "post")
        assert asked[-1].get("side") == ["post"]
    assert errors == [], errors
    page.close()
