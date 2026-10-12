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

"""Pictures of tutorial 10, Your first BI report (docs/tutorials/10-first-bi-report.md), written to docs/tutorials/img/t10/.

A report is built on the World economics sample dataset in the designer (two cards, a map, a bar, a dropdown slicer), looked at (a click on a country, the
drill to its regions and back), published, and then the slicers of the Trades overview sample and the cross-filter of Counterparty risk are shown."""
from __future__ import annotations

import re

from _bi import RISK, WORLD, bi, clean_items, click_map, click_mark, clean_everywhere, settled, element_shot, grid_cell, open_report, settled_design, start_design, submit_design  # noqa: F401
from _tut import OUT, ROOT, SERVER_ENV, elem_shot, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t10", OUT / "t10")
TITLE = "My world report"
WORKSPACE = "Economics desk"
FULL = {"x": 0, "y": 0, "width": 1280, "height": 900}


@shot("01-dataset.jpg", also=("02-preview.jpg",))
def dataset(c: Ctx):
    login(c, height=900)
    go(c, "/bi/d/" + WORLD, wait="h1", settle=1500)
    c.save("01-dataset.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 450})
    c.page.wait_for_selector("[data-fields] option", state="attached", timeout=30000)
    c.page.select_option("[data-fields]", ["economies.regionName"])
    c.page.select_option("[data-measures]", ["GDP (USD bn)", "Population (m)", "GDP per capita (USD)"])
    c.page.click("[data-run]")
    c.page.wait_for_selector("[data-grid] tbody tr", timeout=30000)
    c.page.wait_for_timeout(800)
    c.page.evaluate("document.querySelector('[data-preview]').scrollIntoView()")
    c.page.wait_for_timeout(300)
    elem_shot(c, "02-preview.jpg", "[data-grid-wrap]", pad=14, extra=30)


@shot("03-new-report.jpg", also=("04-cards.jpg", "05-map.jpg", "06-yaml-places.jpg", "07-bar-slicer.jpg"))
def design(c: Ctx):
    login(c, height=900)
    clean_everywhere(c, TITLE, workspace_names=(WORKSPACE,))
    start_design(c, WORLD, TITLE)
    c.page.wait_for_timeout(400)
    c.save("03-new-report.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    submit_design(c)
    p = c.page
    p.drag_and_drop('[data-field="GDP (USD bn)"]', "[data-canvas-grid]", target_position=grid_cell(p, 0, 0))
    p.wait_for_selector(".bi-card-value")
    p.drag_and_drop('[data-field="Population (m)"]', "[data-canvas-grid]", target_position=grid_cell(p, 3, 0))
    p.wait_for_selector('.bi-dvis[data-visual="card-2"] .bi-card-value')
    p.wait_for_timeout(500)
    c.save("04-cards.jpg")
    # a map: choose the type with nothing selected, then its two wells
    p.click("[data-canvas-grid]", position=grid_cell(p, 11, 0))
    p.click('.bi-vtype[data-type="map"]')
    p.wait_for_selector('[data-well="location"]')
    p.select_option('[data-add="location"]', "economies.iso3")
    p.select_option('[data-add="values"]', "GDP per capita (USD)")
    settled_design(p)
    p.wait_for_timeout(1000)
    c.save("05-map.jpg")
    # a bar of the largest economies, and a dropdown slicer on the region
    p.click("[data-canvas-grid]", position=grid_cell(p, 11, 0))
    p.click('.bi-vtype[data-type="bar"]')
    p.wait_for_selector('[data-well="axis"]')
    p.select_option('[data-add="axis"]', "economies.name")
    p.select_option('[data-add="values"]', "GDP (USD bn)")
    settled_design(p)
    p.click("[data-canvas-grid]", position=grid_cell(p, 11, 0))
    p.click('.bi-vtype[data-type="slicer"]')
    p.wait_for_selector('.bi-dvis[data-type="slicer"]')
    p.select_option('[data-add="field"]', "economies.regionName")
    p.click("[data-ptab='format']")
    p.select_option("[data-format-mode]", "dropdown")
    settled_design(p)
    # the places: the YAML tab holds each visual's `at: {x, y, w, h}` on the 12-column grid (dragging a header and the corner does the same)
    p.click('[data-tab="yaml"]')
    p.wait_for_timeout(800)
    yaml = p.evaluate("window.drishtiBi.design.yamlText()")
    places = (("card-1", "x: 0, y: 0, w: 3, h: 2", "GDP (USD bn)", ""), ("card-2", "x: 3, y: 0, w: 3, h: 2", "Population (m)", ""),
              ("slicer-1", "x: 6, y: 0, w: 6, h: 2", "Region", ""), ("map-1", "x: 0, y: 2, w: 8, h: 8", "GDP per capita (USD): click a country", ""),
              ("bar-1", "x: 8, y: 2, w: 4, h: 8", "Largest economies (GDP, USD bn)", "\n        filters: [{ field: economies.name, op: top, n: 10, by: GDP (USD bn) }]"))
    for vid, at, title, extra in places:
        yaml = re.sub(r"(- id: " + vid + r"\n\s+type: \w+\n)(\s+)at: \{[^}]*\}", lambda m: m.group(1) + m.group(2) + 'title: "' + title + '"\n' + m.group(2) + "at: { " + at + " }" + extra, yaml, count=1)
    p.evaluate("(y) => window.drishtiBi.design.loadYaml(y)", yaml)
    p.wait_for_timeout(500)
    c.save("06-yaml-places.jpg")
    p.click('[data-tab="canvas"]')
    settled_design(p)
    p.wait_for_timeout(1200)
    c.save("07-bar-slicer.jpg")
    p.keyboard.press("Control+s")
    p.wait_for_timeout(1500)
    design.draft = p.url.rsplit("/", 1)[-1]


@shot("08-view.jpg", also=("09-country.jpg", "10-regions.jpg", "11-back-to-world.jpg"))
def view(c: Ctx):
    login(c, height=900)
    item = mine(c)
    open_report(c, item["id"], wait_map=True)
    c.save("08-view.jpg")
    click_map(c.page, "map-1", 134, -25)                       # Australia
    c.page.wait_for_timeout(4000)
    c.save("09-country.jpg")
    element_shot(c, "10-regions.jpg", '[data-visual="map-1"]', pad=6)
    c.page.locator("[data-map-up]").first.click()
    c.page.wait_for_timeout(2500)
    c.save("11-back-to-world.jpg")


def mine(c: Ctx) -> dict:
    listing = bi(c, "GET", "/workspaces/me/items")
    items = listing if isinstance(listing, list) else listing.get("items", [])
    return next(i for i in items if i["title"] == TITLE)


@shot("12-workspace.jpg", also=("13-move.jpg", "14-published.jpg", "15-catalogue.jpg"))
def publish(c: Ctx):
    login(c, height=900)
    go(c, "/bi/workspaces", wait="h1", settle=1000)
    c.page.fill("input[name=name]", WORKSPACE)
    c.page.fill("input[name=description]", "Reports on the world economy")
    c.save("12-workspace.jpg", clip={"x": 240, "y": 56, "width": 760, "height": 600})
    c.page.click("text=Create workspace")
    c.page.wait_for_timeout(1500)
    item = mine(c)
    go(c, "/bi/i/" + item["id"], wait="[data-actions] button", settle=1200)
    c.page.click("[data-actions] >> text=Move to workspace")
    c.page.select_option("[data-actions] select", label=WORKSPACE)
    c.save("13-move.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 300})
    c.page.click("[data-actions] >> text=Move")
    c.page.wait_for_timeout(1500)
    c.page.reload()
    c.page.wait_for_selector("[data-actions] button")
    c.page.wait_for_timeout(1200)
    c.page.fill("[data-actions] input", "First cut: map, cards, bar and a region slicer")
    c.page.click("[data-actions] >> text=Publish")
    c.page.wait_for_timeout(2500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("14-published.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 420})
    go(c, "/bi/catalogue", wait="h1", settle=2000)
    c.page.fill("[data-search]", "world")
    c.page.wait_for_timeout(1500)
    c.save("15-catalogue.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 600})


OVERVIEW = "file:report:trades-overview"
CPTY = "file:report:counterparty-risk"


@shot("16-dropdown-open.jpg", also=("17-dropdown-chosen.jpg", "18-donut-top.jpg"))
def dropdown(c: Ctx):
    login(c, height=900)
    open_report(c, OVERVIEW, "products")
    element_shot(c, "18-donut-top.jpg", '[data-visual="share"]', pad=6)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.page.locator('[data-visual="pick-product"] summary.ms-sum').click()
    c.page.locator('[data-visual="pick-product"] .ms-q').fill("SWAP")
    c.page.wait_for_timeout(600)
    c.save("16-dropdown-open.jpg", clip={"x": 240, "y": 230, "width": 1040, "height": 670})
    for name in ("AMORT_SWAP", "BASIS_SWAP"):
        c.page.locator('[data-visual="pick-product"] .ms-pop label', has_text=name).first.click()
    c.page.wait_for_timeout(600)
    c.page.keyboard.press("Escape")
    c.page.mouse.click(1100, 200)
    settled(c.page)
    c.page.wait_for_timeout(1200)
    c.save("17-dropdown-chosen.jpg", clip={"x": 240, "y": 230, "width": 1040, "height": 670})


@shot("19-bookmark.jpg", also=("20-crossfilter.jpg",))
def bookmark_and_cross(c: Ctx):
    login(c, height=900)
    open_report(c, OVERVIEW, "overview")
    click_mark(c.page, "by-desk", "DESK-FX")
    settled(c.page)
    c.page.wait_for_timeout(1500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("20-crossfilter.jpg", clip={"x": 240, "y": 230, "width": 1040, "height": 670})
    open_report(c, OVERVIEW, "overview")
    c.page.select_option("[data-bookmarks], select[aria-label*=ookmark]", label="FX desk only")
    settled(c.page)
    c.page.wait_for_timeout(1500)
    c.save("19-bookmark.jpg", clip={"x": 240, "y": 230, "width": 1040, "height": 670})


@shot("21-counterparty.jpg", also=("22-counterparty-select.jpg", "23-drill-through.jpg"))
def counterparty(c: Ctx):
    login(c, height=900)
    open_report(c, CPTY, wait_map=True)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("21-counterparty.jpg")
    click_mark(c.page, "by-counterparty", "Halcyon Shipping plc")
    settled(c.page)
    c.page.wait_for_timeout(1500)
    c.save("22-counterparty-select.jpg")
    c.page.locator('[data-visual="by-counterparty"] [title*="Drill through"], [data-visual="by-counterparty"] button:has-text("Drill through")').first.click()
    c.page.wait_for_selector("[data-ready=yes]")
    settled(c.page)
    c.page.wait_for_timeout(1500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("23-drill-through.jpg")


@shot("24-phone.jpg")
def phone(c: Ctx):
    login(c, height=844, width=390)
    open_report(c, mine_published(c), width=390, height=844, wait_map=True)
    c.page.locator('[data-visual="map-1"]').scroll_into_view_if_needed()
    c.page.evaluate("window.scrollBy(0, -80)")
    c.page.wait_for_timeout(600)
    c.save("24-phone.jpg")


def mine_published(c: Ctx) -> str:
    cat = bi(c, "GET", "/catalogue")
    items = cat.get("items", []) if isinstance(cat, dict) else cat
    return next(i["id"] for i in items if i["title"] == TITLE and not i.get("shipped"))


@shot("25-slicer-kinds.jpg")
def slicer_kinds(c: Ctx):
    """The three kinds of slicer on one page (docs/tutorials/data/slicer-kinds.report.yaml), as a draft of the person's own."""
    login(c, height=900)
    clean_items(c, "Slicer kinds")
    made = bi(c, "POST", "/workspaces/me/items", {"kind": "report", "title": "Slicer kinds", "body": (ROOT / "docs" / "tutorials" / "data" / "slicer-kinds.report.yaml").read_text()})
    open_report(c, made["id"])
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("25-slicer-kinds.jpg", clip={"x": 240, "y": 230, "width": 1040, "height": 670})
