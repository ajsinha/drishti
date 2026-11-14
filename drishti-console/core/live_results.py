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
"""Every "rows of records" result is a live result set (modules/trading/docs/guide.md section 7): a FORM's result and the search page's
are the server's live set of the search (``GET /api/v1/live/query``), static when the kind has no live binding or the business date is
not the live one, ticking otherwise. This module is the one place the console turns a set's page into what a result table draws: the
search's summary (columns, labels, matched, scanned, failed, noData, pivot), the rows with their cells, and ``live`` (the set's id, whether
it ticks, its total and sequence) that the grid's live mode follows through ``/api/v1/live/{set}/stream``.

A search on two business dates side by side (``vs=``) is a comparison, not a set of records: it stays the search's own answer."""
from __future__ import annotations

TITLE = "_title"               # where the server's set keeps a search row's title (LiveSetConfiguration.TITLE)


async def run(backend, q: str, ident, again: bool = True) -> dict:
    """The search ``q`` as a live result set, in the shape the result tables read (see :func:`as_search`). ``again``: the person pressed
    Search (a static set is read afresh; a live one is current already)."""
    return as_search(await backend.live_query(q, ident, again=again))


def as_search(page: dict) -> dict:
    """A live set's page as the search's summary plus ``rows`` ``[{ref, title, values}]`` (values at the summary's column paths) and
    ``live``: ``{set, live, total, seq}``."""
    data = dict(page.get("search") or {})
    kind = data.get("kind") or (page.get("set") or {}).get("kind")
    cols = data.get("columns") or []
    rows = []
    for r in page.get("rows") or []:
        doc = r.get("doc") or {}
        rows.append({"ref": {"kind": kind, "id": r.get("id")}, "title": doc.get(TITLE) or r.get("id"),
                     "values": {c: value_at(doc, c) for c in cols}})
    data["rows"] = rows
    data["live"] = {"set": (page.get("set") or {}).get("id"), "live": bool(page.get("live")), "total": page.get("total"), "seq": page.get("seq")}
    return data


def value_at(doc: dict, path: str):
    """The value at a column's path (``$.counterparty.name``) as a result cell holds it: a scalar as it is, a list or an object as its size
    (as the search gives it)."""
    node = doc
    for part in (path[2:] if path.startswith("$.") else path).split("."):
        if not isinstance(node, dict):
            return None
        node = node.get(part)
    return len(node) if isinstance(node, (list, dict)) else node


def shown(v) -> str:
    """A value as text: numbers with separators (up to six decimals), lists and objects compactly."""
    if v is None:
        return "—"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        text = f"{v:,.6f}".rstrip("0").rstrip(".") if isinstance(v, float) else f"{v:,}"
        return text.replace("-", "−")
    if isinstance(v, (list, dict)):
        return "[]" if v == [] else str(v)[:80]
    return str(v)


def delta(d) -> str:
    if d is None:
        return ""
    text = f"{d:+,.6f}".rstrip("0").rstrip(".")
    return text.replace("-", "−")


def number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def table_rows(data: dict | None, compared: bool = False) -> list[dict]:
    """The rows a result table draws: ``{ref, title, cells: [(text, is_number)], status}``; ``compared``: each value is ``{to, delta}``
    (the later value, and its change)."""
    out = []
    for r in (data or {}).get("rows", []):
        cells = []
        for c in data.get("columns", []):
            v = r["values"].get(c)
            if compared and isinstance(v, dict):
                to, d = v.get("to"), v.get("delta")
                cells.append((shown(to) + (f"  ({delta(d)})" if d not in (None, 0, 0.0) else ""), number(to)))
            else:
                cells.append((shown(v), number(v)))
        out.append({"ref": r["ref"], "title": r.get("title") or r["ref"]["id"], "cells": cells, "status": r.get("status")})
    return out


def titled(rows: list[dict]) -> bool:
    """Whether a Title column is worth showing: a title that only repeats the id is left out."""
    return any(r["title"] != r["ref"]["id"] for r in rows)


