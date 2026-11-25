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
"""Every "rows of records" result is a query on a live table (docs/guides/USER_GUIDE.md, Live results and blotters: The engine: snapshot, deltas, scale, retention): a FORM's result and the search
page's are the server's search opened on a table of the kind (``GET /api/v1/rdp/searches``: the table, the query with its filter tree, sort
and limit, the first page, the search's summary), following the kind's changes on the live business date and a static snapshot of any
other day. This module is the one place the console turns such a page into what a result table draws: the search's summary (columns,
labels, matched, noData, pivot), the rows with their cells, and ``live`` (the table, its query, whether it ticks, its total) that the grid's
live mode follows through the Data Protocol (``/api/rdp``, js/drishti_records.js).

A search on two business dates side by side (``vs=``) is a comparison, not a set of records: it stays the search's own answer."""
from __future__ import annotations

KEY = "id"                     # the key column of a search's table (SearchFilter.KEY)
TITLE = "_title"               # where a search's table keeps each row's search title (KindSnapshot.TITLE)


async def run(backend, q: str, ident) -> dict:
    """The search ``q`` as a query on a live table, in the shape the result tables read (see :func:`as_search`)."""
    return as_search(await backend.live_query(q, ident))


def page_rows(page: dict) -> list[dict]:
    """A Data Protocol page's rows as maps by column name (``{columns: [{name}], values: [[...]]}``)."""
    cols = [c.get("name") if isinstance(c, dict) else c for c in page.get("columns") or []]
    return [dict(zip(cols, v)) for v in page.get("values") or []]


