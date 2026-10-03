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

"""Step 8 in a real browser over a real server: design -> check -> propose -> approve -> live, the reviewer seeing the evidence, a
read-only share link that shows names and not contents, and a design bound to a file whose edit on disk comes back.

Skipped when Playwright, its Chromium, the server jar or JDK 25 is missing."""
import re

import pytest

from test_workbench_browser import browser, page, wait  # noqa: F401 - fixtures
from wb_live import live_ship_console, new_design  # noqa: F401 - the fixture

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

SUTRA = """rachana: 1
sutra: ship-e2e
version: 1
match: { kind: trade, priority: 100 }
title: { id: $.tradeId }
panels:
  - id: terms
    kind: kv
    columns:
      - { label: Book, bind: $.book }
"""


def _open(page, base, name, sutra=SUTRA):
    id_ = new_design(page, base, name, sutra=sutra, files={"a.json": '{"tradeId": "T-1", "book": "CONFIDENTIAL-BOOK"}', "b.json": '{"tradeId": "T-2", "book": "rates"}'})
    page.goto(f"{base}/build/d/{id_}")
    wait(page, "window.drishtiWorkbench && window.drishtiWorkbench.store.state.id")
    page.locator("[data-preview] [data-panel]").first.wait_for(timeout=20000)
    return id_


def test_design_to_live_with_the_evidence_and_a_share_link(live_ship_console, page, browser):
    base = live_ship_console
    id_ = _open(page, base, "Ship E2E")

    # propose: the button submits with the check matrix; the status follows
    page.fill("[data-note]", "first cut")
    page.locator("[data-save]").click()
    wait(page, "document.querySelector('[data-say]').textContent.indexOf('Submitted for review as P-') >= 0")
    pid = re.search(r"P-\d+", page.locator("[data-say]").inner_text()).group(0)
    wait(page, "document.querySelector('[data-status-chip]').textContent.indexOf('proposed') >= 0")

    # the reviewer sees the matrix, the sample names and no sample contents
    page.goto(f"{base}/build/reviews/{pid}")
    ev = page.locator("[data-evidence]")
    ev.wait_for()
    text = ev.inner_text()
    assert "every sample renders without errors" in text and "a.json" in text and "b.json" in text and "terms" in text
    assert "CONFIDENTIAL-BOOK" not in page.content()
    page.get_by_role("button", name=re.compile("Approve")).click()
    page.get_by_text("approved").first.wait_for()

    # approval made the design live(v1): My designs and the workbench say so
    page.goto(f"{base}/build")
    assert "live v1" in page.locator(f"[data-design='{id_}']").inner_text()
    page.goto(f"{base}/build/d/{id_}")
    wait(page, "document.querySelector('[data-status-chip]').textContent.indexOf('live v1') >= 0")

    # share: a read-only link with names and not contents; revoking stops it
    page.locator("[data-ship-menu]").click()
    page.get_by_role("option", name=re.compile("Create a read-only link")).click()
    page.locator("[data-share-box]:not([hidden])").wait_for()
    wait(page, "document.querySelector('[data-share-url]').value.indexOf('?share=') > 0")
    link = page.locator("[data-share-url]").input_value()
    share = browser.new_page()
    share.goto(link)
    share.locator("[data-shared]").wait_for()
    body = share.content()
    assert "a.json" in body and "sutra: ship-e2e" in body and "CONFIDENTIAL-BOOK" not in body and "data-yaml-src" not in body
    page.locator("[data-share-revoke]").click()
    wait(page, "document.querySelector('[data-share-box]').hidden")
    share.reload()
    assert "not valid" in share.content()
    share.close()


def test_a_bound_design_saves_to_the_file_and_an_edit_on_disk_comes_back(live_ship_console, page):
    base = live_ship_console
    _open(page, base, "Bound E2E", sutra=SUTRA.replace("ship-e2e", "bound-e2e"))
    page.locator("[data-ship-menu]").click()
    page.get_by_role("option", name=re.compile("Bind to a file")).click()
    page.get_by_role("dialog", name="Bind to a file").get_by_label("File").fill("e2e/bound-e2e.v1.sutra.yaml")      # an in-page question, not prompt()
    page.get_by_role("button", name="Bind", exact=True).click()
    wait(page, "document.querySelector('[data-bound-chip]') && !document.querySelector('[data-bound-chip]').hidden")
    page.locator("[data-save]").click()
    wait(page, "document.querySelector('[data-say]').textContent.indexOf('Wrote e2e/bound-e2e.v1.sutra.yaml') >= 0")
    target = next(iter((base.work / "dev-sutras").glob("*/e2e/bound-e2e.v1.sutra.yaml")))      # your own folder, not a loaded Sutra directory
    assert not (base.work / "sutras" / "e2e").exists()
    assert target.read_text() == SUTRA.replace("ship-e2e", "bound-e2e")

    # an edit made elsewhere (the IDE) is read back into the design by the sync
    target.write_text(SUTRA.replace("ship-e2e", "bound-e2e") + "# edited in the IDE\n")
    wait(page, "window.drishtiWorkbench.store.state.yaml.indexOf('# edited in the IDE') >= 0", seconds=20)
    wait(page, "document.querySelector('[data-say]').textContent.indexOf('changed on disk') >= 0")
