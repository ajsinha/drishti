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

"""A stand-in server for Chart any table (RACHANA_REFERENCE.md) in a real browser: synthetic data only. A trade view whose `flows` table
has dates, currencies, desks, amounts, a linked trade column and a `Limit` column masked for the reader; a `big` table of 600 rows
over 400 counterparties; a netting set whose `trades` table offers a Pivot tab (so its raw records are read); a search of trades
whose MTM is masked and whose pack offers the search pivot (Chart all N); and the history fixtures. Used by
test_table_chart_browser.py and table_chart_shots.py."""
import asyncio
import copy
import json
import threading
import time

from conftest import CONSOLE, FIXTURES
from core.backend import BackendError
from engine_standin import inline_answer  # noqa: F401  (pivot_screen_backend imports it from here)
from test_live_tabs_browser import TickingBackend, _free_port

MASK = "•••"
CCY = ["USD", "EUR", "GBP", "JPY"]
DESKS = ["Rates", "FX", "Credit"]


def _amount(i: int) -> float:
    return round(((i * 7919) % 2000 - 600) * 1250.5, 2)


def _text(v: float) -> str:
    return f"{v:,.2f}".replace("-", "−")


def flows_panel(n: int = 40) -> dict:
    rows = []
    for i in range(n):
        day = f"2027-{(i % 12) + 1:02d}-{(i * 3) % 27 + 1:02d}"
        amt = _amount(i)
        rows.append({"cells": [
            {"label": "Pay date", "text": day},
            {"label": "Currency", "text": CCY[i % 4]},
            {"label": "Desk", "text": DESKS[i % 3]},
            {"label": "Amount", "text": _text(amt)},
            {"label": "PV", "text": _text(amt * 0.93)},
            {"label": "Limit", "text": MASK},
            {"label": "Trade", "text": f"TR-{1000 + i}", "link": {"kind": "trade", "id": f"TR-{1000 + i}", "mnemonic": "TRD"}}],
            "highlight": False, "path": f"$.flows[{i}]"})
    return {"id": "flows", "kind": "table", "title": "Cash flows", "code": "CF", "key": "F4", "area": "main", "inferred": False,
            "data": {"columns": ["Pay date", "Currency", "Desk", "Amount", "PV", "Limit", "Trade"],
                     "numeric": [False, False, False, True, True, True, False], "rows": rows}}


def big_panel(n: int = 600) -> dict:
    rows = [{"cells": [{"label": "Counterparty", "text": f"CPTY-{i % 400:03d}"}, {"label": "Exposure", "text": _text(abs(_amount(i)))}],
             "highlight": False, "path": f"$.big[{i}]"} for i in range(n)]
    return {"id": "big", "kind": "table", "title": "Exposure by counterparty", "code": "EXP", "area": "main", "inferred": False,
            "data": {"columns": ["Counterparty", "Exposure"], "numeric": [False, True], "rows": rows}}


SPEC = {"fields": [{"name": "product", "label": "Product", "numeric": False, "fmt": None},
                   {"name": "maturity", "label": "Maturity", "numeric": False, "fmt": "date"},
                   {"name": "mtm", "label": "MTM (USD)", "numeric": True, "fmt": "signed0"}],
        "rows": ["product"], "columns": [], "values": [{"field": "mtm", "agg": "sum"}], "filters": [], "heat": False,
        "chart": None, "totals": True, "maxRows": 50000}

SEARCH_ROWS = [{"ref": {"kind": "trade", "id": f"TR-{2000 + i}"}, "title": f"TR-{2000 + i}", "status": None,
                "values": {"desk": DESKS[i % 3], "currency": CCY[i % 4], "notional": 1_000_000 * ((i * 37) % 50 + 1), "mtm": MASK,
                           "tradeDate": f"2026-{(i % 9) + 1:02d}-15"}} for i in range(30)]


