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

"""The slider control (static/js/controls.js, RACHANA_REFERENCE.md "Controls") in a real browser (Chromium): one handle and two, the ARIA
values (aria-valuemin / -max / -now), every key (arrows, PageUp, PageDown, Home, End), a click on the track and a drag, the two handles that
cannot cross, the value in the address and restored from it, only the panel that reads the control re-requested, and a 44 px thumb on a
phone. The console runs in-process against a stand-in server that evaluates the controls as the real one does; skipped when Playwright's
Chromium is missing."""
import asyncio  # noqa: F401 - the stand-in's stream is an async generator
import json
import threading
import time

import pytest

from conftest import CONSOLE

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import TickingBackend, _free_port  # noqa: E402

VIEW = "/v/trade/IRS-48213"
SCHEDULE = [(1, "2026-10-15", 100), (2, "2026-10-20", 200), (1, "2026-11-15", 300), (2, "2026-12-20", 400), (1, "2027-01-15", 500), (2, "2027-02-20", 600)]
LO, HI, STEP = 0, 600, 100


def clamp(text, dflt):
    try:
        v = float(text)
    except (TypeError, ValueError):
        return dflt
    return min(HI, max(LO, round(v / STEP) * STEP))


class SliderBackend(TickingBackend):
    """A `schedule` ladder that keeps the amounts from `ctl.floor` up and inside the `ctl.band` range; `other` reads neither."""

    def __init__(self):
        super().__init__()
        self.asked = []

    def state(self, ctl):
        floor = clamp(ctl.get("ctl.floor"), LO)
        a, _, b = (ctl.get("ctl.band") or "").partition("..")
        lo, hi = clamp(a, LO), clamp(b, HI)
        return dict(floor=floor, lo=min(lo, hi), hi=max(lo, hi))

    def schedule(self, st):
        rows = [r for r in SCHEDULE if r[2] >= st["floor"] and st["lo"] <= r[2] <= st["hi"]]
        return {"id": "schedule", "kind": "ladder", "title": "Cashflows", "area": "main", "data": {
            "columns": ["Leg", "Pay date", "Amount"], "numeric": [False, False, True],
            "rows": [{"cells": [{"text": str(leg)}, {"text": d}, {"text": str(a)}]} for leg, d, a in rows]}}

    def controls(self, st):
        return [
            {"id": "floor", "kind": "slider", "label": "Amount from", "placement": "strip", "min": str(LO), "max": str(HI), "step": str(STEP), "range": False,
             "value": str(st["floor"]), "required": False, "remember": False, "reads": ["schedule"]},
            {"id": "band", "kind": "slider", "label": "Amount band", "placement": "strip", "min": str(LO), "max": str(HI), "step": str(STEP), "range": True,
             "value": f"{st['lo']}..{st['hi']}", "required": False, "remember": False, "reads": ["schedule"]},
        ]

    def other(self):
        return {"id": "other", "kind": "ladder", "title": "Other", "area": "main",
                "data": {"columns": ["A"], "numeric": [False], "rows": [{"cells": [{"text": "x"}]}]}}

    async def view(self, kind, id_, user, controls=None, panels=None):
        self.asked.append((dict(controls or {}), panels))
        vm = await super().view(kind, id_, user)
        st = self.state(controls or {})
        everything = {"schedule": self.schedule(st), "other": self.other()}
        vm["panels"] = [p for k, p in everything.items() if panels is None or k in panels]
        vm["controls"] = self.controls(st)
        vm["selects"] = []
        vm["strip"] = [{"label": "Tick", "text": "0"}]
        return vm

    async def stream(self, kind, id_, ident=None, opened=None, controls=None):
        yield "view", json.dumps({"provenance": {"generation": 1}})
        while True:
            await asyncio.sleep(5)


@pytest.fixture(scope="module")
def backend():
    return SliderBackend()


@pytest.fixture(scope="module")
def url(backend):
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
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
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e).splitlines()[0]}")
        yield b
        b.close()


@pytest.fixture
def page(browser, url, backend):
    ctx = browser.new_context(viewport={"width": 1280, "height": 900}, bypass_csp=True)
    pg = ctx.new_page()
    backend.asked.clear()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("[data-ctl='floor'] [role=slider]")
    yield pg
    ctx.close()


def amounts(page):
    return page.evaluate("() => [...document.querySelectorAll('#p-schedule tbody tr')].map(r => r.cells[2].textContent.trim())")


def thumb(page, ctl, i=0):
    return page.locator(f"[data-ctl='{ctl}'] [role=slider]").nth(i)


def done(page, action):
    n = page.evaluate("(window.__drishtiControls || []).length")
    action()
    page.wait_for_function("(n) => (window.__drishtiControls || []).length > n", arg=n, timeout=10000)


def now(page, ctl, i=0):
    return thumb(page, ctl, i).get_attribute("aria-valuenow")


