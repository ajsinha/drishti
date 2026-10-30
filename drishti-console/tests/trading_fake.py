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

"""A stand-in server for Drishti Trading's landings (test_trading_landing.py, test_edition.py, test_edition_trading_browser.py), shaped as
the server answers: search rows of ``$.FIELD`` values (masked for a role without raw, as the server masks TRADER_ID), raw records, the VaR
engine's scope, contributions, official records, history and what-if, and the exposure engine's scope and pre-deal what-if. The business
date is 2026-09-30 (FakeBackend's), the day before 2026-09-29.

The desk DESK-RATES (head T. Brennan) holds BOOK-RATES-1, -2 and -3; TRDR-ASHAH trades the first two, TRDR-KMOREAU the third. Twelve
trades MX-20000001 to MX-20000012, trade i (from 0) in BOOK-RATES-(1 + i mod 3), its MtM up 10,000 × (i + 1) since the day before (A.
Shah's P&L today: 480,000; the desk's: 780,000); MX-20000012 is cancelled. Today: MX-20000001 new, MX-20000002 amended, MX-20000004
cancelled (A. Shah's books), MX-20000003 amended (K. Moreau's). VaR: BOOK-RATES-1 6.12m of 7.4m (83%), -2 3.0m of 5m (60%), -3 9.0m of 8m
(113%, a breach); the desk 15m of 20m; the official record of 2026-09-25 is missing. CP-ALDERSHOT's peak PFE 401.5m of a 420m limit."""
from __future__ import annotations

import re

from conftest import FakeBackend
from core import asof
from core.backend import BackendError

BOOKS = ["BOOK-RATES-1", "BOOK-RATES-2", "BOOK-RATES-3"]
CPS = ["CP-ALDERSHOT", "CP-NORTHPLAINS", "CP-MERIDIAN"]
PRODUCTS = ["IR_SWAP", "SWAPTION", "IR_SWAP"]
TRADERS = {"TRDR-ASHAH": {"NAME": "A. Shah", "DESK": "DESK-RATES", "DESK_NAME": "Rates · Swaps and options", "TRADE_COUNT": 8,
                          "BOOKS": [{"ID": "BOOK-RATES-1"}, {"ID": "BOOK-RATES-2"}]},
           "TRDR-KMOREAU": {"NAME": "K. Moreau", "DESK": "DESK-RATES", "DESK_NAME": "Rates · Swaps and options", "TRADE_COUNT": 4,
                            "BOOKS": [{"ID": "BOOK-RATES-3"}]}}
DESKS = {"DESK-RATES": {"NAME": "Rates · Swaps and options", "HEAD": "T. Brennan", "BOOKS": [{"ID": b} for b in BOOKS]},
         "DESK-COMM": {"NAME": "Commodities", "HEAD": "R. Okafor", "BOOKS": []}}
BOOK_VAR = {"BOOK-RATES-1": (6_120_000, 7_400_000), "BOOK-RATES-2": (3_000_000, 5_000_000), "BOOK-RATES-3": (9_000_000, 8_000_000)}
OFFICIAL_DAYS = ["2026-09-24", "2026-09-28", "2026-09-29", "2026-09-30"]          # 2026-09-25 has none


def trade(i: int, day: str) -> dict:
    tid = f"MX-{20000001 + i}"
    book = BOOKS[i % 3]
    mtm = 1_000_000 * (i + 1) * (-1) ** i + (0 if day == "2026-09-30" else -10_000 * (i + 1))
    return {"TRADE_ID": tid, "PORTFOLIO": book, "DESK": "DESK-RATES", "COUNTERPARTY": CPS[i % 3], "PRODUCT_TYPE": PRODUCTS[i % 3],
            "PRODUCT_GROUP": "Rates", "TRADE_STATUS": "CANCELLED" if i == 11 else ("AMENDED" if i % 2 else "VALIDATED"),
            "TRADER_ID": "TRDR-KMOREAU" if book == "BOOK-RATES-3" else "TRDR-ASHAH", "BUY_SELL_CODE": "B" if i % 2 == 0 else "S",
            "NOTIONAL": 50_000_000.0 * (i + 1), "DV01": -1000 * (i + 1), "CURRENCY": "USD", "MTM": mtm, "MATURITY_DATE": "2036-09-30"}


EVENTS = [(0, "NEW", "VALIDATED", "1", "13:52"), (1, "AMEND", "AMENDED", "3", "12:10"), (3, "AMEND", "CANCELLED", "2", "11:41"),
          (2, "AMEND", "AMENDED", "2", "10:05")]


def _rows(kind, items):
    return {"kind": kind, "matched": len(items), "scanned": len(items), "partial": False,
            "rows": [{"ref": {"kind": kind, "id": i}, "title": t, "values": {f"$.{k}": v for k, v in vals.items()}} for i, t, vals in items]}


