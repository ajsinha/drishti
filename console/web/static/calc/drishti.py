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

"""The ``drishti`` module of Calc (docs/guides/PYTHON_CALC.md): the screen you are on, Drishti's data, and output.

It runs inside Pyodide (CPython on WebAssembly) in a Web Worker of your browser tab, never on a server. Every read
(``get``, ``search``, ``columns``, ``history``) is a message to the page, which asks the console with your own session:
the same reads as the screen, with your roles and the same redaction. Nothing here holds a credential.

    view                    the screen: view.kind, view.id, view.business_date, view.doc, view.tables, view.panels
    get(kind, id)           an entity's document, as your role may see it (dict)
    search(query)           a structured search ("TRD where mtm > 1m") as a pandas DataFrame
    columns(kind, paths)    whole columns of a business day (fields the source keeps as columns) as a DataFrame
    history(kind, id, field, days)   one field over the last business days as a DataFrame
    show(x, title=None)     a table (paged like Drishti's), a figure, or text
    chart(df, kind="line", x=None, y=None)   a line, bar, scatter or hist chart, drawn by the page in its theme
"""
from __future__ import annotations

import base64
import inspect
import io
import json
import math
import re
import sys
import traceback

from _drishti_bridge import emit as _emit, request as _request   # registered by calc-worker.js

__all__ = ["view", "get", "search", "columns", "history", "show", "chart", "get_async", "search_async", "columns_async",
           "history_async", "help", "DrishtiError", "LIMITS"]

#: What one run may send to the page: rows per table, points per chart series, bytes in all. Beyond, output is cut with a note.
LIMITS = {"rows": 2000, "points": 5000, "bytes": 8_000_000, "cells": 200_000}
SYNC_HELP = ("drishti.{name}() needs JavaScript Promise Integration to pause Python while the page reads, and this browser "
             "has none: write `await drishti.{name}_async({call})` instead (the _async forms work in every browser; "
             "view needs no read)")


class DrishtiError(Exception):
    """A read Drishti refused or could not answer: ``code`` (DRS-nnnn) and ``status`` (HTTP) say why."""

    def __init__(self, code: str, detail: str, status: int = 0):
        super().__init__(f"{code}: {detail}")
        self.code, self.detail, self.status = code, detail, status


# ---- reads: messages to the page, which fetches with the user's session ------------------------------------------------

def _decode(raw) -> object:
    body = json.loads(str(raw))
    if not body.get("ok"):
        code = body.get("code") or "DRS-5003"
        detail = str(body.get("detail") or "the read failed")
        raise DrishtiError(code, detail[len(code):].strip() if detail.startswith(code) else detail, int(body.get("status") or 0))
    return body.get("data")


def _call(*args, **kw) -> str:
    """The arguments of a call as the user would write them, for the message that says how to write it with await."""
    return ", ".join([repr(a) for a in args] + [f"{k}={v!r}" for k, v in kw.items() if v is not None])


def _sync(op: str, args: dict, call: str = "..."):
    from pyodide.ffi import can_run_sync, run_sync
    if not can_run_sync():
        raise RuntimeError(SYNC_HELP.format(name=op, call=call))
    return _decode(run_sync(_request(op, json.dumps(args, default=str))))


async def _async(op: str, args: dict):
    return _decode(await _request(op, json.dumps(args, default=str)))


def _date(as_of) -> str | None:
    return None if as_of is None else str(as_of)[:10]


def _pd():
    """pandas, loaded from the console the first time it is needed."""
    try:
        import pandas
    except ImportError:
        import pyodide_js
        from pyodide.ffi import can_run_sync, run_sync
        if not can_run_sync():
            raise RuntimeError("pandas is not loaded yet: add `import pandas` at the top of your code") from None
        run_sync(pyodide_js.loadPackage(["numpy", "pandas"]))
        import pandas
    return pandas