def test_a_slider_is_drawn_with_its_aria_values_and_a_range_has_two_handles(page):
    t = thumb(page, "floor")
    assert (t.get_attribute("aria-valuemin"), t.get_attribute("aria-valuemax"), t.get_attribute("aria-valuenow")) == ("0", "600", "0")
    assert t.get_attribute("aria-label") == "Amount from" and t.get_attribute("tabindex") == "0"
    assert page.locator("[data-ctl='band'] [role=slider]").count() == 2
    assert thumb(page, "band", 0).get_attribute("aria-label") == "Amount band from" and thumb(page, "band", 1).get_attribute("aria-label") == "Amount band to"
    assert (now(page, "band", 0), now(page, "band", 1)) == ("0", "600")
    assert page.locator("[data-ctl='floor'] output").inner_text() == "0" and page.locator("[data-ctl='band'] output").inner_text() == "0 – 600"
    assert amounts(page) == ["100", "200", "300", "400", "500", "600"]


def test_the_keys_move_by_a_step_ten_steps_and_to_the_ends(page, backend):
    t = thumb(page, "floor")
    t.focus()
    done(page, lambda: page.keyboard.press("ArrowRight"))
    assert now(page, "floor") == "100" and amounts(page) == ["100", "200", "300", "400", "500", "600"]
    done(page, lambda: page.keyboard.press("ArrowUp"))
    assert now(page, "floor") == "200" and amounts(page) == ["200", "300", "400", "500", "600"]
    done(page, lambda: page.keyboard.press("ArrowLeft"))
    assert now(page, "floor") == "100"
    done(page, lambda: page.keyboard.press("PageUp"))
    assert now(page, "floor") == "600" and amounts(page) == ["600"]                      # ten steps, held to the top
    done(page, lambda: page.keyboard.press("Home"))
    assert now(page, "floor") == "0" and len(amounts(page)) == 6
    done(page, lambda: page.keyboard.press("End"))
    assert now(page, "floor") == "600"
    done(page, lambda: page.keyboard.press("PageDown"))
    assert now(page, "floor") == "0"
    assert "ctl.floor=0" in page.evaluate("location.search")
    asked = [p for _, p in backend.asked if p is not None]
    assert asked and all(list(p) == ["schedule"] for p in asked), asked      # the panel that reads it, no other


def test_the_two_handles_of_a_range_cannot_cross(page):
    low, high = thumb(page, "band", 0), thumb(page, "band", 1)
    high.focus()
    done(page, lambda: page.keyboard.press("PageDown"))
    assert (now(page, "band", 0), now(page, "band", 1)) == ("0", "0")                     # ten steps down, held at the other handle
    done(page, lambda: page.keyboard.press("End"))
    assert now(page, "band", 1) == "600"
    low.focus()
    done(page, lambda: page.keyboard.press("PageUp"))
    assert (now(page, "band", 0), now(page, "band", 1)) == ("600", "600")                 # up to the other handle, not past it
    high.focus()
    page.keyboard.press("Home")                                                          # the upper handle's lowest is the lower handle
    assert (now(page, "band", 0), now(page, "band", 1)) == ("600", "600")
    low.focus()
    done(page, lambda: page.keyboard.press("Home"))
    assert (now(page, "band", 0), now(page, "band", 1)) == ("0", "600")
    assert page.evaluate("location.search").count("ctl.band=") == 1


def test_a_click_on_the_track_moves_the_nearest_handle_and_a_drag_follows_the_pointer(page):
    track = page.locator("[data-ctl='floor'] [data-ctl-track]")
    box = track.bounding_box()
    done(page, lambda: page.mouse.click(box["x"] + box["width"] * 0.5, box["y"] + box["height"] / 2))
    assert now(page, "floor") == "300"
    t = thumb(page, "floor").bounding_box()
    page.mouse.move(t["x"] + t["width"] / 2, t["y"] + t["height"] / 2)
    n = page.evaluate("(window.__drishtiControls || []).length")
    page.mouse.down()
    page.mouse.move(box["x"] + box["width"] * 0.84, box["y"] + box["height"] / 2, steps=6)
    page.mouse.up()
    page.wait_for_function("(n) => (window.__drishtiControls || []).length > n", arg=n, timeout=10000)
    assert now(page, "floor") == "500"
    assert amounts(page) == ["500", "600"]


def test_the_value_is_in_the_address_and_is_restored_from_it(page, url):
    thumb(page, "floor").focus()
    done(page, lambda: page.keyboard.press("End"))
    done(page, lambda: (thumb(page, "band", 0).focus(), page.keyboard.press("ArrowRight")))
    query = page.evaluate("location.search")
    assert "ctl.floor=600" in query and "ctl.band=100..600" in query
    page.goto(url + VIEW + query, wait_until="load")
    page.wait_for_selector("[data-ctl='floor'] [role=slider]")
    assert now(page, "floor") == "600" and (now(page, "band", 0), now(page, "band", 1)) == ("100", "600")
    assert amounts(page) == ["600"]


def test_the_thumb_is_44_px_on_a_phone(browser, url):
    ctx = browser.new_context(viewport={"width": 390, "height": 800}, has_touch=True, is_mobile=True, bypass_csp=True)
    pg = ctx.new_page()
    pg.goto(url + VIEW, wait_until="load")
    pg.wait_for_selector("[data-ctl='floor'] [role=slider]")
    box = pg.locator("[data-ctl='floor'] [role=slider]").bounding_box()
    assert box["width"] >= 44 and box["height"] >= 44, box
    ctx.close()
