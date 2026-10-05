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

"""Expandable rows (PANELS.md, Tree rows): a table or ladder with ``children:`` and a pivot by a list of fields. The markup
the server sends (toggles with aria-expanded, rows hidden below ``expand``), the CSV export of a tree, and the behaviour in a
real browser: a group toggles, the filter keeps the ancestors of a match. The browser test drives headless Chromium directly
(the one Playwright installs, or CHROME_BIN) and is skipped cleanly without one."""
import glob
import json
import os
import re
import shutil
import subprocess
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

def _chrome():
    candidates = [os.environ.get("CHROME_BIN", "")] + sorted(glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome")))
    candidates += [shutil.which(n) or "" for n in ("chromium", "chromium-browser", "google-chrome")]
    return next((c for c in candidates if c and os.access(c, os.X_OK)), None)


def _run(chrome, tmp_path, body, script):
    page = tmp_path / "page.html"
    scripts = "".join(f'<script src="{(STATIC / js).as_uri()}"></script>' for js in ("pivot-engine.js", "pivot-grid.js", "tree-rows.js"))
    page.write_text(f'<!doctype html><html><body>{body}{scripts}<script>var out = {{}};{script}'
                    'document.body.insertAdjacentHTML("beforeend", "<pre id=result>" + JSON.stringify(out) + "</pre>");</script></body></html>')
    done = subprocess.run([chrome, "--headless", "--no-sandbox", "--disable-gpu", "--allow-file-access-from-files",
                           "--virtual-time-budget=3000", "--dump-dom", page.as_uri()], capture_output=True, text=True, timeout=60)
    m = re.search(r'<pre id="result">(.*?)</pre>', done.stdout, re.S)
    assert m, done.stderr[-500:]
    return json.loads(m.group(1).replace("&quot;", '"').replace("&amp;", "&"))


def test_in_a_browser_a_table_row_expands_collapses_and_the_filter_keeps_its_ancestors(client, tmp_path):
    chrome = _chrome()
    if not chrome:
        pytest.skip("needs Chromium (playwright install chromium, or CHROME_BIN)")
    got = _run(chrome, tmp_path, _render(client, "table", ORG), """
      var t = document.querySelector('table.tbl-tree');
      function shown() { return Array.from(t.tBodies[0].rows).filter(function (r) { return !r.hidden; }).map(function (r) { return r.cells[0].textContent.replace(/[▸▾]/g, '').trim(); }); }
      function btn(name) { return Array.from(t.querySelectorAll('.tr-tog')).filter(function (b) { return b.getAttribute('aria-label').indexOf(name) >= 0; })[0]; }
      out.start = shown();
      btn('Desk A').click();
      out.opened = shown(); out.openedState = btn('Desk A').getAttribute('aria-expanded');
      btn('Group').click();
      out.closed = shown(); out.closedState = btn('Group').getAttribute('aria-expanded');
      btn('Group').click();
      var q = document.querySelector('input.tr-q'); q.value = 'book 2'; q.dispatchEvent(new Event('input'));
      out.filtered = shown();
      q.value = ''; q.dispatchEvent(new Event('input'));
      out.cleared = shown();
    """)
    assert got["start"] == ["Group", "Desk A", "Desk B", "Loose"]
    assert got["opened"] == ["Group", "Desk A", "Book 1", "Book 2", "Desk B", "Loose"] and got["openedState"] == "true"
    assert got["closed"] == ["Group", "Loose"] and got["closedState"] == "false"
    assert got["filtered"] == ["Group", "Desk A", "Book 2"]                        # the match and its ancestors
    assert got["cleared"] == ["Group", "Desk A", "Book 1", "Book 2", "Desk B", "Loose"]


def test_in_a_browser_a_pivot_group_toggles_with_the_pivot_grid(client, tmp_path):
    chrome = _chrome()
    if not chrome:
        pytest.skip("needs Chromium (playwright install chromium, or CHROME_BIN)")
    got = _run(chrome, tmp_path, _render(client, "pivot", NEST), """
      var host = document.querySelector('.pv-tree-host');
      function heads() { return Array.from(host.querySelectorAll('tbody th.pv-rh')).map(function (t) { return t.textContent.replace(/[▸▾]/g, '').trim(); }).filter(Boolean); }
      function togs() { return Array.from(host.querySelectorAll('.pv-tog')).map(function (b) { return b.getAttribute('aria-expanded') + ':' + b.getAttribute('aria-label'); }); }
      out.grid = !!host.querySelector('table.pv-grid'); out.start = heads(); out.startTogs = togs();
      host.querySelector('.pv-tog').click();
      out.opened = heads(); out.openedTogs = togs();
      host.querySelector('.pv-tog').click();
      out.closed = heads();
      out.cells = Array.from(host.querySelectorAll('tbody tr:first-child td.pv-c')).map(function (t) { return t.textContent; });
    """)
    assert got["grid"] is True
    assert got["start"] == ["Rates", "FX", "Total"]                                # expand: 1, every group closed
    assert got["startTogs"] == ["false:Expand Rates", "false:Expand FX"]
    assert "B1" in got["opened"] and "B2" in got["opened"] and got["openedTogs"][0].startswith("true:Collapse Rates")
    assert got["closed"] == got["start"]
    assert got["cells"][:2] == ["161", "-40"]                                      # a group row shows its subtotals
