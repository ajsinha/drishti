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

"""Promote to a report visual (docs/guides/ANALYSE.md "Promote to a report"): a slice or a SQL cell of Analyse becomes a visual in a new report draft or in an existing one.

Nothing here runs a query. A slice is already a choice of fields, aggregates and filters; a SQL cell is read (by a small reader, not a SQL engine) for the same three things:
the columns it selects or groups by, the aggregates it computes, and the simple conditions it filters on. What the reader cannot understand (a join, a sub-query, an
expression) is said in the notes and left out, never guessed at. The dataset's measures are what a report shows, so an aggregate becomes a visual's value only when the
dataset has a measure that is exactly that aggregate; otherwise the note says which measure to add.

The visual is a ``matrix`` (rows, columns and values) when there are values, else a ``table`` of the fields; filters become the visual's own ``filters``, so the report
shows what the cell showed. The dataset of the visual is the one the extract was opened from (the visual names it when the report's own dataset is another)."""
from __future__ import annotations

import re

AGGS = ("sum", "avg", "count", "min", "max")
_CLAUSE = re.compile(r"\b(from|where|group\s+by|having|order\s+by|limit)\b", re.I)
_AGG = re.compile(r"^(sum|avg|count|min|max)\s*\(\s*(\*|distinct\s+)?\s*([A-Za-z_][A-Za-z0-9_]*)?\s*\)(?:\s+as\s+\S+)?$", re.I)
_COL = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)(?:\s+as\s+\S+)?$", re.I)
_OPS = {"=": "eq", "==": "eq", "<>": "ne", "!=": "ne", "<": "lt", "<=": "le", ">": "gt", ">=": "ge"}


def _split(text: str, sep: str) -> list[str]:
    """``text`` split on ``sep`` where it is outside quotes and brackets."""
    out, depth, quote, cur = [], 0, "", ""
    i = 0
    while i < len(text):
        ch = text[i]
        if quote:
            cur += ch
            if ch == quote:
                quote = ""
        elif ch in "'\"":
            quote, cur = ch, cur + ch
        elif ch == "(":
            depth, cur = depth + 1, cur + ch
        elif ch == ")":
            depth, cur = depth - 1, cur + ch
        elif depth == 0 and text[i:i + len(sep)].lower() == sep and (not sep.isalpha() or (i == 0 or not text[i - 1].isalnum()) and not text[i + len(sep):i + len(sep) + 1].isalnum()):
            out.append(cur.strip())
            cur, i = "", i + len(sep)
            continue
        else:
            cur += ch
        i += 1
    out.append(cur.strip())
    return [p for p in out if p]


def _clauses(sql: str) -> dict[str, str]:
    """``{select, from, where, group by, ...}``: the text after each top-level keyword."""
    flat = re.sub(r"\s+", " ", sql.strip().rstrip(";"))
    if not re.match(r"select\b", flat, re.I):
        return {}
    out, marks = {}, []
    for m in _CLAUSE.finditer(flat):
        before = flat[:m.start()]
        if before.count("(") == before.count(")") and before.count("'") % 2 == 0:
            marks.append((m.start(), m.end(), re.sub(r"\s+", " ", m.group(1).lower())))
    head_end = marks[0][0] if marks else len(flat)
    out["select"] = flat[len("select"):head_end].strip()
    for k, (s, e, name) in enumerate(marks):
        out[name] = flat[e:marks[k + 1][0] if k + 1 < len(marks) else len(flat)].strip()
    return out


def _literal(text: str):
    t = text.strip()
    if len(t) >= 2 and t[0] == t[-1] and t[0] in "'\"":
        return t[1:-1].replace("''", "'")
    try:
        return int(t)
    except ValueError:
        pass
    try:
        return float(t)
    except ValueError:
        return None


