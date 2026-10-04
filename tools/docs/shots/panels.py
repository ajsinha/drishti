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


"""The pictures of the panel guides (docs/guides/PANEL_KINDS.md and PANEL_DEVELOPER_GUIDE.md), written to docs/guides/img/panels/.

  kind-<kind>.jpg          each of the 21 kinds, light theme, from the all-panels showcase opened in the workbench (the real page and scripts)
  state-<kind>-<state>.jpg empty, error, masked and phone width for one kind of each family (kv, table, line, metric), and dark for three
  metric-*.jpg             the worked example: palette tile, inspector, the suggestion menu

The "state" pictures are the real view model (the server's own preview of the showcase, or of a document that lacks the data) drawn by the
console's own panel macro and stylesheets: the first paint of a panel, without the page scripts (a masked value or an error cannot be made
to happen in a design preview, so those two are the macro's output for the view model the server returns for them). Run through
tools/docs/screenshots.py --guide panels."""
from __future__ import annotations

import copy
import json

from shotlib import EXAMPLES, ROOT, Ctx, backend_preview, register, render_panel, showcase  # noqa: F401

shot = register("panels")
MASK = "•••"

# the showcase panel that shows each kind (the table: the plain one; the tree is "table with children")
KIND_PANEL = {"kv": "terms", "status": "ops", "waterfall": "explain", "line": "pnl", "hbar": "dv01", "gauge": "dv01use", "ladder": "coupons",
              "timeline": "life", "table": "versions", "tabs": "sets", "area": "exposure", "graph": "group", "pivot": "grid", "scatter": "books",
              "histogram": "scenarios", "candlestick": "bars", "surface": "smile", "provenance": "built", "markdown": "note", "links": "refs",
              "metric": "headline"}
# one kind per family for the states: the panel in the showcase, and a Sutra line of the same kind over a document that lacks its data
FAMILIES = {"kv": "terms", "table": "versions", "line": "pnl", "metric": "headline"}
DARK = ("kv", "table", "metric")
EMPTY_PANEL = {
    "kv": "{ id: terms, kind: kv, title: Bond terms, columns: [ { label: Coupon, bind: $.terms.coupon, fmt: pct4 }, { label: Yield, bind: $.terms.yield, fmt: pct4 } ] }",
    "table": "{ id: versions, kind: table, title: Versions, rows: $.lifecycle.events, columns: [ { label: Event, bind: '@.event' }, { label: By, bind: '@.by' } ] }",
    "line": "{ id: pnl, kind: line, title: Daily P&L (USD), rows: $.pnlHistory, x: date, y: pnl, fmt: signed0 }",
    "metric": "{ id: headline, kind: metric, title: Mark to market (USD), value: $.mtm, fmt: signed0, tone: sign, unit: USD }",
}
_cache: dict = {}


def full_view() -> dict:
    """The server's own view of the showcase: {panel id: PanelView}."""
    if "full" not in _cache:
        doc, sutra = showcase()
        _cache["full"] = {p["id"]: p for p in backend_preview(sutra, json.loads(doc))["panels"]}
    return _cache["full"]


def empty_view(kind: str) -> dict:
    y = "rachana: 1\nsutra: state-demo\nversion: 1\nmatch: { kind: trade }\ntitle: { pill: Demo, id: $.tradeId }\npanels:\n  - " + EMPTY_PANEL[kind] + "\n"
    return backend_preview(y, {"tradeId": "DEMO-1", "currency": "USD"})["panels"][0]


def masked(panel: dict) -> dict:
    """What a user whose roles mask the fields sees: every text cell is the mask, with no tone (the contract of a masked value)."""
    p = copy.deepcopy(panel)

    def walk(n):
        if isinstance(n, dict):
            if "text" in n and ("label" in n or "path" in n or "tone" in n or "link" in n):
                n["text"] = MASK
                n.pop("tone", None)
                n.pop("link", None)
            for v in n.values():
                walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)
    walk(p["data"])
    return p


