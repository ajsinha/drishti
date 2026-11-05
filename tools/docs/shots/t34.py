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

"""Pictures of tutorial 34, one file per lake (docs/tutorials/34-one-file-per-lake.md), written to docs/tutorials/img/t34/, in the light
theme. Run the tutorial's steps 4 to 6 first (they write the scratch folder $SCRATCH), then start a scratch stack on the demo lake whose
lake folder is $SCRATCH/lakes (DRISHTI_LAKES_DIR: the demo lake file, the official-results lakes, the append lake `intraday` of step 5
and the lake `lake` the migration of step 6 wrote), sign-in off, and point this at its console:

    DRISHTI_SHOTS_CONSOLE_PORT=<port> drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t34

The pictures, in the tutorial's order: Admin → Lakes with the demo lake open, Admin → Connectors on trade-endur, the lakes checked,
trading.trade_endur's row, the append table's row, and the lake written by the migration."""
from __future__ import annotations

from _tut import OUT, light
from shotlib import Ctx, register

shot = register("t34", OUT / "t34")
WIDE, HIGH = 1440, 900


def _lakes(c: Ctx, lake: str = "", row: str = "") -> None:
    """Admin -> Lakes, The lakes: only `lake` open (none: all closed), scrolled to it, or to its table `row` (filtered to it)."""
    light(c)
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/admin/lakes")
    c.until("document.querySelectorAll('details.lk-files').length > 0", 120)
    c.page.evaluate("""([lake, row]) => {
        let target = document.querySelector('#lkFiles');
        document.querySelectorAll('details.lk-files').forEach(d => {
            const on = !!lake && d.querySelector('summary').innerText.trim().indexOf(lake + ' ') === 0;
            d.open = on;
            if (on) { target = d; }
        });
        target.scrollIntoView({block: 'start'}); window.scrollBy(0, -80);
    }""", [lake, row])
    c.page.wait_for_timeout(800)
    if row:
        box = c.page.locator("details.lk-files[open] input").first
        if box.count():
            box.fill(row)
            c.page.wait_for_timeout(800)
    c.page.evaluate("""() => { const d = document.querySelector('details.lk-files[open]') || document.querySelector('#lkFiles');
        d.scrollIntoView({block: 'start'}); window.scrollBy(0, -80); }""")
    c.page.wait_for_timeout(500)


@shot("01-lakes.jpg")
def lakes(c: Ctx):
    _lakes(c, "demo")
    c.save("01-lakes.jpg")


@shot("02-connector.jpg")
def connector(c: Ctx):
    light(c)
    c.page.set_viewport_size({"width": WIDE, "height": HIGH})
    c.page.goto(c.base + "/admin/connectors")
    c.page.wait_for_timeout(2500)
    c.page.fill("input[placeholder^='Filter by name']", "trade-endur")
    c.page.wait_for_timeout(1500)
    c.page.evaluate("() => { const h = Array.from(document.querySelectorAll('h1, h2')).find(x => /connectors/i.test(x.textContent)); if (h) { h.scrollIntoView({block: 'start'}); window.scrollBy(0, -20); } }")
    c.page.wait_for_timeout(500)
    c.save("02-connector.jpg")


@shot("03-check.jpg")
def check(c: Ctx):
    _lakes(c)
    c.save("03-check.jpg")


@shot("04-trade-endur.jpg")
def trade_endur(c: Ctx):
    _lakes(c, "demo", "trade_")
    c.save("04-trade-endur.jpg")


@shot("05-append-table.jpg")
def append_table(c: Ctx):
    _lakes(c, "intraday")
    c.save("05-append-table.jpg")


@shot("06-migrated-lake.jpg")
def migrated(c: Ctx):
    _lakes(c, "lake")
    c.save("06-migrated-lake.jpg")