class ChartBackend(TickingBackend):
    """The stand-in server of the chart tests: quiet live views, a search, the search pivot and panel records."""

    def __init__(self):
        super().__init__()
        self.pivot_calls = []
        self.inline_calls = []                             # the shown rows the chart asked the engine to aggregate
        self.record_calls = []

    async def stream(self, kind, id_, ident=None, opened=None):
        yield "view", json.dumps({"provenance": {"generation": 1}})
        while True:
            await asyncio.sleep(3600)

    async def view(self, kind, id_, user, controls=None, panels=None):
        if (kind, id_) == ("trade", "CHART-1"):
            vm = json.loads((FIXTURES / "view_trade_IRS-48213.json").read_text())
            vm["ref"] = {"kind": "trade", "id": "CHART-1"}
            vm.setdefault("provenance", {})["sutra"] = "trade-overview"
            vm["panels"] = [flows_panel(), big_panel(), {**copy.deepcopy(flows_panel(1)), "id": "one", "title": "One flow"}]
            return vm
        vm = await super().view(kind, id_, user, controls, panels)
        if (kind, id_) == ("netting-set", "NS-NORTH-01"):
            vm["provenance"]["sutra"] = "netting-set"
            trades = next(p for p in vm["panels"] if p["id"] == "trades")
            trades["data"]["pivot"] = {**copy.deepcopy(SPEC), "source": "panel/netting-set/trades/NS-NORTH-01"}
        return vm

    async def pivot(self, method, path, ident, body=None, raw=False, **params):
        """The pivot engine as the chart's Chart all N asks it: a source's fields, and one level of rows aggregated (the engine's answer shape)."""
        if path == "/catalogue":
            source = params.get("source", "")
            self.record_calls.append(source)
            if source.startswith("panel/"):
                return {"source": source, "fields": [{"name": f["name"], "label": f.get("label", f["name"]), "type": "string"} for f in SPEC["fields"]]}
            return {"source": source, "fields": [{"name": n, "label": n, "type": "string"} for n in ("desk", "currency", "notional")]}
        if path == "/query":
            view = (body or {}).get("view") or {}
            self.pivot_calls.append(view)
            x = (view.get("rows") or [None])[0]
            keys = DESKS if x == "desk" else CCY
            rows = [{"key": f"{x}={k}", "kind": "node", "value": k, "depth": 1, "path": [[x, k]]} for k in keys]
            rows.append({"key": "*", "kind": "grand", "value": None, "depth": 0, "path": []})
            cells = {}
            for i, k in enumerate(keys):
                cells[f"{x}={k}|*"] = {"values": {v["as"]: float((i + 1) * 100_000_000) for v in view.get("values") or []}}
            return {"source": view.get("source"), "rows": rows, "columns": [{"key": "*", "kind": "grand"}], "cells": cells}
        if path == "/inline":
            self.inline_calls.append(len((body or {}).get("rows") or []))
            return inline_answer(body or {})
        raise BackendError(404, "DRS-1001", "no such pivot call in the stand-in")

    async def search(self, q, ident=None):
        if not q.upper().startswith("TRD"):
            raise BackendError(400, "DRS-4001", "unknown mnemonic")
        return {"kind": "trade", "mnemonic": "TRD", "columns": ["desk", "currency", "notional", "mtm", "tradeDate"],
                "labels": {"desk": "Desk", "currency": "Currency", "notional": "Notional", "mtm": "MTM", "tradeDate": "Trade date"},
                "matched": 500, "scanned": 500, "elapsedMs": 3, "partial": False, "rows": copy.deepcopy(SEARCH_ROWS),
                "pivot": {"source": "trade", "fields": ["desk", "currency", "notional"]}}



def serve(backend=None):
    """Starts the console in this process against the stand-in server; returns (url, stop)."""
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend or ChartBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started

    def stop():
        server.should_exit = True
        thread.join(10)
    return f"http://127.0.0.1:{port}", app.state.backend, stop