def _condition(text: str, columns: list[str]):
    """One condition as a filter ``{field, op, values}`` over a column of the extract, or None."""
    t = text.strip()
    while t.startswith("(") and t.endswith(")") and t.count("(") == t.count(")") and _split(t[1:-1], ",") == [t[1:-1].strip()] and "(" not in t[1:-1]:
        t = t[1:-1].strip()                                                       # one pair of brackets around the whole condition
    m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*(==|=|<>|!=|<=|>=|<|>)\s*(.+)$", t)
    if m and m.group(1) in columns and _literal(m.group(3)) is not None:
        return {"field": m.group(1), "op": _OPS[m.group(2)], "values": [_literal(m.group(3))]}
    m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s+(not\s+)?in\s*\((.*)\)$", t, re.I)
    if m and m.group(1) in columns:
        vals = [_literal(v) for v in _split(m.group(3), ",")]
        if vals and None not in vals:
            return {"field": m.group(1), "op": "not-in" if m.group(2) else "in", "values": vals}
    m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s+between\s+(.+?)\s+and\s+(.+)$", t, re.I)
    if m and m.group(1) in columns and _literal(m.group(2)) is not None and _literal(m.group(3)) is not None:
        return {"field": m.group(1), "op": "between", "values": [_literal(m.group(2)), _literal(m.group(3))]}
    m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s+is\s+(not\s+)?null$", t, re.I)
    if m and m.group(1) in columns:
        return {"field": m.group(1), "op": "not-null" if m.group(2) else "is-null", "values": []}
    return None


def sql_to_slice(sql: str, columns: list[str]) -> dict:
    """Reads a SQL cell as ``{rows, columns, values: [{field, agg}], filters: [{field, op, values}], notes}`` over the extract's ``columns`` (short names).
    A query that is not one SELECT over ``extract`` with plain columns, aggregates and simple conditions is read as far as it can be and the notes say what was left."""
    notes: list[str] = []
    c = _clauses(sql)
    out = {"rows": [], "columns": [], "values": [], "filters": [], "notes": notes}
    if not c or "select" not in c:
        notes.append("The cell is not a SELECT, so nothing could be read from it.")
        return out
    if c.get("from", "").split(" ")[0].lower() != "extract" or re.search(r"\bjoin\b|\bunion\b|\bselect\b", c.get("from", ""), re.I):
        notes.append("Only a query over the table extract (no join, no sub-query) is read; the visual shows the fields it could find.")
    items = _split(c["select"], ",")
    plain, aggs = [], []
    for item in items:
        if item == "*":
            plain = list(columns)
            continue
        a = _AGG.match(item)
        if a:
            field = a.group(3) or ""
            if a.group(2) and a.group(2).lower().startswith("distinct"):
                notes.append(f"count(distinct {field}) has no plain dataset measure to match; add a measure for it.")
            elif a.group(1).lower() == "count" and not field:
                aggs.append({"field": "*", "agg": "count"})
            elif field in columns:
                aggs.append({"field": field, "agg": a.group(1).lower()})
            else:
                notes.append(f"'{item}' reads a column the extract does not have.")
            continue
        col = _COL.match(item)
        if col and col.group(1) in columns:
            plain.append(col.group(1))
        else:
            notes.append(f"The expression '{item}' is not a column or an aggregate of one, so it was left out.")
    groups = [g for g in (_split(c.get("group by", ""), ",") if c.get("group by") else []) if g in columns]
    rows = list(dict.fromkeys(groups + [p for p in plain if not aggs or p in groups or not groups]))
    out["rows"] = rows
    out["values"] = aggs
    where = re.sub(r"(\bbetween\s+(?:'[^']*'|\S+))\s+and\s+", "\\1 \x00 ", c.get("where", ""), flags=re.I)       # the AND of BETWEEN is not a conjunction
    for cond in (_split(where, "and") if where else []):
        cond = cond.replace(" \x00 ", " and ")
        f = _condition(cond, columns)
        if f:
            out["filters"].append(f)
        else:
            notes.append(f"The condition '{cond}' is not one the report's filters can say (a column, a comparison, in, between, is null), so it was left out.")
    if c.get("having"):
        notes.append("HAVING was left out: filter the measure in the report with a filter on the measure.")
    if c.get("order by") or c.get("limit"):
        notes.append("ORDER BY and LIMIT were left out: a report visual sorts and limits in its own settings.")
    return out


