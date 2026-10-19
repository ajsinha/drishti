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

"""The pictures of BI Studio (BI_GUIDE.md section 26, wave C, 2.4.0), written to docs/guides/img/bistudio/.

They are made on the report browser tests' scratch stack (drishti-console/tests/bi_report_stack.py: the built server jar, the trading and market-risk
packs, the model trades-basic over a small synthetic lake). Its ports come from DRISHTI_BI_REPORT_SERVER_PORT and DRISHTI_BI_REPORT_CONSOLE_PORT, so run:

    DRISHTI_BI_REPORT_SERVER_PORT=18990 DRISHTI_BI_REPORT_CONSOLE_PORT=17990 \\
        drishti-console/.venv/bin/python tools/docs/screenshots.py --guide bistudio --base http://127.0.0.1:17990"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "drishti-console" / "tests"))
from shotlib import ROOT, Ctx, register  # noqa: E402

shot = register("bistudio", ROOT / "docs" / "guides" / "img" / "bistudio")
STACK = {}
PASSWORD = "long-enough-pass-1"

# The desk pack of REPORTS.md section 20: what the acceptance test builds in the designer without the YAML tab.
DESK_PACK = """rachana: 1
report: desk-pack
title: Desk pack
model: trades-basic@1
theme: { use: corporate }
pages:
  - id: page1
    title: Page 1
    interactions:
      column-1: { matrix-1: none }
    visuals:
      - id: matrix-1
        type: matrix
        title: P&L by desk and portfolio
        at: { x: 0, y: 0, w: 6, h: 5 }
        wells: { rows: [trade.DESK, trade.PORTFOLIO], columns: [trade.CURRENCY], values: [MTM, Notional] }
        format:
          subtotals: true
          expandTo: 0
          rules:
            - { type: gradient, apply: MTM, min: "#c62828", mid: "#ffffff", max: "#2e7d32" }
            - { type: icons, apply: Notional, icons: arrows, thresholds: [0, 1000000] }
      - id: column-1
        type: column
        title: MTM by desk
        at: { x: 6, y: 0, w: 6, h: 5 }
        wells: { axis: [trade.DESK], values: [MTM], split: [trade.CURRENCY] }
        tooltip: tip1
        format: { subtitle: "By desk, one chart per currency", border: true, valueAxis: { gridlines: false }, multiples: { columns: 3 } }
      - { id: card-1, type: card, title: Trades, at: { x: 0, y: 5, w: 3, h: 2 }, wells: { values: [Trades] } }
      - { id: slicer-1, type: slicer, title: Currency, at: { x: 3, y: 5, w: 3, h: 4 }, wells: { field: [trade.CURRENCY] }, format: { syncGroup: ccy } }
  - id: tip1
    title: Tooltip 1
    tooltip: true
    visuals:
      - { id: card-1, type: card, title: Trades, at: { x: 0, y: 0, w: 6, h: 2 }, wells: { values: [Trades] } }
"""


def start_servers(work: Path) -> list:
    import bi_report_stack

    STACK["stack"] = bi_report_stack.ReportStack(work, delta_root="").start()
    return list(STACK["stack"].procs.values())


def sign_in(c: Ctx, user: str = "wes", to: str = "/bi") -> None:
    c.page.context.clear_cookies()
    c.page.goto(c.base + "/login?next=" + to)
    c.page.fill("#lu", user)
    c.page.fill("#lp", PASSWORD)
    c.page.click("button[type=submit]")
    c.page.wait_for_url("**" + to.split("?")[0])


def item(c: Ctx, kind: str, title: str, body: str) -> str:
    made = c.page.request.post(c.base + "/bi/api/workspaces/me/items", data=json.dumps({"kind": kind, "title": title, "body": body}),
                               headers={"Content-Type": "application/json"}).json()
    return made["id"]


@shot("studio.jpg")
def studio(c: Ctx):
    """The report designer in the Studio shell: the Explorer, the open items, the canvas with the desk pack, Format of the selected matrix, the dock."""
    sign_in(c)
    c.page.set_viewport_size({"width": 1600, "height": 1180})
    rid = item(c, "report", "Desk pack", DESK_PACK)
    c.page.goto(c.base + "/bi/design/" + rid)
    c.page.wait_for_selector("[data-design-root][data-ready=yes]", timeout=30000)
    c.page.wait_for_function("() => !document.querySelector('.bi-dvis.bi-vis-busy') && document.querySelectorAll('.bi-dvis .bi-vis-ms').length >= 3", timeout=30000)
    c.page.click("[data-design-root] [data-visual='matrix-1']")
    c.page.click("[data-ptab='format']")
    c.page.evaluate("window.scrollTo(0, 0)")
    c.page.wait_for_timeout(1500)                                         # the dock's Problems answer a second after the last change
    c.save("studio.jpg")


@shot("theme-designer.jpg")
def theme_designer(c: Ctx):
    """The theme designer on a copy of the shipped Corporate theme: the palette, the format defaults and the live preview."""
    sign_in(c)
    c.page.set_viewport_size({"width": 1600, "height": 1000})
    import re
    body = re.sub(r"(?m)^theme: corporate\b", "theme: desk-theme", (ROOT / "config" / "bi" / "themes" / "corporate.theme.yaml").read_text(), count=1)
    tid = item(c, "theme", "Desk theme", body)
    c.page.goto(c.base + "/bi/design/theme/" + tid)
    c.page.wait_for_selector("[data-theme-root][data-ready=yes]", timeout=30000)
    c.page.wait_for_timeout(2500)
    c.save("theme-designer.jpg")