def card(c: Ctx, name: str, panel: dict, theme: str = "light", width: int = 620, height: int = 700) -> None:
    html = f"""<!doctype html><html lang="en" data-theme="{theme}"><head><meta charset="utf-8"><base href="{c.base}/">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="/static/vendor/bootstrap/css/bootstrap.min.css"><link rel="stylesheet" href="/static/vendor/bootstrap-icons/bootstrap-icons.css">
<link rel="stylesheet" href="/static/css/tokens.css"><link rel="stylesheet" href="/static/css/theme.css"><link rel="stylesheet" href="/static/css/terminal.css">
<link rel="stylesheet" href="/static/css/layout.css"></head>
<body class="terminal" style="padding:16px"><main class="view-main">{render_panel(panel)}</main></body></html>"""
    c.page.set_viewport_size({"width": width, "height": height})
    c.page.set_content(html, wait_until="networkidle")
    c.page.wait_for_timeout(400)
    c.save(name, "section.pnl")
    c.page.set_viewport_size({"width": 1440, "height": 900})


def before(c: Ctx, name: str) -> None:
    if name.startswith("kind-") and name != "kind-metric.jpg" and not c.showcase_open:
        c.example("all-panels-showcase")
        c.page.evaluate("document.documentElement.setAttribute('data-theme', 'light')")
        c.page.wait_for_timeout(800)


def kind_shot(kind: str, panel_id_: str):
    def run(c: Ctx):
        if kind == "metric":                      # the tile in the workbench's narrow columns wraps its digits: draw the real panel at a tile's width
            sutra = (EXAMPLES / "metric.sutra.yaml").read_text()
            panel = {p["id"]: p for p in backend_preview(sutra, json.loads((EXAMPLES / "metric.json").read_text()), "desk-summary")["panels"]}["mtm"]
            card(c, "kind-metric.jpg", panel, width=330, height=400)
            return
        panel_id = panel_id_
        el = c.panel(panel_id)
        el.scroll_into_view_if_needed()
        c.page.wait_for_timeout(500)
        c.save(f"kind-{kind}.jpg", f'[data-preview] [data-panel="{panel_id}"]')
    return run


for _k, _p in KIND_PANEL.items():
    shot(f"kind-{_k}.jpg")(kind_shot(_k, _p))


def state_shots(kind: str, panel_id: str):
    def empty(c: Ctx):
        card(c, f"state-{kind}-empty.jpg", empty_view(kind))

    def error(c: Ctx):
        p = dict(empty_view(kind), error="rows: expected a list of objects, found the text 'Rates desk'")
        card(c, f"state-{kind}-error.jpg", p)

    def mask(c: Ctx):
        card(c, f"state-{kind}-masked.jpg", masked(full_view()[panel_id]))

    def phone(c: Ctx):
        card(c, f"state-{kind}-phone.jpg", full_view()[panel_id], width=390, height=800)

    def dark(c: Ctx):
        card(c, f"state-{kind}-dark.jpg", full_view()[panel_id], theme="terminal")
    out = [("empty", empty), ("error", error), ("phone", phone)]
    if kind != "line":
        out.append(("masked", mask))
    if kind in DARK:
        out.append(("dark", dark))
    return out


for _k, _p in FAMILIES.items():
    for _s, _fn in state_shots(_k, _p):
        shot(f"state-{_k}-{_s}.jpg")(_fn)


@shot("state-metric-no-access.jpg")
def no_access(c: Ctx):
    p = dict(full_view()["headline"], data=None, empty=True, denied="no access to curve")
    card(c, "state-metric-no-access.jpg", p)


# ---- the worked example in the workbench ------------------------------------------------------------------------------------------------------

@shot("metric-palette.jpg")
def palette(c: Ctx):
    c.example("metric")
    c.page.locator("[data-kind-filter]").fill("metric")
    c.page.wait_for_timeout(400)
    c.save("metric-palette.jpg", "[data-palette-box]")


@shot("metric-inspector.jpg")
def inspector(c: Ctx):
    c.example("metric")
    c.panel("mtm").locator(".pnl-h").click()
    c.page.wait_for_timeout(500)
    c.save("metric-inspector.jpg", ".wb-right")


@shot("metric-suggest.jpg")
def suggest(c: Ctx):
    c.example("metric")
    f = c.page.locator(".wb-field-row", has_text="var99Yesterday").first
    f.scroll_into_view_if_needed()
    s = c.box(f)
    t = c.box(c.panel("books"))
    c.page.mouse.move(s["x"] + 30, s["y"] + 5)
    c.page.mouse.down()
    c.page.mouse.move(s["x"] + 80, s["y"] + 30, steps=3)
    c.page.mouse.move(t["x"] + t["width"] / 2, t["y"] + t["height"] - 6, steps=10)
    c.page.mouse.up()
    c.page.locator(".wb-menu").wait_for()
    c.page.wait_for_timeout(300)
    c.save("metric-suggest.jpg")
    c.page.keyboard.press("Escape")