def matching_measure(model: dict, agg: str, column: str, table: str = "") -> str | None:
    """The dataset measure that is exactly ``agg(column)`` (``column`` is table.column; ``*`` is the rows of ``table``), else None."""
    if column == "*":
        want = {f"count({t.get('name')})" for t in model.get("tables") or [] if not table or t.get("name") == table}
    else:
        want = {f"{agg}({column})".replace(" ", "").lower()}
    for m in model.get("measures") or []:
        if str(m.get("expr") or "").replace(" ", "").lower() in {w.lower() for w in want}:
            return m["name"]
    return None


def visual_for(model: dict, sl: dict, name: str, vid: str, qualify, notes: list[str]) -> dict | None:
    """The visual a slice stands for, or None when it has nothing to show. ``qualify(short)`` gives the table.column of an extract column."""
    rows = [qualify(f) for f in sl.get("rows") or []]
    cols = [qualify(f) for f in sl.get("columns") or []]
    measures = []
    for v in sl.get("values") or []:
        field, agg = str(v.get("field", "")), str(v.get("agg", "sum"))
        table = (qualify(field).split(".")[0] if field and field != "*" else (rows[0].split(".")[0] if rows else ""))
        found = matching_measure(model, agg, qualify(field) if field != "*" else "*", table)
        if found:
            measures.append(found)
        else:
            notes.append(f"No dataset measure is {agg}({qualify(field) if field != '*' else '*'}); add one to the dataset, then put it in the visual's values.")
    visual: dict
    at = {"x": 0, "y": 0, "w": 12, "h": 6}
    if measures and rows:
        visual = {"id": vid, "type": "matrix", "title": name, "at": at, "wells": {"rows": rows, "values": measures}}
        if cols:
            visual["wells"]["columns"] = cols[:1]
    elif rows or cols:
        visual = {"id": vid, "type": "table", "title": name, "at": at, "wells": {"columns": rows + cols}}
    elif measures:
        visual = {"id": vid, "type": "card" if len(measures) == 1 else "multirow-card", "title": name, "at": at, "wells": {"values": measures}}
    else:
        return None
    filters = [{"field": qualify(f["field"]), "op": f["op"], "values": list(f.get("values") or [])} for f in sl.get("filters") or []]
    if filters:
        visual["filters"] = filters
    return visual


def add_to_report(report: dict, visual: dict, page_id: str, dataset_name: str, notes: list[str]) -> dict:
    """The report model with the visual added to ``page_id`` (the first page when blank; a new page when the id is new), below what is there, with a free id. A visual
    of a dataset other than the report's names its own."""
    pages = report.setdefault("pages", [])
    page = next((p for p in pages if p.get("id") == page_id), None) if page_id else (pages[0] if pages else None)
    if page is None:
        page = {"id": re.sub(r"[^A-Za-z0-9_-]", "-", page_id or "analyse").strip("-") or "analyse", "title": page_id or "From Analyse", "visuals": []}
        pages.append(page)
    taken = {v.get("id") for p in pages for v in p.get("visuals") or []}
    base, n = visual["id"], 1
    while visual["id"] in taken:
        n += 1
        visual["id"] = f"{base}-{n}"
    bottom = max([(v.get("at") or {}).get("y", 0) + (v.get("at") or {}).get("h", 0) for v in page.get("visuals") or []] or [0])
    visual["at"] = {**visual["at"], "y": bottom}
    home = str(report.get("dataset") or "")
    if home and dataset_name and home not in (dataset_name, "file:dataset:" + dataset_name):
        visual["dataset"] = dataset_name
        notes.append(f"The report reads '{home}'; this visual names its own dataset '{dataset_name}' (a link in the report can connect their slicers).")
    page.setdefault("visuals", []).append(visual)
    return report
