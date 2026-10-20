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

"""An HTML5 drag and drop that waits for the browser at each step.

Playwright's ``drag_and_drop`` presses, moves and releases in one go. On a loaded machine Chromium may not have started the drag (no
``dragstart`` yet, or no ``dragover`` on the target) when the button is released, and the drop is lost: the page saw nothing to refuse.
``drag`` presses on the source, moves a little until the page has seen ``dragstart``, moves onto the target until the target has seen
``dragover``, and only then releases."""
from __future__ import annotations

_WATCH = """([src, dst]) => { const w = window.__dnd = {start: false, over: false};
  const s = document.querySelector(src);
  document.addEventListener('dragstart', (e) => { if (s && (e.target === s || s.contains(e.target))) { w.start = true; } }, {capture: true});
  document.addEventListener('dragover', (e) => { const t = document.querySelector(dst); if (t && (e.target === t || t.contains(e.target))) { w.over = true; } }, {capture: true}); }"""


def drag(page, source: str, target: str, target_position: dict | None = None, timeout: int = 15000) -> None:
    """Drags the first match of ``source`` onto the first match of ``target`` (at ``target_position`` in it, else its middle)."""
    src, dst = page.locator(source).first, page.locator(target).first
    src.scroll_into_view_if_needed()
    page.evaluate(_WATCH, [source, target])
    b = src.bounding_box()
    x, y = b["x"] + b["width"] / 2, b["y"] + b["height"] / 2
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x + 8, y + 8, steps=4)
    page.wait_for_function("() => window.__dnd.start", timeout=timeout)          # the browser has started the drag
    t = dst.bounding_box()
    pos = target_position or {"x": t["width"] / 2, "y": t["height"] / 2}
    tx, ty = t["x"] + pos["x"], t["y"] + pos["y"]
    page.mouse.move(tx, ty, steps=10)
    try:
        page.wait_for_function("() => window.__dnd.over", timeout=timeout)       # the target has been offered the drag
    except Exception:  # noqa: BLE001 - one more small move over the target, then the same wait
        page.mouse.move(tx + 2, ty + 2, steps=2)
        page.wait_for_function("() => window.__dnd.over", timeout=timeout)
    page.mouse.up()
