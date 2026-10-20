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

"""The history page's rows from the server's answer (HISTORY_QUERIES.md section 8): newest first, each cell with its shown text,
whether it changed from the day before and which way a number moved; the change log of DIFF; the headline numbers; the export
grid. Pure functions over the JSON: the server has already masked every value, the console never masks."""
from __future__ import annotations

from urllib.parse import quote

#: the range buttons of each shape: (label, query key, value)
DOCUMENT_RANGES = [("10", "days", "10"), ("30", "days", "30"), ("90", "days", "90"), ("YTD", "range", "YTD"), ("1Y", "range", "1Y")]
MARKET_RANGES = [("1M", "range", "1M"), ("3M", "range", "3M"), ("6M", "range", "6M"), ("YTD", "range", "YTD"), ("1Y", "range", "1Y")]


def _num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def shown(v) -> str:
    """A raw value as text, when the server sent no display text: numbers with separators, lists and objects briefly."""
    if v is None:
        return "—"
    if isinstance(v, bool):
        return "true" if v else "false"
    if _num(v):
        text = f"{v:,.6f}".rstrip("0").rstrip(".") if isinstance(v, float) else f"{v:,}"
        return text.replace("-", "−")
    if isinstance(v, (list, dict)):
        return f"{len(v)} items" if isinstance(v, list) else "{…}"
    return str(v)


def decimals(fmt: str | None, default: int = 2) -> int:
    """``price2`` → 2, ``price4`` → 4, ``pct2`` → 2; anything else the default."""
    digits = "".join(c for c in (fmt or "") if c.isdigit())
    return int(digits) if digits else default


def number(v, places: int = 2, signed: bool = False) -> str:
    if not _num(v):
        return "—"
    text = f"{v:+,.{places}f}" if signed else f"{v:,.{places}f}"
    return text.replace("-", "−")


def range_label(params: dict) -> str:
    """What the range means, for the header: ``30 business days``, ``3 months``, ``2026-09-01 → 2026-09-30``."""
    if params.get("days"):
        n = params["days"]
        return f"{n} business day{'' if n == '1' else 's'}"
    r = (params.get("range") or "").upper()
    if r in ("YTD", "MTD", "QTD"):
        return {"YTD": "year to date", "MTD": "month to date", "QTD": "quarter to date"}[r]
    if r[:-1].isdigit() and r[-1:] in "DWMY":
        n = int(r[:-1])
        unit = {"D": "calendar day", "W": "week", "M": "month", "Y": "year"}[r[-1]]
        return f"{n} {unit}{'' if n == 1 else 's'}"
    if params.get("from"):
        return f"{params['from']} → {params.get('to') or 'today'}"
    return ""


def _changes(row: dict, prev: dict | None, columns: list, parts: list) -> list[dict]:
    """The day's change log: the server's ``changes`` (with DIFF) or, when it sent none, the changed columns and parts against the
    row before, each ``{label, path, before, after, delta}``."""
    if row.get("changes"):
        return [{"label": c.get("label") or c.get("path"), "path": c.get("path", ""), "before": shown(c.get("before")),
                 "after": shown(c.get("after")), "delta": number(c.get("delta"), 4, True).rstrip("0").rstrip(".") if _num(c.get("delta")) else "",
                 "kind": c.get("kind") or "changed"} for c in row["changes"]]
    out = []
    changed = set(row.get("changed") or [])
    for c in columns:
        p = c["path"]
        if p in changed:
            before = (prev or {}).get("display", {}).get(p) if prev else None
            a, b = (prev or {}).get("values", {}).get(p) if prev else None, (row.get("values") or {}).get(p)
            delta = number(b - a, 4, True).rstrip("0").rstrip(".") if _num(a) and _num(b) else ""
            out.append({"label": c.get("label") or p, "path": p, "before": before if before is not None else "—",
                        "after": (row.get("display") or {}).get(p, shown(b)), "delta": delta, "kind": "changed"})
    for part in parts:
        p = part["path"]
        if "parts." + p in changed:
            a, b = (prev or {}).get("parts", {}).get(p) if prev else None, (row.get("parts") or {}).get(p)
            out.append({"label": part.get("label") or p, "path": p, "before": shown(a) if a is not None else "—", "after": shown(b),
                        "delta": f"{b - a:+d}".replace("-", "−") if isinstance(a, int) and isinstance(b, int) and a != b else "",
                        "kind": "changed"})
    return out