def _search_frame(data: dict, query: str):
    pd = _pd()
    cols = [c for c in data.get("columns") or []]
    labels = {c: (c[2:] if c.startswith("$.") else c) for c in cols}
    rows = []
    for r in data.get("rows") or []:
        row = {"id": r["ref"]["id"], "title": r.get("title") or r["ref"]["id"]}
        for c in cols:
            row[labels[c]] = (r.get("values") or {}).get(c)
        rows.append(row)
    df = pd.DataFrame(rows, columns=["id", "title", *labels.values()])
    if len(df) and (df["title"] == df["id"]).all():
        df = df.drop(columns=["title"])
    df.attrs.update({"query": query, "kind": data.get("kind"), "matched": data.get("matched"), "scanned": data.get("scanned"),
                     "partial": bool(data.get("partial"))})
    if data.get("partial") or (data.get("matched") or 0) > len(df):
        _note(f"search: {len(df):,} of {data.get('matched', 0):,} matching shown"
              + (" (the scan stopped early: narrow the condition)" if data.get("partial") else " (add limit N, at most 1000)"))
    return df


def _columns_frame(data: dict):
    pd = _pd()
    df = pd.DataFrame(data.get("values") or {}, index=pd.Index(data.get("ids") or [], name="id"))
    df.attrs.update({"kind": data.get("kind"), "businessDate": data.get("businessDate"), "total": data.get("total"),
                     "masked": data.get("masked") or []})
    if data.get("truncated"):
        _note(f"columns: the first {len(df):,} of {data.get('total', 0):,} entities (drishti.calc.max-column-rows)")
    if data.get("masked"):
        _note(f"columns: your role sees {', '.join(data['masked'])} masked")
    return df


def _history_frame(data: dict, field: str):
    pd = _pd()
    pts = data.get("points") or []
    df = pd.DataFrame({"date": pd.to_datetime([p.get("date") for p in pts]), field: [p.get("value") for p in pts]})
    return df.set_index("date")


def _args(op, **kw):
    return {k: v for k, v in kw.items() if v is not None}


def get(kind: str, id: str, as_of=None) -> dict:
    """An entity's document as your role may see it (masked fields read •••), on the business date ``as_of``
    ('2026-09-25'; default: the date the screen shows)."""
    return _sync("get", _args("get", kind=kind, id=id, asOf=_date(as_of)), _call(kind, id, as_of=as_of))


def search(query: str, as_of=None):
    """A structured search, as on the command line ("TRD where currency = 'EUR' and mtm > 1m order by mtm desc limit 500"),
    as a DataFrame: id, title, then the query's fields and the kind's key fields. At most 1000 rows (``limit``)."""
    return _search_frame(_sync("search", _args("search", q=query, asOf=_date(as_of)), _call(query, as_of=as_of)), query)


def columns(kind: str, paths, as_of=None, limit: int | None = None):
    """Every entity of a kind on a business date with these fields, from the source that keeps them as columns (a Delta
    table laid out by its pack): ``columns("TRD", ["mtm", "book", "risk.dv01"])``. Indexed by id. ``columns("TRD", [])``
    lists the fields kept as columns."""
    paths = [paths] if isinstance(paths, str) else list(paths or [])
    data = _sync("columns", _args("columns", kind=kind, paths=",".join(paths), asOf=_date(as_of), limit=limit),
                 _call(kind, paths, as_of=as_of, limit=limit))
    return data.get("available", []) if not paths else _columns_frame(data)


def history(kind: str, id: str, field: str, days: int = 30):
    """One field of an entity over the last ``days`` business days (2-260), oldest first, indexed by date."""
    return _history_frame(_sync("history", _args("history", kind=kind, id=id, path=field, days=int(days)),
                                _call(kind, id, field, days)), field)


async def get_async(kind: str, id: str, as_of=None) -> dict:
    """``get``, for browsers without JavaScript Promise Integration: ``doc = await drishti.get_async(...)``."""
    return await _async("get", _args("get", kind=kind, id=id, asOf=_date(as_of)))


async def search_async(query: str, as_of=None):
    return _search_frame(await _async("search", _args("search", q=query, asOf=_date(as_of))), query)


