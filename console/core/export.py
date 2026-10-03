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
import zipfile

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
    if "columns" in d and "rows" in d:                                   # table, ladder (a tree: every row, each followed by its children)
        rows = [[plain(c.get("text")) for c in r.get("cells", [])] for r in _walk(d.get("rows", []))]
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


def _walk(rows):
    """The rows of a tree table in order: each row, then the rows nested under it."""
    for r in rows:
        yield r
        yield from _walk(r.get("children") or [])


def _pivot(d: dict) -> tuple[list[str], list[list]]:
    cols = list(d.get("columns") or [])
    totals = d.get("totals")
    levels = list(d.get("levels") or [])                                 # nested groups: a column per level, a group row's deeper ones empty
    header = (levels or [d.get("by") or ""]) + cols + (["Total"] if totals else [])
    rows = [((list(r.get("path") or []) + [""] * len(levels))[:len(levels)] if levels else [r.get("label", "")])
            + list(r.get("values") or []) + ([plain((r.get("total") or {}).get("text"))] if totals else [])
            for r in d.get("rows") or []]
    if totals:
        rows.append(["Total"] + [""] * max(0, len(levels) - 1) + [plain(c.get("text")) for c in totals])
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


# ---- Excel: a grid as an .xlsx workbook (the Pivot tab's export), written with the standard library -----------------------
_XML_BAD = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f]")


def _xml(text) -> str:
    s = _XML_BAD.sub("", str(text))
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def _col(i: int) -> str:
    """0 -> A, 25 -> Z, 26 -> AA."""
    name = ""
    i += 1
    while i:
        i, r = divmod(i - 1, 26)
        name = chr(65 + r) + name
    return name


def _cell(ref: str, v, style: int = 0) -> str:
    s = f' s="{style}"' if style else ""
    if isinstance(v, bool):
        return f'<c r="{ref}" t="b"{s}><v>{int(v)}</v></c>'
    if isinstance(v, (int, float)) and v == v and v not in (float("inf"), float("-inf")):
        return f'<c r="{ref}"{s}><v>{v!r}</v></c>'
    if v is None or v == "":
        return ""
    return f'<c r="{ref}" t="inlineStr"{s}><is><t xml:space="preserve">{_xml(v)}</t></is></c>'


_XLSX_PARTS = {
    "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
    "</Types>",
    "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
    "</Relationships>",
    "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
    "</Relationships>",
    "xl/styles.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
    '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>'
    '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
    '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
    '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
    '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>'
    "</styleSheet>",
}


def to_xlsx(header: list, rows: list[list], sheet: str = "Pivot") -> bytes:
    """One sheet: a bold header row (frozen), then the rows; numbers stay numbers, text is inline (never a formula)."""
    lines = []
    if header:
        lines.append('<row r="1">' + "".join(_cell(f"{_col(i)}1", h, 1) for i, h in enumerate(header)) + "</row>")
    start = 2 if header else 1
    for n, r in enumerate(rows):
        lines.append(f'<row r="{start + n}">' + "".join(_cell(f"{_col(i)}{start + n}", v) for i, v in enumerate(r)) + "</row>")
    pane = ('<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>'
            '</sheetView></sheetViews>') if header else ""
    parts = dict(_XLSX_PARTS)
    parts["xl/workbook.xml"] = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                                '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                                'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                                f'<sheets><sheet name="{_xml(sheet[:31])}" sheetId="1" r:id="rId1"/></sheets></workbook>')
    parts["xl/worksheets/sheet1.xml"] = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                                         '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                                         f'{pane}<sheetData>{"".join(lines)}</sheetData></worksheet>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in parts.items():
            z.writestr(name, text)
    return buf.getvalue()


def grid(body: dict) -> tuple[str, list, list[list]]:
    """A grid the page posts (the Pivot tab's export): its name, header and rows, bounded, every value plain."""
    raw_header = body.get("header") if isinstance(body.get("header"), list) else []
    header = ["" if h is None else str(h)[:200] for h in raw_header[:2000]]
    rows = []
    for r in (body.get("rows") if isinstance(body.get("rows"), list) else [])[:200_000]:
        if isinstance(r, list):
            rows.append([v if isinstance(v, (int, float, bool)) or v is None else str(v)[:2000] for v in r[:2000]])
    return str(body.get("name") or "pivot")[:120], header, rows
