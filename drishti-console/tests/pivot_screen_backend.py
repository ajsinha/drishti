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

"""A stand-in server for the pivot screen (BI_GUIDE.md) in a real browser: synthetic trades only. The engine's answers come from the
chart tests' stand-in (``inline_answer``: one level of rows and of columns, each cell from its own rows; the real figures are proved by
PivotApiTest and RowsProviderTest), shaped as /api/v1/pivot/query answers them. Used by test_pivot_screen_browser.py and pivot_shots.py."""
from core.backend import BackendError
from table_chart_backend import ChartBackend, inline_answer

FIELDS = [("DESK", "string"), ("CURRENCY", "string"), ("PRODUCT", "string"), ("TRADE_DATE", "date"), ("NOTIONAL", "number"), ("PNL", "number")]
NUMBER_FUNCS = ["sum", "count", "count_distinct", "avg", "wavg", "min", "max", "median", "percentile", "var_samp", "stddev_samp"]
DESKS = ["Rates", "FX", "Credit", "Equities"]
CCY = ["USD", "EUR", "GBP", "JPY", "CAD"]
PRODUCTS = ["IRS", "FX Forward", "CDS", "Equity Swap", "Bond"]
TRADES = [[DESKS[i % 4], CCY[(i * 7) % 5], PRODUCTS[(i * 3) % 5], f"2026-{(i % 9) + 1:02d}-{(i % 27) + 1:02d}",
           float(1_000_000 * ((i * 37) % 50 + 1)), float(((i * 7919) % 2001) - 1000) * 1_000] for i in range(400)]


