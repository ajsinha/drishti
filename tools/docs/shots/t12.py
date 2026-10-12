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
from shotlib import Ctx, register

shot = register("t12", OUT / "t12")
LIVE_DATASET = "file:dataset:live-trading"
LIVE_REPORT = "file:report:live-trading-desk"
BOARD = "file:board:desk-wall"


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
    c.page.request.post(c.base + "/bi/api/datasets/" + LIVE_DATASET + "/query", data='{"fields":["trade.desk"],"measures":["Trades"]}', headers={"Content-Type": "application/json"})
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
    c.page.goto(c.base + "/bi/b/" + BOARD)
    c.page.wait_for_selector("[data-ready=yes], [data-board]", timeout=30000)
    c.page.wait_for_timeout(6000)
    c.save("08-board.jpg")
    c.page.get_by_role("tab").nth(1).click()
    c.page.wait_for_timeout(5000)
    c.save("09-board-exposure.jpg")
    c.page.get_by_role("tab").nth(0).click()
    c.page.click("[data-kiosk]")
    c.page.wait_for_timeout(5000)
    c.save("10-kiosk.jpg")