def as_search(answer: dict) -> dict:
    """A search's table and first page as the search's summary plus ``rows`` ``[{ref, title, values}]`` (values at the summary's column paths)
    and ``live``: ``{table, query, key, live, total}``."""
    data = dict(answer.get("search") or {})
    kind = data.get("kind")
    cols = data.get("columns") or []
    if "page" not in answer and "set" in answer:        # a module's kernel page (Asker.live): rows {id, doc}, the table in set
        s = answer.get("set") or {}
        answer = {**answer, "table": s.get("table") or s.get("id"), "query": s.get("query") or {},
                  "page": {"columns": [{"name": k} for k in ((answer.get("rows") or [{}])[0].get("doc") or {})],
                           "values": [list((r.get("doc") or {}).values()) for r in answer.get("rows") or []], "total": answer.get("total")}}
    page = answer.get("page") or {}
    rows = []
    for r in page_rows(page):
        rid = r.get(KEY)
        rows.append({"ref": {"kind": kind, "id": rid}, "title": r.get(TITLE) or rid, "values": {c: value_at(r, c) for c in cols}})
    data["rows"] = rows
    total = page.get("total")
    data["live"] = {"table": answer.get("table"), "query": answer.get("query") or {}, "key": [KEY], "live": bool(answer.get("live")),
                    "total": total if total is not None else len(rows)}
    data["asOfNotices"] = answer.get("asOfNotices") or []
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
    """The declaration the records grid (drishti-grid's LiveSetGrid, over the Data Protocol) draws a result with: the id first (the key, pinned), the title when it says
    more than the id, then each column at its field (a number column by its values), as the search's labels name them. Never editable here: a
    result is read, the record's page edits."""
    d = data or {}
    mnemonic = d.get("mnemonic") or d.get("kind") or "Id"
    cols = [{"field": "__id", "label": mnemonic, "type": "id"}]
    if titled:
        cols.append({"field": "_title", "label": "Title", "type": "text"})
    labels = d.get("labels") or {}
    first = d.get("rows", [])[:50]
    for path in d.get("columns") or []:
        seen = [r["values"].get(path) for r in first if r["values"].get(path) is not None]
        if first and len(seen) == len(first) and all(str(r["values"].get(path)) == str(r["ref"]["id"]) for r in first):
            continue                                    # a column that only repeats the key: the key is shown once
        if first and not seen:
            continue                                    # a column nothing on this page holds (a snapshot note of a live document) is not shown
        numeric = bool(seen) and all(number(v) for v in seen)
        cols.append({"field": path[2:] if path.startswith("$.") else path, "label": labels.get(path, path), "type": "number" if numeric else "text"})
    live = d.get("live") or {}
    noun = (d.get("kind") or "").replace("-", " ")
    noun = (noun[:-1] + "ies" if noun[-1:] == "y" and noun[-2:-1] not in "aeiou" else noun + "s") if noun else "rows"
    return {"id": live.get("table") or "result", "table": live.get("table"), "query": live.get("query") or {}, "key": live.get("key") or [KEY],
            "title": d.get("title") or mnemonic, "kind": d.get("kind") or "", "noun": noun, "columns": cols, "groupBy": [], "subtotals": [],
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


def table_spec(table: str, schema: dict) -> dict:
    """A live table's schema (``GET /api/v1/rdp/tables/{name}``) as the declaration the records grid draws: its columns (the key first, a
    number column by its type), its own order, grouping and subtotals, its links, and for a reader who may edit (the schema's ``write``) the
    editable fields with their rules; ``readOnly`` says why not otherwise."""
    key = schema.get("key") or []
    view = schema.get("view") or {}
    numeric = ("integer", "decimal", "number")
    cols = []
    for c in schema.get("columns") or []:
        name = c.get("name")
        t = "id" if name in key[:1] else "number" if c.get("type") in numeric else "date" if c.get("type") == "date" else "datetime" if c.get("type") == "timestamp" else "text"
        col = {"field": name, "label": c.get("label") or name, "type": t}
        if c.get("fmt"):
            col["format"] = c["fmt"]
        cols.append(col)
    sort = (view.get("sort") or [None])[0] or {}
    write = schema.get("write") or {}
    fields = []
    for name, rule in (write.get("columns") or {}).items():
        f = {"id": name, "bind": name, "label": next((c["label"] for c in cols if c["field"] == name), name), "type": rule.get("type") or "text"}
        for k in ("required", "min", "max", "pattern"):
            if rule.get(k) is not None:
                f[k] = rule[k]
        if rule.get("options"):
            f["options"] = [o if isinstance(o, dict) else {"value": o, "label": str(o)} for o in rule["options"]]
        fields.append(f)
    kind = schema.get("kind") or (schema.get("profile") or {}).get("kind") or table
    return {"id": table, "table": table, "query": {}, "key": key, "version": schema.get("rowVersion"), "title": schema.get("title") or table,
            "kind": kind, "noun": "rows", "columns": cols, "groupBy": view.get("groupBy") or [], "subtotals": view.get("subtotals") or [],
            "links": schema.get("links") or (schema.get("profile") or {}).get("links") or [], "sort": {"field": sort.get("col"), "descending": sort.get("dir") == "desc"} if sort else {},
            "editable": bool(fields), "readOnly": None if fields else (write.get("readOnly") or schema.get("readOnly") or "read only"),
            "fields": fields, "tray": bool(schema.get("tray")), "reason": write.get("reason"),
            **({"excludes": list(schema["excludes"]), "banner": "excludes " + ", ".join(schema["excludes"]) + " (switched off)"} if schema.get("excludes") else {})}


def kind_page(answer: dict) -> dict:
    """A page of a kind's own table (a bare mnemonic) in the search's shape: the table's columns, its total as matched and scanned, and
    ``paged`` (offset, shown, total) for the pager."""
    table, schema, page = answer.get("table"), answer.get("schema") or {}, answer.get("page") or {}
    spec = table_spec(table, schema)
    cols = [c for c in spec["columns"] if c.get("field")]
    paths = ["$." + c["field"] for c in cols]
    total = int(page.get("total") or 0)
    key = spec["key"][0] if spec["key"] else KEY
    rows = []
    for r in page_rows(page):
        rows.append({"ref": {"kind": spec["kind"], "id": r.get(key)}, "title": r.get(key), "values": {p: value_at(r, p) for p in paths}})
    data = {"kind": spec["kind"], "mnemonic": spec["title"], "columns": paths, "labels": {p: c["label"] for p, c in zip(paths, cols)},
            "matched": total, "scanned": total, "partial": bool(schema.get("capped")), "failed": [], "elapsedMs": 0, "rows": rows,
            "live": {"table": table, "query": {}, "key": spec["key"], "live": bool(schema.get("live")), "total": total}}
    data["paged"] = {"offset": int(answer.get("offset") or 0), "shown": len(rows), "total": total}
    data["asOfNotices"] = answer.get("asOfNotices") or []
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
