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

"""A stand-in server for the live result sets (the grid's live mode): the trade blotter's declaration as GET /api/v1/live describes it, a page
of rows, one record whole, the stream (hello, then rows or resync, pushed by the test), a stream's window, a blotter's edit with the server's
version check (409 naming who changed it) and the write-back's change list. Synthetic trades only; the real rules are the server's
(LiveSetTest). Used by test_live_grid_browser.py."""
import asyncio
import copy
import json

from core.backend import BackendError
from table_chart_backend import ChartBackend

MASK = "•••"
COLUMNS = [
    {"field": "TRADE_ID", "label": "Trade", "type": "id", "width": 120}, {"field": "TRADE_VERSION", "label": "V", "type": "number", "width": 48},
    {"field": "TRADE_STATUS", "label": "Status", "type": "status"}, {"field": "PORTFOLIO", "label": "Book"},
    {"field": "COUNTERPARTY", "label": "Counterparty"}, {"field": "QUANTITY", "label": "Quantity", "type": "amount", "format": "amount0"},
    {"field": "PRICE", "label": "Price", "type": "number", "format": "price4"}]
FIELDS = [
    {"id": "trade_id", "label": "Trade", "type": "string", "bind": "TRADE_ID", "required": False, "min": None, "max": None, "options": []},
    {"id": "price", "label": "Price", "type": "number", "bind": "PRICE", "required": False, "min": 0, "max": None, "options": []},
    {"id": "quantity", "label": "Quantity", "type": "number", "bind": "QUANTITY", "required": False, "min": 0, "max": None, "options": []},
    {"id": "status", "label": "Status", "type": "enum", "bind": "TRADE_STATUS", "required": False, "min": None, "max": None,
     "options": [{"value": v, "label": v} for v in ("NEW", "VALIDATED", "AMENDED", "CANCELLED")]}]
BOOKS = ["BOOK-A", "BOOK-B", "BOOK-C"]


def trade(i: int) -> dict:
    """One synthetic trade; the fields past the grid's columns are what the panel's Raw JSON shows and the grid does not."""
    return {"TRADE_ID": f"T-{i:03d}", "TRADE_VERSION": 1, "TRADE_STATUS": "NEW", "PORTFOLIO": BOOKS[i % 3], "COUNTERPARTY": f"CP-{i % 7}",
            "QUANTITY": 1000 * (i + 1), "PRICE": round(1 + i / 100, 4), "UPDATED_BY": "feed",
            "SOURCE_UPDATE_TIME": "2026-11-14T09:00:00Z", "BOOKING_SYSTEM": "MUREX", "LEGS": [{"LEG_ID": 1, "NOTIONAL": 1000 * (i + 1), "CCY": "USD"}],
            "TRADER_TAX_ID": MASK}


