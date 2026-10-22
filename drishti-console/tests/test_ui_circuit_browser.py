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

""""A board should look like a circuit", in a real browser (Chromium): every wire is straight segments with 90° bends (the SVG path parsed),
a crossing hops, a fan-out has a junction dot, the live "current" respects prefers-reduced-motion, and the wires re-route when a chip moves."""
import re

import pytest

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_ui_board_browser import BOOK, browser, page, put_board  # noqa: E402,F401
from ui_board_backend import serve  # noqa: E402

CROSS = {"rachana": 1, "board": "crossing", "title": "Crossing", "chips": {
    "a": {"use": "search-box@1"}, "y": {"use": "filterable-table@2"}, "z": {"use": "search-box@1"}, "w": {"use": "filterable-table@2"}},
    "traces": [{"from": "a.filter-out", "to": "y.filter"}, {"from": "z.filter-out", "to": "w.filter"}],
    "layout": {"desktop": {"columns": 12, "areas": [{"chip": "a", "at": [1, 1], "span": [3, 2]}, {"chip": "y", "at": [9, 7], "span": [4, 3]},
                                                     {"chip": "z", "at": [1, 4], "span": [3, 2]}, {"chip": "w", "at": [9, 4], "span": [4, 2]}]}}}


@pytest.fixture(scope="module")
def stack():
    url, backend, stop = serve()
    yield url, backend
    stop()


def segments(d):
    """The straight segments of a path (M, L), skipping the hops (A arcs), whose ends continue the run."""
    out, cur = [], None
    for cmd, args in re.findall(r"([MLA])\s*([^MLA]+)", d):
        nums = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?", args)]
        end = (nums[-2], nums[-1])
        if cmd == "L":
            out.append((cur, end))
        cur = end
    return out


def wires(page):
    return page.eval_on_selector_all("svg[data-ub-wires] path.ub-wire[data-trace]", "ps => ps.map(p => ({d: p.getAttribute('d'), hops: p.getAttribute('data-hops'), to: p.dataset.to}))")


def test_wires_are_orthogonal_hop_at_crossings_and_join_with_a_dot(page, stack):
    url, be = stack
    put_board(be, "book-circuit", BOOK)
    page.goto(url + "/build/board/book-circuit?advanced=1")
    page.wait_for_selector("svg[data-ub-wires] path.ub-wire[data-trace]", state="attached")
    page.wait_for_function("document.querySelectorAll('svg[data-ub-wires] path.ub-wire[data-trace]').length >= 2")
    for w in wires(page):
        for (x1, y1), (x2, y2) in segments(w["d"]):
            assert abs(x1 - x2) < 0.6 or abs(y1 - y2) < 0.6, w["d"]
    assert page.locator("svg[data-ub-wires] circle.ub-junction").count() >= 1         # trades.selected fans out to the ticket and the flows
    assert page.evaluate("getComputedStyle(document.querySelector('.ub-canvas')).backgroundImage").startswith("radial-gradient")
    # a chip moved: the wires re-route
    before = [w["d"] for w in wires(page)]
    page.locator('.ub-box[data-instance="ticket"]').focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("ArrowDown")
    page.wait_for_function("ds => Array.from(document.querySelectorAll('svg[data-ub-wires] path.ub-wire[data-trace]')).map(p => p.getAttribute('d')).join() !== ds.join()", arg=before)
    put_board(be, "crossing", CROSS)
    page.goto(url + "/build/board/crossing?advanced=1")
    page.wait_for_function("document.querySelectorAll('svg[data-ub-wires] path.ub-wire[data-trace]').length >= 2")
    ws = wires(page)
    assert any(w["hops"] for w in ws), ws
    for w in ws:
        for (x1, y1), (x2, y2) in segments(w["d"]):
            assert abs(x1 - x2) < 0.6 or abs(y1 - y2) < 0.6, w["d"]
    assert " A" in next(w["d"] for w in ws if w["hops"])
    assert page.errors == []


def test_live_current_flows_unless_motion_is_reduced(page, stack):
    url, be = stack
    put_board(be, "book-current", BOOK)
    page.goto(url + "/build/board/book-current?advanced=1")
    page.wait_for_function("document.querySelectorAll('svg[data-ub-wires] path.ub-wire[data-trace]').length >= 2")
    page.evaluate("window.drishtiUiBoard.canvas.flow('ticket')")
    assert page.evaluate("getComputedStyle(document.querySelector('path.ub-wire.ub-flow')).animationName") == "ub-current"
    page.emulate_media(reduced_motion="reduce")
    page.evaluate("window.drishtiUiBoard.canvas.flow('ticket')")
    assert page.evaluate("getComputedStyle(document.querySelector('path.ub-wire.ub-flow')).animationName") == "none"
    assert page.errors == []
