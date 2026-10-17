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

"""Pictures of tutorial 6, Design a screen (docs/tutorials/06-design-a-screen.md), written to docs/tutorials/img/t06/.

The workbench takes the three desk documents of docs/tutorials/data/desks, drafts a screen, and the tutorial's Sutra (desk-book.sutra.yaml: tabs that hold
panels, a slider, a table with column filters) replaces the draft. Nothing is saved or proposed: the pictures are of the design in progress."""
from __future__ import annotations

import json

from _tut import OUT, ROOT, SERVER_ENV, elem_shot, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t06", OUT / "t06")
DATA = ROOT / "docs" / "tutorials" / "data"
DESKS = [str(DATA / "desks" / n) for n in ("desk-rates.json", "desk-fx.json", "desk-credit.json")]
SUTRA = (DATA / "desk-book.sutra.yaml").read_text()


def new_screen(c: Ctx):
    login(c, width=1280, height=900)
    c.page.goto(c.base + "/build/new")
    c.page.locator("#bsFiles").set_input_files(DESKS)
    c.page.get_by_text("3 files ready.").wait_for()
    c.page.fill("#bsName", "Desk book")
    c.page.fill("#bsDesignKind", "desk")
    c.page.wait_for_timeout(400)


def open_design(c: Ctx):
    new_screen(c)
    c.page.locator("[data-create]").click()
    c.page.wait_for_url("**/build/d/*")
    c.ready()


def paste_sutra(c: Ctx):
    c.page.evaluate("(y) => window.drishtiWorkbench.store.send([{ op: 'text', yaml: y }], 'Sutra')", SUTRA)
    c.page.wait_for_timeout(2500)


@shot("01-new-screen.jpg", also=("02-first-draft.jpg",))
def start(c: Ctx):
    new_screen(c)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("01-new-screen.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 700})
    c.page.locator("[data-create]").click()
    c.page.wait_for_url("**/build/d/*")
    c.ready()
    c.save("02-first-draft.jpg")


@shot("03-yaml.jpg", also=("04-tabs.jpg", "05-controls-tab.jpg", "06-slider-moved.jpg"))
def sutra(c: Ctx):
    open_design(c)
    c.page.locator("#wbTabYaml").click()
    c.page.wait_for_timeout(600)
    paste_sutra(c)
    c.save("03-yaml.jpg")
    c.page.locator("#wbTabDesign").click()
    c.page.wait_for_timeout(1500)
    c.save("04-tabs.jpg")
    c.page.locator("#wbTabCtl").click()
    c.page.wait_for_timeout(1200)
    c.save("05-controls-tab.jpg", clip={"x": 880, "y": 140, "width": 400, "height": 760})
    c.page.locator("[data-ctl-preview]").scroll_into_view_if_needed()
    c.page.evaluate("document.querySelector('[data-ctl-preview]').scrollIntoView({block: 'start'})")
    c.page.wait_for_timeout(500)
    slider = c.page.locator("[data-ctl-preview] input[type=range], [data-ctl-preview] [role=slider]").first
    slider.focus()
    for _ in range(4):
        c.page.keyboard.press("ArrowRight")
    c.page.wait_for_timeout(1200)
    c.save("06-slider-moved.jpg", ".wb-right")


@shot("07-column-filter.jpg", also=("08-phone.jpg",))
def column_filter(c: Ctx):
    """Every table on a live view has a funnel on each column (the workbench preview is a sketch; the live view is the real thing)."""
    login(c)
    go(c, "/v/trade/MX-20000001", height=1700, wait="#p-event-list .cf-btn", settle=1500)
    th = c.page.locator("#p-event-list th", has_text="Amount").first
    th.locator(".cf-btn").click()
    c.page.locator(".cf-menu").wait_for()
    c.page.locator(".cf-menu .cf-op").select_option(label="Greater than")
    c.page.locator(".cf-menu .cf-a").fill("5m")
    c.page.wait_for_timeout(500)
    box = c.page.locator("#p-event-list").bounding_box()
    menu = c.page.locator(".cf-menu").bounding_box()
    top = max(0, min(box["y"], menu["y"]) - 10)
    c.save("07-column-filter.jpg", clip={"x": 0, "y": top, "width": 1280, "height": min(box["y"] + 520, 1700) - top})
    c.page.keyboard.press("Enter")
    c.page.wait_for_timeout(800)
    go(c, "/v/trade/MX-20000001", width=390, height=844, wait="#p-event-list .cf-btn", settle=1200)
    c.page.locator("#p-event-list").scroll_into_view_if_needed()
    c.page.wait_for_timeout(500)
    c.save("08-phone.jpg")


@shot("09-about-tab.jpg", also=("10-problems.jpg",))
def about(c: Ctx):
    open_design(c)
    paste_sutra(c)
    c.page.locator("#wbTabProb").click()
    c.page.wait_for_timeout(800)
    c.save("10-problems.jpg", ".wb-right")
    c.page.locator("#wbTabAbout").click()
    c.page.locator("[data-about-starter]").click()
    c.page.wait_for_timeout(2500)
    c.save("09-about-tab.jpg", ".wb-right")


@shot("11-about-drawer.jpg")
def drawer(c: Ctx):
    login(c)
    go(c, "/v/trade/MX-20000001", wait="[data-panel]", settle=1500)
    c.page.keyboard.press("?")
    c.page.locator("#aboutDrawer:not([hidden])").wait_for(timeout=30000)
    c.page.wait_for_timeout(1500)
    c.save("11-about-drawer.jpg")


@shot("12-map-live.jpg", also=("13-map-hover.jpg", "13-map-click.jpg"))
def map_live(c: Ctx):
    """The `map` panel on a live view: the jurisdiction page of Australia draws the world with JUR-AUS highlighted; a click on another country opens that country's page."""
    login(c)
    go(c, "/v/jurisdiction/JUR-AUS", height=900, wait="#p-geography", settle=4000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("12-map-live.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 600})
    where = c.page.evaluate("""() => { const host = document.querySelector('#p-geography'); const e = [...host.querySelectorAll('div')].find(d => window.echarts && echarts.getInstanceByDom(d));
        const g = echarts.getInstanceByDom(e).convertToPixel({geoIndex: 0}, [113.9, -0.8]); const b = e.getBoundingClientRect(); return [b.left + g[0], b.top + g[1]]; }""")
    c.page.mouse.move(where[0], where[1])
    c.page.wait_for_timeout(700)
    c.save("13-map-hover.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 600})
    c.page.mouse.click(where[0], where[1])
    c.page.wait_for_url("**/v/jurisdiction/JUR-IDN*", timeout=30000)
    c.page.wait_for_selector("#p-geography")
    c.page.wait_for_timeout(3500)
    c.save("13-map-click.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 600})


@shot("14-map-workbench.jpg", also=("15-map-palette.jpg",))
def map_workbench(c: Ctx):
    """The map example opened as a copy in the workbench: two map panels (the world with the country highlighted, and its regions), and the palette that adds one."""
    login(c, width=1280, height=900)
    c.example("map")
    c.page.wait_for_timeout(1500)
    c.save("14-map-workbench.jpg")
    c.page.locator('[data-palette] [data-kind="map"]').scroll_into_view_if_needed()
    c.page.wait_for_timeout(400)
    c.save("15-map-palette.jpg", "[data-palette-box]")
