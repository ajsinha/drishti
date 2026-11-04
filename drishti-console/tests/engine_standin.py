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

"""The pivot engine's answer to POST /api/v1/pivot/inline in the browser tests' stand-ins (synthetic data; the real figures are proved by
PivotApiTest and RowsProviderTest in Java)."""
import re


OTHERS = "\u0000Others"


def inline_answer(body, kept=None):
    """The engine's answer to POST /api/v1/pivot/inline in the stand-in (the real one is proved by PivotApiTest and RowsProviderTest): one level of
    rows and of columns, each cell's figure from its own rows, blanks left out of every function; a level's top N (``view.top``: n and the
    measure it ranks by, largest first, ties in member order) keeps n members and puts the rest in one Others member with its own figures."""
    names = [f["name"] for f in body.get("fields") or []]
    view = body.get("view") or {}
    rd, cd = (view.get("rows") or [None])[0], (view.get("columns") or [None])[0]
    at = {n: i for i, n in enumerate(names)}

    def key(dim, v):
        return "*" if dim is None or v is None else f"{dim}={v}"

    groups = {}
    for row in body.get("rows") or []:
        r = None if rd is None else (row[at[rd]] if row[at[rd]] is not None else "(blank)")
        c = None if cd is None else (row[at[cd]] if row[at[cd]] is not None else "(blank)")
        if kept and rd in kept and r not in kept[rd]:
            r = OTHERS
        if kept and cd in kept and c not in kept[cd]:
            c = OTHERS
        for rk in {key(rd, r), "*"}:
            for ck in {key(cd, c), "*"}:
                groups.setdefault(f"{rk}|{ck}", []).append(row)

    def figure(fn, arg, rows):
        vals = [r[at[arg]] for r in rows if r[at[arg]] is not None] if arg else rows
        if fn == "count":
            return len(vals)
        if fn == "count_distinct":
            return len(set(vals))
        nums = [v for v in vals if isinstance(v, (int, float))]
        if not nums:
            return None
        return {"sum": sum(nums), "avg": sum(nums) / len(nums), "min": min(nums), "max": max(nums)}[fn]

    cells = {}
    for k, rows in groups.items():
        values = {}
        for m in view.get("values") or []:
            fn, arg = re.match(r"(\w+)\((\w*)\)", m["measure"]).groups()
            values[m["as"]] = figure(fn, arg or None, rows)
        cells[k] = {"values": values, "members": len(rows)}

    more = {}

    def axis(dim, pos):
        seen = sorted({k.split("|")[pos] for k in cells} - {"*"}, key=lambda x: x.split("=", 1)[1])
        out = [{"key": k, "kind": "node", "value": k.split("=", 1)[1], "depth": 1} for k in seen if k.split("=", 1)[1] != OTHERS]
        if any(k.split("=", 1)[1] == OTHERS for k in seen):
            out.append({"key": f"{dim}={OTHERS}", "kind": "others", "value": None, "depth": 1, "others": more.get(dim, 1)})
        return out + [{"key": "*", "kind": "grand", "value": None, "depth": 0}]

    if kept is None:
        top, keep = view.get("top") or {}, {}
        for dim, pos in ((rd, 0), (cd, 1)):
            t = top.get(dim) if dim else None
            members = [x["value"] for x in axis(dim, pos) if x["kind"] == "node"] if dim else []
            if not t or len(members) <= t["n"]:
                continue
            def fig(m):
                v = cells.get(f"{dim}={m}|*" if pos == 0 else f"*|{dim}={m}", {}).get("values", {}).get(t["by"])
                return v if isinstance(v, (int, float)) else float("-inf")
            ranked = sorted(range(len(members)), key=lambda i: (-fig(members[i]), i))[:t["n"]]
            keep[dim] = {members[i] for i in ranked}
        if keep:
            again = inline_answer(body, keep)
            return again
    else:
        for dim in kept:
            more[dim] = len({(row[at[dim]] if row[at[dim]] is not None else "(blank)") for row in body.get("rows") or []} - kept[dim])

    return {"source": "inline", "rows": axis(rd, 0), "columns": axis(cd, 1), "cells": cells}


# ---- the console's figures (core/engine_figures.py) ------------------------------------------------------------------------------
# The engine's answer for the console's own asks: one level of rows and of columns, filters, top N with Others, show-as pct-grand,
# the built-ins the console asks (sum, count, avg, min, max, median, stddev_pop, count_distinct) and formulas of them with + - * /,
# abs and least, as the engine computes them (FormulaFunctionTest, RowsProviderTest and ConsoleFiguresTest prove the real figures).
import math as _math  # noqa: E402
import statistics as _st  # noqa: E402

