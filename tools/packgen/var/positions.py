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

"""Positions derived from trades, and one P&L vector per trade (VAR_DATA_MODEL.md section 3).

A **position** is a holding: one instrument in one book, its signed QUANTITY the sum of its trades' (a buy adds, a sell takes away), its
DIRECTION LONG or SHORT from the sign, and the trades that build or reduce it (TRADE_IDS, TRADE_QUANTITIES). The instrument of a trade is
its product type, currency, underlying index and maturity quarter (INS-IRS_FIXFLOAT-USD-2032Q2): trades with the same terms in the same
book are one holding to the market. A trade belongs to exactly one position on a business date.

The **vector grain is the trade**: each live trade gets its own 509 scenario P&Ls (``pnl-vector``), and a position's vector is the sum of
its trades' (P&L adds). So trade, position, book and any basket of trades or positions are all exact; long and short trades in one
position net in the sum, and positions net in a portfolio the same way. A trade's P&L is its signed quantity times the instrument's
P&L per unit (factors.unit_path) plus a little of its own (different dates, strikes, counterparties), rounded to whole USD.

The demo's risk batch values a sample of every book (whole positions only, up to ``budget`` trades a book, positions with both buys
and sells first), so the committed data stays a few megabytes; ``make_var_data.py --budget 0`` values every live trade (local only)."""
from __future__ import annotations

import gzip
import json
import statistics
from collections import defaultdict
from pathlib import Path

import factors as F

LIVE = ("VALIDATED", "AMENDED", "NEW")
DIVISIONS = {"DESK-MM": "DIV-TREASURY", "DESK-SFT": "DIV-TREASURY"}       # every other desk is in DIV-MARKETS
DIVISION_NAMES = {"DIV-MARKETS": "Markets", "DIV-TREASURY": "Treasury"}


