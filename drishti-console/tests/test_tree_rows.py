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

"""Expandable rows (RACHANA_REFERENCE.md, Tree rows): a table or ladder with ``children:`` and a pivot by a list of fields. The markup
the server sends (toggles with aria-expanded, rows hidden below ``expand``), the CSV export of a tree, and the behaviour in a
real browser: the table drawn by drishti-tree (the tree grid on drishti-grid's tree data: a row toggles, the filter keeps the
ancestors of a match, every cell the server's text, tone and link; the org chart of the same rows, a chain opening as it; the
server's table kept hidden), the nested pivot by drishti-grid. The browser tests run in Playwright's Chromium
(the grid draws on animation frames) and are skipped cleanly without it."""
import json
import re
from pathlib import Path

import pytest

from core.export import panel_rows

STATIC = Path(__file__).resolve().parent.parent / "web" / "static" / "js"


def _c(text):
    return {"text": text}


def _row(name, size, *children):
    return {"cells": [_c(name), _c(size)], "highlight": False, "children": list(children)}


ORG = {"columns": ["Name", "Size"], "numeric": [False, True], "search": True, "expand": 2,
       "rows": [_row("Group", "999", _row("Desk A", "999", _row("Book 1", "30"), _row("Book 2", "20")), _row("Desk B", "999")),
                _row("Loose", "5")],
       "total": {"cells": [_c("Total"), _c("1,054")], "highlight": False}}

CHAIN = {"columns": ["Entity", "Relationship"], "numeric": [False, False], "search": False, "expand": 99,
         "rows": [{"cells": [{"text": "Top Co", "link": {"kind": "org", "id": "TOP"}}, _c("Ultimate parent")], "highlight": False, "children": [
             {"cells": [_c("Middle Co"), {"text": "Direct parent", "tone": "neg"}], "highlight": False, "children": [
                 {"cells": [_c("This Co"), _c("")], "highlight": True, "children": []}]}]}]}


def _pivot_row(path, values, total, group):
    return {"label": path[-1], "path": path, "group": group, "cells": [_c(v) for v in values], "values": [float(v) for v in values],
            "total": _c(total)}


NEST = {"by": "desk › book", "levels": ["desk", "book"], "across": "ccy", "expand": 1, "columns": ["USD", "EUR"], "agg": "sum",
        "heat": False, "more": 0,
        "rows": [_pivot_row(["Rates"], ["161", "-40"], "121", True), _pivot_row(["Rates", "B1"], ["101", "-40"], "61", False),
                 _pivot_row(["Rates", "B2"], ["60", "0"], "60", False), _pivot_row(["FX"], ["7", "0"], "7", True),
                 _pivot_row(["FX", "B9"], ["7", "0"], "7", False)],
        "totals": [_c("168"), _c("-40"), _c("128")]}


def _render(client, kind, data):
    tpl = client.app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    return tpl.render(p={"id": "x", "kind": kind, "title": "X", "area": "main", "empty": False, "data": data})


def test_a_table_with_children_sends_toggles_and_hides_the_rows_below_expand(client):
    html = _render(client, "table", ORG)
    assert "tbl-tree" in html and "data-plain" in html
    rows = re.findall(r'<tr class="tr-node[^>]*>', html)
    assert [("hidden" in r, re.search(r'data-depth="(\d)"', r).group(1)) for r in rows] == [
        (False, "0"), (False, "1"), (True, "2"), (True, "2"), (False, "1"), (False, "0")]
    toggles = re.findall(r'<button type="button" class="tr-tog" aria-expanded="(\w+)" aria-label="([^"]+)"', html)
    assert toggles == [("true", "Collapse Group"), ("false", "Expand Desk A")]     # Desk B and Loose have no children
    assert 'class="total"' in html


def test_a_table_without_children_is_drawn_as_before(client):
    flat = dict(ORG, rows=[{"cells": [_c("A"), _c("1")], "highlight": False}])
    del flat["expand"]
    html = _render(client, "table", flat)
    assert "tbl-tree" not in html and "tr-tog" not in html and "<td class=\"mono\">A</td>" in html


def test_a_pivot_by_a_list_sends_its_data_for_the_grid_and_a_plain_table_for_pages_without_it(client):
    html = _render(client, "pivot", NEST)
    assert 'class="pv-tree-host"' in html and "data-pv-tree=" in html
    data = json.loads(re.search(r'data-pv-tree="([^"]*)"', html).group(1).replace("&#34;", '"').replace("&amp;", "&"))
    assert data["levels"] == ["desk", "book"] and data["expand"] == 1 and data["rows"][1]["path"] == ["Rates", "B1"]
    assert "<b>Rates</b>" in html and "B9" in html                                # the plain table lists every row
    single = _render(client, "pivot", {k: v for k, v in NEST.items() if k not in ("levels", "across", "expand")}
                     | {"by": "desk", "rows": [{"label": "A", "cells": [_c("1"), _c("2")], "values": [1, 2], "total": _c("3")}]})
    assert "pv-tree-host" not in single and '<th scope="row">A</th>' in single


