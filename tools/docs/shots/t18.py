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

One model with two facts: the trade and netting-set pack datasets and their grains, the model built in the model designer (suggested relationships, measures
from columns, a measure over two facts in the expression editor, a row rule and the safeguard), a report with MTM and exposure in one visual, the refusal
of exposure by desk, the inflated-total trap in Analyse and its cure, a dataset made by a Python Transform, two readers (rita sees everything, hana is a
desk head without the raw power) and a promotion from Analyse. Everything is made here in a workspace called Exposure desk; the model through the designer,
the rest from the files in docs/tutorials/data/, so any picture can be remade on its own (the model is made first when it is missing)."""
from __future__ import annotations

from _bi import bi, click_mark, element_shot, grid_cell, open_report, quiet_inbox, settled, settled_design  # noqa: F401
from _tut import ADMIN, OUT, PASSWORD, ROOT, SERVER_ENV, call, elem_shot, go, login, page_shot  # noqa: F401
from shotlib import Ctx, register

shot = register("t18", OUT / "t18")
DATA = ROOT / "docs" / "tutorials" / "data"
WORKSPACE = "Exposure desk"
AUDIENCE = ["risk", "desk-head", "admin"]
TRADE, NETTING, COUNTERPARTY, DESK = "pack.trading.trade", "pack.counterparty-risk.netting-set", "pack.banking-core.counterparty", "pack.banking-core.desk"
STATE: dict = {}


def ensure(c: Ctx):
    """The people, the two roles and the workspace, made once per run (a repeated run starts from nothing)."""
    if STATE:
        return
    login(c)
    quiet_inbox(c)
    for role in ("risk", "desk-head"):                                 # desk-head: a row rule on trades and no raw power, so counterparty names read three dots
        call(c, "POST", f"/admin/api/roles/{role}", {"kinds": ["*"], "raw": role == "risk", "bi": ["bi"]})
    for user, name, role, desk in (("rita", "Rita Rao", "risk", ""), ("hana", "Hana Ito", "desk-head", "DESK-FX")):
        call(c, "POST", "/admin/api/users", {"username": user, "displayName": name, "roles": [role], "desk": desk, "password": PASSWORD,
                                             "email": user + "@example.com", "mustChangePassword": False})
    for w in bi(c, "GET", "/workspaces") or []:
        if w.get("name") == WORKSPACE:
            for it in bi(c, "GET", f"/workspaces/{w['id']}/items") or []:
                bi(c, "DELETE", "/items/" + it["id"])
            bi(c, "DELETE", "/workspaces/" + w["id"])
    STATE["ws"] = bi(c, "POST", "/workspaces", {"name": WORKSPACE, "description": "Counterparty P&L and exposure"})["id"]


def make(c: Ctx, kind: str, title: str, file: str) -> str:
    made = bi(c, "POST", f"/workspaces/{STATE['ws']}/items", {"kind": kind, "title": title, "body": (DATA / file).read_text(), "audienceRoles": AUDIENCE})
    bi(c, "POST", f"/items/{made['id']}/publish", {"note": "tutorial 18"})
    return made["id"]


def the_rest(c: Ctx):
    """The model (made in the designer when the model pictures run, else from its file), then the items the later steps read."""
    ensure(c)
    if "model" not in STATE:
        STATE["model"] = make(c, "model", "Counterparty P&L and exposure", "cp-risk.model.yaml")
    if "report" not in STATE:
        login(c)
        STATE.update(python=make(c, "python", "Counterparty table", "cp-table.python.yaml"),
                     table=make(c, "dataset", "Counterparty table (Python)", "cp-table.dataset.yaml"),
                     report=make(c, "report", "MTM and exposure by sector", "cp-risk-view.report.yaml"),
                     notebook=make(c, "notebook", "Two extracts, joined the wrong way and the right way", "cp-risk-extracts.notebook.yaml"))


@shot("01-trade-dataset.jpg", also=("02-netting-set-dataset.jpg",))
def grains(c: Ctx):
    ensure(c)
    login(c, height=900)
    for name, pic in ((TRADE, "01-trade-dataset.jpg"), (NETTING, "02-netting-set-dataset.jpg")):
        go(c, "/bi/ds/" + name, wait='[data-layer-dataset][data-ready="yes"]', settle=1200)
        c.page.evaluate("window.scrollTo(0, 0)")
        c.save(pic, clip={"x": 240, "y": 56, "width": 1040, "height": 640})


def wait_valid(c: Ctx):
    c.page.wait_for_function("() => !document.querySelector('[data-valid]') || !/Not ready|Has errors/.test(document.querySelector('[data-valid]').textContent) || true")
    c.page.wait_for_timeout(1200)


@shot("03-new-model.jpg", also=("04-canvas-suggestions.jpg", "05-relationships.jpg", "06-measures.jpg", "07-ratio-measure.jpg", "08-security.jpg", "09-model-yaml.jpg"))
def designer(c: Ctx):
    ensure(c)
    login(c, height=900)
    p = c.page
    go(c, "/bi/models", wait='[data-models-root][data-ready="yes"]', settle=800)
    p.click("[data-new-model]")
    p.fill("[data-nm-title]", "CP risk")
    p.select_option("[data-nm-ws]", STATE["ws"])
    for name in (TRADE, NETTING, COUNTERPARTY, DESK):
        p.check(f'[data-nm-pick="{name}"]')
    p.wait_for_timeout(300)
    p.evaluate("window.scrollTo(0, 0)")                                    # the sticky top bar stays at the top of the page, above the form
    p.wait_for_timeout(300)
    top = p.locator("[data-nm-title]").evaluate("e => e.getBoundingClientRect().y + window.scrollY")
    bottom = p.locator("[data-nm-go]").evaluate("e => e.getBoundingClientRect().bottom + window.scrollY")
    page_shot(c, "03-new-model.jpg", {"x": 250, "y": top - 50, "width": 1010, "height": bottom - top + 60})
    p.click("[data-nm-go]")
    p.wait_for_url("**/bi/md/**")
    p.wait_for_selector('[data-md-root][data-ready="yes"]', timeout=30000)
    STATE["model"] = p.url.rsplit("/", 1)[-1]
    p.wait_for_selector('[data-suggestion]', timeout=30000)
    p.select_option('[data-role-of="netting_set"]', "fact")                 # trades reference netting sets, so it was guessed a dimension: it is a fact
    p.wait_for_selector('[data-suggestion="trade.nettingSet->netting_set.id"]', timeout=15000)
    p.wait_for_timeout(1500)
    elem_shot(c, "04-canvas-suggestions.jpg", '[data-pane="canvas"]', pad=8)
    for s in ("trade.counterpartyId->counterparty.id", "netting_set.counterparty->counterparty.id", "trade.desk->desk.id"):
        p.locator(f'[data-suggestion="{s}"] [data-accept]').click()
        p.wait_for_selector(f'[data-rel="{s.replace("->", " → ")}"]', timeout=15000)
    p.wait_for_timeout(1000)
    elem_shot(c, "05-relationships.jpg", "[data-suggestions-box]", pad=8, extra=260)
    p.click('[data-tab="measures"]')
    for alias, col, name in (("trade", "mtm", "MTM"), ("trade", "pnl1d", "P&L 1d"), ("trade", "*", "Trades"), ("netting_set", "eePeak", "Exposure (EE peak)"),
                             ("netting_set", "*", "Netting sets")):
        p.select_option("[data-nm-alias]", alias)
        p.select_option("[data-nm-column]", col)
        p.click("[data-nm-add]")
        p.wait_for_timeout(900)
        made = p.locator("[data-measure]").last.get_attribute("data-measure")
        if made != name:
            nm = p.locator(f'[data-m-name="{made}"]')
            nm.fill(name)
            nm.press("Tab")
            p.wait_for_selector(f'[data-measure="{name}"]', timeout=15000)
    elem_shot(c, "06-measures.jpg", ".bi-md-measures", pad=8)
    p.click("[data-nf] summary")
    p.fill("[data-nf-name]", "MTM per exposure")
    ed = p.locator("[data-nf-expr]")
    ed.click()
    ed.type("MT")
    p.click('[data-xe-opt="MTM"]')
    ed.type(" / Exp")
    p.click('[data-xe-opt="Exposure (EE peak)"]')
    p.wait_for_timeout(1200)
    element_shot(c, "07-ratio-measure.jpg", "[data-nf]")
    p.click("[data-nf-add]")
    p.wait_for_selector('[data-measure="MTM per exposure"]', timeout=15000)
    for name, fmt in (("MTM", "signed0"), ("P&L 1d", "signed0"), ("Exposure (EE peak)", "amount0"), ("MTM per exposure", "signed2")):
        p.select_option(f'[data-m-format="{name}"]', fmt)
        p.wait_for_timeout(300)
    p.click('[data-tab="security"]')
    for roles, kind in (("desk-head", "attr"), ("risk", "all"), ("admin", "all")):
        p.select_option("[data-rule-alias]", "trade")
        p.fill("[data-rule-roles]", roles)
        p.select_option("[data-rule-kind]", kind)
        if kind == "attr":
            p.select_option("[data-rule-field]", "trade.desk")
        p.click("[data-rule-add]")
        p.wait_for_timeout(1500)
    p.wait_for_selector('[data-exposure="netting_set:desk-head"]', timeout=15000)
    elem_shot(c, "08-security.jpg", "[data-safeguard]", pad=8, extra=330)
    for alias in ("netting_set", "counterparty", "desk"):
        sel = f'[data-safeguard-all="{alias}:desk-head"]'
        if p.locator(sel).count():
            p.click(sel)
            p.wait_for_timeout(1200)
    p.select_option("[data-mask-field]", "counterparty.name")
    p.click("[data-mask-add]")
    p.wait_for_timeout(1200)
    p.click('[data-tab="contract"]')
    title = p.locator("[data-c-title]")
    title.fill("Counterparty P&L and exposure")
    title.press("Tab")
    p.wait_for_timeout(1200)
    p.click('[data-tab="yaml"]')
    p.wait_for_timeout(800)
    elem_shot(c, "09-model-yaml.jpg", '[data-pane="yaml"]', pad=8)
    p.click("[data-save]")
    p.wait_for_function("() => /Saved draft/.test(document.querySelector('[data-state]').textContent)", timeout=15000)
    p.click("[data-publish]")
    p.wait_for_function("() => /Published/.test(document.querySelector('[data-state]').textContent)", timeout=30000)
    bi(c, "PUT", "/items/" + STATE["model"], {"audienceRoles": AUDIENCE})     # its item page's Audience: rita and hana may read the model (step 7)
    STATE["model_yaml"] = p.evaluate("window.drishtiModel.yaml")
    print("  the designer wrote:\n" + STATE["model_yaml"])


@shot("10-report.jpg", also=("11-sector-chosen.jpg",))
def report(c: Ctx):
    the_rest(c)
    login(c, height=1000)
    open_report(c, STATE["report"], height=1000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("10-report.jpg")
    c.page.locator('[data-visual="sector"] summary.ms-sum').click()
    c.page.locator('[data-visual="sector"] .ms-pop label', has_text="Banking").first.click()
    c.page.wait_for_timeout(600)
    c.page.keyboard.press("Escape")
    c.page.locator("h1").first.click()
    settled(c.page)
    c.page.wait_for_timeout(1200)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("11-sector-chosen.jpg")


@shot("12-exposure-by-desk.jpg")
def refused(c: Ctx):
    the_rest(c)
    login(c, height=900)
    p = c.page
    item = bi(c, "POST", f"/workspaces/{STATE['ws']}/items", {"kind": "report", "title": "Exposure by desk (refused)",
                                                                "body": "report: exposure-by-desk\ntitle: Exposure by desk (refused)\nmodel: cp-risk@1\npages:\n"
                                                                        "  - id: main\n    title: Main\n    visuals:\n"
                                                                        "      - { id: bar, type: bar, title: Exposure by desk, at: { x: 0, y: 0, w: 8, h: 6 }, wells: { axis: [desk.name], values: [Exposure (EE peak)] } }\n"})
    go(c, "/bi/design/" + item["id"], wait='[data-design-root][data-ready="yes"]', settle=1500)
    settled_design(p)
    p.locator(".bi-dvis").first.click()
    p.wait_for_timeout(1000)
    c.save("12-exposure-by-desk.jpg", clip={"x": 0, "y": 56, "width": 1280, "height": 560})
    bi(c, "DELETE", "/items/" + item["id"])


@shot("13-two-extracts.jpg", also=("14-the-trap.jpg", "15-the-cure.jpg"))
def analyse(c: Ctx):
    the_rest(c)
    login(c, height=900)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/explore?item=" + STATE["notebook"])
    c.page.wait_for_selector("[data-extra='exposure']", timeout=60000)
    c.page.wait_for_function("() => /rows/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("13-two-extracts.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 844})
    c.page.click("#bxT-python")
    cells = c.page.locator("[data-cells=python] [data-run]")
    cells.nth(0).click()
    c.page.wait_for_selector("[data-cells=python] [data-stdout]", timeout=90000)
    c.page.wait_for_timeout(1500)
    elem_shot(c, "14-the-trap.jpg", "[data-pane=python]", pad=6)
    cells.nth(1).click()
    c.page.wait_for_function("() => document.querySelectorAll('[data-cells=python] .bx-out canvas, [data-cells=python] .bx-out .bi-chart').length > 0", timeout=90000)
    c.page.wait_for_timeout(1500)
    elem_shot(c, "15-the-cure.jpg", "[data-pane=python]", pad=6)


@shot("16-python-dataset.jpg")
def python_dataset(c: Ctx):
    the_rest(c)
    login(c, height=900)
    go(c, "/bi/ds/" + STATE["table"], wait='[data-layer-dataset][data-ready="yes"]', settle=1500)
    c.page.evaluate("window.scrollTo(0, 0)")
    c.save("16-python-dataset.jpg", clip={"x": 240, "y": 56, "width": 1040, "height": 700})


@shot("17-as-rita.jpg", also=("18-as-hana.jpg",))
def readers(c: Ctx):
    the_rest(c)
    for user, name in (("rita", "17-as-rita.jpg"), ("hana", "18-as-hana.jpg")):
        login(c, user, height=1000)
        open_report(c, STATE["report"], height=1000)
        c.page.evaluate("window.scrollTo(0, 0)")
        c.save(name)


@shot("19-promote-visual.jpg")
def promote(c: Ctx):
    the_rest(c)
    login(c, height=900)
    quiet_inbox(c)
    c.page.set_viewport_size({"width": 1280, "height": 900})
    c.page.goto(c.base + "/bi/explore?item=" + STATE["notebook"])
    c.page.wait_for_selector("[data-extra='exposure']", timeout=60000)
    c.page.wait_for_function("() => /rows/.test(document.querySelector('[data-info]').textContent)", timeout=60000)
    c.page.click("#bxT-python")
    c.page.locator("[data-cells=python] [data-promote]").first.wait_for(timeout=30000)
    button = c.page.locator('[data-cells=python] [data-promote="visual"]').nth(1)
    button.click()
    c.page.locator("[data-cells=python] .bi-near [data-promoted-link]").first.wait_for(timeout=30000)      # the answer is beside the button that asked
    button.scroll_into_view_if_needed()
    c.page.wait_for_timeout(500)
    box = c.page.locator("[data-cells=python] .bi-near").first.bounding_box()
    c.save("19-promote-visual.jpg", clip={"x": 240, "y": max(0, box["y"] - 160), "width": 1040, "height": 400})
