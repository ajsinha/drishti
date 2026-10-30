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

"""The pictures of "Controls on a screen" (docs/guides/USER_GUIDE.md), written to docs/guides/img/controls/: an interest-rate swap's events with
their controls (legs, paid between) in the panel header, the same screen filtered with its address, a netting set where a click on a bar of
"Net MTM by asset class" filters the member trades, the same screen on a phone (390 px, touch), and the workbench's Controls tab. A view of the
QUICKSTART packs, the real console:

    DRISHTI_SHOTS_SERVER_PORT=18982 DRISHTI_SHOTS_CONSOLE_PORT=17982 drishti-console/.venv/bin/python tools/docs/screenshots.py --guide controls
"""
from __future__ import annotations

from shotlib import Ctx, register

shot = register("controls")
IRS = "/v/trade/MX-20000001"
NETTING = "/v/netting-set/NS-MERIDIAN-RE-NY"


def open_view(c: Ctx, path: str, width: int, height: int):
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.goto(c.base + path)
    c.page.locator("[data-ctl]").first.wait_for(state="attached", timeout=30000)
    c.page.wait_for_timeout(1500)


def done(c: Ctx, action):
    n = c.page.evaluate("(window.__drishtiControls || []).length")
    action()
    c.until(f"(window.__drishtiControls || []).length > {n}", 30)
    c.page.wait_for_timeout(400)


def schedule_box(c: Ctx, height: int):
    """The Events panel from its top (it sits below the cash flows, deeper than the window: it is scrolled to the top first)."""
    c.page.evaluate("() => { const p = document.querySelector('#p-event-list'); window.scrollBy(0, p.getBoundingClientRect().top - 70); }")
    c.page.wait_for_timeout(300)
    b = c.page.locator("#p-event-list").first.bounding_box()
    y = max(0, b["y"] - 12)
    return {"x": 0, "y": y, "width": min(b["x"] + b["width"] + 30, 1440), "height": min(height, 1700 - y)}


@shot("01-header.jpg")
def header(c: Ctx):
    open_view(c, IRS, 1440, 1700)
    c.page.locator("#p-event-list [data-ctl='legs'] summary").click()
    c.page.wait_for_timeout(300)
    c.save("01-header.jpg", clip=schedule_box(c, 470))


@shot("02-filtered.jpg")
def filtered(c: Ctx):
    open_view(c, IRS, 1440, 1700)
    done(c, lambda: c.page.locator("#p-event-list [data-ctl='when'] .ctl-preset").select_option(label="Next 90 days"))
    c.page.locator("#p-event-list [data-ctl='legs'] summary").click()
    done(c, lambda: c.page.locator("#p-event-list [data-ctl='legs'] input[value='2']").uncheck())
    c.page.keyboard.press("Escape")
    c.page.mouse.move(1430, 1650)
    c.page.wait_for_timeout(300)
    c.save("02-filtered.jpg", clip=schedule_box(c, 330))


@shot("03-cross-filter.jpg")
def cross(c: Ctx):
    open_view(c, NETTING, 1440, 1700)
    # the derived panels (2.9.7: net MTM by product group, computed from the trade valuations) fill a moment after the page
    c.until("(() => { const p = document.querySelector('#p-byProductGroup'); return !p || /\\d/.test(p.innerText); })()", 120)
    done(c, lambda: c.page.locator("#p-byAsset li[data-pick]").first.click())
    c.page.mouse.move(1430, 1650)
    c.page.wait_for_timeout(300)
    top = c.page.locator(".vhead").first.bounding_box()
    t = c.page.locator("#p-trades").first.bounding_box()
    c.save("03-cross-filter.jpg", clip={"x": 0, "y": top["y"], "width": 1440, "height": min(t["y"] - top["y"] + 380, 1700)})


@shot("04-phone.jpg")
def phone(c: Ctx):
    open_view(c, IRS, 390, 844)
    c.page.locator("#p-event-list").scroll_into_view_if_needed()
    c.page.locator("#p-event-list [data-ctl='legs'] summary").click()
    c.page.wait_for_timeout(400)
    # the Legs control just under the top bar (on a phone the bar is three rows, about 160 px), so its open list shows whole
    c.page.evaluate("() => window.scrollBy(0, document.querySelector(\"#p-event-list [data-ctl='legs']\").getBoundingClientRect().top - 170)")
    c.page.wait_for_timeout(300)
    c.save("04-phone.jpg")


@shot("05-workbench.jpg")
def workbench(c: Ctx):
    c.page.set_viewport_size({"width": 1440, "height": 1000})
    sample = c.page.request.get(c.base + "/api/raw/trade/MX-20000001").json()
    doc = sample.get("data", sample)
    import json
    sutra = (c.page.request.get(c.base + "/api/sutras/irs-fixfloat").text() if False else None)
    d = c.design("controls-demo", files={"irs.json": json.dumps(doc)}, kind="trade")
    c.open(d)
    c.page.evaluate("""() => window.drishtiWorkbench.store.send([{ op: 'text', yaml:
      'rachana: 1\\nsutra: controls-demo\\nversion: 1\\nmatch: { kind: trade }\\ntitle: { id: $.tradeId }\\npanels:\\n  - id: schedule\\n    kind: table\\n    title: Cashflows\\n    rows: $.schedule\\n    where: "oneOf(@.leg, ctl.legs) && within(@.payDate, ctl.when)"\\n    columns:\\n      - { label: Pay date, bind: "@.payDate" }\\n      - { label: Leg, bind: "@.leg" }\\n      - { label: Amount, bind: "@.amount", fmt: signed0 }\\n' }], 'Sutra')""")
    c.page.wait_for_timeout(1500)
    c.page.evaluate("""() => window.drishtiWorkbench.store.send([{ op: 'setControls', items: [
      { id: 'legs', kind: 'multi-select', label: 'Legs', options: '$.schedule[*].leg', default: 'all', placement: 'header', panel: 'schedule', remember: 'user' },
      { id: 'when', kind: 'date-range', label: 'Paid between', placement: 'header', panel: 'schedule' }] }], 'Controls')""")
    c.page.wait_for_timeout(1500)
    c.page.locator("#wbTabCtl").click()
    c.page.locator("[data-ctl-preview] [data-ctl='legs'] summary").wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.save("05-workbench.jpg")
