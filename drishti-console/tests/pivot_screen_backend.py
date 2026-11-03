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

"""A stand-in server for the pivot screen (PIVOT_GUIDE.md) in a real browser: synthetic trades only. The engine's answers come from the
chart tests' stand-in (``inline_answer``: one level of rows and of columns, each cell from its own rows; the real figures are proved by
PivotApiTest and RowsProviderTest), shaped as /api/v1/pivot/query answers them. Used by test_pivot_screen_browser.py and pivot_shots.py."""
from core.backend import BackendError
from table_chart_backend import ChartBackend, inline_answer

FIELDS = [("DESK", "string"), ("CURRENCY", "string"), ("PRODUCT", "string"), ("TRADE_DATE", "date"), ("NOTIONAL", "number"), ("PNL", "number")]
DESKS = ["Rates", "FX", "Credit", "Equities"]
CCY = ["USD", "EUR", "GBP", "JPY", "CAD"]
PRODUCTS = ["IRS", "FX Forward", "CDS", "Equity Swap", "Bond"]
TRADES = [[DESKS[i % 4], CCY[(i * 7) % 5], PRODUCTS[(i * 3) % 5], f"2026-{(i % 9) + 1:02d}-{(i % 27) + 1:02d}",
           float(1_000_000 * ((i * 37) % 50 + 1)), float(((i * 7919) % 2001) - 1000) * 1_000] for i in range(400)]


class PivotBackend(ChartBackend):
    """The chart tests' stand-in, plus the pivot screen's calls: sources, a catalogue, the estimate and the query of one source."""

    async def pivot(self, method, path, ident, body=None, raw=False, **params):
        if path == "/sources":
            return {"sources": [{"source": "trade", "title": "Trades", "provider": "tabular"}]}
        if path.startswith("/catalogue"):
            return {"source": "trade", "title": "Trades", "provider": "tabular", "date": "2026-10-30", "dates": ["2026-10-29", "2026-10-30"],
                    "fields": [{"name": n, "type": t, "label": n.replace("_", " ").title()} for n, t in FIELDS], "measures": [], "masked": 0}
        if path == "/estimate":
            return {"rows": len(TRADES), "cells": 40, "bytes": 0, "route": "stand-in", "confirm": False}
        if path == "/views" and method == "GET":
            return {"views": []}
        if path in ("/query", "/expand"):
            view = dict((body or {}).get("view") or body or {})
            names = [n for n, _ in FIELDS]
            for k in ("rows", "columns"):
                view[k] = [str(d).split(":")[0] for d in view.get(k) or []][:1]
            values = [{"measure": v["measure"], "as": v.get("as") or v["measure"]} for v in view.get("values") or []]
            a = inline_answer({"fields": [{"name": n} for n in names], "rows": TRADES, "view": {**view, "values": values}})
            rd, cd = (view["rows"] or [None])[0], (view["columns"] or [None])[0]
            for r in a["rows"]:
                r["label"] = "Total" if r["kind"] == "grand" else r["value"]
                r["expandable"] = False
            for c in a["columns"]:
                c["label"] = "Total" if c["kind"] == "grand" else c["value"]
            a.update({"source": "trade", "title": "Trades", "date": view.get("date") or "2026-10-30", "view": (body or {}).get("view") or body,
                      "rowDimensions": [rd] if rd else [], "columnDimensions": [cd] if cd else [], "provenance": "stand-in-1",
                      "measures": [{"id": v["measure"], "label": v["as"], "kind": "holistic" if v["measure"].startswith("median") else "mergeable",
                                    "exact": True} for v in values],
                      "cellCount": len(a["cells"]), "exactness": "exact", "computed": "2026-10-30T18:00:00Z"})
            return a
        if path == "/inline":
            return await super().pivot(method, path, ident, body=body, raw=raw, **params)
        raise BackendError(404, "DRS-1001", "no such pivot call in the stand-in")
