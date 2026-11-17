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


"""The trading and market-data modules' landing answers as the stand-in server gives them (THIN_CONSOLE_DESIGN.md step S8a): the figures
are worked out on the server (modules/trading TradingLanding, modules/market-data's landing), as the person; the console passes each call on
and gives the answer back. These stand-ins say who is who and answer in the server's shapes, so the console's tests check what the
console does: which workspace may ask, that every call is the person's own, the View as choice kept in their settings, the first landing
by the trading role the server answers, and the blotter's grid drawn from the live set the server sends. Every call is kept (``sent``)."""
from __future__ import annotations

from conftest import live_page

BUSINESS = "2026-09-30"
SCOPES = {   # each person's scope as the server resolves it (drishti.trading.people, a trader record, or the View as choice)
    "drishti-dev-admin": {"kind": "trader", "id": "TRDR-ASHAH", "label": "A. Shah", "source": "config", "canPick": False,
                          "books": ["BOOK-RATES-1", "BOOK-RATES-2"], "desk": "DESK-RATES"},
    "kim": {"kind": "trader", "id": "TRDR-KMOREAU", "label": "K. Moreau", "source": "record", "canPick": False, "books": ["BOOK-COMM-1"],
            "desk": "DESK-COMM"},
}
ROLES = {"t.brennan": "desk-head", "drishti-dev-admin": "trader", "kim": "trader"}   # the trading role the server answers
DESKS = [{"id": "DESK-RATES", "label": "Rates", "head": "T. Brennan"}, {"id": "DESK-COMM", "label": "Commodities", "head": "R. Okafor"}]


class LandingServer:
    """The two modules' landing APIs of a stand-in server; ``store``: each person's settings on that server (their View as choice,
    ``tradingView``, which the console keeps there)."""

    def __init__(self, store: dict | None = None):
        self.sent: list[tuple] = []
        self.store = store if store is not None else {}

    def scope(self, ident) -> dict:
        user = getattr(ident, "user", "")
        view = str((self.store.get(user) or {}).get("tradingView") or "")
        if view.startswith("desk:"):
            desk = next((d for d in DESKS if d["id"] == view[5:]), {"id": view[5:], "head": ""})
            return {"kind": "desk", "id": desk["id"], "source": "choice", "canPick": True, "head": desk["head"], "books": []}
        return dict(SCOPES.get(user) or {"kind": "", "id": "", "source": "", "canPick": True, "books": []})

    async def trading(self, method, path, ident, /, body=None, **params):
        self.sent.append(("trading", method, path, getattr(ident, "user", None), tuple(getattr(ident, "roles", ()) or ()), dict(params)))
        scope = self.scope(ident)
        if path == "/role":
            return {"role": "desk-head" if scope["kind"] == "desk" else ROLES.get(getattr(ident, "user", ""), "")}
        if path == "/me":
            out = {"scope": scope, "admin": bool(getattr(ident, "admin", False))}
            if scope["canPick"]:
                out["options"] = {"desks": [{"id": d["id"], "label": d["label"]} for d in DESKS],
                                  "traders": [{"id": s["id"], "label": s["label"]} for s in SCOPES.values()]}
            return out
        if path == "/check":
            if not scope["kind"]:
                return {"ok": False, "reason": "pick a trader or a desk to view as first"}
            if not {"product", "notional", "counterparty"} <= set(body or {}):
                return {"ok": False, "reason": "a check is {product, notional, counterparty, side, book}"}
            return {"ok": True, "var": {"state": "near", "before": 6_120_000, "after": 6_300_000},
                    "exposure": {"state": "near", "after": 409_200_000}, "template": {"trade": "MX-20000010"}}
        if not scope["kind"]:
            return {"available": False, "reason": "pick a trader or a desk to view as", "scope": scope}
        if path == "/blotter":
            rows = [{"title": "MX-20000001", "ref": {"kind": "trade", "id": "MX-20000001"},
                     "values": {"$.TRADE_ID": "MX-20000001", "$.TRADE_STATUS": "LIVE", "$.TRADE_DATE": BUSINESS}}]
            page = live_page({"kind": "trade", "columns": ["$.TRADE_ID", "$.TRADE_STATUS", "$.TRADE_DATE"], "rows": rows})
            return {"available": True, "businessDate": BUSINESS, "how": "trades", "rows": [{"trade": "MX-20000001", "notional": 1_000_000}],
                    "page": page}
        if path == "/traders":
            return {"available": True, "traderMasked": "raw" not in (getattr(ident, "roles", ()) or ()),
                    "rows": [{"trader": "TRDR-ASHAH", "pnl": None, "var": None}]}
        return {"available": True, "businessDate": BUSINESS, "part": path.lstrip("/")}

    async def market(self, method, path, ident, /, body=None, **params):
        self.sent.append(("market", method, path, getattr(ident, "user", None), tuple(getattr(ident, "roles", ()) or ()), dict(params)))
        if path == "/quality":
            return {"critical": [{"id": "CRV-EUR-OIS", "check": "forwards"}], "warnings": [{"id": "CRV-GBP-OIS", "check": "stale"}],
                    "cleanPct": 57.1}
        if path == "/desk":
            return {"tiles": [{"id": "ois10y"}, {"id": "slope"}, {"id": "fxvol"}, {"id": "cds"}]}
        return {"mode": "sigma", "rows": []}
