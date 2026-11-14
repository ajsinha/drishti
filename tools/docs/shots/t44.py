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

"""Pictures of the tables tutorial, virtual tables and models (docs/guides/BI_GUIDE.md), written to docs/tutorials/img/t44/,
in the light theme. Runs on its own on the scratch stack (the demo source on, sign-in off):

    DRISHTI_SHOTS_SERVER_PORT=<port> DRISHTI_SHOTS_CONSOLE_PORT=<port> drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t44

The pictures, in the tutorial's order: the trades' legs in the Pivot (one row per leg, the grain the table declares), the model
trading-risk's Security tab (its row rules per role and masked columns: what tables migrate carries into tables), the legs by book and
currency with subtotals and the grand total (what a model of legs and books answers by book), the model's measures (Open MTM, the null
rule's example), and the tables guide in Help."""
from __future__ import annotations

from _tut import OUT, go, light
from shotlib import Ctx, register

SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}             # runs on its own (--guide t44): its own scratch stack
shot = register("t44", OUT / "t44")
WIDE, HIGH = 1280, 860
LEGS = {"source": "trade", "rows": ["$.LEGS[*].CURRENCY"],
        "values": [{"measure": "count()", "as": "Legs"}, {"measure": "sum($.LEGS[*].NOTIONAL)", "as": "Notional"}]}
BY_BOOK = {"source": "trade", "rows": ["$.PORTFOLIO", "$.LEGS[*].CURRENCY"], "subtotals": "below", "expandTo": {"rows": 2, "columns": 1},
           "values": [{"measure": "count()", "as": "Legs"}, {"measure": "sum($.LEGS[*].NOTIONAL)", "as": "Notional"}]}


def before(c: Ctx, _name: str) -> None:
    light(c)
    c.page.request.fetch(c.base + "/api/settings", method="PATCH", data='{"theme": "light"}', headers={"Content-Type": "application/json"})


def _pivot(c: Ctx, name: str, view: dict) -> None:
    r = c.api("/api/pivot/views", {"name": name, "view": view})
    if "id" not in r:
        raise RuntimeError(f"the pivot view {name}: {r}")
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/pivot?view=" + r["id"])
    c.until("document.querySelectorAll('[data-pv-grid] td.pv-c').length > 0", 300)
    c.page.wait_for_timeout(1200)


def _model_tab(c: Ctx, tab: str) -> None:
    go(c, "/bi/md/file:model:trading-risk", width=WIDE, height=HIGH, wait=f"[data-tab='{tab}']", settle=1500)
    c.page.locator(f".bi-md-tab[data-tab='{tab}']").click()
    c.until(f"!document.querySelector('[data-pane={tab}]').hidden && document.querySelector('[data-pane={tab}]').innerText.trim().length > 40", 60)
    c.page.wait_for_timeout(800)
    y = c.page.evaluate("() => document.querySelector('.bi-md-tab[data-tab]').getBoundingClientRect().top + window.scrollY")
    c.page.evaluate(f"() => window.scrollTo(0, {max(0, int(y) - 70)})")
    c.page.wait_for_timeout(500)


@shot("01-legs-in-the-pivot.jpg")
def legs(c: Ctx):
    """Step 1: the trades' legs, one row per leg, by currency: what the table's grain $.LEGS[*] makes."""
    _pivot(c, "t44-legs-by-currency", LEGS)
    c.save("01-legs-in-the-pivot.jpg", clip={"x": 0, "y": 56, "width": WIDE, "height": 560})


@shot("02-model-security.jpg")
def security(c: Ctx):
    """Step 4: the model trading-risk's row rules per role and its masked columns: what tables migrate carries into the tables."""
    _model_tab(c, "security")
    c.save("02-model-security.jpg", clip={"x": 0, "y": 0, "width": WIDE, "height": HIGH})


@shot("03-legs-by-book.jpg")
def by_book(c: Ctx):
    """Step 5: the legs by book and currency, every book open: their subtotals and the grand total, as a model of legs and books answers by book."""
    _pivot(c, "t44-legs-by-book", BY_BOOK)                     # every book open (expandTo): its currencies, then its subtotal
    c.until("document.querySelectorAll('[data-pv-grid] .dg-ctoggle[aria-expanded=\"true\"]').length > 0", 120)
    c.page.wait_for_timeout(800)
    c.save("03-legs-by-book.jpg", clip={"x": 0, "y": 56, "width": WIDE, "height": HIGH - 56})


@shot("04-model-measures.jpg")
def measures(c: Ctx):
    """Step 6: the model trading-risk's measures, Open MTM among them (the null rule's example)."""
    _model_tab(c, "measures")
    y = c.page.evaluate("""() => { const i = [...document.querySelectorAll('[data-pane=measures] input')].find(x => x.value === 'Open MTM');
        return i ? i.getBoundingClientRect().top + window.scrollY : 0; }""")
    if y:                                                       # the measures' table, Open MTM in view with the rows above it
        c.page.evaluate(f"() => window.scrollTo(0, {max(0, int(y) - 560)})")
        c.page.wait_for_timeout(500)
    c.save("04-model-measures.jpg", clip={"x": 0, "y": 0, "width": WIDE, "height": HIGH})


@shot("05-tables-guide-in-help.jpg")
def guide(c: Ctx):
    """Next steps: the tables guide in Help, beside this tutorial."""
    go(c, "/help/tables-guide", width=WIDE, height=HIGH, wait="body", settle=1500)
    c.save("05-tables-guide-in-help.jpg", clip={"x": 0, "y": 56, "width": WIDE, "height": HIGH - 56})
