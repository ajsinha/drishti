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
"""Reading the VaR and exposure screens' Drishti Elements in a browser test (ELEMENTS.md 4.8): a risk grid's rows through its model (a
grid draws only the rows in view, so the DOM is not the table) and a risk chart's drawing through its built option and figures (a
drishti-chart draws on a canvas, so there are no SVG nodes to count). The screens' tests assert the server's figures on what these give."""
from __future__ import annotations

#: the leaf rows of the risk grid under a selector: [{data, cells: [the text of each shown column]}], or null before it is drawn
GRID_ROWS_JS = """(sel) => {
  const el = document.querySelector(sel + ' drishti-grid') || document.querySelector(sel);
  if (!el || !el.grid) { return null; }
  const m = el.grid.model, cols = m.shownColumns(), out = [];
  for (let r = 0; r < m.rowCount(); r++) {
    const x = m.rowAt(r);
    if (!x || x.kind !== 'leaf') { continue; }
    out.push({ data: x.row, cells: cols.map((c, j) => m.cellText(r, j)) });
  }
  return out;
}"""

#: the built drawing of the chart under a selector: {kind, series: [{name, type, n}], x (the category axis' members), yScale (the value
#: axis leaves zero out), figures, badge, summary}, or null before it is drawn
CHART_JS = """(sel) => {
  const el = document.querySelector(sel + ' drishti-chart') || document.querySelector(sel);
  const c = el && el.chart, b = c && c.built;
  if (!b) { return null; }
  const o = b.option, xa = Array.isArray(o.xAxis) ? o.xAxis[0] : o.xAxis, ya = Array.isArray(o.yAxis) ? o.yAxis[0] : o.yAxis;
  return { kind: c.spec.kind, series: (o.series || []).map((s) => ({ name: s.name || '', type: s.type, n: (s.data || []).filter((d) => d && (d.value !== null && d.value !== undefined)).length })),
           x: (xa && xa.data) || null, yScale: !!(ya && ya.scale), figures: b.figures || null, badge: b.badge || null, summary: b.summary || '' };
}"""


def grid_rows(pg, sel: str) -> list[dict] | None:
    """The leaf rows of the risk grid under ``sel`` (each its data and its cells' texts, in the grid's order)."""
    return pg.evaluate(GRID_ROWS_JS, sel)


def grid_ready(sel: str) -> str:
    """A wait condition: the risk grid under ``sel`` is drawn."""
    return "(() => { const el = document.querySelector(%r); return !!(el && el.grid); })()" % (sel + " drishti-grid")


def chart(pg, sel: str) -> dict | None:
    """The built drawing of the risk chart under ``sel``."""
    return pg.evaluate(CHART_JS, sel)


def chart_ready(sel: str) -> str:
    """A wait condition: the chart under ``sel`` is drawn."""
    return "(() => { const el = document.querySelector(%r); return !!(el && el.chart && el.chart.built); })()" % (sel + " drishti-chart")
