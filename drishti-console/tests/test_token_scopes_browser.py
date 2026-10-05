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

"""The account page makes a scoped API token in a real browser: the scopes are listed in words, a write scope is ticked, the
request carries it, the secret is shown once, and the token list says what each token may do.

Runs Chromium through Playwright against the stand-in server of test_live_tabs_browser.py; skipped when Playwright or its
Chromium is not installed."""
import json

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import browser, console_url  # noqa: E402,F401 - the shared browser fixtures


def test_the_account_page_makes_a_token_with_a_write_scope(browser, console_url):
    page = browser.new_page()
    sent = []
    page.on("request", lambda r: sent.append(json.loads(r.post_data)) if r.url.endswith("/api/tokens") and r.method == "POST" else None)
    page.goto(console_url + "/account")
    box = page.locator("[data-scope-box]")
    box.wait_for()
    text = box.inner_text()
    assert "design:write" in text and "design:approve" in text and "packs:admin" in text and "read" in text      # the choices, in words
    assert "must expire" in text
    rows = page.locator("[data-token]")
    assert "read only" in rows.nth(0).inner_text() and "Can: design:write, design:approve" in rows.nth(1).inner_text()
    page.fill("[data-token-new] input[name=name]", "CI deploy")
    page.fill("[data-token-new] input[name=days]", "30")
    page.check("input[name=scope][value='design:write']")
    page.click("[data-token-new] button[type=submit]")
    page.locator("[data-secret]:not([hidden])").wait_for()
    assert page.locator("[data-secret-text]").inner_text().startswith("drk_")
    assert sent and sent[-1]["scopes"] == ["design:write"] and sent[-1]["days"] == "30"
    page.close()
