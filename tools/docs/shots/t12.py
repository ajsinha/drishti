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

"""Pictures of tutorial 12, Live BI and boards (docs/tutorials/12-live-bi-and-boards.md), written to docs/tutorials/img/t12/.

The demo source ticks: a curve that moves as a whole (CRV-USD-BASIS), a live dataset, a report that redraws as the trades change (with Pause and Resume),
and the Desk wall board, on a screen and in kiosk mode."""
from __future__ import annotations

from _bi import open_report
from _tut import OUT, SERVER_ENV, elem_shot, go, login  # noqa: F401
from shotlib import ROOT, Ctx, register

shot = register("t12", OUT / "t12")
LIVE_DATASET = "file:dataset:live-trading"
LIVE_REPORT = "file:report:live-trading-desk"
BOARD = "file:report:desk-wall"                                # 2.4.0: a board is a report with board pages


@shot("01-curve-now.jpg", also=("02-curve-later.jpg",))
def curve(c: Ctx):
    login(c, height=1000)
    c.page.request.post(c.base + "/alerts/measure/MTM%20floor/delete")         # tutorial 11's alert would toast over the picture
    go(c, "/v/ir-curve/CRV-USD-BASIS", height=1000, wait="[data-panel]", settle=2500)
    c.save("01-curve-now.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 944})
    c.page.wait_for_timeout(8000)
    c.save("02-curve-later.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 944})


@shot("03-live-dataset.jpg")
def live_dataset(c: Ctx):
    login(c, height=900)
    c.page.request.post(c.base + "/bi/api/mq", data='{"mq":1,"model":"live-trading@1","select":{"fields":["trade.desk"],"measures":["Trades"]}}',
                        headers={"Content-Type": "application/json"})                     # one question starts the live table
    go(c, "/bi/d/" + LIVE_DATASET, wait="h1", settle=2500)
    elem_shot(c, "03-live-dataset.jpg", "[data-layers]", pad=14)


@shot("04-live-report.jpg", also=("05-live-paused.jpg", "06-live-resumed.jpg", "07-live-grid.jpg"))
def live_report(c: Ctx):
    login(c, height=900)
    open_report(c, LIVE_REPORT)
    c.page.wait_for_function("() => /Last update/.test(document.querySelector('[data-live-bar]').textContent)", timeout=30000)
    c.page.wait_for_timeout(4000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("04-live-report.jpg")
    c.page.click("[data-live-pause]")
    c.page.wait_for_timeout(2500)
    elem_shot(c, "05-live-paused.jpg", "[data-live-bar]", pad=10)
    c.page.click("[data-live-pause]")
    c.page.wait_for_timeout(3000)
    elem_shot(c, "06-live-resumed.jpg", "[data-live-bar]", pad=10)
    open_report(c, LIVE_REPORT, "grid")
    c.page.wait_for_timeout(5000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("07-live-grid.jpg")


@shot("08-board.jpg", also=("09-board-exposure.jpg", "10-kiosk.jpg"))
def board(c: Ctx):
    login(c, height=900)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/r/" + BOARD)
    c.page.wait_for_selector("[data-ready=yes]", timeout=30000)
    c.page.wait_for_timeout(6000)
    c.save("08-board.jpg")
    c.page.get_by_role("tab").nth(1).click()
    c.page.wait_for_timeout(5000)
    c.save("09-board-exposure.jpg")
    c.page.get_by_role("tab").nth(0).click()
    c.page.click("[data-kiosk]")
    c.page.wait_for_timeout(5000)
    c.save("10-kiosk.jpg")


# ---- wave B: the history of a live table, and the board designer ---------------------------------------------------------------------

DESIGN_BOARD = (ROOT / "docs" / "tutorials" / "data" / "night-desk.report.yaml").read_text()        # the tutorial's file: a report with one board page


@shot("11-history-line.jpg")
def history_line(c: Ctx):
    login(c, height=1000)
    open_report(c, LIVE_REPORT)
    c.page.wait_for_function("() => /Last update/.test(document.querySelector('[data-live-bar]').textContent)", timeout=30000)
    c.page.wait_for_timeout(75000)                                  # a sample every ten seconds: a line of seven or eight points
    c.page.evaluate("document.querySelector('[data-visual=last-hour]').scrollIntoView({block: 'center'})")
    c.page.wait_for_function("() => { const c = document.querySelector('[data-visual=last-hour] .bi-chart'); const i = c && echarts.getInstanceByDom(c); return i && i.getOption().series[0].data.length >= 5; }", timeout=30000)
    c.page.wait_for_timeout(1500)
    box = c.page.locator("[data-visual=last-hour]").bounding_box()          # a window picture, not a full-page one: the chart is not redrawn by a resize
    c.save("11-history-line.jpg", clip={"x": max(0, box["x"] - 12), "y": max(0, box["y"] - 12), "width": box["width"] + 24, "height": box["height"] + 24})


@shot("12-board-designer.jpg", also=("13-tile-alert.jpg", "14-board-yaml.jpg"))
def board_designer(c: Ctx):
    """The Night desk board in the report designer (BI Studio, 2.4.0): three tiles drawn with the live desk's numbers, the Tile section of Format."""
    import json

    login(c, height=900)
    mine = c.page.request.get(c.base + "/bi/api/workspaces/me/items").json()
    for it in mine if isinstance(mine, list) else mine.get("items", []):
        if it.get("title") == "Night desk":
            c.page.request.delete(c.base + "/bi/api/items/" + it["id"])
    made = c.page.request.post(c.base + "/bi/api/workspaces/me/items", data=json.dumps({"kind": "report", "title": "Night desk", "body": DESIGN_BOARD}),
                               headers={"Content-Type": "application/json"}).json()
    c.page.set_viewport_size({"width": 1400, "height": 900})
    c.page.goto(c.base + "/bi/design/" + made["id"])
    c.page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    c.page.wait_for_selector("[data-design-root] [data-visual='net-mtm']")
    c.page.wait_for_timeout(5000)                                      # the tiles draw the live report's numbers
    c.page.click("[data-design-root] [data-visual='net-mtm']")
    c.page.click("[data-ptab='format']")
    c.page.wait_for_selector("[data-format-section='tile']")
    c.page.wait_for_timeout(600)
    c.save("12-board-designer.jpg", clip={"x": 0, "y": 56, "width": 1400, "height": 844})
    section = c.page.locator("[data-format-section='tile']")
    section.scroll_into_view_if_needed()
    c.page.wait_for_timeout(400)
    box = section.bounding_box()
    c.save("13-tile-alert.jpg", clip={"x": box["x"] - 6, "y": max(0, box["y"] - 6), "width": box["width"] + 12, "height": min(900 - max(0, box["y"] - 6), box["height"] + 12)})
    c.page.click("[data-tab=yaml]")
    c.page.wait_for_function("() => /Night desk/.test(document.querySelector('[data-view=yaml]').innerText)")
    c.page.wait_for_timeout(800)
    c.save("14-board-yaml.jpg", clip={"x": 0, "y": 56, "width": 1400, "height": 640})


@shot("15-chart-page.jpg")
def desk_currency_chart(c: Ctx):
    """The Chart page of the live desk report: net MTM by desk, one stacked series per currency, a column visual the server groups and the browser only
    draws (2.3.0: no Perspective, no computing in the browser)."""
    login(c, height=900)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(f"{c.base}/bi/r/{LIVE_REPORT}/chart")
    c.page.wait_for_selector('[data-visual="mtm-chart"] canvas', timeout=60000)
    c.page.wait_for_timeout(2500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("15-chart-page.jpg")


PYTHON_BOARD = "file:report:python-wall"


@shot("17-python-wall.jpg")
def python_wall(c: Ctx):
    """The Python wall: three tiles that are Python visuals, run for the person looking."""
    login(c, height=900)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(f"{c.base}/bi/r/{PYTHON_BOARD}")
    c.page.wait_for_function("() => document.querySelectorAll('[data-tile] .bi-py-foot').length === 3", timeout=120000)
    c.page.wait_for_timeout(1500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("17-python-wall.jpg")
