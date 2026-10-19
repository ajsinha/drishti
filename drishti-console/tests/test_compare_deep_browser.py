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

"""Compare on a deep, production-shaped trade in a real browser (Chromium): two business dates of Endur deal 1000001 whose second leg's
float spread was amended. The change list names the path inside the legs and cash flows (LEGS[2].CASH_FLOWS[5000060].FLOAT_SPREAD,
matched by their ids), and beside it the side-by-side panel shows the ticket and each leg's terms on both dates with the changed fields
marked. The console runs in-process against a stand-in server; skipped when Playwright's Chromium is missing."""
import threading
import time

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402


def _view(version: str, spread: str) -> dict:
    """The deal screen as the server builds it: the ticket (kv) and the legs side by side (tabs with a kv part each)."""
    def kv(fields):
        return {"kind": "kv", "data": {"fields": [{"label": k, "text": v} for k, v in fields]}}
    return {"ref": {"kind": "trade", "id": "1000001"}, "title": {"id": "1000001"}, "panels": [
        {"id": "ticket", "kind": "kv", "title": "Deal ticket",
         "data": {"fields": [{"label": "Trade id", "text": "1000001"}, {"label": "Source system", "text": "ENDUR"},
                             {"label": "Version", "text": version}, {"label": "Product", "text": "ComSwap"}]}},
        {"id": "legs", "kind": "tabs", "title": "Legs", "data": {"tabs": [
            {"title": "Leg 1 · Fixed price FIXED", "panels": [kv([("Notional", "72,000"), ("Price", "2,332.9131")])]},
            {"title": "Leg 2 · Floating price FLOAT · IDX-ALU-3M", "panels": [kv([("Notional", "72,000"), ("Spread", spread)])]}]}}]}


class DeepBackend(FakeBackend):
    async def history_diff(self, kind, id_, ident=None, **params):
        return {"ref": {"kind": kind, "id": id_}, "added": 0, "removed": 0, "changed": 2, "truncated": False,
                "from": {"businessDate": "2026-09-22", "knownAt": None, "provenance": {"source": "trade-endur", "generation": 3}},
                "to": {"businessDate": "2026-09-30", "knownAt": None, "provenance": {"source": "trade-endur", "generation": 9}},
                "changes": [{"path": "TRADE_VERSION", "label": "Trade version", "kind": "changed", "before": "4", "after": "5", "delta": None},
                            {"path": "LEGS[2].CASH_FLOWS[5000060].FLOAT_SPREAD", "label": "Float spread [5000060]", "kind": "changed",
                             "before": 0.0, "after": 0.0025, "delta": 0.0025}]}

    async def view_on(self, kind, id_, ident, business_date):
        return _view("4", "0.0000") if business_date == "2026-09-22" else _view("5", "0.0025")


@pytest.fixture(scope="module")
def url():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = DeepBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def page(url):
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        pg = b.new_page(viewport={"width": 1400, "height": 900})
        yield pg
        b.close()


def test_two_dates_of_a_deep_trade_side_by_side_with_the_leg_change_marked(page, url):
    page.goto(url + "/compare/trade/1000001?from=2026-09-22&to=2026-09-30", wait_until="load")
    paths = page.eval_on_selector_all(".cmp-path", "els => els.map(e => e.innerText)")
    assert "LEGS[2].CASH_FLOWS[5000060].FLOAT_SPREAD" in paths                       # a path inside the legs, by the arrays' own ids
    side = page.locator("section.cmp-side")
    assert side.is_visible()
    sections = page.eval_on_selector_all("table.cmp-sbs", "els => els.map(e => e.getAttribute('data-cmp-section'))")
    assert sections == ["Deal ticket", "Leg 1 · Fixed price FIXED", "Leg 2 · Floating price FLOAT · IDX-ALU-3M", "Cash flows, resets and events"]
    marked = page.eval_on_selector_all('table.cmp-sbs:not([data-cmp-section^="Cash flows"]) tr[data-cmp-diff] th', "els => els.map(e => e.innerText.trim())")
    assert marked == ["Version", "Spread"]                                             # only what moved is marked
    inside = page.eval_on_selector_all('table.cmp-sbs[data-cmp-section^="Cash flows"] tr[data-cmp-diff] th', "els => els.map(e => e.innerText.trim())")
    assert inside and all(t.startswith("Leg 2 › Cash flow ") for t in inside), inside   # the changes inside the leg's lists, said where they sit
    leg2 = page.locator('table.cmp-sbs[data-cmp-section^="Leg 2"] tr[data-cmp-diff] td')
    assert leg2.all_inner_texts() == ["0.0000", "0.0025"]
    box_side, box_list = side.bounding_box(), page.locator(".cmp-list").bounding_box()
    assert box_side["x"] + box_side["width"] <= box_list["x"] + 1                   # beside the change list on a wide screen