def test_the_csv_of_a_tree_lists_every_row_and_of_a_nested_pivot_a_column_per_level():
    header, rows = panel_rows({"kind": "table", "data": ORG})
    assert header == ["Name", "Size"] and [r[0] for r in rows] == ["Group", "Desk A", "Book 1", "Book 2", "Desk B", "Loose", "Total"]
    header, rows = panel_rows({"kind": "pivot", "data": NEST})
    assert header == ["desk", "book", "USD", "EUR", "Total"]
    assert rows[0][:2] == ["Rates", ""] and rows[1][:2] == ["Rates", "B1"] and rows[-1][0] == "Total"


# ---- in a browser --------------------------------------------------------------------------------------------------

ORIGIN = "http://tree.test"


def _drive(browsers, body_html, steps, ready=".dt-tp"):
    """Opens a page holding the panel, drishti-core, drishti-inputs (drishti-grid and drishti-tree need it), drishti-grid and drishti-tree and the tree rows (Playwright: the grid draws on
    animation frames, which a real page runs), then runs ``steps(page)``; no error may reach the page."""
    elements = STATIC.parent / "elements"
    files = {"/core.js": elements / "drishti-core" / "drishti_core.js", "/inputs.js": elements / "drishti-inputs" / "drishti_inputs.js",
             "/grid.js": elements / "drishti-grid" / "drishti_grid.js",
             "/tree.js": elements / "drishti-tree" / "drishti_tree.js", "/rows.js": STATIC / "drishti_tree-rows.js",
             "/grid.css": elements / "drishti-grid" / "drishti_grid.css", "/tree.css": elements / "drishti-tree" / "drishti_tree.css"}
    html = ('<!doctype html><html><head><link rel="stylesheet" href="/grid.css"><link rel="stylesheet" href="/tree.css"></head><body>'
            + body_html + "".join(f'<script src="{k}"></script>' for k in files if k.endswith(".js")) + "</body></html>")

    async def route(r):
        path = r.request.url[len(ORIGIN):].split("?")[0]
        if path == "/page.html":
            return await r.fulfill(status=200, body=html, headers={"content-type": "text/html; charset=utf-8"})
        if path in files:
            return await r.fulfill(status=200, body=files[path].read_bytes(), headers={"content-type": "text/css" if path.endswith(".css") else "application/javascript"})
        return await r.fulfill(status=404, body="")

    async def go():
        async with browsers.page(1200, 900, route=route) as pg:
            await pg.goto(ORIGIN + "/page.html")
            await pg.wait_for_selector(ready)
            await steps(pg)
            assert pg.errors == [], pg.errors
    browsers.run(go(), timeout=90)


SHOWN = """() => Array.from(document.querySelectorAll('.dt-tp .dg-row')).sort((a, b) => +a.getAttribute('aria-rowindex') - +b.getAttribute('aria-rowindex'))
  .map((r) => r.querySelector('.dg-cell[aria-colindex="1"]').textContent.replace(/[+−]/g, '').trim())"""


async def _until(pg, n):
    await pg.wait_for_function(f"() => ({SHOWN})().length === {n}", timeout=5000)
    return await pg.evaluate(SHOWN)


async def _twisty(pg, name):
    await pg.locator(".dt-tp .dg-row", has_text=name).first.locator(".dg-tw:not(.dg-tw-none)").click()