class TradingBackend(FakeBackend):
    """The servers' answers; ``previous=False`` holds no valuations the day before; ``paths=False`` answers the exposure engine's exact
    what-if 503 (its Python engine not running), so the check falls back to the add-on proxy; ``history='replay'`` gives no official
    history."""

    def __init__(self, previous: bool = True, paths: bool = True, history: str = "official", carried: str | None = None):
        super().__init__()
        self.previous, self.paths, self.history = previous, paths, history
        self.carried = carried                    # every date's valuations are of this day (a lake not loaded since)
        self.asked: list = []

    @staticmethod
    def _raw_role(ident) -> bool:
        roles = tuple(getattr(ident, "roles", ()) or ())
        return "*" in roles or "admin" in roles or "author" in roles

    async def search(self, q, ident=None):
        self.asked.append((asof.current(), q))
        head = q.split()[0].upper()
        if head == "TRDR":
            desk = re.search(r"DESK = '([^']+)'", q)
            items = [(t, d["NAME"], {"NAME": d["NAME"], "DESK": d["DESK"], "TRADE_COUNT": d["TRADE_COUNT"]}) for t, d in TRADERS.items()
                     if not desk or d["DESK"] == desk.group(1)]
            return _rows("trader", items)
        if head == "DESK":
            return _rows("desk", [(d, v["NAME"], {"NAME": v["NAME"]}) for d, v in DESKS.items()])
        if head == "TVAL":
            day = asof.current() if asof.current() != "live" else "2026-09-30"
            if day == "2026-09-29" and not self.previous:
                return {**_rows("trade-valuation", []), "noData": {"businessDate": day, "latestDate": "2026-09-30"}}
            books = re.findall(r"PORTFOLIO = '([^']+)'", q)
            desk = re.search(r"DESK = '([^']+)'", q)
            floor = re.search(r"MTM <= (-?[0-9.]+)", q)
            limit = int(re.search(r"limit (\d+)", q).group(1))
            ts = [trade(i, day) for i in range(12)]
            ts = [t for t in ts if (not books or t["PORTFOLIO"] in books) and (not desk or t["DESK"] == desk.group(1))]
            matched = len(ts)
            if floor:
                ts = [t for t in ts if t["MTM"] <= float(floor.group(1))]
            ts.sort(key=lambda t: -t["MTM"])
            raw = self._raw_role(ident)
            items = [(f"TVAL-{t['TRADE_ID']}", f"TVAL-{t['TRADE_ID']}", {**{k: v for k, v in t.items() if k != "MATURITY_DATE"},
                                                                        "TRADER_ID": t["TRADER_ID"] if raw else "•••"}) for t in ts[:limit]]
            out = _rows("trade-valuation", items)
            out["matched"] = matched
            return out
        if head == "TRD":
            books = re.findall(r"PORTFOLIO = '([^']+)'", q)
            items = []
            for i, action, status, version, at in EVENTS:
                t = trade(i, "2026-09-30")
                if books and t["PORTFOLIO"] not in books:
                    continue
                items.append((t["TRADE_ID"], t["TRADE_ID"], {"PORTFOLIO": t["PORTFOLIO"], "COUNTERPARTY": t["COUNTERPARTY"], "PRODUCT_TYPE": t["PRODUCT_TYPE"],
                                                             "TRADE_ACTION": action, "TRADE_STATUS": status, "TRADE_VERSION": version,
                                                             "TRADE_DATE": "2026-08-27", "SOURCE_UPDATE_TIME": f"2026-09-30T{at}:00Z"}))
            return _rows("trade", items)
        raise BackendError(404, "DRS-4023", f"{head} is not part of the edition")

    async def raw(self, kind, id_, ident=None):
        if kind == "trader" and id_ in TRADERS:
            return {"ref": {"kind": kind, "id": id_}, "data": {"TRADER_ID": id_, **TRADERS[id_]}}
        if kind == "desk" and id_ in DESKS:
            return {"ref": {"kind": kind, "id": id_}, "data": {"DESK_ID": id_, **DESKS[id_]}}
        if kind == "trade-valuation" and id_.startswith("TVAL-MX-"):
            i = int(id_[8:]) - 20000001
            day = asof.current() if asof.current() != "live" else "2026-09-30"
            return {"ref": {"kind": kind, "id": id_}, "provenance": {"businessDate": self.carried or day}, "data": trade(i, day)}
        raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")

    async def var(self, method, path, ident, body=None, raw=False, accept="", **params):
        self.asked.append(("var", method, path, dict(params), body))
        scope = str(params.get("scope") or (body or {}).get("scope") or "")
        if method == "GET" and path == "/scope":
            if scope.startswith("BOOK:") and " " not in scope:
                v, lim = BOOK_VAR[scope[5:]]
                return {"scope": scope, "businessDate": "2026-09-30", "var": v, "es": int(v * 1.1), "limit": lim, "limitUse": v / lim,
                        "confidence": 0.99, "trades": 4}
            if scope.startswith("DESK:"):
                return {"scope": scope, "businessDate": "2026-09-30", "var": 15_000_000, "limit": 20_000_000, "limitUse": 0.75, "confidence": 0.99,
                        "trades": 11}
            return {"scope": scope, "businessDate": "2026-09-30", "var": 8_000_000, "limit": None, "limitUse": None, "confidence": 0.99, "trades": 8,
                    "intraday": {"updates": 2, "trades": 3, "lastAt": "2026-09-30T14:05:00Z"}}
        if method == "GET" and path == "/scope/contributions":
            if params.get("by") == "trade":
                return {"rows": [{"key": trade(i, "2026-09-30")["TRADE_ID"], "componentVar": 1_000_000 + 100_000 * i} for i in range(11)],
                        "truncated": False, "total": 11}
            return {"rows": [{"label": "USD SOFR swaps 10Y", "scope": "POS:POS-BOOK-RATES-1~MX-20000001", "componentVar": 2_310_000,
                              "marketValue": -42_100_000, "book": "BOOK-RATES-1"},
                             {"label": "USD swaptions 1Yx10Y", "scope": "POS:POS-BOOK-RATES-2~MX-20000002", "componentVar": 710_000,
                              "marketValue": 9_200_000, "book": "BOOK-RATES-2"}]}
        if method == "GET" and path == "/official":
            day = params.get("asOf")
            if day not in OFFICIAL_DAYS:
                raise BackendError(404, "DRS-1001", f"no official VaR record of {day}")
            k = OFFICIAL_DAYS.index(day)
            nodes = {f"MGMT~{b}": {"var": v * (0.9 + 0.025 * k), "limit": lim, "limitUse": v * (0.9 + 0.025 * k) / lim} for b, (v, lim) in BOOK_VAR.items()}
            return {"businessDate": day, "nodes": nodes}
        if method == "GET" and path == "/scope/history":
            if self.history != "official":
                return {"mode": "replay", "rows": [{"date": "2025-01-01", "var": 1, "pnl": -2, "exception": True}]}
            rows = [{"date": f"2026-{7 + d // 22:02d}-{1 + d % 22:02d}", "var": 15_000_000, "pnl": (-1) ** d * 4_000_000 * (1 + d % 3),
                     "exception": False} for d in range(64)]
            rows[40]["pnl"], rows[40]["exception"] = -16_500_000, True
            return {"mode": "official", "rows": rows, "backtest": {"days": 64, "exceptions": 1, "light": "green"}}
        if method == "POST" and path == "/what-if":
            v, lim = BOOK_VAR[scope[5:]]
            f = body["items"][0]["factor"]
            after = int(v + 360_000 * f)
            return {"scope": scope, "before": {"var": v}, "after": {"var": after}, "change": {"var": after - v}, "confidence": 0.99, "currency": "USD",
                    "engine": "var-whatif (Python VaR engine)", "note": "copies"}
        raise BackendError(404, "DRS-1001", f"no VaR endpoint {path}")

    async def exposure(self, method, path, ident, body=None, raw=False, **params):
        self.asked.append(("exposure", method, path, dict(params), body))
        scope = str(params.get("scope") or (body or {}).get("scope") or "")
        if method == "GET" and path == "/scope":
            if scope == "CPTY:CP-ALDERSHOT":
                return {"scope": scope, "peakPfe": 401_500_000, "limit": {"limit": 420_000_000, "confidence": 0.95, "used": 401_500_000},
                        "byNettingSet": [{"nettingSet": "NS-ALDERSHOT-LDN", "peakPfe": 90_000_000}, {"nettingSet": "NS-ALDERSHOT-NY", "peakPfe": 300_000_000}]}
            if scope == "CPTY:CP-NORTHPLAINS":
                return {"scope": scope, "peakPfe": 50_000_000, "limit": None, "byNettingSet": []}
            raise BackendError(404, "DRS-5401", f"no exposure of {scope}")
        if method == "POST" and path == "/what-if":
            if body.get("method") == "paths" and not self.paths:
                raise BackendError(503, "DRS-5446", "the Python exposure engine is not running")
            base = 401_500_000 if scope == "CPTY:CP-ALDERSHOT" else 50_000_000
            return {"base": {"peakPfe": base}, "with": {"peakPfe": base + 7_700_000}, "incremental": {"peakPfe": 7_700_000}, "method": body.get("method"),
                    "flags": ["proxy"] if body.get("method") == "addon" else []}
        raise BackendError(404, "DRS-1001", f"no exposure endpoint {path}")

    async def command_history(self, ident=None):
        return ["TRD MX-20000001", "ECON CAN", "VAR BANK", "BOOK BOOK-RATES-1", "TRD MX-20000001", "SCN SCN-2008", "CTRD CP-ALDERSHOT"]