def replay(folder: Path, key: str, dates: list[str]) -> dict[str, dict]:
    """A dataset's documents on its last day: the first day's snapshot, then each later day's changes by key."""
    state: dict[str, dict] = {}
    for i, day in enumerate(dates):
        for f in sorted(folder.glob(f"{'snapshot' if i == 0 else 'changes'}-{day}*.jsonl.gz")):
            with gzip.open(f, "rt", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        d = json.loads(line)
                        state[str(d[key])] = d
    return state


def book_of(t: dict) -> str:
    return t.get("TT_BOOK") or t.get("PORTFOLIO") or ""


def underlying(t: dict) -> str:
    for leg in t.get("LEGS") or []:
        if isinstance(leg, dict) and leg.get("INDEX_NAME"):
            return "".join(c for c in str(leg["INDEX_NAME"]).upper() if c.isalnum())[:12]
    return ""


def instrument_id(t: dict) -> str:
    mat = str(t.get("MATURITY_DATE") or "")
    quarter = f"{mat[:4]}Q{(int(mat[5:7]) - 1) // 3 + 1}" if len(mat) >= 7 and mat[5:7].isdigit() else "OPEN"
    parts = [str(t.get("PRODUCT_TYPE") or "OTHER").upper(), str(t.get("CURRENCY") or "USD").upper()]
    if underlying(t):
        parts.append(underlying(t))
    return "INS-" + "-".join(parts + [quarter])


def instrument_name(t: dict) -> str:
    what = t.get("PRODUCT_SUB_TYPE") or t.get("PRODUCT_TYPE") or "Instrument"
    idx = f" on {underlying(t)}" if underlying(t) else ""
    return f"{what}{idx} {t.get('CURRENCY') or 'USD'} {str(t.get('MATURITY_DATE') or '')[:7]}".strip()


def signed_qty(t: dict) -> float:
    q = float(t.get("QUANTITY") or 0.0)
    return -abs(q) if str(t.get("BUY_SELL_CODE") or "B").upper().startswith("S") else abs(q)


def group_of(t: dict) -> str:
    g = str(t.get("PRODUCT_GROUP") or "")
    return g if g else "Commodity"


class Batch:
    """The demo risk batch of one business date: the live trades it values, grouped into positions, each trade with its vector."""

    def __init__(self, trades: dict[str, dict], desks: dict[str, dict], valuations: dict[str, dict], cob: str, budget: int = 60,
                 market: F.Market | None = None):
        self.cob, self.market = cob, market or F.Market()
        self.desk_of_book: dict[str, str] = {}
        self.le_of_desk: dict[str, str] = {}
        for d in desks.values():
            self.le_of_desk[d["DESK_ID"]] = d.get("LEGAL_ENTITY") or ""
            for b in d.get("BOOKS") or []:
                self.desk_of_book[b["ID"]] = d["DESK_ID"]
        live = [t for t in trades.values() if t.get("TRADE_STATUS") in LIVE and str(t.get("MATURITY_DATE") or "9999") >= cob
                and book_of(t) in self.desk_of_book]
        groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
        for t in sorted(live, key=lambda t: str(t["TRADE_ID"])):
            groups[(book_of(t), instrument_id(t))].append(t)
        self.groups = self._sample(groups, budget)
        self.valuations = valuations
        self.trade_vectors: dict[str, list[int]] = {}       # trade id -> scenario P&Ls, then the business date's own move last
        self.positions: list[dict] = []
        self.vector_rows: list[dict] = []
        self._value()

    @staticmethod
    def _sample(groups: dict, budget: int) -> dict:
        if budget <= 0:
            return dict(sorted(groups.items()))
        by_book: dict[str, list] = defaultdict(list)
        for (book, ins), ts in groups.items():
            by_book[book].append((ins, ts))
        out = {}
        for book in sorted(by_book):
            # both directions first (a position netting buys and sells), then the small ones (more positions for the budget)
            cands = sorted(by_book[book], key=lambda x: (len({signed_qty(t) > 0 for t in x[1]}) < 2, len(x[1]), x[0]))
            used = 0
            for ins, ts in cands:
                if used and used + len(ts) > budget:
                    continue
                out[(book, ins)] = ts
                used += len(ts)
                if used >= budget:
                    break
        return dict(sorted(out.items()))

    def _value(self) -> None:
        m = self.market
        for (book, ins), ts in self.groups.items():
            desk = self.desk_of_book[book]
            t0 = ts[0]
            grp = group_of(t0)
            r = F.seeded("instrument", book, ins)
            risk = F.BASE_RISK.get(grp.lower(), 150_000) * r.lognormvariate(0.0, 0.55)
            gross = sum(abs(signed_qty(t)) for t in ts) or 1.0
            per_unit = risk / gross
            unit = F.unit_path(m, F.loadings(grp, t0.get("PRODUCT_TYPE"), t0.get("PRODUCT_SUB_TYPE"), t0.get("CURRENCY")),
                               F.is_option(t0.get("PRODUCT_TYPE")), r)
            pos_id = f"POS-{book}~{ins[4:]}"
            qty = sum(signed_qty(t) for t in ts)
            mv = 0.0
            for t in ts:
                tid = str(t["TRADE_ID"])
                q = signed_qty(t)
                tr = F.seeded("trade", tid)
                own = 0.06 * abs(q) * per_unit
                vec = [round(q * per_unit * unit[j] + own * F.student_t(tr)) for j in range(m.n + 1)]
                self.trade_vectors[tid] = vec
                val = self.valuations.get(f"TVAL-{tid}") or {}
                pv = float(val.get("MTM") or 0.0)
                mv += pv
                self.vector_rows.append({
                    "VECTOR_ID": f"PV-{tid}-HS-509", "TRADE_ID": tid, "POSITION_ID": pos_id, "INSTRUMENT_ID": ins,
                    "BUSINESS_DATE": self.cob, "SCENARIO_SET_ID": "HS-509", "SOURCE_SYSTEM": t.get("SOURCE_SYSTEM"),
                    "TRADE_VERSIONS": f"v{t.get('TRADE_VERSION') or 1}", "LEGAL_ENTITY": self.le_of_desk.get(desk) or t.get("LEGAL_ENTITY"),
                    "DIVISION": DIVISIONS.get(desk, "DIV-MARKETS"), "DESK": desk, "TT_BOOK": book, "PORTFOLIO": t.get("PORTFOLIO") or book,
                    "STRATEGY": t.get("STRATEGY") or "", "PRODUCT_GROUP": grp, "PRODUCT_TYPE": t.get("PRODUCT_TYPE"),
                    "CURRENCY": t.get("CURRENCY") or "USD", "COUNTERPARTY": t.get("COUNTERPARTY") or "",
                    "DIRECTION": "LONG" if q >= 0 else "SHORT", "QUANTITY": q, "REPORTING_CCY": "USD", "PV": round(pv),
                    "PNL": vec[:m.n], "STATUS": "ok", "PRICER": "demo-factor-model", "MODEL_VERSION": "demo-1",
                    "COMPUTED_AT": f"{self.cob}T22:30:00Z", "BATCH_ID": f"B-{self.cob}-01", "RESTATEMENT": 0})
            cps = sorted({str(t.get("COUNTERPARTY") or "") for t in ts} - {""})
            self.positions.append({
                "POSITION_ID": pos_id, "BUSINESS_DATE": self.cob, "INSTRUMENT_ID": ins, "INSTRUMENT": instrument_name(t0),
                "POSITION_TYPE": "fungible" if len(ts) > 1 else "single-trade",
                "DIRECTION": "LONG" if qty > 0 else "SHORT" if qty < 0 else "FLAT", "QUANTITY": qty,
                "GROSS_QUANTITY": gross, "TRADE_IDS": [str(t["TRADE_ID"]) for t in ts],
                "TRADE_QUANTITIES": [{"TRADE_ID": str(t["TRADE_ID"]), "QUANTITY": signed_qty(t),
                                      "DIRECTION": "LONG" if signed_qty(t) >= 0 else "SHORT"} for t in ts],
                "TRADE_COUNT": len(ts),
                "LONG_TRADES": sum(1 for t in ts if signed_qty(t) > 0), "SHORT_TRADES": sum(1 for t in ts if signed_qty(t) < 0),
                "LEGAL_ENTITY": self.le_of_desk.get(desk) or t0.get("LEGAL_ENTITY"), "DIVISION": DIVISIONS.get(desk, "DIV-MARKETS"),
                "DESK": desk, "TT_BOOK": book, "PORTFOLIO": t0.get("PORTFOLIO") or book, "STRATEGY": t0.get("STRATEGY") or "",
                "COUNTERPARTY": cps[0] if len(cps) == 1 else "", "COUNTERPARTIES": cps, "PRODUCT_GROUP": grp,
                "PRODUCT_TYPE": t0.get("PRODUCT_TYPE"), "CURRENCY": t0.get("CURRENCY") or "USD", "MARKET_VALUE": round(mv),
                "NOTIONAL": round(sum(abs(signed_qty(t)) for t in ts)), "LINEAR": not F.is_option(t0.get("PRODUCT_TYPE")),
                "STATUS": "open", "OPENED_ON": min(str(t.get("TRADE_DATE") or self.cob) for t in ts), "TAGS": self._tags(t0, qty)})

    @staticmethod
    def _tags(t: dict, qty: float) -> list[str]:
        tags = []
        if str(t.get("INTERNAL_EXTERNAL_CODE") or "").lower() == "internal":
            tags.append("internal")
        if F.is_option(t.get("PRODUCT_TYPE")):
            tags.append("option")
        if group_of(t) in ("Rates", "Fixed income") and qty < 0:
            tags.append("hedge")
        return tags

    def position_vector(self, pos: dict, upto: int | None = None) -> list[int]:
        """The position's vector: the sum of its trades' (``upto`` scenario days, all with the business date's move when None)."""
        n = len(self.trade_vectors[pos["TRADE_IDS"][0]]) if upto is None else upto
        out = [0] * n
        for tid in pos["TRADE_IDS"]:
            v = self.trade_vectors[tid]
            for j in range(n):
                out[j] += v[j]
        return out


def latest(folder: Path, key: str, dates: list[str]) -> dict[str, dict]:
    """replay() when the folder has the dated files, else empty (a dataset this generator can do without, as trade valuations)."""
    return replay(folder, key, dates) if folder.is_dir() else {}
