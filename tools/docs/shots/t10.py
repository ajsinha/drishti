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


COMPOSED = "file:dataset:desk-pnl-vs-exposure"
PNL_REPORT = "file:report:pnl-vs-exposure"


@shot("26-composed-dataset.jpg", also=("27-composed-preview.jpg", "28-model-view.jpg"))
def composed(c: Ctx):
    """The composed sample dataset: what it is built from, a query preview of the joined table, and the Model view that draws the two sources, the join and the table."""
    login(c, height=900)
    go(c, "/bi/d/" + COMPOSED, wait="[data-built-sec]:not([hidden])", settle=1500)
    element_shot(c, "26-composed-dataset.jpg", "[data-built-sec]", pad=10)
    c.page.wait_for_selector("[data-fields] option", state="attached", timeout=30000)
    c.page.select_option("[data-fields]", ["joined.name", "joined.sector"])
    c.page.select_option("[data-measures]", ["Desk P&L", "Exposure (EE peak)", "P&L per exposure"])
    c.page.fill("[data-limit]", "8")
    c.page.click("[data-run]")
    c.page.wait_for_selector("[data-grid] tbody tr", timeout=60000)
    c.page.wait_for_timeout(800)
    c.page.evaluate("document.querySelector('[data-result-meta]').scrollIntoView()")
    c.page.wait_for_timeout(300)
    elem_shot(c, "27-composed-preview.jpg", "[data-grid-wrap]", pad=14)
    go(c, "/bi/m/" + COMPOSED, wait="[data-model-root][data-ready=yes]", settle=1500)
    c.save("28-model-view.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})


@shot("29-two-datasets-report.jpg", also=("30-sector-chosen.jpg",))
def two_datasets(c: Ctx):
    """The sample report over two datasets, then a sector chosen in the slicer that reads the second dataset: the cards and the bars of the composed one follow it."""
    login(c, height=900)
    open_report(c, PNL_REPORT)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("29-two-datasets-report.jpg")
    c.page.locator('[data-visual="sector"] summary.ms-sum').click()
    c.page.locator('[data-visual="sector"] .ms-pop label', has_text="Banking").first.click()
    c.page.wait_for_timeout(600)
    c.page.keyboard.press("Escape")
    c.page.locator("h1").first.click()
    settled(c.page)
    c.page.wait_for_timeout(1500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("30-sector-chosen.jpg")


RELATIVE = """rachana: 1
report: relative-dates
dates: { from: 2026-10-07, to: 2026-10-08 }
title: Relative dates
dataset: trades-basic
pages:
  - id: main
    title: Main
    visuals:
      - { id: when, type: slicer, title: When, at: { x: 0, y: 0, w: 4, h: 3 }, wells: { field: [trade.tradeDate] }, format: { mode: relative-date, default: last-30-days } }
      - { id: count, type: card, title: Trades, at: { x: 4, y: 0, w: 4, h: 3 }, wells: { values: [Trades] } }
      - { id: mtm, type: card, title: Mark to market, at: { x: 8, y: 0, w: 4, h: 3 }, wells: { values: [MTM] } }
      - { id: desk, type: bar, title: MTM by desk, at: { x: 0, y: 3, w: 12, h: 6 }, wells: { axis: [trade.desk], values: [MTM] } }
"""


@shot("31-relative-date.jpg")
def relative_date(c: Ctx):
    """A relative-date slicer: its default applies until a name is chosen; the server works the dates out (here four weeks ending 8 October)."""
    login(c, height=900)
    clean_items(c, "Relative dates")
    made = bi(c, "POST", "/workspaces/me/items", {"kind": "report", "title": "Relative dates", "body": RELATIVE})
    print("relative-date report:", made.get("id"), made.get("problems"))
    print("latest trade dates:", bi(c, "POST", "/datasets/file:dataset:trades-basic/query", {"fields": ["trade.tradeDate"], "orderBy": [{"by": "trade.tradeDate", "desc": True}], "limit": 3}).get("rows"))
    open_report(c, made["id"], height=620)
    c.page.select_option("[data-relative]", "last-4-weeks")
    c.page.wait_for_timeout(600)
    settled(c.page)
    c.page.wait_for_timeout(800)
    c.save("31-relative-date.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    clean_items(c, "Relative dates")


@shot("32-entity-view.jpg")
def entity_view(c: Ctx):
    """An entity view beside a bar: click a counterparty and its Drishti view opens inline, as the person looking."""
    login(c, height=900)
    open_report(c, "file:report:counterparty-risk", "lookup", height=760)
    first = c.page.evaluate("""() => { const o = echarts.getInstanceByDom(document.querySelector('[data-visual="ids"] .bi-chart')).getOption();
        return (o.yAxis[0].type === 'category' ? o.yAxis[0].data : o.xAxis[0].data)[0]; }""")
    click_mark(c.page, "ids", first)
    c.page.wait_for_selector(".bi-entity-frame", timeout=30000)
    c.page.wait_for_timeout(4000)
    c.save("32-entity-view.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 744})


TABLET = """rachana: 1
report: tablet-demo
title: Tablet demo
dataset: trades-basic
pages:
  - id: main
    title: Main
    visuals:
      - { id: trades, type: card, title: Trades, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Trades] } }
      - { id: mtm, type: card, title: Mark to market, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }
      - { id: notional, type: card, title: Notional, at: { x: 6, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }
      - { id: desk, type: bar, title: MTM by desk, at: { x: 0, y: 2, w: 8, h: 6 }, wells: { axis: [trade.desk], values: [MTM] } }
      - { id: rows, type: table, title: Desks, at: { x: 8, y: 2, w: 4, h: 6 }, wells: { columns: [trade.desk, Trades, MTM] } }
layouts:
  tablet:
    main:
      order: [desk, trades, mtm, notional, rows]
      wide: [desk, rows]
      span: { desk: 5, rows: 4 }
"""


@shot("33-tablet-tab.jpg", also=("34-tablet-view.jpg",))
def tablet(c: Ctx):
    """The designer's Tablet tab (order, Wide, rows) and the report on an 820 px screen: two columns, the bar and the table across."""
    login(c, height=900)
    clean_items(c, "Tablet demo")
    made = bi(c, "POST", "/workspaces/me/items", {"kind": "report", "title": "Tablet demo", "body": TABLET})
    print("tablet report:", made.get("id"), made.get("problems"))
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/design/" + made["id"])
    c.page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    c.page.click('[data-tab="tablet"]')
    c.page.wait_for_selector("[data-tablet-list] li")
    c.page.wait_for_timeout(600)
    c.save("33-tablet-tab.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    open_report(c, made["id"], width=820, height=900)
    c.page.wait_for_timeout(800)
    c.save("34-tablet-view.jpg", clip={"x": 0, "y": 0, "width": 820, "height": 900})
    clean_items(c, "Tablet demo")


# ---- the dataset wizard and the designer controls that used to need YAML ---------------------------------------------------------------------

WIZ_TITLE = "Economies by region"


def wiz_step(c: Ctx, step: str):
    c.page.click("[data-wiz-next]")
    c.page.wait_for_selector(f'[data-wiz-body][data-step="{step}"]')
    c.page.wait_for_timeout(400)


@shot("35-wizard-source.jpg", also=("36-wizard-columns.jpg", "37-wizard-measures.jpg", "38-wizard-refresh.jpg", "39-wizard-review.jpg"))
def wizard(c: Ctx):
    """The dataset wizard on the economics pack's connector: source, columns, a measure, the refresh and the review (the YAML it wrote)."""
    login(c, height=900)
    clean_everywhere(c, WIZ_TITLE)
    p = c.page
    go(c, "/bi/datasets", wait="[data-new-wizard]", settle=800)
    p.click("[data-new-wizard]")
    p.wait_for_selector('[data-wiz-body][data-step="source"]')
    p.check('[data-wiz-mode="connector"]')
    p.wait_for_selector("[data-wiz-connector]")
    p.select_option("[data-wiz-connector]", "macro-store")
    p.check('[data-kind="economy"]')
    p.fill("[data-wiz-name]", "economies-by-region")
    p.fill("[data-wiz-title-in]", WIZ_TITLE)
    p.fill("[data-wiz-desc]", "Economies of the world with their region and income group")
    p.wait_for_timeout(400)
    c.save("35-wizard-source.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 760})
    wiz_step(c, "columns")
    p.evaluate("window.scrollTo(0, 0)")
    c.save("36-wizard-columns.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 780})
    wiz_step(c, "relationships")
    wiz_step(c, "measures")
    p.get_by_role("button", name="Add a measure").click()
    last = p.locator("[data-measure-row]").count() - 1
    p.fill(f'[data-m-name="{last}"]', "Total GDP")
    numeric = p.eval_on_selector(f'[data-m-field="{last}"]', "s => [...s.options].map(o => o.value).find(v => /gdp/i.test(v)) || ''")
    if numeric:
        p.select_option(f'[data-m-field="{last}"]', numeric)
    p.click(f"[data-measure-row] >> nth={last} >> text=Check")
    p.wait_for_timeout(2000)
    p.evaluate("(i) => document.querySelectorAll('[data-measure-row]')[i].scrollIntoView({block: 'center'})", last)
    p.wait_for_timeout(400)
    c.save("37-wizard-measures.jpg", locator=f"[data-measure-row] >> nth={last}")
    wiz_step(c, "hierarchies")
    wiz_step(c, "refresh")
    p.select_option("[data-refresh-kind]", "daily")
    p.fill("[data-refresh-time]", "06:30")
    p.evaluate("window.scrollTo(0, 0)")
    c.save("38-wizard-refresh.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    wiz_step(c, "review")
    p.wait_for_selector("[data-wiz-valid]", timeout=30000)
    p.wait_for_timeout(600)
    c.save("39-wizard-review.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 900})
    clean_everywhere(c, WIZ_TITLE)


CONTROLS = """rachana: 1
report: controls-demo
title: Controls demo
dataset: trades-basic
pages:
  - id: main
    title: Main
    visuals:
      - { id: when, type: slicer, title: When, at: { x: 0, y: 0, w: 4, h: 3 }, wells: { field: [trade.tradeDate] }, format: { mode: list } }
      - { id: count, type: card, title: Trades, at: { x: 4, y: 0, w: 4, h: 3 }, wells: { values: [Trades] } }
      - { id: desk, type: bar, title: MTM by desk, at: { x: 0, y: 3, w: 12, h: 6 }, wells: { axis: [trade.desk], values: [MTM] } }
"""


@shot("40-slicer-relative.jpg", also=("41-top-filter.jpg", "42-bookmarks.jpg"))
def controls(c: Ctx):
    """The designer's own controls: a slicer's relative-date mode with its default and windows, a top filter in the Filters pane, a bookmark of the page."""
    login(c, height=900)
    clean_items(c, "Controls demo")
    made = bi(c, "POST", "/workspaces/me/items", {"kind": "report", "title": "Controls demo", "body": CONTROLS})
    p = c.page
    p.set_viewport_size({"width": 1280, "height": 1180})
    p.goto(c.base + "/bi/design/" + made["id"])
    p.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    settled_design(p)
    p.click('[data-visual="when"] .bi-dhandle')
    p.click('[data-ptab="format"]')
    p.select_option("[data-format-mode]", "relative-date")
    p.select_option("[data-format-default]", "last-30-days")
    p.uncheck('[data-format-options] [data-option="last-90-days"]')
    p.uncheck('[data-format-options] [data-option="last-12-months"]')
    p.wait_for_timeout(900)
    c.save("40-slicer-relative.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 1100})
    p.click('[data-add-filter="report"]')
    p.select_option('[data-filter-editor] [name="field"]', "trade.desk")
    p.select_option('[data-filter-editor] [name="mode"]', "top")
    p.fill('[data-filter-editor] [name="n"]', "3")
    p.wait_for_timeout(500)
    p.evaluate("document.querySelector('[data-filters]').scrollIntoView({block: 'center'})")
    c.save("41-top-filter.jpg", locator="[data-filters]")
    p.click('[data-filter-editor] button[type=submit]')
    p.evaluate("window.drishtiBi.design.select(null)")
    p.click('[data-ptab="format"]')
    p.fill("[data-bookmark-name]", "Top desks")
    p.evaluate("document.querySelector('[data-bookmarks]').scrollIntoView({block: 'center'})")
    p.wait_for_timeout(400)
    c.save("42-bookmarks.jpg", locator="[data-bookmarks]")
    clean_items(c, "Controls demo")
