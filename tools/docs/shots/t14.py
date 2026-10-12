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

"""Pictures of tutorial 14, Python and Analyse (docs/tutorials/14-python-and-analyse.md), written to docs/tutorials/img/t14/.

A Python measure and a Python visual in a report (the shipped ones, then one of your own); then Analyse: an extract of a dataset, a slice, a SQL cell, a Python cell
that draws a chart, a saved notebook, and the promotion of a cell to a visual and of a slice to a report draft."""
from __future__ import annotations

from _bi import bi, clean_items, quiet_inbox
from _tut import OUT, ROOT, SERVER_ENV, elem_shot, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t14", OUT / "t14")
DATA = ROOT / "docs" / "tutorials" / "data"
ITEM, REPORT = "Notional by desk chart", "Desks in Python"
WORKSPACE = "economics-desk"
CHART = ("g = df.groupby('desk', as_index=False)['notional'].sum()\n"
         "result = {'title': {'text': 'Notional by desk'}, 'xAxis': {'type': 'category', 'data': list(g['desk'])}, 'yAxis': {'type': 'value'}, "
         "'series': [{'type': 'bar', 'data': [float(v) for v in g['notional']]}]}")


def open_py(c: Ctx, item: str):
    """Opens a report with Python visuals: they run when the page opens (a worker per visual), so give them a few seconds."""
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(f"{c.base}/bi/r/{item}")
    c.page.wait_for_selector("[data-ready=yes]", timeout=30000)
    c.page.wait_for_timeout(12000)


def python_drawn(c: Ctx):
    c.page.wait_for_timeout(500)


@shot("01-python-report.jpg")
def shipped_report(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    open_py(c, "file:report:python-desk-risk")
    python_drawn(c)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("01-python-report.jpg")


@shot("02-python-item.jpg", also=("03-check-python.jpg", "04-desks-in-python.jpg"))
def own_python(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    clean_items(c, ITEM, REPORT)
    cat = bi(c, "GET", "/catalogue")
    for it in (cat.get("items", []) if isinstance(cat, dict) else cat):
        if it["title"] == ITEM and not it.get("shipped"):
            bi(c, "DELETE", "/items/" + it["id"])
    if not any(w["id"] == WORKSPACE for w in bi(c, "GET", "/workspaces")):
        bi(c, "POST", "/workspaces", {"name": "Economics desk", "description": "Reports on the world economy"})
    made = bi(c, "POST", f"/workspaces/{WORKSPACE}/items", {"kind": "python", "title": ITEM, "body": (DATA / "notional-by-desk.python.yaml").read_text()})
    go(c, "/bi/i/" + made["id"], wait="[data-meta]", settle=1500)
    c.save("02-python-item.jpg")
    c.page.get_by_role("button", name="Check the Python").click()
    c.page.wait_for_timeout(3500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("03-check-python.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 420})
    bi(c, "POST", f"/items/{made['id']}/publish", {"note": "a Python visual"})
    report = bi(c, "POST", "/workspaces/me/items", {"kind": "report", "title": REPORT, "body": (DATA / "desk-python.report.yaml").read_text()})
    open_py(c, report["id"])
    python_drawn(c)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("04-desks-in-python.jpg")


def open_extract(c: Ctx):
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/explore")
    c.page.wait_for_function("() => document.querySelectorAll('[data-dataset] option').length > 1", timeout=30000)
    c.page.select_option("[data-dataset]", "file:dataset:trades-basic")
    c.page.wait_for_selector("[data-fields] input[name=f]", timeout=30000)


@shot("05-analyse-start.jpg", also=("06-slice.jpg", "07-sql.jpg", "08-python-cell.jpg", "09-saved.jpg", "10-promote-visual.jpg", "11-promote-slice.jpg"))
def analyse(c: Ctx):
    login(c, height=900)
    quiet_inbox(c)
    listing = bi(c, "GET", "/workspaces/me/items")
    for it in (listing if isinstance(listing, list) else listing.get("items", [])):
        if it["title"].startswith(("Slice of ", "Analyse cell", "Desk notional, first look")):
            bi(c, "DELETE", "/items/" + it["id"])
    open_extract(c)
    c.save("05-analyse-start.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("[data-open-go]")
    c.page.wait_for_function("() => /rows,/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.click('.bx-chip[data-field="desk"] [data-to="rows"]')
    c.page.click('.bx-chip[data-field="currency"] [data-to="columns"]') if c.page.locator('.bx-chip[data-field="currency"]').count() else c.page.click('.bx-chip[data-field="book"] [data-to="columns"]')
    c.page.click('.bx-chip[data-field="notional"] [data-to="values"]')
    c.page.wait_for_selector("[data-pivot] table")
    c.page.wait_for_timeout(500)
    elem_shot(c, "06-slice.jpg", "#bx", pad=6)
    c.page.click("[data-promote-slice]")
    c.page.wait_for_timeout(2500)
    c.save("11-promote-slice.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("#bxT-sql")
    c.page.fill("[data-cells=sql] textarea", "SELECT desk, COUNT(*) AS trades, SUM(notional) AS notional\nFROM extract\nGROUP BY desk\nORDER BY notional DESC")
    c.page.click("[data-cells=sql] [data-run]")
    c.page.wait_for_selector("[data-cells=sql] table")
    c.page.wait_for_timeout(500)
    elem_shot(c, "07-sql.jpg", "[data-pane=sql]", pad=6)
    c.page.click("#bxT-python")
    c.page.fill("[data-cells=python] textarea", CHART)
    c.page.click("[data-cells=python] [data-run]")
    c.page.wait_for_selector("[data-cells=python] [data-ready]", timeout=60000)
    c.page.wait_for_timeout(800)
    elem_shot(c, "08-python-cell.jpg", "[data-pane=python]", pad=6)
    c.page.click('[data-cells=python] [data-promote="visual"]')
    c.page.wait_for_timeout(2500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("10-promote-visual.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("[data-save]")
    c.page.fill("input[name=title], [data-save-title]", "Desk notional, first look") if c.page.locator("input[name=title], [data-save-title]").count() else None
    c.page.click("[data-save-go]")
    c.page.wait_for_selector("[data-saved]:not([hidden])", timeout=30000)
    c.page.wait_for_timeout(600)
    c.save("09-saved.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
