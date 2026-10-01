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


"""Export (W18): any table-like panel, a search's results or a comparison as CSV. Values are exported as shown,
except that numbers shown with separators, signs or a percent sign become plain numbers a spreadsheet reads as
numbers (-412580, 0.0425). UTF-8 with a byte-order mark, so spreadsheet programs pick the right encoding."""
from __future__ import annotations

import csv
import io
import re

# a number as a view shows it: sign (ASCII or Unicode minus), digits with thousands separators, decimals, maybe %
_SHOWN_NUMBER = re.compile(r"^([+\-−]?)(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?(%?)$")
EXPORTABLE = {"table", "ladder", "kv", "status", "tabs", "line", "area", "hbar", "surface", "links", "gauge",
              "waterfall", "histogram", "scatter", "candlestick", "graph", "timeline", "pivot"}


def plain(text):
    """'−412,580' -> -412580, '4.25%' -> 0.0425, anything else unchanged."""
    if not isinstance(text, str):
        return "" if text is None else text
    m = _SHOWN_NUMBER.match(text.strip())
    if not m:
        return text
    sign, whole, frac, pct = m.groups()
    value = float(("-" if sign in ("-", "−") else "") + whole.replace(",", "") + (frac or ""))
    if pct:
        return round(value / 100, 10)
    return int(value) if not frac else value


def panel_rows(panel: dict) -> tuple[list[str], list[list]]:
    """Header and rows for one panel of a view model; ([], []) when the panel has nothing to export."""
    d = panel.get("data") or {}
    kind = panel.get("kind")
    if not isinstance(d, dict) or panel.get("empty") or kind not in EXPORTABLE:
        return [], []
    if kind in _CHARTS:
        return _CHARTS[kind](d)
    if "columns" in d and "rows" in d:                                   # table, ladder
        rows = [[plain(c.get("text")) for c in r.get("cells", [])] for r in d.get("rows", [])]
        if d.get("total"):
            rows.append([plain(c.get("text")) for c in d["total"].get("cells", [])])
        return list(d["columns"]), rows
    if "fields" in d:                                                    # kv, status
        return ["Field", "Value"], [[c.get("label", ""), plain(c.get("text"))] for c in d["fields"]]
    if "tabs" in d:
        return ["Tab", "Field", "Value"], [[t.get("title", ""), c.get("label", ""), plain(c.get("text"))] for t in d["tabs"] for c in t.get("fields", [])]
    if "z" in d:                                                         # surface: a row per y, a column per x
        return [""] + list(d.get("x", [])), [[y] + list(row) for y, row in zip(d.get("y", []), d.get("z", []))]
    if "series" in d:                                                    # line, area
        series = d.get("series", [])
        return [""] + [s.get("label", "") for s in series], [[x] + [s["values"][i] if i < len(s.get("values", [])) else "" for s in series]
                                                              for i, x in enumerate(d.get("x", []))]
    if "bars" in d:
        return ["Label", "Value"], [[b.get("label", ""), b.get("value")] for b in d["bars"]]
    if "links" in d:
        return ["Link", "Entity", "Kind", "Summary"], [[i.get("label", ""), i.get("text", ""), (i.get("link") or {}).get("kind", ""), i.get("badge") or ""]
                                                       for i in d["links"]]
    if "value" in d and "max" in d:                                      # gauge
        return ["Label", "Value", "Limit"], [[d.get("label") or "", d.get("value"), d.get("max")]]
    return [], []


def _pivot(d: dict) -> tuple[list[str], list[list]]:
    cols = list(d.get("columns") or [])
    totals = d.get("totals")
    header = [d.get("by") or ""] + cols + (["Total"] if totals else [])
    rows = [[r.get("label", "")] + list(r.get("values") or []) + ([plain((r.get("total") or {}).get("text"))] if totals else [])
            for r in d.get("rows") or []]
    if totals:
        rows.append(["Total"] + [plain(c.get("text")) for c in totals])
    return header, rows


def _graph(d: dict) -> tuple[list[str], list[list]]:
    nodes = [["node", n.get("id", ""), "", n.get("label", ""), n.get("group") or ""] for n in d.get("nodes") or []]
    edges = [["edge", e.get("from", ""), e.get("to", ""), e.get("label") or "", ""] for e in d.get("edges") or []]
    return ["Type", "Id or from", "To", "Label", "Group"], nodes + edges


# the chart and aggregate kinds: their own columns, numbers as numbers (not as the chart shows them)
_CHARTS = {
    "waterfall": lambda d: (["Step", "Amount", "Total"], [[s.get("label", ""), s.get("value"), bool(s.get("total"))] for s in d.get("steps") or []]),
    "histogram": lambda d: (["From", "To", "Count"], [[b.get("from"), b.get("to"), b.get("count")] for b in d.get("bins") or []]),
    "scatter": lambda d: (["Label", "Group", d.get("xLabel") or "x", d.get("yLabel") or "y", "Size"],
                          [[q.get("label") or "", q.get("group") or "", q.get("x"), q.get("y"), q.get("size") if q.get("size") is not None else ""]
                           for q in d.get("points") or []]),
    "candlestick": lambda d: (["Date", "Open", "High", "Low", "Close", "Volume"],
                              [[c.get("x"), c.get("open"), c.get("high"), c.get("low"), c.get("close"),
                                c.get("volume") if c.get("volume") is not None else ""] for c in d.get("candles") or []]),
    "graph": _graph,
    "timeline": lambda d: (["Date", "Event", "Status", "Detail"],
                           [[e.get("date", ""), e.get("label", ""), e.get("status") or "", e.get("detail") or ""] for e in d.get("events") or []]),
    "pivot": _pivot,
}


def to_csv(header: list, rows: list[list]) -> bytes:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\r\n")
    if header:
        w.writerow(header)
    w.writerows(rows)
    return ("﻿" + buf.getvalue()).encode("utf-8")


def filename(*parts: str) -> str:
    """A safe download name from parts: letters, digits, dot, dash and underscore only."""
    return re.sub(r"[^A-Za-z0-9._-]+", "_", "-".join(p for p in parts if p)).strip("_") + ".csv"
