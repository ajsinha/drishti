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

"""Figures of rows the console holds, computed by the server's pivot engine (docs/architecture/ARCHITECTURE.md section 5: the server
computes, the console displays). The console has read the rows as the person (a search, raw documents, a field's history); it sends them
to ``POST /api/v1/pivot/inline`` with the measures it needs (``sum(MTM)``, ``median(S)``, ``stddev_pop(H)``, a formula of aggregates
such as ``median(TO) - median(FROM)``) and reads the engine's cells back. The engine is the one that aggregates every pivot cell
(drishti-bicompute: compensated sums, exact medians, Welford's deviations); nothing here adds up a figure.

``ask`` returns :class:`Figures`: ``at(row, column)`` is a cell's figures by their ``as`` names (``None`` for the total along an axis),
``members()`` the members of the rows in the engine's order. With no rows nothing is asked and every figure is None."""
from __future__ import annotations

import math
import re
from typing import Any, Iterable

MAX_ROWS = 100_000                    # the engine's most inline rows (PivotController.INLINE_MAX_ROWS)
FIELD = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
OTHERS = "\u0000Others"
BLANK = "(blank)"                     # the member of rows with no value of the dimension (CellKey.BLANK)


def num(v: Any) -> float | None:
    """A row's value as the engine reads a number: a finite number, else nothing (a masked or missing value is left out)."""
    if isinstance(v, bool) or v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


class Figures:
    """The engine's answer, by member: ``at(row, column)`` (None is the total along that axis), ``get(name, row, column)``."""

    def __init__(self, answer: dict | None):
        self.answer = answer or {}
        cells = self.answer.get("cells") or {}
        self._rows: list = []
        self._at: dict[tuple, dict] = {}

        def member(e: dict) -> Any:
            kind = e.get("kind")
            return None if kind == "grand" else OTHERS if kind == "others" else e.get("value")

        heads = [e for e in self.answer.get("rows") or [] if e.get("kind") in ("node", "grand", "others")]
        cols = [e for e in self.answer.get("columns") or [] if e.get("kind") in ("node", "grand", "others")] or [{"key": "*", "kind": "grand"}]
        for r in heads:
            if r.get("kind") == "node":
                self._rows.append(r.get("value"))
            for c in cols:
                cell = cells.get(f"{r.get('key')}|{c.get('key')}")
                if cell is not None:
                    self._at[(member(r), member(c))] = cell
        if not heads and "*|*" in cells:
            self._at[(None, None)] = cells["*|*"]

    def at(self, row: Any = None, column: Any = None) -> dict:
        """A cell's figures by their ``as`` names ({} when the engine has no such cell)."""
        return dict((self._at.get((row, column)) or {}).get("values") or {})

    def get(self, name: str, row: Any = None, column: Any = None) -> float | None:
        v = self.at(row, column).get(name)
        return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None

    def members(self) -> list:
        """The row members the engine answered (its order; Others and the total left out)."""
        return list(self._rows)


def body(fields: Iterable[tuple[str, str]], rows: list[list], values: list[dict], by: str | None = None, across: str | None = None,
         top: dict | None = None, filters: dict | None = None) -> dict:
    """The request: the fields ``(name, string|number)``, the rows (a number as :func:`num` reads it, a member as text), the measures
    ``{measure, as[, showAs]}`` and the arrangement (one level of rows ``by`` and of columns ``across``, a top N, filters)."""
    fields = [{"name": n, "type": t} for n, t in fields]
    for f in fields:
        if not FIELD.match(f["name"]) or f["type"] not in ("string", "number"):
            raise ValueError(f"an inline field is letters, digits and _, a string or a number: {f}")
    kinds = [f["type"] for f in fields]
    out_rows = [[(num(v) if k == "number" else (None if v is None or v == "" else str(v))) for k, v in zip(kinds, r)] for r in rows]
    view: dict[str, Any] = {"rows": [by] if by else [], "columns": [across] if across else [], "values": values,
                            "expandTo": {"rows": 1, "columns": 1}}
    if top:
        view["top"] = top
    if filters:
        view["filters"] = filters
    return {"fields": fields, "rows": out_rows, "view": view}


async def ask(backend, me, fields: Iterable[tuple[str, str]], rows: list[list], values: list[dict], by: str | None = None,
              across: str | None = None, top: dict | None = None, filters: dict | None = None) -> Figures:
    """The engine's figures of ``rows`` (``POST /api/v1/pivot/inline`` as the person; a refusal raises the server's BackendError, the
    pivot engine switched off among them). No rows: nothing is asked, every figure is None. More rows than the engine takes: ValueError."""
    if not rows:
        return Figures(None)
    if len(rows) > MAX_ROWS:
        raise ValueError(f"the engine takes at most {MAX_ROWS} rows inline ({len(rows)} were read)")
    return Figures(await backend.pivot("POST", "/inline", me, body(fields, rows, values, by, across, top, filters)))