def test_in_a_browser_drishti_tree_draws_the_table_its_rows_toggle_and_the_filter_keeps_their_ancestors(client, browsers):
    async def steps(pg):
        assert await _until(pg, 4) == ["Group", "Desk A", "Desk B", "Loose"]                  # expand: 2, as the server sent it
        src = await pg.evaluate("() => { const t = document.querySelector('table.tbl-tree'); return [t.closest('.tbl-wrap').hidden, t.hasAttribute('data-tree-ready')]; }")
        assert src == [True, True]                                                            # the server's table stays, hidden (print, no scripts)
        assert await pg.get_attribute(".dt-tp", "data-mode") == "grid"                       # two top rows: the grid
        sizes = await pg.evaluate("() => Array.from(document.querySelectorAll('.dt-tp .dg-row')).sort((a, b) => +a.getAttribute('aria-rowindex') - +b.getAttribute('aria-rowindex')).map((r) => r.querySelector('.dg-cell[aria-colindex=\"2\"]').textContent.trim())")
        assert sizes == ["999", "999", "999", "5"]                                            # the server's text
        foot = await pg.text_content(".dt-tp-foot")
        assert "Total" in foot and "1,054" in foot
        await _twisty(pg, "Desk A")
        assert await _until(pg, 6) == ["Group", "Desk A", "Book 1", "Book 2", "Desk B", "Loose"]
        await _twisty(pg, "Group")
        assert await _until(pg, 2) == ["Group", "Loose"]
        await _twisty(pg, "Group")
        await pg.fill(".dt-tp-q", "book 2")
        assert await _until(pg, 3) == ["Group", "Desk A", "Book 2"]                            # the match and its ancestors
        assert (await pg.text_content(".dt-tp-bar .dt-lbl")).startswith("1 of 6")
        await pg.fill(".dt-tp-q", "")
        assert await _until(pg, 6) == ["Group", "Desk A", "Book 1", "Book 2", "Desk B", "Loose"]
        await pg.click(".dt-tp [data-mode='org']")
        assert await pg.get_attribute(".dt-tp", "data-mode") == "org"
        org = await pg.evaluate("() => Array.from(document.querySelectorAll('.dt-tp svg .dt-box')).map((b) => b.getAttribute('aria-label'))")
        assert len(org) == 6 and any(x.startswith("Book 2") for x in org)                     # the same rows, open as in the grid
        await pg.click(".dt-tp [data-mode='grid']")
        assert await _until(pg, 6) == ["Group", "Desk A", "Book 1", "Book 2", "Desk B", "Loose"]
    _drive(browsers, _render(client, "table", ORG), steps)


def test_in_a_browser_a_chain_opens_as_the_org_chart_with_the_servers_links_and_tones(client, browsers):
    async def steps(pg):
        await pg.wait_for_selector(".dt-tp svg .dt-box")
        assert await pg.get_attribute(".dt-tp", "data-mode") == "org"                        # one chain: the org chart
        assert await pg.locator(".dt-tp-q").count() == 0                                      # search: false, no filter box
        boxes = sorted(await pg.evaluate("() => Array.from(document.querySelectorAll('.dt-tp svg .dt-box')).map((b) => b.getAttribute('aria-label'))"))
        assert boxes[0].startswith("Middle Co") and "Direct parent" in boxes[0] and boxes[1].startswith("This Co") and boxes[2].startswith("Top Co")
        await pg.click(".dt-tp [data-mode='grid']")
        assert await _until(pg, 3) == ["Top Co", "Middle Co", "This Co"]
        assert await pg.get_attribute(".dt-tp .dg-row a.lnk", "href") == "/v/org/TOP"         # the server's link, tone and highlighted row
        assert await pg.locator(".dt-tp .dg-cell.t-neg").count() == 1
        assert await pg.locator(".dt-tp .dg-row.dt-tp-hl").count() == 1
    _drive(browsers, _render(client, "table", CHAIN), steps)


def test_in_a_browser_a_pivot_group_toggles_with_the_grid(client, browsers):
    """The nested pivot panel is drawn by drishti-grid (the Pivot screen's grid) from the engine's figures, shown as the server's text."""
    heads = "() => Array.from(document.querySelectorAll('.pv-tree-host .dg-row [role=rowheader] .dg-text')).map((t) => t.textContent.trim())"

    async def until(pg, ok):
        await pg.wait_for_function(f"() => {{ const h = ({heads})(); return {ok}; }}", timeout=5000)
        return await pg.evaluate(heads)

    async def steps(pg):
        assert await pg.locator(".pv-tree-host .dg[data-chrome='none'] [role=treegrid], .pv-tree-host .dg[data-chrome='none'] [role=grid]").count() >= 1
        assert await until(pg, "h.length === 3") == ["Rates", "FX", "Total"]                # expand: 1, every group closed
        togs = "() => Array.from(document.querySelectorAll('.pv-tree-host .dg-row[aria-expanded]')).map((r) => r.getAttribute('aria-expanded') + ':' + r.querySelector('[role=rowheader] .dg-text').textContent.trim())"
        assert await pg.evaluate(togs) == ["false:Rates", "false:FX"]
        await pg.locator(".pv-tree-host .dg-row[aria-expanded] .dg-tw").first.click()
        opened = await until(pg, "h.indexOf('B1') >= 0")
        assert "B2" in opened and (await pg.evaluate(togs))[0] == "true:Rates"
        await pg.locator(".pv-tree-host .dg-row[aria-expanded] .dg-tw").first.click()
        assert await until(pg, "h.length === 3") == ["Rates", "FX", "Total"]
        cells = await pg.evaluate("() => Array.from(document.querySelectorAll('.pv-tree-host .dg-row')[0].querySelectorAll('.dg-cell[data-cell] .dg-text')).map((t) => t.textContent)")
        assert cells[:2] == ["161", "-40"]                                                  # a group row shows its subtotals, the server's text
    _drive(browsers, _render(client, "pivot", NEST), steps, ready=".pv-tree-host .dg-row")