def grid_spec(data: dict | None, titled: bool = False) -> dict:
    """The declaration the records grid (drishti-grid's LiveSetGrid) draws a result with: the id first (the key, pinned), the title when it says
    more than the id, then each column at its field (a number column by its values), as the search's labels name them. Never editable here: a
    result is read, the record's page edits."""
    d = data or {}
    mnemonic = d.get("mnemonic") or d.get("kind") or "Id"
    cols = [{"field": "__id", "label": mnemonic, "type": "id"}]
    if titled:
        cols.append({"field": "_title", "label": "Title", "type": "text"})
    labels = d.get("labels") or {}
    for path in d.get("columns") or []:
        seen = [r["values"].get(path) for r in d.get("rows", [])[:50] if r["values"].get(path) is not None]
        numeric = bool(seen) and all(number(v) for v in seen)
        cols.append({"field": path[2:] if path.startswith("$.") else path, "label": labels.get(path, path), "type": "number" if numeric else "text"})
    live = d.get("live") or {}
    return {"id": live.get("set") or "result", "title": d.get("title") or mnemonic, "kind": d.get("kind") or "", "columns": cols, "groupBy": [], "subtotals": [],
            "links": [], "editable": False, "readOnly": "a search result: open the record's page to change it", "fields": [], "tray": False}


def compared_rows(data: dict | None, vs: str = "") -> tuple[list[dict], list[dict]]:
    """A search on two business dates as the records grid's static rows: the id, the title, the status (only on one date) and each column as
    the later value with its change; with the columns (the grid's, as :func:`grid_spec` names them)."""
    d = data or {}
    labels = d.get("labels") or {}
    cols = [{"field": "id", "label": d.get("mnemonic") or d.get("kind") or "Id", "type": "id"}, {"field": "status", "label": "Status", "type": "text"}]
    rows = []
    for r in d.get("rows", []):
        row = {"id": r["ref"]["id"], "status": ({"added": "only on the later date", "removed": "only on " + vs}.get(r.get("status") or "", r.get("status")) or "")}
        for i, c in enumerate(d.get("columns") or []):
            v = r["values"].get(c)
            if isinstance(v, dict):
                to, dl = v.get("to"), v.get("delta")
                row[f"c{i}"] = shown(to) + (f"  ({delta(dl)})" if dl not in (None, 0, 0.0) else "")
            else:
                row[f"c{i}"] = shown(v)
        rows.append(row)
    cols += [{"field": f"c{i}", "label": labels.get(c, c), "type": "text"} for i, c in enumerate(d.get("columns") or [])]
    return cols, rows


def kind_page(page: dict) -> dict:
    """A page of a kind's own set (a bare mnemonic) in the same shape: the declaration's columns, the set's total as matched and scanned,
    and ``paged`` (offset, shown, total) for the pager."""
    spec = page.get("set") or {}
    cols = [c for c in spec.get("columns") or [] if c.get("field")]
    paths = ["$." + c["field"] for c in cols]
    total = int(page.get("total") or 0)
    search = {"kind": spec.get("kind"), "mnemonic": spec.get("mnemonic") or spec.get("title"), "columns": paths,
              "labels": {p: c.get("label") or c["field"] for p, c in zip(paths, cols)}, "matched": total, "scanned": total,
              "partial": bool(page.get("capped")), "failed": [], "elapsedMs": page.get("elapsedMs", 0)}
    data = as_search({**page, "search": search})
    data["paged"] = {"offset": int(page.get("offset") or 0), "shown": len(data["rows"]), "total": total}
    return data


def pages(data: dict | None, path: str, params) -> dict | None:
    """The pager of a kind's set: the rows shown (``from``-``to`` of ``total``) and the addresses of the pages before and after."""
    if not data or not data.get("paged"):
        return None
    from urllib.parse import urlencode
    p = data["paged"]
    size = max(1, p["shown"])
    keep = [(k, v) for k, v in params.multi_items() if k != "offset"]

    def at(offset: int) -> str:
        return path + "?" + urlencode(keep + ([("offset", str(offset))] if offset else []))
    return {"from": p["offset"] + 1 if p["shown"] else 0, "to": p["offset"] + p["shown"], "total": p["total"],
            "prev": at(max(0, p["offset"] - size)) if p["offset"] > 0 else None,
            "next": at(p["offset"] + size) if p["offset"] + p["shown"] < p["total"] else None}