_CALL = re.compile(r"\b(sum|count|avg|min|max|median|stddev_pop|count_distinct)\(\s*(\w*)\s*\)")


def _agg(fn, vals):
    if fn == "count":
        return len(vals)
    if fn == "count_distinct":
        return len(set(vals))
    nums = [v for v in vals if isinstance(v, (int, float))]
    if not nums:
        return None
    return {"sum": lambda: _math.fsum(nums), "avg": lambda: _math.fsum(nums) / len(nums), "min": lambda: min(nums), "max": lambda: max(nums),
            "median": lambda: _st.median(nums), "stddev_pop": lambda: _st.pstdev(nums)}[fn]()


def _measure(text, rows, at):
    def call(m):
        fn, arg = m.group(1), m.group(2)
        vals = [r[at[arg]] for r in rows if r[at[arg]] is not None] if arg else rows
        v = _agg(fn, vals)
        return "None" if v is None else repr(float(v))
    expr = _CALL.sub(call, text)

    def least(*xs):
        xs = [x for x in xs if x is not None]
        return min(xs) if xs else None
    try:
        return eval(expr, {"__builtins__": {}}, {"abs": lambda x: None if x is None else abs(x), "least": least, "None": None})  # noqa: S307
    except (TypeError, ZeroDivisionError):
        return None


def engine_answer(body):
    """POST /api/v1/pivot/inline as the engine answers the console's asks (see above)."""
    names = [f["name"] for f in body.get("fields") or []]
    at = {n: i for i, n in enumerate(names)}
    view = body.get("view") or {}
    rd, cd = (view.get("rows") or [None])[0], (view.get("columns") or [None])[0]
    rows = [list(r) for r in body.get("rows") or []]

    def member(r, dim):
        return None if dim is None else (r[at[dim]] if r[at[dim]] is not None else "(blank)")

    for dim, f in (view.get("filters") or {}).items():
        keep_in, keep_out = (f.get("in"), f.get("notIn")) if isinstance(f, dict) else (f, None)
        rows = [r for r in rows if (not keep_in or member(r, dim) in keep_in) and (not keep_out or member(r, dim) not in keep_out)]

    def figures(rs):
        return {m["as"]: _measure(m["measure"], rs, at) for m in view.get("values") or []}

    kept = {}
    for dim in (rd, cd):
        t = (view.get("top") or {}).get(dim) if dim else None
        if not t:
            continue
        ms = sorted({member(r, dim) for r in rows}, key=str)
        if len(ms) <= t["n"]:
            continue
        fig = {m: figures([r for r in rows if member(r, dim) == m]).get(t["by"]) for m in ms}
        ranked = sorted(range(len(ms)), key=lambda i: (-(fig[ms[i]] if isinstance(fig[ms[i]], (int, float)) else float("-inf")), i))
        kept[dim] = {ms[i] for i in ranked[:t["n"]]}

    def shown(r, dim):
        m = member(r, dim)
        return OTHERS if dim in kept and m not in kept[dim] else m

    def key(dim, m):
        return "*" if dim is None or m is None else f"{dim}={m}"

    groups = {}
    for r in rows:
        for rk in {key(rd, shown(r, rd)), "*"}:
            for ck in {key(cd, shown(r, cd)), "*"}:
                groups.setdefault(f"{rk}|{ck}", []).append(r)
    cells = {k: {"values": figures(g), "members": len(g)} for k, g in groups.items()}
    grand = dict(cells.get("*|*", {}).get("values", {}))
    for m in view.get("values") or []:
        if m.get("showAs") == "pct-grand":
            for c in cells.values():
                v, base = c["values"][m["as"]], grand.get(m["as"])
                c["values"][m["as"]] = None if v is None or not base else v / base

    def axis(dim, pos):
        seen = sorted({k.split("|")[pos] for k in cells} - {"*"}, key=lambda x: x.split("=", 1)[1])
        out = [{"key": k, "kind": "node", "value": k.split("=", 1)[1], "depth": 1} for k in seen if k.split("=", 1)[1] != OTHERS]
        if any(k.split("=", 1)[1] == OTHERS for k in seen):
            out.append({"key": f"{dim}={OTHERS}", "kind": "others", "value": None, "depth": 1})
        return out + [{"key": "*", "kind": "grand", "value": None, "depth": 0}]

    return {"source": "inline", "rows": axis(rd, 0), "columns": axis(cd, 1), "cells": cells}