async def columns_async(kind: str, paths, as_of=None, limit: int | None = None):
    paths = [paths] if isinstance(paths, str) else list(paths or [])
    data = await _async("columns", _args("columns", kind=kind, paths=",".join(paths), asOf=_date(as_of), limit=limit))
    return data.get("available", []) if not paths else _columns_frame(data)


async def history_async(kind: str, id: str, field: str, days: int = 30):
    return _history_frame(await _async("history", _args("history", kind=kind, id=id, path=field, days=int(days))), field)


# ---- the screen ---------------------------------------------------------------------------------------------------------

_NUM = re.compile(r"^([+-]?)(?:[A-Z]{3}\s+)?(\d[\d,]*(?:\.\d+)?|\.\d+)\s*(k|m|mm|bn|b|tn)?\s*(%|bp| bp)?$", re.I)
_SCALE = {"k": 1e3, "m": 1e6, "mm": 1e6, "bn": 1e9, "b": 1e9, "tn": 1e12}


def number(text):
    """A number as a Drishti screen shows it ("−1,234.5", "USD 1,300,000", "1.5m", "4.08%", "+12.0 bp") as a float, as
    written (4.08% is 4.08); anything else unchanged."""
    if not isinstance(text, str):
        return text
    t = text.strip().replace("−", "-").replace("–", "-")
    m = _NUM.match(t)
    if not m:
        return text
    v = float(m.group(2).replace(",", "")) * (_SCALE[m.group(3).lower()] if m.group(3) else 1)
    return -v if m.group(1) == "-" else v


def _cells(cells):
    return [c.get("text") for c in cells or []]


def _panel_frame(p: dict):
    """A panel's rows as a DataFrame, for the panel kinds that hold rows; None for the others."""
    pd = _pd()
    d, kind = p.get("data") or {}, p.get("kind")
    if kind in ("table", "ladder"):
        cols = d.get("columns") or []
        numeric = d.get("numeric") or []
        rows = []
        for r in d.get("rows") or []:
            vals = _cells(r.get("cells"))
            rows.append([number(v) if i < len(numeric) and numeric[i] else v for i, v in enumerate(vals)])
        return pd.DataFrame(rows, columns=_unique(cols)[:len(rows[0])] if rows else _unique(cols))
    if kind in ("kv", "status", "provenance"):
        return pd.DataFrame([{"label": c.get("label"), "value": c.get("text")} for c in d.get("fields") or []])
    if kind in ("line", "area"):
        df = pd.DataFrame({s.get("label") or f"series {i + 1}": s.get("values") for i, s in enumerate(d.get("series") or [])},
                          index=pd.Index(d.get("x") or [], name="x"))
        return df
    if kind == "bars":
        return pd.DataFrame([{"label": b.get("label"), "value": b.get("value")} for b in d.get("bars") or []])
    if kind == "waterfall":
        return pd.DataFrame([{k: s.get(k) for k in ("label", "value", "from", "to", "total")} for s in d.get("steps") or []])
    if kind == "histogram":
        return pd.DataFrame([{k: b.get(k) for k in ("from", "to", "count", "label")} for b in d.get("bins") or []])
    if kind == "scatter":
        return pd.DataFrame([{k: q.get(k) for k in ("label", "group", "x", "y", "size")} for q in d.get("points") or []])
    if kind == "pivot":
        cols = _unique(d.get("columns") or [])
        return pd.DataFrame([r.get("values") or [] for r in d.get("rows") or []], columns=cols,
                            index=pd.Index([r.get("label") for r in d.get("rows") or []], name=d.get("by") or "row"))
    if kind == "candlestick":
        return pd.DataFrame([{k: c.get(k) for k in ("x", "open", "high", "low", "close", "volume")} for c in d.get("candles") or []])
    if kind == "timeline":
        return pd.DataFrame([{k: e.get(k) for k in ("date", "label", "detail", "status")} for e in d.get("events") or []])
    if kind == "surface":
        return pd.DataFrame(d.get("z") or [], index=pd.Index(d.get("y") or [], name="y"), columns=d.get("x") or None)
    return None