class LiveBackend(ChartBackend):
    """The stand-in server of the live grid tests. ``editable`` False is a person without the edit power (the server's readOnly says why)."""

    def __init__(self, rows: int = 30, editable: bool = True):
        super().__init__()
        self.docs = {t["TRADE_ID"]: t for t in (trade(i) for i in range(rows))}
        self.seq = 1
        self.editable = editable
        self.edits = []                                   # the edit bodies the server was sent
        self.windows = []                                 # the windows the viewer named
        self.raw_reads = 0
        self.changes = []                                 # the write-back's change list (the tray, a record's history)
        self.queue = None
        self.loop = None
        self.backlog = []

    # -- what a test does to the server ------------------------------------------------------------------------------------------------------
    def spec(self) -> dict:
        s = {"id": "trade", "title": "Trade blotter", "kind": "trade", "query": "trade", "key": "TRADE_ID", "version": "TRADE_VERSION", "changedBy": "UPDATED_BY",
             "changedAt": "SOURCE_UPDATE_TIME", "columns": COLUMNS, "groupBy": [], "subtotals": ["QUANTITY"], "sort": {"field": "TRADE_ID", "descending": False},
             "links": [{"label": "Explain", "href": "/explain/trade/{TRADE_ID}"}, {"label": "VaR", "href": "/var?scope=book:{PORTFOLIO}"},
                       {"label": "Limits", "href": "/limits?book={PORTFOLIO}"}], "retention": None, "tray": True, "module": "trading",
             "editable": self.editable, "readOnly": None if self.editable else "your role holds no edit power"}
        if self.editable:
            s.update({"fields": FIELDS, "approval": "none", "reason": "optional"})
        return s

    def shown(self, doc: dict) -> dict:
        return {"id": doc["TRADE_ID"], "version": doc["TRADE_VERSION"], "seq": self.seq, "doc": copy.deepcopy(doc)}

    def push(self, upserts=(), removes=(), kind="rows") -> None:
        """Sends one stream event from the test's thread: a rows event of these records (already changed in ``docs``), or a resync."""
        self.seq += 1
        ev = {"type": kind, "total": len(self.docs), "seq": self.seq}
        if kind == "rows":
            ev.update({"upserts": [self.shown(self.docs[i]) for i in upserts], "removes": list(removes), "countOnly": False})
        else:
            ev["reason"] = "more than 5000 records changed before they could be sent"
        if self.loop is None:
            self.backlog.append(ev)
        else:
            self.loop.call_soon_threadsafe(self.queue.put_nowait, ev)

    def tick(self, trade_id: str, **fields) -> None:
        """Changes a trade as the feed would (a new version) and streams it."""
        d = self.docs[trade_id]
        d.update(fields)
        d["TRADE_VERSION"] += 1
        d["UPDATED_BY"] = "feed"
        self.push([trade_id])

    # -- the server's calls ------------------------------------------------------------------------------------------------------------------
    async def _send(self, method, path, ident=None, **kw):
        if path == "/live":
            return {"sets": [self.spec()]}
        if path.startswith("/live/trade/rows/"):
            self.raw_reads += 1
            d = self.docs.get(path.rsplit("/", 1)[1].split("?")[0])
            if d is None:
                raise BackendError(404, "DRS-1001", "no such trade")
            return self.shown(d)
        if path.startswith("/live/trade/rows"):
            rows = sorted(self.docs.values(), key=lambda t: t["TRADE_ID"])
            return {"set": self.spec(), "live": True, "total": len(rows), "seq": self.seq, "offset": 0, "rows": [self.shown(t) for t in rows]}
        if path.endswith("/window"):
            self.windows.append(kw.get("json"))
            return {"stream": "x", "paged": not (kw.get("json") or {}).get("all")}
        if path == "/live/trade/edit":
            return self.edit(kw["json"])
        if path.startswith("/changes"):
            return {"changes": [c for c in self.changes if "mine=true" not in path or c["by"] == "me"]}
        return await super()._send(method, path, ident, **kw)

    def edit(self, body: dict) -> dict:
        if not self.editable:
            raise BackendError(403, "DRS-9300", "blotter trade is read-only for you: " + "your role holds no edit power")
        self.edits.append(body)
        d = self.docs[body["entity"]]
        if d["TRADE_VERSION"] != body["basedOn"]:
            raise BackendError(409, "DRS-9301", f"trade {body['entity']} is at version {d['TRADE_VERSION']}, changed by {d['UPDATED_BY']} at {d['SOURCE_UPDATE_TIME']}; "
                               f"you edited version {body['basedOn']}: open it again and edit from what is there")
        change = {"id": f"chg-{len(self.changes) + 1:026d}", "kind": "trade", "entity": body["entity"], "state": "awaiting", "needed": 1, "by": "me",
                  "at": "2026-11-14T10:00:00", "reason": body.get("reason", ""), "items": [{"path": i["field"], "from": i["from"], "to": i["to"]} for i in body["items"]]}
        self.changes.insert(0, change)
        return {"changes": [{"id": change["id"], "state": "awaiting", "needed": 1}]}

    async def sse(self, path, ident=None, opened=None):
        if not path.startswith("/live/trade/stream"):
            async for pair in super().sse(path, ident, opened):
                yield pair
            return
        self.loop = asyncio.get_running_loop()
        self.queue = asyncio.Queue()
        for ev in self.backlog:
            self.queue.put_nowait(ev)
        self.backlog = []
        yield "hello", json.dumps({"stream": "stream-0001-abcdef", "total": len(self.docs), "live": True})
        while True:
            ev = await self.queue.get()
            yield ev["type"], json.dumps(ev)
