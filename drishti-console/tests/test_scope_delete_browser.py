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

"""Deleting a saved VaR basket in Chromium (docs/guides/VAR_GUIDE.md section 3.5), the console in-process against a stand-in server that
keeps two baskets (the test console signs in as ash, an administrator): on the VaR explorer's My baskets drop-down, Delete (reached from the keyboard) opens the confirm dialog naming the basket
and whom it is shared with, Escape keeps it, Delete removes it, the list refreshes and the page says it was deleted; on Admin -> Ad-hoc
scopes an administrator deletes someone else's with a reason, sent with the version the dialog showed. Skipped without Playwright or
Chromium."""
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402


def _baskets():
    return [{"id": "ash/unwind", "owner": "ash", "name": "Unwind candidates", "type": "BASKET",
             "scope": "TRD:T1,T2", "sharedWith": ["ola", "role:risk"], "createdAt": "2026-10-01T09:00:00Z",
             "updatedAt": "2026-10-02T09:00:00Z", "version": 2, "bytes": 300},
            {"id": "rita/hedges", "owner": "rita", "name": "Rates hedges", "type": "RULE", "scope": "WHERE:'hedge' in TAGS",
             "sharedWith": [], "createdAt": "2026-10-03T09:00:00Z", "updatedAt": "2026-10-03T09:00:00Z", "version": 1, "bytes": 250}]


class BasketBackend(FakeBackend):
    """The engine's basket endpoints over a list; every other VaR answer is empty (the page draws what it can)."""

    def __init__(self):
        super().__init__()
        self.kept = _baskets()
        self.deleted = []

    async def var(self, method, path, ident, /, body=None, raw=False, accept="", **params):
        if path == "/baskets":
            return [dict(b) for b in self.kept]
        if path == "/admin/baskets":
            return [dict(b) for b in self.kept if params.get("owner", "") in b["owner"]]
        if method == "DELETE" and path.startswith("/baskets/"):
            bid = path[len("/baskets/"):]
            self.deleted.append((bid, params))
            self.kept = [b for b in self.kept if b["id"] != bid]
            return None
        if path == "/tree":
            return {"nodes": [], "portfolios": [], "businessDate": "2026-09-30"}
        from core.backend import BackendError
        raise BackendError(404, "DRS-1003", "nothing here in this test")


@pytest.fixture(scope="module")
def served():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    be = BasketBackend()
    app.state.backend = be
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}", be
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


def test_delete_on_the_var_explorer_confirms_then_refreshes_the_list(served, browser):
    url, be = served
    be.kept, be.deleted = _baskets(), []
    pg = browser.new_page()
    try:
        pg.goto(url + "/var?scope=BASKET:ash/unwind")
        pg.wait_for_selector('[data-vx-baskets-mine] [data-vx-delete="ash/unwind"]', state="attached")
        # an administrator: Delete on someone else's basket too
        assert pg.locator('[data-vx-baskets-mine] [data-vx-delete="rita/hedges"]').count() == 1
        pg.click('[data-vx-pick="mine"] [data-vx-pick-btn]')                  # My baskets (2.9.11.b: the toolbar's drop-down)
        btn = pg.locator('[data-vx-delete="ash/unwind"]')
        btn.focus()
        pg.keyboard.press("Enter")                                        # from the keyboard
        dlg = pg.locator("[data-sd-dialog]")
        dlg.wait_for(state="visible")
        text = dlg.inner_text()
        assert "Unwind candidates" in text and "owned by you" in text and "ola, the role risk" in text and "Captures taken of it are kept" in text
        assert not pg.is_visible("[data-sd-reason]")                      # your own: no reason asked
        assert pg.evaluate("document.activeElement.hasAttribute('data-sd-no')")   # focus starts on Keep it
        pg.keyboard.press("Escape")                                       # kept
        dlg.wait_for(state="hidden")
        assert be.deleted == [] and pg.evaluate("document.activeElement.dataset.vxDelete") == "ash/unwind"
        btn.focus()
        pg.keyboard.press("Enter")
        dlg.wait_for(state="visible")
        pg.click("[data-sd-yes]")
        dlg.wait_for(state="hidden")
        pg.locator('[data-vx-delete="ash/unwind"]').wait_for(state="detached")
        assert be.deleted == [("ash/unwind", {"version": "2"})]
        notice = pg.inner_text("[data-vx-notice]")
        assert "Unwind candidates" in notice and "was deleted" in notice and "no longer exists" in notice   # the open view says so
        assert pg.locator('[data-vx-delete="rita/hedges"]').count() == 1
    finally:
        pg.close()


def test_admin_deletes_someone_elses_basket_with_a_reason(served, browser):
    url, be = served
    be.kept, be.deleted = _baskets(), []
    pg = browser.new_page()
    try:
        pg.goto(url + "/admin/scopes")
        row = pg.locator('tr[data-basket="rita/hedges"]')
        assert "Rates hedges" in row.inner_text() and pg.locator("tr[data-basket]").count() == 2
        row.locator("[data-scope-delete]").click()
        dlg = pg.locator("[data-sd-dialog]")
        dlg.wait_for(state="visible")
        assert "owned by rita" in dlg.inner_text() and "as an administrator" in dlg.inner_text() and "Not shared with anyone" in dlg.inner_text()
        pg.fill("[data-sd-reason]", "left the desk")
        pg.click("[data-sd-yes]")
        pg.locator('tr[data-basket="rita/hedges"]').wait_for(state="detached")
        assert be.deleted == [("rita/hedges", {"version": "1", "reason": "left the desk"})]
        assert "rita was told in their inbox" in pg.inner_text("[data-scope-admin] [data-msg]")
        pg.goto(url + "/admin/scopes?owner=rita")                          # the filter: none of rita's left
        assert pg.locator("tr[data-basket]").count() == 0 and "No baskets of an owner matching" in pg.inner_text("[data-scope-table]")
    finally:
        pg.close()