def _unique(names):
    seen, out = {}, []
    for n in names:
        n = n or "column"
        seen[n] = seen.get(n, 0) + 1
        out.append(n if seen[n] == 1 else f"{n} ({seen[n]})")
    return out


class View:
    """The screen the code was run from. ``doc`` is the entity's document as your role sees it; ``tables`` every panel
    that holds rows (tables, ladders, pivots, charts, bars, …) as a DataFrame, by the panel's title; ``panels`` the
    view model's panels as they are."""

    def __init__(self, ctx: dict):
        ref = ctx.get("ref") or {}
        self.kind: str = ref.get("kind") or ctx.get("kind") or ""
        self.id: str = ref.get("id") or ctx.get("id") or ""
        self.title: str = (ctx.get("title") or {}).get("id") or self.id
        self.provenance: dict = ctx.get("provenance") or {}
        self.business_date: str | None = self.provenance.get("businessDate") or ctx.get("businessDate")
        self.doc: dict = ctx.get("doc") or {}
        self.panels: list = ctx.get("panels") or []
        self.strip: dict = {c.get("label"): c.get("text") for c in ctx.get("strip") or []}
        self._tables = None

    @property
    def tables(self) -> dict:
        if self._tables is None:
            out = {}
            for p in self.panels:
                try:
                    df = _panel_frame(p)
                except Exception:       # one panel whose rows do not fit a frame never stops the others
                    df = None
                if df is not None:
                    title = p.get("title") or p.get("id") or "panel"
                    out[title if title not in out else f"{title} ({p.get('id')})"] = df
            self._tables = out
        return self._tables

    def table(self, name: str):
        """One panel's rows, by its title or its id."""
        if name in self.tables:
            return self.tables[name]
        for p in self.panels:
            if p.get("id") == name:
                return _panel_frame(p)
        raise KeyError(f"no panel '{name}' with rows on {self.kind} {self.id}: {list(self.tables)}")

    def __repr__(self):
        return f"<view {self.kind} {self.id}{' as of ' + self.business_date if self.business_date else ''}: {len(self.panels)} panels>"


view = View({})


# ---- output -------------------------------------------------------------------------------------------------------------

class _Budget:
    """Bytes sent to the page in this run; past LIMITS['bytes'], one note and nothing more."""
    sent = 0
    capped = False


def _send(item: dict) -> None:
    text = json.dumps(item, default=_jsonable, allow_nan=False)
    if _Budget.capped:
        return
    if _Budget.sent + len(text) > LIMITS["bytes"]:
        _Budget.capped = True
        text = json.dumps({"kind": "note", "text": f"output capped at {LIMITS['bytes'] // 1_000_000} MB for one run; "
                                                  "show() a smaller piece (df.head(), a groupby)"})
    _Budget.sent += len(text)
    _emit(text)


def _note(text: str) -> None:
    _send({"kind": "note", "text": text})


def _iso(v) -> str | None:
    """A date as 2026-09-30, a time as 2026-09-30T14:05:00 (midnight without a zone is a date); NaT as None."""
    pd = sys.modules.get("pandas")
    if pd is not None and v is pd.NaT:
        return None
    if callable(getattr(v, "date", None)) and getattr(v, "tzinfo", None) is None \
            and not any(getattr(v, a, 0) for a in ("hour", "minute", "second", "microsecond")):
        return v.date().isoformat()
    return v.isoformat()


def _jsonable(v):
    if hasattr(v, "isoformat"):
        return _iso(v)
    if hasattr(v, "item"):
        try:
            return _clean(v.item())
        except (ValueError, TypeError):
            pass
    return str(v)