class PivotBackend(ChartBackend):
    """The chart tests' stand-in, plus the pivot screen's calls: sources, a catalogue, the estimate and the query of one source."""

    async def explain_number(self, method, path, ident, body=None, **params):
        """The one Explain (/api/v1/explain): the Pivot's Why card asks kind pivot with the view in the body; answered as the server
        words a pivot cell's difference (PivotCellExplainer), its why part as words only (the steps' waterfall is the server's panels)."""
        if path == "/kinds":
            return {"kinds": [{"kind": "pivot", "measures": ["cell"]}]}
        self.asked.append(("explain", dict(body or {}, **params)))
        return {"kind": "pivot", "measure": "cell", "subject": "trade", "title": "count() of trade", "text": "30", "unit": "", "asOf": "2026-10-30",
                "origin": "computed by the pivot engine in the server for you, from the rows you may see",
                "why": {"available": False, "note": "Rates minus FX is 30 by DESK, member by member."},
                "checks": [{"ok": True, "text": "the steps add up to the difference"}]}

    async def pivot(self, method, path, ident, body=None, raw=False, **params):
        if path == "/sources":
            return {"sources": [{"source": "trade", "title": "Trades", "provider": "tabular"}]}
        if path.startswith("/catalogue"):
            # the field list's metadata as the server's catalogue gives it (CatalogueMeta): group, folder, hidden, the functions valid per
            # field, one hierarchy (Organisation: Desk › Product, its level columns hidden) and the show-as forms
            fns = {"number": NUMBER_FUNCS, "string": ["count", "count_distinct"], "date": ["count", "count_distinct"]}
            return {"source": "trade", "title": "Trades", "provider": "tabular", "date": "2026-10-30", "dates": ["2026-10-29", "2026-10-30"],
                    "fields": [{"name": n, "type": t, "label": n.replace("_", " ").title(), "unit": "money" if t == "number" else "",
                                "group": "hierarchy" if n in ("DESK", "PRODUCT") else "field", "hierarchy": "ORG" if n in ("DESK", "PRODUCT") else None,
                                "folder": "Figures" if t == "number" else None, "hidden": n in ("DESK", "PRODUCT"), "functions": fns[t]} for n, t in FIELDS],
                    "measures": [], "masked": 0,
                    "hierarchies": [{"id": "ORG", "title": "Organisation", "levels": [{"field": "DESK", "label": "Desk"}, {"field": "PRODUCT", "label": "Product"}],
                                     "columns": []}],
                    "showAs": [{"id": "pct-grand", "label": "% of grand total", "needs": None}, {"id": "pct-row", "label": "% of row total", "needs": None},
                               {"id": "rank", "label": "Rank among siblings", "needs": None}, {"id": "diff", "label": "Difference from previous", "needs": "dimension"}]}
        if path == "/estimate":
            return {"rows": len(TRADES), "cells": 40, "bytes": 0, "route": "stand-in", "confirm": False}
        if path == "/views" and method == "GET":                     # the saved views: u/desk shared with everyone, and the ones saved here
            return {"views": [{"id": "u/desk", "owner": "u", "name": "desk", "version": 2, "sharedWith": ["role:risk"],
                               "view": {"source": "trade", "rows": ["DESK"], "columns": [], "values": [{"measure": "count()", "as": "count()"}]}}]
                    + list(getattr(self, "saved", {}).values())}
        if path == "/views" and method == "POST":
            self.saved = getattr(self, "saved", {})
            owner = getattr(ident, "user", None) or "ash"
            sv = {"id": owner + "/" + body["name"], "owner": owner, "name": body["name"], "version": 1, "sharedWith": body.get("sharedWith") or [],
                  "view": body["view"]}
            self.saved[sv["id"]] = sv
            self.asked.append(("views", body))
            return sv
        if path.startswith("/views/") and method == "GET" and path[len("/views/"):] in getattr(self, "saved", {}):
            return self.saved[path[len("/views/"):]]
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
                      "cellCount": len(a["cells"]), "exactness": "exact", "computed": "2026-10-30T18:00:00Z",
                      "computedAs": self.computed_as(body)})
            self.asked.append((path[1:], body))
            return a
        if path == "/inline":
            return await super().pivot(method, path, ident, body=body, raw=raw, **params)
        live = await self._switches(method, path, ident, body, params)
        if live is not None:
            return live
        raise BackendError(404, "DRS-1001", "no such pivot call in the stand-in")

    # ---- the screen's switches, live pivots and cell comments (BI_GUIDE.md) --------------------------------------------------------
    asked: list = []
    comments: list = []
    ended: list = []

    @staticmethod
    def computed_as(body):
        """The answer's computedAs as the engine words it, for the options asked (the stand-in has no sketches or rollups)."""
        exact = (body or {}).get("exact", "auto")
        word = "exact" if exact is True else "approximate" if exact is False else "auto"
        words = ["exact"] if exact is not False else ["≈ from a sketch (Distinct)"]
        if (body or {}).get("rollups") is False:
            words.append("rollups off")
        return {"asked": {"exact": word, "progressive": (body or {}).get("progressive", True), "rollups": (body or {}).get("rollups", True)},
                "words": "; ".join(words), "exact": exact is not False, "sketches": [], "routes": [], "topChosen": []}

    async def answer(self, ident, body):
        a = await self.pivot("POST", "/query", ident, body=body)
        for k, cell in a["cells"].items():
            cell["key"] = "c:" + k
            cell["coords"] = {} if k == "*|*" else {"DESK": k.split("|")[0].split("=")[-1]}
        a["computedAs"] = self.computed_as(body)
        return a

    async def _switches(self, method, path, ident, body, params):
        if path.startswith("/provenance/"):
            row = str(params.get("cell", "*|*")).split("|")[0]
            coords = {} if row == "*" else {"DESK": row.split("=")[-1]}
            return {"id": "stand-in-1", "source": "trade", "date": "2026-10-30", "version": "v1", "provider": "tabular", "filters": "none",
                    "queries": 1, "sets": 1, "fromCache": 0, "computeMs": 1, "functions": [],
                    "cell": {"coords": coords, "values": {}, "members": 100}}
        if path == "/live" and method == "POST":
            self.asked.append(("live", body))
            return {"id": "lp-1", "node": "", "answer": await self.answer(ident, body), "options": {"exact": True}}
        if path.startswith("/live/") and method == "DELETE":
            self.ended.append(path)
            return {}
        # ---- the What-if and Functions cards (drishti_pivot-functions.js), answered as the engine words them ----
        if path == "/whatif" and method == "POST":
            self.asked.append(("whatif", body))
            return {"diff": {"DESK=Rates|*": {"delta": {"count()": -25}}, "DESK=FX|*": {"delta": {"count()": 0}}, "*|*": {"delta": {"count()": -25}}}}
        if path == "/functions/test" and method == "POST":
            self.asked.append(("functions/test", body))
            return {"harness": {"passed": True, "kind": "mergeable", "onePass": 0.007, "rows": 4, "nsPerRow": 2245.0, "stateBytes": 56,
                                "checks": [{"check": "one pass", "passed": True, "detail": "the figure is 0.007"}]},
                    "playground": {"steps": [{"what": "part 1 (rows 1 to 2)", "state": "[Σ=0.036 n=2, Σ=4.0 n=2]", "bytes": 56, "value": 0.008},
                                             {"what": "part 2 (rows 3 to 4)", "state": "[Σ=0.045 n=2, Σ=7.0 n=2]", "bytes": 56,
                                              "value": 0.0064285714285714285}],
                                   "final": 0.007, "onePass": 0.007, "same": True}}
        if path == "/functions" and method == "GET":
            return {"languages": {"formula": True, "sql": True, "python": False, "java": False}, "requireApproval": True,
                    "functions": [{"id": "u/wret", "version": 1, "ref": "u.wret", "language": "formula", "kind": "mergeable", "status": "private",
                                   "inputs": [{"name": "r"}, {"name": "w"}]}]}
        if path == "/views/u/desk" and method == "GET":
            return {"id": "u/desk", "version": 2, "owner": "u", "view": {"source": "trade", "rows": ["DESK"], "columns": [],
                    "values": [{"measure": "count()", "as": "count()"}]}}
        if path == "/views/u/desk/comments":
            if method == "POST":
                c = {"id": f"c-{len(self.comments) + 1}", "cell": "DESK=" + body["coords"].get("DESK", "*"), "text": body["text"], "by": "alice",
                     "at": "2026-11-03T18:00:00Z", "version": 2, "resolved": None, "older": False,
                     "cellId": "c:DESK=" + body["coords"]["DESK"] + "|*" if body["coords"].get("DESK") else "c:*|*"}
                self.comments.append(c)
                return c
            return {"id": "u/desk", "version": 2, "comments": list(self.comments), "open": len(self.comments)}
        return None

    async def sse(self, path, ident=None, opened=None, node=None):
        """A live pivot's stream: its answer, then one patch of the Rates row (its count doubled), as the engine would push it."""
        import asyncio
        import json
        a = await self.answer(ident, (self.asked[-1][1] if self.asked else {}) or {})
        yield "answer", json.dumps({"id": "lp-1", "seq": 0, "answer": a})
        await asyncio.sleep(0.3)
        k = next(x for x in a["cells"] if x.startswith("DESK=Rates"))
        cell = dict(a["cells"][k])
        cell["values"] = {m: v * 2 for m, v in cell["values"].items()}
        yield "patch", json.dumps({"id": "lp-1", "seq": 1, "kind": "patch", "cells": {k: cell}, "removed": [], "changed": 1,
                                   "computedAs": a["computedAs"], "causes": ["a load of trade for 2026-10-30"]})
        await asyncio.sleep(30)

    async def sse_post(self, path, ident, body, node=None):
        """A progressive answer: ≈ first (marked approximate), then exact."""
        import json
        self.asked.append(("progressive", body))
        a = await self.answer(ident, body)
        approx = dict(a, exactness="approximate", computedAs=dict(a["computedAs"], words="≈ from a 2% sample", exact=False),
                      progressive={"phase": "approximate"})
        yield "approximate", json.dumps(approx)
        yield "exact", json.dumps(a)
