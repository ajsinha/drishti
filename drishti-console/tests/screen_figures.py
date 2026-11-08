#
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
#
"""The figures a screen shows, read the same way whether its tables are hand-built ``<table>`` elements (before Drishti Elements) or
``<drishti-grid>`` (after, ELEMENTS.md section 12), so a screen moved onto the libraries can be compared with itself before the move:
every table's rows as {heading: text}, every tile's label and value, and every chart's series (a hand-drawn SVG's bars, or drishti-chart's
drawing). ``figures(page)`` reads them; ``compare(old, new)`` lists what differs (a heading only one side has is left out: a moved table
may name its blank columns). The VaR family's comparison (tests/test_var_family_figures_browser.py) is built on it."""
from __future__ import annotations

import re

#: reads every table and grid under the page's main content: [{host, headings, rows: [[text]]}]
_READ = r"""() => {
  const out = [], clean = (t) => String(t || '').replace(/\s+/g, ' ').trim();
  const hostOf = (el) => { let x = el; while (x && x !== document.body) { for (const a of x.getAttributeNames()) { if (/^data-(vx|vb|pg|ex|bt|sw|sm|rg|mr|rl|frtb|simm|vs|vr|smat|fr|sim|xp)[a-z-]*$/.test(a)) return a; } x = x.parentElement; } return el.id ? '#' + el.id : el.className || 'table'; };
  document.querySelectorAll('table').forEach((t) => {
    if (t.closest('[hidden]')) return;
    const heads = [...t.querySelectorAll('thead th')].map((th) => clean(th.textContent));
    const rows = [...t.querySelectorAll('tbody tr')].filter((tr) => !tr.classList.contains('vbs-spacer')).map((tr) => [...tr.children].map((td) => clean(td.textContent)));
    out.push({ host: hostOf(t), kind: 'table', headings: heads, rows: rows });
  });
  document.querySelectorAll('.vx-tree').forEach((t) => {                       // the VaR explorer's tree before Elements: rows of spans
    const hdr = t.querySelector('.vx-row.hdr');
    if (!hdr) return;
    out.push({ host: 'data-vx-tree', kind: 'tree', headings: [...hdr.children].map((x) => clean(x.textContent)),
      rows: [...t.querySelectorAll('.vx-row[data-scope]')].map((r) => [...r.children].map((x) => clean(x.textContent))) });
  });
  const grids = [...document.querySelectorAll('drishti-grid')].map((el) => [el, el.grid]).concat(   // a drishti-tree's tree grid too (the VaR tree)
    [...document.querySelectorAll('drishti-tree')].map((el) => [el, el.tree && el.tree.grid && el.tree.grid.grid]));
  grids.forEach(([el, grid]) => {
    if (!grid || el.closest('[hidden]')) return;
    const m = grid.model, cols = m.shownColumns(), rows = [];
    for (let r = 0; r < m.rowCount(); r++) { rows.push(cols.map((c, j) => clean(m.cellText(r, j)))); }
    out.push({ host: hostOf(el), kind: 'grid', headings: cols.map((c) => clean(c.header)), rows: rows });
  });
  const tiles = [...document.querySelectorAll('.vx-tile, [data-tile], .xp-tile, .tile')].filter((t) => !t.closest('[hidden]'))
    .map((t) => clean(t.textContent));
  return { tables: out, tiles: tiles };
}"""

_NUMBER = re.compile(r"[−-]?\d[\d,]*(?:\.\d+)?\s*(?:k|m|bn|tn|%|bp)?")
#: a cell that is a figure: a number with its sign, unit or share, a dash for none, a date, or a figure with a note in brackets
_FIGURE = re.compile(r"(?:[+−-]?\d[\d,]*(?:\.\d+)?(?:e[+-]?\d+)?\s*(?:k|m|bn|tn|%|bp)?(?:\s*\([^)]*\))?|—|\d{4}-\d\d-\d\d)")


def figures(page) -> dict:
    """Every table's and grid's rows (as {heading: text}), and the tiles' texts, of the page as it is drawn."""
    raw = page.evaluate(_READ)
    tables = {}
    for t in raw["tables"]:
        heads = t["headings"] or [f"col{i}" for i in range(max((len(r) for r in t["rows"]), default=0))]
        rows = []
        for r in t["rows"]:
            if len(r) != len(heads):              # a note row across the columns (computing…, a total spanning cells): kept as text
                rows.append({"_": " ".join(x for x in r if x)})
                continue
            rows.append({h or f"col{i}": v for i, (h, v) in enumerate(zip(heads, r))})
        tables.setdefault(t["host"], []).extend(rows)
    return {"tables": tables, "tiles": raw["tiles"]}


def _key(row: dict, common: list[str]) -> tuple:
    return tuple(row.get(h, "") or "—" for h in common)          # no figure: a blank cell or a dash (drishti-risk's riskMoney draws "—")


def compare(old: dict, new: dict) -> list[str]:
    """What differs between two readings of one screen: a table missing, a row whose figures changed, a tile changed. Rows are matched as
    sets over the headings both sides have (a grid may order or name its blank columns differently)."""
    out = []
    for host, orows in old["tables"].items():
        nrows = new["tables"].get(host)
        if nrows is None:
            out.append(f"{host}: gone")
            continue
        oh = {h for r in orows for h in r if h != "_"}
        nh = {h for r in nrows for h in r if h != "_"}
        # the figures: headings both sides have whose every old value is a number (a name cell may carry its chips and buttons)
        common = sorted(h for h in (oh & nh) - {""}
                        if all(_FIGURE.fullmatch(r.get(h, "") or "—") for r in orows if "_" not in r))
        if not common:
            out.append(f"{host}: no heading in common ({sorted(oh)} / {sorted(nh)})")
            continue
        a = sorted(_key(r, common) for r in orows if "_" not in r)
        b = sorted(_key(r, common) for r in nrows if "_" not in r)
        if a != b:
            gone = [x for x in a if x not in b][:3]
            came = [x for x in b if x not in a][:3]
            out.append(f"{host}: {len(a)} rows before, {len(b)} after over {common}; before only {gone}; after only {came}")
    if [_NUMBER.findall(t) for t in old["tiles"]] != [_NUMBER.findall(t) for t in new["tiles"]]:
        out.append(f"tiles: {old['tiles']} / {new['tiles']}")
    return out
