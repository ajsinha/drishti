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

"""Shared pieces of the BI tutorials' pictures (tutorials 10 to 14): the report page, the designer, a click on a map or a bar, cleaning up what an earlier
run made, and a call to the BI API through the signed-in console (`/bi/api/...` is passed on to the server's `/api/v1/bi/...`)."""
from __future__ import annotations

import json

from _tut import call, login  # noqa: F401
from shotlib import Ctx

WORLD = "file:dataset:world-economics"
RISK = "file:dataset:trading-risk"


def bi(c: Ctx, method: str, path: str, body: dict | None = None) -> dict:
    """A BI API call as the signed-in person."""
    return call(c, method, "/bi/api" + path, body)


def clean_items(c: Ctx, *titles: str, workspace: str = "me"):
    """Deletes the items of these titles from a workspace (default: the person's own), so a repeated run starts from nothing."""
    listing = bi(c, "GET", f"/workspaces/{workspace}/items")
    for it in (listing if isinstance(listing, list) else listing.get("items", [])) or []:
        if it.get("title") in titles:
            bi(c, "DELETE", "/items/" + it["id"])


def clean_everywhere(c: Ctx, *titles: str, workspace_names: tuple = ()):
    """Deletes what an earlier run made: the items of these titles (drafts and published) and the workspaces of these names."""
    clean_items(c, *titles)
    cat = bi(c, "GET", "/catalogue")
    for it in (cat.get("items", []) if isinstance(cat, dict) else cat):
        if it.get("title") in titles and not it.get("shipped"):
            bi(c, "DELETE", "/items/" + it["id"])
    ws = bi(c, "GET", "/workspaces")
    for w in ws if isinstance(ws, list) else ws.get("workspaces", []):
        if w.get("name") in workspace_names:
            for it in bi(c, "GET", f"/workspaces/{w['id']}/items") or []:
                bi(c, "DELETE", "/items/" + it["id"])
            bi(c, "DELETE", "/workspaces/" + w["id"])


def quiet_inbox(c: Ctx):
    """Marks the inbox read, so old notifications do not toast over a picture."""
    call(c, "POST", "/api/inbox/read", {"upTo": 1000000000})


def settled(page, timeout: int = 30000):
    """Every visual of a report has answered (no frame busy) and shows its rows-and-milliseconds line, or its error."""
    page.wait_for_function("() => !document.querySelector('.bi-vis-busy') && document.querySelectorAll('.bi-vis').length > 0 && "
                           "[...document.querySelectorAll('.bi-vis:not(.bi-vis-text):not(.bi-vis-button):not(.bi-vis-image):not(.bi-vis-entity-view)')].every(v => v.querySelector('.bi-vis-ms') || v.classList.contains('bi-vis-failed'))",
                           timeout=timeout)


def settled_design(page, timeout: int = 30000):
    page.wait_for_function("() => !document.querySelector('.bi-dvis.bi-vis-busy') && [...document.querySelectorAll('.bi-dvis')].every(v => v.querySelector('.bi-vis-ms') || v.querySelector('.bi-dropnote') || "
                           "v.querySelector('.bi-vis-error') || v.classList.contains('bi-vis-failed') || v.querySelector('.bi-text,.bi-button,.bi-picture'))", timeout=timeout)


def open_report(c: Ctx, item: str, page_id: str = "", width: int = 1280, height: int = 900, wait_map: bool = False, **query):
    """Opens a published report and waits until it has drawn."""
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(f"{c.base}/bi/r/{item}" + (f"/{page_id}" if page_id else "") + ("?" + "&".join(f"{k}={v}" for k, v in query.items()) if query else ""))
    c.page.wait_for_selector("[data-ready=yes]", timeout=30000)
    settled(c.page)
    if wait_map:
        c.page.wait_for_function("() => ![...document.querySelectorAll('.bi-vis-empty')].some(e => /Loading the (map|regions)/.test(e.textContent))", timeout=20000)
    c.page.wait_for_timeout(900)


def grid_cell(page, cx: int, cy: int) -> dict:
    """A point in the designer's canvas that falls in grid cell (cx, cy): where to drop a field."""
    w = page.evaluate("document.querySelector('[data-canvas-grid]').clientWidth")
    return {"x": cx * ((w - 110) / 12 + 10) + 20, "y": cy * 66 + 20}


def start_design(c: Ctx, dataset: str, title: str, width: int = 1280, height: int = 900):
    """The designer's start form filled and submitted; returns when the empty canvas is ready."""
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + "/bi/designer")
    c.page.wait_for_selector(f"[data-new-dataset] option[value='{dataset}']", state="attached")
    c.page.select_option("[data-new-dataset]", dataset)
    c.page.fill("[data-new-title]", title)


def submit_design(c: Ctx):
    c.page.click("[data-new] button[type=submit]")
    c.page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    c.page.wait_for_timeout(800)


def map_point(page, visual: str, lon: float, lat: float):
    """The page position of a longitude and latitude on a map visual, scrolled into view (what a person would point at)."""
    page.locator(f'[data-visual="{visual}"]').scroll_into_view_if_needed()
    page.wait_for_timeout(300)
    return page.evaluate("""([vis, lon, lat]) => { const el = document.querySelector('[data-visual="' + vis + '"] .bi-chart'); const c = echarts.getInstanceByDom(el);
        const g = c.convertToPixel({geoIndex: 0}, [lon, lat]); const b = el.getBoundingClientRect(); return [b.left + g[0], b.top + g[1]]; }""", [visual, lon, lat])


def click_map(page, visual: str, lon: float, lat: float):
    x, y = map_point(page, visual, lon, lat)
    page.mouse.click(x, y)


MARK = """([vis, category]) => {
  const el = document.querySelector('[data-visual="' + vis + '"] .bi-chart');
  const c = echarts.getInstanceByDom(el), o = c.getOption();
  const horizontal = o.yAxis[0].type === 'category';
  const cats = horizontal ? o.yAxis[0].data : o.xAxis[0].data;
  const i = cats.indexOf(category);
  if (i < 0) return null;
  const lay = c.getModel().getSeriesByIndex(0).getData().getItemLayout(i);
  const r = el.getBoundingClientRect();
  return { x: r.left + lay.x + lay.width / 2, y: r.top + lay.y + lay.height / 2 };
}"""


def click_mark(page, visual: str, category: str):
    """Clicks the bar or column of a category, by where the chart drew it."""
    page.locator(f'[data-visual="{visual}"] .bi-chart').scroll_into_view_if_needed()
    page.wait_for_timeout(300)
    p = page.evaluate(MARK, [visual, category])
    assert p, f"no mark {category} in {visual}"
    page.mouse.click(p["x"], p["y"])


def element_shot(c: Ctx, name: str, selector: str, pad: int = 8):
    """A picture of one element at its place on the page (so a sticky top bar is not in the way): scrolled to the top of the element first."""
    c.page.locator(selector).first.scroll_into_view_if_needed()
    c.page.evaluate("(s) => { const e = document.querySelector(s); window.scrollTo(0, Math.max(0, e.getBoundingClientRect().top + window.scrollY - 70)); }", selector)
    c.page.wait_for_timeout(400)
    b = c.page.locator(selector).first.bounding_box()
    c.save(name, clip={"x": max(0, b["x"] - pad), "y": max(0, b["y"] - pad), "width": min(c.page.viewport_size["width"], b["width"] + 2 * pad), "height": b["height"] + 2 * pad})


def dumps(x) -> str:
    return json.dumps(x, indent=1, default=str)
