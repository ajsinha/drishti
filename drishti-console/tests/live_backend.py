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

"""A stand-in server for the live tables (the grid's live mode over the Data Protocol): the trade blotter's schema as GET /api/v1/rdp/tables/trade
gives it (its write rules for a person who may edit), a page (POST .../query, one record whole when the filter fixes the key), the minimal change
stream (msg patches and gap, pushed by the test), a write with the server's version check (409 naming who changed it) answered held for four
eyes, and the write-back's change list. Synthetic trades only; the real rules are the server's (LiveTablesAdapterTest). Used by
test_live_grid_browser.py."""
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
        self.order = {"field": "TRADE_ID", "descending": False}   # the declaration's own order (the trade blotter's: newest first)
        self.page = None                                  # the most rows a snapshot gives (None: all): fewer makes the viewer paged
        self.raw_reads = 0
        self.changes = []                                 # the write-back's change list (the tray, a record's history)
        self.queue = None
        self.loop = None
        self.backlog = []

    # -- what a test does to the server ------------------------------------------------------------------------------------------------------
    def schema(self) -> dict:
        types = {"number": "number", "amount": "number"}
        cols = [{"name": c["field"], "label": c["label"], "type": types.get(c.get("type"), "string"), **({"fmt": c["format"]} if c.get("format") else {})}
                for c in self.columns()]
        s = {"name": "trade", "title": "Trade blotter", "key": ["TRADE_ID"], "rowVersion": "TRADE_VERSION", "changedBy": "UPDATED_BY",
             "changedAt": "SOURCE_UPDATE_TIME", "columns": cols, "view": {"sort": [{"col": self.order["field"], "dir": "desc" if self.order["descending"] else "asc"}],
                                                                        "subtotals": ["QUANTITY"]},
             "profile": {"kind": "trade", "links": [{"label": "Explain", "href": "/explain/trade/{TRADE_ID}"}, {"label": "VaR", "href": "/var?scope=book:{PORTFOLIO}"},
                                                    {"label": "Limits", "href": "/limits?book={PORTFOLIO}"}]},
             "tray": True, "live": {"state": "up"}, "version": {"epoch": "e1", "seq": self.seq}}
        off = getattr(self, "excludes", None)
        if off:                                                     # the source systems switched off that feed the kind
            s["excludes"] = list(off)
        if not self.editable:
            s["readOnly"] = "your role holds no edit power"
        if self.editable:
            s["write"] = {"columns": {f["bind"]: {k: v for k, v in (("type", f["type"]), ("min", f["min"]), ("options", [o["value"] for o in f["options"]]))
                                                 if v not in (None, [])} for f in FIELDS if f["bind"] != "TRADE_ID"}, "reason": "optional"}
        return s

    def columns(self) -> list:
        """The declaration's columns; ordered by a field it does not show (newest first: the update time), that column too, as the
        trade blotter declares it."""
        f = self.order["field"]
        return COLUMNS if any(c["field"] == f for c in COLUMNS) else COLUMNS + [{"field": f, "label": "Updated", "type": "datetime"}]

    def push(self, upserts=(), removes=(), kind="rows", top=(), count_only=False) -> None:
        """Sends one stream frame from the test's thread: a patch of these records (already changed in ``docs``: every field of each), or a gap
        (the reader resyncs); ``top``: the rows a viewer at the top of a newest-first order puts on top; ``count_only``: only the total moved."""
        self.seq += 1
        if kind == "rows":
            data = {"table": "trade", "total": len(self.docs)}
            if not count_only:
                data["update"] = [copy.deepcopy(self.docs[i]) for i in upserts]
                data["remove"] = [[r] for r in removes]
            if top:
                data["top"] = [[t] for t in top]
            ev = ("msg", {"t": "msg", "table": "trade", "epoch": "e1", "seq": self.seq, "data": data})
        else:
            ev = ("gap", {"t": "gap", "table": "trade", "recoverable": False, "reason": "more than 5000 records changed before they could be sent"})
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
    @staticmethod
    def _page(docs, names, total) -> dict:
        return {"columns": [{"name": n} for n in names], "values": [[copy.deepcopy(d.get(n)) for n in names] for d in docs], "total": total,
                "version": {"epoch": "e1", "seq": 0}}

    async def _send(self, method, path, ident=None, **kw):
        if path == "/rdp/tables/trade" and method == "GET":
            return self.schema()
        if path == "/rdp/tables/trade/query":
            body = kw.get("json") or {}
            f = body.get("filter") or {}
            if f.get("op") == "==" and (f.get("args") or [{}])[0].get("col") == "TRADE_ID":     # one record whole: every field
                self.raw_reads += 1
                d = self.docs.get(str(f["args"][1].get("lit")))
                return self._page([d] if d else [], sorted(d) if d else ["TRADE_ID"], 1 if d else 0)
            sort = (body.get("sort") or [{"col": self.order["field"], "dir": "desc" if self.order["descending"] else "asc"}])[0]
            rows = sorted(self.docs.values(), key=lambda t: (str(t.get(sort["col"])), t["TRADE_ID"]), reverse=sort.get("dir") == "desc")
            off = int(body.get("offset") or 0)
            cut = rows[off:off + (self.page or int(body.get("limit") or 500))]
            return self._page(cut, [c["field"] for c in self.columns()], len(rows))
        if path.startswith("/rdp/changes/") and path.endswith("/window"):
            self.windows.append(kw.get("json"))
            return {}
        if path == "/rdp/tables/trade/write":
            return self.write(kw["json"])
        if path.startswith("/changes"):
            return {"changes": [c for c in self.changes if "mine=true" not in path or c["by"] == "me"]}
        return await super()._send(method, path, ident, **kw)

    def write(self, change: dict) -> dict:
        if not self.editable:
            raise BackendError(403, "rdp.write.forbidden", "you may not change trade: your role holds no edit power")
        entity = change["key"][0]
        self.edits.append({"entity": entity, "basedOn": change.get("basedOn"), "reason": change.get("reason", ""),
                           "items": [{"field": k, "from": (change.get("from") or {}).get(k), "to": v} for k, v in (change.get("set") or {}).items()]})
        d = self.docs[entity]
        if d["TRADE_VERSION"] != change.get("basedOn"):
            raise BackendError(409, "rdp.write.conflict", f"trade {entity} is at version {d['TRADE_VERSION']}, changed by {d['UPDATED_BY']} at "
                               f"{d['SOURCE_UPDATE_TIME']}; you edited version {change.get('basedOn')}: open it again and edit from what is there")
        item = {"id": change["id"], "kind": "trade", "entity": entity, "state": "awaiting", "needed": 1, "by": "me", "at": "2026-11-14T10:00:00",
                "reason": change.get("reason", ""), "items": self.edits[-1]["items"] and [{"path": i["field"], "from": i["from"], "to": i["to"]} for i in self.edits[-1]["items"]]}
        self.changes.insert(0, item)
        return {"change": change["id"], "state": "held", "message": "waiting for approval (1 needed)"}

    async def sse(self, path, ident=None, opened=None):
        if not path.startswith("/rdp/changes?table=trade"):
            async for pair in super().sse(path, ident, opened):
                yield pair
            return
        self.loop = asyncio.get_running_loop()
        self.queue = asyncio.Queue()
        for ev in self.backlog:
            self.queue.put_nowait(ev)
        self.backlog = []
        while True:
            name, frame = await self.queue.get()
            yield name, json.dumps(frame)