def _clean(v):
    if isinstance(v, float):
        return v if math.isfinite(v) else None
    if v is None or isinstance(v, (bool, int, str)):
        return v
    if hasattr(v, "isoformat"):
        return _iso(v)
    if hasattr(v, "item"):
        try:
            return _clean(v.item())
        except (ValueError, TypeError):
            return str(v)
    try:
        import pandas as pd
        if pd.isna(v):
            return None
    except (ImportError, TypeError, ValueError):
        pass
    return str(v)


def _table(df, title=None):
    pd = sys.modules.get("pandas")
    if pd is not None and not isinstance(df.index, pd.RangeIndex):
        df = df.reset_index()
    total = len(df)
    max_rows = max(1, min(LIMITS["rows"], LIMITS["cells"] // max(1, len(df.columns))))
    part = df.head(max_rows)
    numeric = [bool(pd is not None and pd.api.types.is_numeric_dtype(part[c]) and not pd.api.types.is_bool_dtype(part[c]))
               for c in part.columns]
    rows = [[_clean(v) for v in r] for r in part.itertuples(index=False, name=None)]
    _send({"kind": "table", "title": title, "columns": [str(c) for c in part.columns], "numeric": numeric, "rows": rows,
           "total": total})
    if total > len(part):
        _note(f"{title + ': ' if title else ''}showing the first {len(part):,} of {total:,} rows")


def _figure(fig, title=None):
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=100, bbox_inches="tight")
    _send({"kind": "image", "title": title, "png": base64.b64encode(buf.getvalue()).decode("ascii")})


def show(obj, title: str | None = None) -> None:
    """Shows a value: a DataFrame or Series as a table (paged, sortable and filtered like Drishti's tables), a matplotlib
    figure as an image, a list of dicts or a dict as a table, anything else as text."""
    pd = sys.modules.get("pandas")
    if pd is not None and isinstance(obj, pd.DataFrame):
        return _table(obj, title)
    if pd is not None and isinstance(obj, pd.Series):
        return _table(obj.to_frame(name=obj.name if obj.name is not None else "value"), title)
    if type(obj).__name__ == "Figure" and hasattr(obj, "savefig"):
        return _figure(obj, title)
    np = sys.modules.get("numpy")
    if np is not None and isinstance(obj, np.ndarray) and obj.ndim <= 2:
        return show(_pd().DataFrame(obj), title)
    if isinstance(obj, list) and obj and all(isinstance(x, dict) for x in obj):
        return show(_pd().DataFrame(obj), title)
    if isinstance(obj, dict) and obj and all(not isinstance(v, (dict, list)) for v in obj.values()):
        return show(_pd().DataFrame({"key": list(obj), "value": [_clean(v) for v in obj.values()]}), title)
    text = obj if isinstance(obj, str) else _pretty(obj)
    _send({"kind": "text", "title": title, "text": text[:LIMITS["cells"] * 5]})


def _pretty(obj) -> str:
    if isinstance(obj, (dict, list)):
        try:
            return json.dumps(obj, indent=2, default=str)[:1_000_000]
        except (TypeError, ValueError):
            pass
    return repr(obj)


def chart(data, kind: str = "line", x: str | None = None, y=None, title: str | None = None, bins: int = 30) -> None:
    """Draws a chart in the page's theme (ECharts): ``kind`` is line, bar, scatter or hist. ``x`` is a column (default:
    the index); ``y`` one column or a list (default: every numeric column). ``hist`` counts the values of one column in
    ``bins`` equal bins."""
    pd = _pd()
    if kind not in ("line", "bar", "scatter", "hist"):
        raise ValueError("chart kind is line, bar, scatter or hist")
    df = data.to_frame() if isinstance(data, pd.Series) else data if isinstance(data, pd.DataFrame) else pd.DataFrame(data)
    if kind == "hist":
        import numpy as np
        col = y if isinstance(y, str) else (y[0] if y else next((c for c in df.columns if pd.api.types.is_numeric_dtype(df[c])), None))
        if col is None:
            raise ValueError("hist needs a numeric column")
        values = pd.to_numeric(df[col], errors="coerce").dropna().to_numpy()
        counts, edges = np.histogram(values, bins=bins)
        labels = [f"{edges[i]:,.4g} to {edges[i + 1]:,.4g}" for i in range(len(counts))]
        _send({"kind": "chart", "type": "hist", "title": title or f"{col}: {len(values):,} values", "x": labels,
               "xLabel": str(col), "series": [{"name": "count", "values": [int(c) for c in counts]}]})
        return
    xs = df.index.to_list() if x is None else df[x].to_list()
    ys = [y] if isinstance(y, str) else list(y) if y else [c for c in df.columns if c != x and pd.api.types.is_numeric_dtype(df[c])]
    if not ys:
        raise ValueError("no numeric column to draw: name y")
    n, step = len(xs), max(1, math.ceil(len(xs) / LIMITS["points"]))
    if step > 1:
        _note(f"chart: every {step}th of {n:,} points drawn")
    series = [{"name": str(c), "values": [_clean(v) for v in df[c].to_list()[::step]]} for c in ys]
    _send({"kind": "chart", "type": kind, "title": title, "x": [_clean(v) for v in xs[::step]],
           "xLabel": str(x if x is not None else (df.index.name or "")), "series": series})


def help() -> None:   # noqa: A001 - drishti.help() reads naturally
    """Prints this module's functions."""
    print(__doc__)


# ---- running a cell -----------------------------------------------------------------------------------------------------

_namespace: dict = {"__name__": "__main__"}


def _flush_figures() -> None:
    plt = sys.modules.get("matplotlib.pyplot")
    if plt is None:
        return
    for num in plt.get_fignums():
        _figure(plt.figure(num))
    plt.close("all")


def _where(e: BaseException) -> int | None:
    """The line of the user's code an error is on (the last frame in <calc>), for the editor to mark."""
    if isinstance(e, SyntaxError) and e.filename == "<calc>":
        return e.lineno
    line = None
    for frame in traceback.extract_tb(e.__traceback__):
        if frame.filename == "<calc>":
            line = frame.lineno
    return line


def _traceback(e: BaseException) -> str:
    """The traceback from the user's code on: frames of the runtime that ran it are left out."""
    te = traceback.TracebackException.from_exception(e)
    first = next((i for i, f in enumerate(te.stack) if f.filename == "<calc>"), None)
    if first is not None:        # from the user's first line on: the frames that ran it are not theirs
        stack = list(te.stack)[first:]
        while len(stack) > 1 and stack[-1].filename.endswith("/drishti.py"):
            stack.pop()          # nor are the module's own, under a drishti.get() that was refused
        te.stack = traceback.StackSummary.from_list(stack)
    return "".join(te.format()).strip()


async def _run(code: str, context: str) -> str:
    """Runs one cell (called by calc-worker.js): the view first, then the code, then its last value and any figures."""
    global view
    from pyodide.code import eval_code_async
    _Budget.sent, _Budget.capped = 0, False
    view = View(json.loads(context))
    me = sys.modules[__name__]
    _namespace.update({"view": view, "drishti": me, "show": show, "chart": chart})
    try:
        result = await eval_code_async(code, globals=_namespace, filename="<calc>")
        if inspect.iscoroutine(result):      # a last line of drishti.get_async(...) without await: awaited for you
            result = await result
        if result is not None and not type(result).__module__.startswith("matplotlib"):   # figures are shown below
            show(result)
        _flush_figures()
        return json.dumps({"ok": True})
    except BaseException as e:   # noqa: BLE001 - every failure is the user's to read, with its traceback
        try:
            _flush_figures()
        except Exception:        # noqa: BLE001 - a broken figure must not hide the error
            pass
        message = f"{type(e).__name__}: {e}"
        if isinstance(e, ModuleNotFoundError):
            message += " (Calc has numpy, pandas, scipy, statsmodels, matplotlib and the standard library)"
        elif "'coroutine' object" in str(e):
            message += " (an _async read returns something to await: write `x = await drishti.get_async(...)`)"
        return json.dumps({"ok": False, "error": message, "traceback": _traceback(e), "line": _where(e)})
