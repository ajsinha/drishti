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

"""Pictures of tutorial 18, Several datasets in one report (docs/tutorials/18-several-datasets-in-one-report.md), written to docs/tutorials/img/t18/.

The two datasets and their grains, a report with a visual on each (Main dataset and Also add), links (a slicer and a click carry across, an unlinked
field does not, a wrong link gives an empty visual), the inflated-total trap in Analyse, a composed dataset and its check, the same as a Python Transform,
two readers (rita sees everything, hana is a desk head without the raw power) and the pandas join promoted. Everything is built here from the files in
docs/tutorials/data/, in a workspace called Exposure desk, so any picture can be remade on its own."""
from __future__ import annotations

from _bi import bi, clean_items, click_mark, element_shot, grid_cell, open_report, quiet_inbox, settled, start_design, submit_design  # noqa: F401
from _tut import ADMIN, OUT, PASSWORD, ROOT, SERVER_ENV, call, elem_shot, go, login  # noqa: F401
from shotlib import Ctx, register

shot = register("t18", OUT / "t18")
DATA = ROOT / "docs" / "tutorials" / "data"
TR, EX = "file:dataset:trading-risk", "file:dataset:counterparty-exposure"
WORKSPACE = "Exposure desk"
AUDIENCE = ["risk", "desk-head", "admin"]
FANOUT = """rachana: 1
dataset: sector-fanout
version: 1
title: Sector fan-out
from:
  pnl:
    dataset: trading-risk
    by: ["counterparties.sector as sector"]
    measures: [MTM, Trades]
  exposure:
    dataset: counterparty-exposure
    by: ["counterparties.id as who", "counterparties.sector as sector"]
    measures: [Exposure (EE peak)]
join:
  - { left: pnl, right: exposure, on: [sector], type: left }
"""
STATE: dict = {}


def ensure(c: Ctx):
    """The people, the two roles and everything the tutorial builds, made once per run (a repeated run starts from nothing)."""
    if STATE:
        return
    login(c)
    quiet_inbox(c)
    for role in ("risk", "desk-head"):                                 # desk-head: a row rule on trades (trading-risk) and no raw power, so names read three dots
        call(c, "POST", f"/admin/api/roles/{role}", {"kinds": ["*"], "raw": role == "risk", "bi": ["bi"]})
    for user, name, role, desk in (("rita", "Rita Rao", "risk", ""), ("hana", "Hana Ito", "desk-head", "DESK-FX")):
        call(c, "POST", "/admin/api/users", {"username": user, "displayName": name, "roles": [role], "desk": desk, "password": PASSWORD,
                                             "email": user + "@example.com", "mustChangePassword": False})
    clean_items(c, "Side by side", "Sector fan-out")
    for w in bi(c, "GET", "/workspaces") or []:
        if w.get("name") == WORKSPACE:
            for it in bi(c, "GET", f"/workspaces/{w['id']}/items") or []:
                bi(c, "DELETE", "/items/" + it["id"])
            bi(c, "DELETE", "/workspaces/" + w["id"])
    ws = bi(c, "POST", "/workspaces", {"name": WORKSPACE, "description": "Counterparty P&L and exposure"})["id"]

    def make(kind: str, title: str, file: str) -> str:
        made = bi(c, "POST", f"/workspaces/{ws}/items", {"kind": kind, "title": title, "body": (DATA / file).read_text(), "audienceRoles": AUDIENCE})
        bi(c, "POST", f"/items/{made['id']}/publish", {"note": "tutorial 18"})
        return made["id"]

    STATE.update(ws=ws, transform=make("python", "MTM against exposure", "mtm-vs-exposure.python.yaml"),
                 composed=make("dataset", "Counterparty P&L and exposure", "cp-pnl-exposure.dataset.yaml"),
                 derived=make("dataset", "Counterparty P&L and exposure (Python)", "cp-pnl-exposure-py.dataset.yaml"),
                 side=make("report", "P&L and exposure side by side", "two-datasets.report.yaml"),
                 wrong=make("report", "P&L and GDP, with a wrong link", "wrong-link.report.yaml"),
                 view=make("report", "MTM and exposure by sector, one table", "counterparty-view.report.yaml"),
                 notebook=make("notebook", "Two extracts, joined the wrong way and the right way", "two-extracts.notebook.yaml"))


