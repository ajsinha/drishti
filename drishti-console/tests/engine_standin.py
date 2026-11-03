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


def inline_answer(body):
    """The engine's answer to POST /api/v1/pivot/inline in the stand-in (the real one is proved by PivotApiTest and RowsProviderTest): one level of
    rows and of columns, each cell's figure from its own rows, blanks left out of every function."""
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

    def axis(dim, pos):
        seen = sorted({k.split("|")[pos] for k in cells} - {"*"}, key=lambda x: x.split("=", 1)[1])
        return [{"key": k, "kind": "node", "value": k.split("=", 1)[1], "depth": 1} for k in seen] + [{"key": "*", "kind": "grand", "value": None, "depth": 0}]

    return {"source": "inline", "rows": axis(rd, 0), "columns": axis(cd, 1), "cells": cells}