def build_page(data: dict, kind: str, id_: str, known_at: str | None = None) -> dict:
    """Everything the template draws: the visible columns (a column empty on every row is left out), the rows newest first with
    their cells, counts for the header, the headline numbers and the chart's series (oldest first, as the chart reads)."""
    rows_asc = data.get("rows") or []
    parts = data.get("parts") or []
    columns = [c for c in (data.get("columns") or [])
               if any((r.get("values") or {}).get(c["path"]) not in (None, "", []) for r in rows_asc) or c.get("masked")]
    market = data.get("shape") == "market"
    built = []
    for i, r in enumerate(rows_asc):
        prev = rows_asc[i - 1] if i else None
        changed = set(r.get("changed") or [])
        values, disp = r.get("values") or {}, r.get("display") or {}
        cells = []
        for c in columns:
            p = c["path"]
            v = values.get(p)
            pv = (prev or {}).get("values", {}).get(p) if prev else None
            moved = p in changed
            arrow = ""
            if moved and _num(v) and _num(pv) and v != pv and (not market or p == (data.get("summary") or {}).get("field")):
                arrow = "up" if v > pv else "down"
            before = (prev or {}).get("display", {}).get(p) if prev else None
            cells.append({"path": p, "text": disp.get(p, shown(v)), "num": c.get("type") == "number", "changed": moved, "arrow": arrow,
                          "masked": bool(c.get("masked")), "neg": _num(v) and v < 0 and market and p != (data.get("summary") or {}).get("field"),
                          "pos": _num(v) and v > 0 and market and c.get("format", "") and str(c.get("format")).startswith("pct"),
                          "title": (f"changed from {before}" if before is not None else "changed from the day before the range") if moved else ""})
        pcells = [{"path": p["path"], "count": (r.get("parts") or {}).get(p["path"]), "changed": "parts." + p["path"] in changed}
                  for p in parts]
        gen = r.get("generation")
        # via=history: the day opens with a banner that says it came from the history (not "as it was shared") and a way back to it (QA1-16)
        href = f"/v/{quote(kind)}/{quote(id_)}?asOf={r['date']}" + (f"&gen={gen}" if gen is not None else "") \
            + (f"&knownAt={quote(known_at)}" if known_at else "") + "&via=history"
        built.append({"date": r["date"], "data_date": r.get("dataDate") or r["date"], "carried": bool(r.get("carried")),
                      "deleted": bool(r.get("deleted")), "cells": cells, "parts": pcells, "changed": bool(changed),
                      "versions": r.get("versions"), "restated": bool(r.get("restated")), "generation": gen,
                      "source": r.get("source") or "", "href": href, "changes": _changes(r, prev, columns, parts),
                      "prev_date": prev["date"] if prev else (data.get("window") or {}).get("baseline")})
    built.reverse()                                     # newest first on screen
    window = data.get("window") or {}
    summary = data.get("summary") or {}
    chart = data.get("chart") or []
    places = decimals(chart[0].get("format") if chart else None)
    headline = None
    if market and summary.get("field"):
        chg = summary.get("change")
        headline = {
            "field": summary["field"], "label": next((c.get("label") for c in chart if c.get("path") == summary["field"]), summary["field"]),
            "last": number(summary.get("last"), places), "change": number(chg, places, True),
            "pct": (number(summary.get("changePct"), 2, True) + "%") if _num(summary.get("changePct")) else "—",
            "dir": "pos" if _num(chg) and chg > 0 else "neg" if _num(chg) and chg < 0 else "",
            "high": number(summary.get("high"), places), "high_date": summary.get("highDate") or "",
            "low": number(summary.get("low"), places), "low_date": summary.get("lowDate") or "",
            "range": number(summary.get("range"), places), "average": number(summary.get("average"), places)}
    series = [{"path": c["path"], "label": c.get("label") or c["path"], "places": decimals(c.get("format")),
               "points": [[r["date"], (r.get("values") or {}).get(c["path"]) if _num((r.get("values") or {}).get(c["path"])) else None]
                          for r in rows_asc]} for c in chart]
    restated = sum(1 for r in rows_asc if r.get("restated"))
    max_versions = max([r.get("versions") or 1 for r in rows_asc] or [1])
    sources = sorted({r.get("source") for r in rows_asc if r.get("source")})
    return {"data": data, "market": market, "columns": columns, "parts": parts, "rows": built, "window": window,
            "count": len(rows_asc), "changed_days": data.get("changedDays", sum(1 for r in rows_asc if r.get("changed"))),
            "restated": restated, "max_versions": max(max_versions, 2), "carried": sum(1 for r in rows_asc if r.get("carried")),
            "masked": any(c.get("masked") for c in columns), "sources": sources, "headline": headline, "series": series,
            "ranges": MARKET_RANGES if market else DOCUMENT_RANGES, "missing": window.get("missing") or [],
            "problems": data.get("problems") or [], "partial": bool(data.get("partial")), "truncated": bool(data.get("truncated")),
            "diff": any(r.get("changes") for r in rows_asc)}


def export_rows(data: dict, only_changed: bool = False) -> tuple[list, list[list]]:
    """The export grid, newest first: Date, each column (raw numbers stay numbers, everything else its shown text), each part's
    count, and the paths that changed that day."""
    columns = data.get("columns") or []
    parts = data.get("parts") or []
    header = ["Date"] + [c.get("label") or c["path"] for c in columns] + [p.get("label") or p["path"] for p in parts] + ["Changed"]
    rows = []
    for r in reversed(data.get("rows") or []):
        if only_changed and not r.get("changed"):
            continue
        values, disp = r.get("values") or {}, r.get("display") or {}
        line = [r["date"]]
        for c in columns:
            v = values.get(c["path"])
            line.append(v if _num(v) and not c.get("masked") else disp.get(c["path"], "" if v is None else shown(v)))
        line += [(r.get("parts") or {}).get(p["path"]) for p in parts]
        line.append(" ".join(r.get("changed") or []))
        rows.append(line)
    return header, rows
