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

"""``drishti_bi``: the BI engine from Calc (docs/guides/USER_GUIDE.md, "The BI client").

    import drishti_bi

    drishti_bi.datasets()                                    # the datasets you may query: a DataFrame
    drishti_bi.describe("trades-basic")                      # a dataset's fields and measures: a DataFrame
    df = drishti_bi.query("trades-basic", measures=["MTM", "Trades"], by=["trade.desk"])
    df = drishti_bi.query("trades-basic", measures=["MTM"], by=["trade.desk", "trade.book"],
                          filters={"trade.desk": ["FX", "RATES"]})            # or [{"field": ..., "op": "in", "values": [...]}]
    drishti_bi.walk(df)                                      # explore the frame with Graphic Walker (PygWalker's interface)

It runs inside Pyodide in the Web Worker of Calc, like ``drishti``. Every call is a message to the page, which asks the BI engine of the server with **your**
session: the same query a report makes, so your row rules apply, and a column that is masked for you comes back masked (the names are in ``df.attrs["masked"]``).
Nothing here holds a credential, and nothing runs on a server: the engine's answer is a DataFrame in this tab, and what you do with it is pandas.

Both forms exist for each read, as in ``drishti``: ``drishti_bi.query(...)`` pauses Python while the page reads (needs JavaScript Promise Integration, as
``drishti.get`` does) and ``await drishti_bi.query_async(...)`` works in every browser.
"""
from __future__ import annotations

import json
import sys

import drishti
from drishti import DrishtiError, _async, _call, _pd, _sync

__all__ = ["datasets", "describe", "query", "walk", "datasets_async", "describe_async", "query_async", "DrishtiError"]

#: The most rows ``walk`` hands to Graphic Walker (it keeps them in the tab's memory); more are cut with a note.
WALK_ROWS = 20_000


def _filters(filters) -> list[dict]:
    """``{"trade.desk": ["FX", "RATES"]}`` as ``in`` filters; a list of ``{field, op, values}`` as it is."""
    if not filters:
        return []
    if isinstance(filters, dict):
        return [{"field": str(k), "op": "in", "values": list(v) if isinstance(v, (list, tuple, set)) else [v]} for k, v in filters.items()]
    return [dict(f) for f in filters]


def _body(measures, by, filters, dates, limit) -> dict:
    if isinstance(measures, str):
        measures = [measures]
    if isinstance(by, str):
        by = [by]
    if not measures and not by:
        raise ValueError("name at least one measure, or one field in by")
    body = {"mq": 1, "select": {"fields": list(by or []), "measures": list(measures or [])}}
    where = _filters(filters)
    if where:
        body["where"] = where
    if dates:
        first, last = (dates[0], dates[-1]) if isinstance(dates, (list, tuple)) else (dates, dates)
        body["time"] = {"window": {"from": str(first)[:10], "to": str(last)[:10]}}
    if limit:
        body["limit"] = int(limit)
    return body


def model_ref(source: str) -> str:
    """The model a name stands for (the console's core/explore.py model_of): ``trading-risk@1`` or ``dataset:<name>`` as they are, a 2.1 dataset's name
    (``trades-basic``) or shipped id (``file:dataset:trades-basic``) the model the converter made of it, ``trades-basic@1``."""
    s = str(source or "").strip()
    if s.startswith("mq:"):
        return s[3:]
    if s.startswith("file:dataset:"):
        s = s[len("file:dataset:"):]
    return s if not s or s.startswith(("dataset:", "bi-")) or "@" in s else s + "@1"


def _frame(answer: dict, dataset: str):
    pd = _pd()
    cols = [c["name"] for c in answer.get("columns") or []]
    df = pd.DataFrame(answer.get("rows") or [], columns=cols)
    for c in answer.get("columns") or []:                      # numbers are numbers, dates are dates
        if c.get("type") in ("integer", "decimal"):
            df[c["name"]] = pd.to_numeric(df[c["name"]], errors="coerce")
        elif c.get("type") in ("date", "timestamp"):
            df[c["name"]] = pd.to_datetime(df[c["name"]], errors="coerce")
    meta = answer.get("meta") or {}
    df.attrs.update({"dataset": dataset, "masked": [c["name"] for c in answer.get("columns") or [] if c.get("masked")],
                     "asOf": (meta.get("dates") or [None])[-1], "layer": meta.get("layer"), "millis": meta.get("millis")})
    return df


