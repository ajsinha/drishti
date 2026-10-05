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

"""The share dialog's "Include a picture" in a real browser, against a real server with snapshots switched on (COLLABORATION.md, step 10): the option
and its preview appear, the preview is the server's PNG, a share sent with it shows the recipient a picture on the shared view's banner; with
nothing ticked no picture travels.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py). Every wait is wb_live.BROWSER_WAIT_MS."""
import pytest

from test_collab_polish_browser import PASSWORD, TRADE, api, people, sign_in  # noqa: F401 - fixtures and helpers
from test_workbench_browser import browser, wait  # noqa: F401
from wb_live import TOKEN_SECRET, _stack


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {"DRISHTI_SECURITY_ENABLED": "true", "DRISHTI_TOKEN_SECRET": TOKEN_SECRET,
                                         "DRISHTI_COLLAB_SNAPSHOTS_ENABLED": "true"}, auth=True)


def open_dialog(ann, base):
    ann.goto(f"{base}/v/trade/{TRADE}")
    ann.locator(".vtitle .vid").wait_for()
    ann.keyboard.press("Alt+s")
    ann.locator("[data-share-dialog]:not([hidden])").wait_for()
    ann.keyboard.type("rav")
    ann.locator("#shrList [role=option]").first.wait_for()
    ann.keyboard.press("Enter")
    ann.locator("[data-shr-chips] .shr-chip").first.wait_for()


def test_the_option_previews_the_picture_and_the_recipient_sees_it(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    open_dialog(ann, base)
    assert ann.locator("[data-shr-picture-row]").is_visible()                         # the server allows pictures for this kind
    ann.locator("[data-shr-picture]").check()
    img = ann.locator("[data-shr-preview-img]")
    img.wait_for(state="visible")
    wait(ann, "document.querySelector('[data-shr-preview-img]').naturalWidth > 100")   # a real PNG, not a broken image
    assert ann.locator("[data-shr-picture-msg]").inner_text().startswith("This is what")
    ann.locator("[data-shr-note]").fill("Picture attached: have a look.")
    ann.locator("[data-shr-note]").press("Control+Enter")
    wait(ann, "document.querySelector('[data-shr-result]').textContent.indexOf('Sent to 1') === 0")

    ravi = sign_in(browser, base, "ravi", PASSWORD)
    ravi.goto(base + "/inbox")
    ravi.locator(".inbox-row.unread a.inbox-open").first.click()
    ravi.locator("[data-share-banner]").wait_for()
    ravi.locator("[data-share-picture] summary").click()
    wait(ravi, "document.querySelector('[data-share-picture] img').naturalWidth > 100")
    ann.context.close()
    ravi.context.close()


def test_without_the_tick_no_picture_travels(stack, people, browser):
    base = str(stack)
    ann = sign_in(browser, base, "ann", PASSWORD)
    open_dialog(ann, base)
    assert ann.locator("[data-shr-preview-img]").is_hidden()
    ann.locator("[data-shr-note]").fill("No picture this time.")
    ann.locator("[data-shr-note]").press("Control+Enter")
    wait(ann, "document.querySelector('[data-shr-result]').textContent.indexOf('Sent to 1') === 0")
    ravi = sign_in(browser, base, "ravi", PASSWORD)
    ravi.goto(base + "/inbox")
    ravi.locator(".inbox-row.unread a.inbox-open").first.click()
    ravi.locator("[data-share-banner]").wait_for()
    assert ravi.locator("[data-share-picture]").count() == 0
    ann.context.close()
    ravi.context.close()
