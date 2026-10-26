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

"""Write-back in a real browser (docs/architecture/WRITE_BACK.md W5-W7, tutorial 24), against the built server jar with editing on: a trade's
notional corrected beside the book desk's table, Submit and the review and confirm sheet (the difference, where it goes, who approves,
the reason), Review -> Changes, the approval by another person, the sheet following the change to Applied, the new value shown to a
second browser watching the book (drawn again by itself, with "Changed ... just now"), and the sheet at phone width. The pictures are tutorial 24's. Skipped without Playwright, Chromium,
the jar or JDK 25."""
import json
import urllib.request
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

IMG = Path(__file__).resolve().parents[2] / "docs" / "tutorials" / "img" / "t24"
DESK = "/v/portfolio-trades/BOOK-RATES-2?sutra=book-desk"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {"DRISHTI_EDIT_ENABLED": "true"})


def open_desk(browser, base, size=None):
    pg = browser.new_page(viewport=size or {"width": 1500, "height": 950})
    pg.goto(base + DESK)
    wait(pg, "window.drishtiForms && Object.keys(drishtiForms.state()).length > 0 && window.drishtiWriteBack")
    return pg


def row_under_limit(pg):
    """The first trade of the book under the desk limit (its rules hold without a comment): (row index, trade id, notional)."""
    return pg.evaluate("""(() => { const f = JSON.parse(document.querySelector('#p-trades').getAttribute('data-form'));
      const i = f.rows.findIndex((r, k) => k < 20 && r.values.notional > 0 && r.values.notional < 29000000);
      return [i, f.rows[i].key, f.rows[i].values.notional]; })()""")


def approve_as(stack, change, user):
    req = urllib.request.Request(stack.server + "/api/v1/changes/" + change + "/approve", data=json.dumps({"note": "checked against the ticket"}).encode(),
                                 headers={"Content-Type": "application/json", "X-Drishti-User": user}, method="POST")
    return json.loads(urllib.request.urlopen(req, timeout=20).read())


def test_a_correction_is_submitted_approved_applied_and_seen_by_another_browser(stack, browser):
    pg = open_desk(browser, stack)
    assert pg.evaluate("drishtiWriteBack.settings().then(s => s.enabled && s.mayEdit)")
    i, trade, notional = row_under_limit(pg)
    assert i >= 0
    pg.click(f"#p-trades tbody tr[data-frow='{i}'] td:nth-child(2)")
    pg.locator("[data-fm-side]").wait_for()
    assert "Submit keeps a correction over the source after one approver" in pg.locator("[data-fm-send-note]").inner_text()
    wanted = int(notional) + 1250
    pg.fill("[data-fm-side] [data-field='notional']", f"{wanted:,}")
    pg.click("[data-fm-side] [data-fm-submit]")
    sheet = pg.locator("[data-wb-sheet]")
    sheet.wait_for()
    wait(pg, "document.querySelector('[data-wb-sheet] [data-wb-change]')")
    text = sheet.inner_text()
    assert "Correction kept by" in text and "One approver (not you)" in text and "Reason (required)" in text
    assert trade in text
    pg.fill("[data-wb-sheet] .wb-reason", "Booking typo: the ticket says " + f"{wanted:,}")
    IMG.mkdir(parents=True, exist_ok=True)
    pg.screenshot(path=str(IMG / "review-sheet.jpg"), type="jpeg", quality=84)
    pg.click("[data-wb-sheet] .wb-confirm")
    wait(pg, "document.querySelector('[data-wb-sheet] [data-wb-id]')")
    change = pg.get_attribute("[data-wb-sheet] [data-wb-id]", "data-wb-id")
    assert "Waiting for an approver" in sheet.inner_text()

    queue = browser.new_page(viewport={"width": 1500, "height": 950})
    queue.goto(stack + "/review/changes")
    queue.locator(f"[data-change='{change}']").wait_for()
    queue.screenshot(path=str(IMG / "review-changes.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 700})
    queue.close()

    other = open_desk(browser, stack)                               # another person watching the book (its source does not stream)
    wait(other, "window.DrishtiChannel && document.readyState === 'complete'")
    other.wait_for_timeout(1500)                                    # its console's live connection is open
    assert approve_as(stack, change, "kim")["state"] in ("queued", "applied")
    wait(pg, f"(document.querySelector('[data-wb-id=\"{change}\"] [data-wb-state]') || {{}}).textContent.startsWith('Applied')")
    pg.screenshot(path=str(IMG / "applied.jpg"), type="jpeg", quality=84)
    shown = f"{wanted:,}"
    wait(other, f"(document.querySelector('#p-trades') || {{innerText: ''}}).innerText.includes('{shown}') && !!document.querySelector('[data-edit-mark]')")
    assert "just now" in other.locator("[data-edit-mark]").inner_text()
    other.screenshot(path=str(IMG / "other-browser.jpg"), type="jpeg", quality=84, clip={"x": 0, "y": 0, "width": 1500, "height": 900})
    assert pg.evaluate("fetch('/api/drafts').then(r => r.json()).then(l => l.length)") == 0   # the draft was closed with the change id
    other.close()
    pg.close()


def test_the_sheet_at_phone_width(stack, browser):
    pg = open_desk(browser, stack, {"width": 390, "height": 844})
    i, trade, notional = row_under_limit(pg)
    pg.evaluate(f"drishtiForms.open('trades', {i})")
    pg.locator("[data-fm-side]").wait_for()
    pg.fill("[data-fm-side] [data-field='notional']", f"{int(notional) + 10:,}")
    pg.click("[data-fm-side] [data-fm-submit]")
    wait(pg, "document.querySelector('[data-wb-sheet] [data-wb-change]')")
    assert not pg.evaluate("document.documentElement.scrollWidth > window.innerWidth + 1")
    box = pg.locator("[data-wb-sheet]").bounding_box()
    assert box["width"] <= 392
    pg.screenshot(path=str(IMG / "phone.jpg"), type="jpeg", quality=84)
    pg.close()