def preview(c: Ctx, dataset: str, fields: list, measures: list, name: str):
    go(c, "/bi/d/" + dataset, wait="[data-fields] option", settle=1200)
    c.page.select_option("[data-fields]", fields)
    c.page.select_option("[data-measures]", measures)
    c.page.click("[data-run]")
    c.page.wait_for_selector("[data-grid] tbody tr", timeout=60000)
    c.page.wait_for_timeout(800)
    c.page.evaluate("document.querySelector('[data-preview]').scrollIntoView()")
    c.page.wait_for_timeout(300)
    elem_shot(c, name, "[data-grid-wrap]", pad=14, extra=30)


@shot("01-trades-dataset.jpg", also=("02-exposure-dataset.jpg",))
def grains(c: Ctx):
    ensure(c)
    login(c, height=900)
    preview(c, TR, ["desks.name"], ["P&L 1d", "MTM", "Trades"], "01-trades-dataset.jpg")
    preview(c, EX, ["counterparties.sector"], ["Exposure (EE peak)", "Netting sets"], "02-exposure-dataset.jpg")


@shot("03-new-report.jpg", also=("04-two-cards.jpg", "05-links-panel.jpg"))
def two_trees(c: Ctx):
    ensure(c)
    login(c, height=900)
    start_design(c, TR, "Side by side")
    c.page.select_option("[data-new-also]", [EX])
    c.page.wait_for_timeout(400)
    c.save("03-new-report.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 620})
    submit_design(c)
    p = c.page
    p.drag_and_drop(f'[data-dataset-tree="{TR}"] [data-field="P&L 1d"]', "[data-canvas-grid]", target_position=grid_cell(p, 0, 0))
    p.wait_for_selector(".bi-card-value")
    p.drag_and_drop(f'[data-dataset-tree="{EX}"] [data-field="Exposure (EE peak)"]', "[data-canvas-grid]", target_position=grid_cell(p, 6, 0))
    p.wait_for_selector('.bi-dvis[data-visual="card-2"] .bi-card-value')
    p.wait_for_timeout(800)
    c.save("04-two-cards.jpg")
    tick = p.locator('[data-link-tick="counterparties.sector = counterparties.sector"]')
    tick.scroll_into_view_if_needed()
    tick.check()
    p.wait_for_timeout(600)
    tick.scroll_into_view_if_needed()
    p.wait_for_timeout(300)
    box = p.locator("[data-links-panel]").evaluate("e => { const r = e.closest('aside, section, div[class*=fields]') || e.parentElement; const b = r.getBoundingClientRect(); return {x: b.x, y: b.y, w: b.width, h: b.height}; }")
    c.save("05-links-panel.jpg", clip={"x": max(0, box["x"] - 10), "y": 0, "width": min(1280, box["w"] + 20), "height": 900})
    p.keyboard.press("Control+s")
    p.wait_for_timeout(1200)


def pick(c: Ctx, visual: str, value: str):
    c.page.locator(f'[data-visual="{visual}"] summary.ms-sum').click()
    c.page.locator(f'[data-visual="{visual}"] .ms-pop label', has_text=value).first.click()
    c.page.wait_for_timeout(600)
    c.page.keyboard.press("Escape")
    c.page.locator("h1").first.click()
    settled(c.page)
    c.page.wait_for_timeout(1200)
    c.page.evaluate("window.scrollTo(0, 0)")


@shot("06-report.jpg", also=("07-sector-chosen.jpg", "08-country-chosen.jpg", "09-cross-filter.jpg", "10-wrong-link.jpg"))
def linked(c: Ctx):
    ensure(c)
    login(c, height=1000)
    open_report(c, STATE["side"], height=1000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("06-report.jpg")
    pick(c, "sector", "Banking")
    c.save("07-sector-chosen.jpg")
    open_report(c, STATE["side"], height=1000)
    pick(c, "country", "GB")
    c.save("08-country-chosen.jpg")
    open_report(c, STATE["side"], height=1000)
    click_mark(c.page, "pnl-by-sector", "Insurance")
    settled(c.page)
    c.page.wait_for_timeout(1200)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("09-cross-filter.jpg")
    open_report(c, STATE["wrong"], height=800)
    pick(c, "iso", "GBR")
    c.save("10-wrong-link.jpg")


@shot("11-two-extracts.jpg", also=("12-the-trap.jpg", "13-the-cure.jpg"))
def analyse(c: Ctx):
    ensure(c)
    login(c, height=900)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/explore?item=" + STATE["notebook"])
    c.page.wait_for_selector("[data-extra='exposure']", timeout=60000)
    c.page.wait_for_function("() => /rows/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("11-two-extracts.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("#bxT-python")
    cells = c.page.locator("[data-cells=python] [data-run]")
    cells.nth(0).click()
    c.page.wait_for_selector("[data-cells=python] .bx-out", timeout=90000)
    c.page.wait_for_timeout(1500)
    elem_shot(c, "12-the-trap.jpg", "[data-pane=python]", pad=6)
    cells.nth(1).click()
    c.page.wait_for_function("() => document.querySelectorAll('[data-cells=python] .bx-out canvas, [data-cells=python] .bx-out .bi-chart').length > 0", timeout=90000)
    c.page.wait_for_timeout(1500)
    elem_shot(c, "13-the-cure.jpg", "[data-pane=python]", pad=6)


@shot("14-composed-dataset.jpg", also=("15-fan-out.jpg", "16-composed-report.jpg"))
def composed(c: Ctx):
    ensure(c)
    login(c, height=900)
    go(c, "/bi/d/" + STATE["composed"], wait="[data-built-sec]:not([hidden])", settle=1500)
    element_shot(c, "14-composed-dataset.jpg", "[data-built-sec]", pad=10)
    made = bi(c, "POST", "/workspaces/me/items", {"kind": "dataset", "title": "Sector fan-out", "body": FANOUT})
    go(c, "/bi/d/" + made["id"], wait="[data-built-sec]:not([hidden])", settle=1500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("15-fan-out.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 330})
    bi(c, "DELETE", "/items/" + made["id"])
    open_report(c, STATE["view"], height=1000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("16-composed-report.jpg")


@shot("17-transform-item.jpg", also=("18-derived-dataset.jpg",))
def derived(c: Ctx):
    ensure(c)
    login(c, height=900)
    quiet_inbox(c)
    go(c, "/bi/i/" + STATE["transform"], wait="[data-actions] button", settle=1200)
    c.save("17-transform-item.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    go(c, "/bi/d/" + STATE["derived"], wait="[data-built-sec]:not([hidden])", settle=1500)
    c.page.click("[data-run-transform]")
    c.page.wait_for_function("() => /rows/.test(document.querySelector('[data-run-result]').textContent)", timeout=90000)
    c.page.wait_for_timeout(600)
    element_shot(c, "18-derived-dataset.jpg", "[data-built-sec]", pad=10)


@shot("19-as-rita.jpg", also=("20-as-hana.jpg",))
def readers(c: Ctx):
    ensure(c)
    for user, name in (("rita", "19-as-rita.jpg"), ("hana", "20-as-hana.jpg")):
        login(c, user, height=1000)
        open_report(c, STATE["view"], height=1000)
        c.page.evaluate("window.scrollTo(0, 0)")
        c.save(name)


@shot("21-promote-visual.jpg")
def promote(c: Ctx):
    ensure(c)
    login(c, height=900)
    quiet_inbox(c)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/explore?item=" + STATE["notebook"])
    c.page.wait_for_selector("[data-extra='exposure']", timeout=60000)
    c.page.wait_for_function("() => /rows/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.click("#bxT-python")
    c.page.locator("[data-cells=python] [data-promote]").first.wait_for(timeout=30000)
    print("  promote buttons:", c.page.eval_on_selector_all("[data-cells=python] [data-promote]", "els => els.map(e => e.getAttribute('data-promote'))"))
    button = c.page.locator('[data-cells=python] [data-promote="visual"]').nth(1)
    button.click()
    c.page.locator("[data-cells=python] .bi-near [data-promoted-link]").first.wait_for(timeout=30000)      # the answer is beside the button that asked
    button.scroll_into_view_if_needed()
    c.page.wait_for_timeout(500)
    box = c.page.locator("[data-cells=python] .bi-near").first.bounding_box()
    c.save("21-promote-visual.jpg", clip={"x": 240, "y": max(0, box["y"] - 160), "width": 1040, "height": 400})