def _datasets_frame(data):
    pd = _pd()
    return pd.DataFrame([{"id": d.get("id"), "title": d.get("title"), "tables": d.get("tables"), "measures": d.get("measures"), "description": d.get("description", "")}
                         for d in data or []])


def _describe_frame(model: dict):
    pd = _pd()
    rows = []
    for t in model.get("tables") or []:
        for c in t.get("columns") or []:
            rows.append({"kind": "field", "name": f"{t['name']}.{c['name']}", "type": c.get("type"), "masked": bool(c.get("masked")), "description": c.get("description", "")})
    for m in model.get("measures") or []:
        rows.append({"kind": "measure", "name": m["name"], "type": "python" if m.get("python") else "decimal", "masked": False, "description": m.get("description", "")})
    return pd.DataFrame(rows, columns=["kind", "name", "type", "masked", "description"])


def datasets():
    """The datasets you may query, as a DataFrame (id, title, tables, measures, description)."""
    return _datasets_frame(_sync("bi.datasets", {}, ""))


async def datasets_async():
    return _datasets_frame(await _async("bi.datasets", {}))


def describe(dataset: str):
    """A dataset's fields (``table.column``) and measures, as a DataFrame; ``masked`` says which fields are masked for you."""
    return _describe_frame(_sync("bi.model", {"dataset": dataset}, _call(dataset)))


async def describe_async(dataset: str):
    return _describe_frame(await _async("bi.model", {"dataset": dataset}))


def query(dataset: str, measures=None, by=None, filters=None, dates=None, limit=None):
    """Asks the BI engine for ``measures`` grouped by the fields in ``by`` and returns a DataFrame.

    ``filters``: ``{"table.column": [values]}`` (an ``in`` filter each) or a list of ``{field, op, values}`` (ops: eq ne lt le gt ge in not-in between contains
    starts-with is-null not-null). ``dates``: a date, or ``(from, to)``; the engine's latest business day when omitted. ``limit``: most rows. The call is the one
    a report visual makes (one model query on the dataset's model, ``model_ref``), as you: row rules and masks are applied by the engine, and the answer says so in ``df.attrs``."""
    body = {**_body(measures, by, filters, dates, limit), "model": model_ref(dataset)}
    return _frame(_sync("bi.query", {"dataset": dataset, "body": body}, _call(dataset, measures=measures, by=by, filters=filters, dates=dates, limit=limit)), dataset)


async def query_async(dataset: str, measures=None, by=None, filters=None, dates=None, limit=None):
    body = {**_body(measures, by, filters, dates, limit), "model": model_ref(dataset)}
    return _frame(await _async("bi.query", {"dataset": dataset, "body": body}), dataset)


def walk(df, title: str | None = None, limit: int = WALK_ROWS) -> None:
    """Explores a DataFrame with Graphic Walker, the interface PygWalker puts over a frame: drag fields to the axes, colour, size and filters, and the chart is drawn.

    It is vendored and runs in a sandboxed frame of this page that cannot reach the network or the console; the rows go to it by message. Graphic Walker is
    loaded the first time you ask for it. (PygWalker itself is a Jupyter package that needs a Python server and the internet; this is its front end, over your frame.)"""
    pd = _pd()
    if not isinstance(df, pd.DataFrame):
        df = pd.DataFrame(df)
    if not isinstance(df.index, pd.RangeIndex):
        df = df.reset_index()
    total = len(df)
    cap = max(1, min(int(limit), drishti.LIMITS["cells"] * 10 // max(1, len(df.columns))))
    part = df.head(cap)
    fields = []
    for c in part.columns:
        s = part[c]
        if pd.api.types.is_bool_dtype(s):
            semantic, analytic = "nominal", "dimension"
        elif pd.api.types.is_numeric_dtype(s):
            semantic, analytic = "quantitative", "measure"
        elif pd.api.types.is_datetime64_any_dtype(s):
            semantic, analytic = "temporal", "dimension"
        else:
            semantic, analytic = "nominal", "dimension"
        fields.append({"fid": str(c), "name": str(c), "semanticType": semantic, "analyticType": analytic})
    rows = [{str(c): drishti._clean(v) for c, v in zip(part.columns, r)} for r in part.itertuples(index=False, name=None)]
    drishti._send({"kind": "walker", "title": title, "fields": fields, "rows": rows, "total": total})
    if total > len(part):
        drishti._note(f"{title + ': ' if title else ''}Graphic Walker has the first {len(part):,} of {total:,} rows")
