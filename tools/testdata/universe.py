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

"""The demo universe behind data/testdata: three source systems' trade books of 5,000 trades each and every document the seven packs read
about them, consistent with one another on the last business day.

    trades        CALYPSO and MUREX: the trading pack's sample trades (same ids, same content) and clones of them numbered from the
                  systems' clone ranges (CLY-4000000 ..., MX-30000000 ...), amounts scaled by a factor between 0.2 and 5, counterparty
                  drawn again; ENDUR: the 15 sample deals and synthetic deals of the five product types (1000016 ...), booked with the six
                  look-alike counterparties below where an Endur commodity book would (vault, nostro, internal, exchanges, an energy name)
    look-alikes   six fictitious counterparties mirroring the roles of a real Endur book's counterparties (custodian vault, nostro bank,
                  the bank itself, a futures exchange, a gas exchange, an energy corporate): never the real names
    risk          the banking generator's results (tools/packgen/banking/data_results.py) computed over the whole book: netting sets with
                  their trades (TRADE_IDS, all of them), exposure, CVA, SA-CCR, SIMM, limits, collateral, margin calls, books, desks,
                  traders, counterparties with UEN, COUNTRY_OF_RISK and LEGAL_ENTITY; market data; the risk engine's trade valuations

Everything is deterministic and UPPER_SNAKE (the documents a pack reads); nothing here reads a real extract.
"""
from __future__ import annotations

import copy
import random
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
for sub in ("tools", "tools/packgen", "tools/packgen/common", "tools/packgen/banking", "tools/packgen/trades"):
    sys.path.insert(0, str(ROOT / sub))

import data_names as N  # noqa: E402
import refdata as R  # noqa: E402  (tools/packgen/trades)
import booking as BK  # noqa: E402
import convert as TC  # noqa: E402
import endur as TE  # noqa: E402
import schema as TS  # noqa: E402

AS_OF = N.AS_OF
PER_SYSTEM = 5_000
# key: (name, type, sector, country, rating, group) as data_names.COUNTERPARTIES; the role of the real counterparty each one mirrors
LOOKALIKES = {
    "VAULTGUARD": (("Vaultguard Secure Logistics", "Custodian", "Financial services", "US", "A-", "VAULTGUARD"), "vault and custody"),
    "HARBOURLINE-LDN": (("Harbourline Bank London Branch", "Bank", "Banking", "GB", "A+", "HARBOURLINE-LDN"), "nostro bank"),
    "HOUSE": (("House Account (internal)", "Internal", "Financial services", "US", "AA-", "HOUSE"), "the bank itself"),
    "METROEX": (("Metro Futures Exchange", "Exchange", "Financial services", "US", "AA-", "METROEX"), "futures exchange"),
    "PRAIRIEGAS": (("Prairie Gas Exchange", "Exchange", "Financial services", "CA", "A", "PRAIRIEGAS"), "gas exchange"),
    "NORTHPLAINS": (("Northern Plains Energy Canada", "Corporate", "Utilities", "CA", "BBB", "NORTHPLAINS"), "energy corporate"),
}
# which look-alike an ENDUR deal of a product (sub type) is booked with, most of the time (the rest keep a bilateral counterparty)
ROLE_OF = {"CallNot": ("VAULTGUARD", "HARBOURLINE-LDN"), "Cash": ("HOUSE",), "ComFut": ("METROEX",), "NGX-COMM-PHYS": ("PRAIRIEGAS",),
           "COMM-WARRANTS-INV": ("HOUSE", "VAULTGUARD"), "ComSwap": ("NORTHPLAINS",)}
ROLE_SHARE = 0.6
DEAD = {"MATURED", "CANCELLED"}                         # trade statuses no netting set holds (they neither net nor expose)
SCALE = {"notional", "mtm", "pnl1d", "pv", "amount", "dv01", "cs01", "ie01", "delta", "gamma", "vega", "fxDelta", "jtd", "theta", "pnl",
         "quantity", "faceAmount", "premium", "principal", "interest", "npv", "marketValue"}
SLIM = ("tradeId", "sourceSystem", "productType", "productName", "assetClass", "family", "currency", "book", "notional", "maturityDate", "mtm",
        "pnl1d", "nettingSet", "direction", "desk", "trader", "risk", "counterparty", "legalEntity", "clearingAccount", "issuer", "status")


def _scale(v, f: float):
    if isinstance(v, dict):
        return {k: (round(x * f, 2) if k in SCALE and isinstance(x, (int, float)) and not isinstance(x, bool) else _scale(x, f)) for k, x in v.items()}
    if isinstance(v, list):
        return [_scale(x, f) for x in v]
    return v


def _num(v) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


class Universe:
    """Builds the book and the documents; ``trades[system]`` are common-schema trades, ``docs[kind][id]`` everything else."""

    def __init__(self, per_system: int = PER_SYSTEM):
        self.per_system = per_system
        self.trades: dict[str, list[dict]] = {}
        self.rich: dict[str, dict] = {}            # slim internal trades (data_results reads these), every system
        self.docs: dict[str, dict[str, dict]] = {}
        self.lookalike_uen: dict[str, str] = {}

    # ---- the book ---------------------------------------------------------------------------------------------------------------------
    def build(self) -> "Universe":
        import data_trades
        samples = data_trades.build()                # the pack's 750 rich sample trades, before the look-alikes join the universe
        self._join_lookalikes()
        for system in ("CALYPSO", "MUREX"):
            temps = [d for d in samples.values() if TC.system_of(d) == system]
            book = [self._keep(d, TC.convert(d, AS_OF.isoformat())) for d in temps]
            i = 0
            while len(book) < self.per_system:
                book.append(self._clone(temps[i % len(temps)], i, system))
                i += 1
            self.trades[system] = book[: self.per_system]
        self.trades["ENDUR"] = self._endur()
        self._risk()
        return self

    def _join_lookalikes(self) -> None:
        start = len(N.COUNTERPARTIES)
        for n, (key, (row, _role)) in enumerate(LOOKALIKES.items(), start=start + 1):
            N.COUNTERPARTIES[key] = row
            uen = f"UEN{100000 + n * 37:07d}"                   # after the existing numbering: no UEN changes hands
            R.CPTY[f"CP-{key}"] = {"name": row[0], "uen": uen, "country": row[3]}
            self.lookalike_uen[f"CP-{key}"] = uen

    def _keep(self, rich: dict, trade: dict) -> dict:
        self.rich[str(rich["tradeId"])] = {k: rich[k] for k in SLIM if k in rich}
        return trade

    def _clone(self, t: dict, i: int, system: str) -> dict:
        cid, native = BK.clone_id(system, i)
        rng = random.Random(f"testdata/{cid}")
        d = _scale(copy.deepcopy(t), round(rng.uniform(0.2, 5.0), 3))
        d["tradeId"], d["sourceTradeId"] = cid, native
        if d["counterparty"]["id"] != "CP-SUMMIT":            # a listed trade stays with the clearing broker
            cp = rng.choice([c for c in R.BILATERAL])
            d["counterparty"] = {"id": cp, "name": R.CPTY[cp]["name"]}
            le_short = d["nettingSet"].rsplit("-", 1)[1]
            d["nettingSet"] = f"NS-{cp[3:]}-{le_short}"
        out = TC.convert(d, AS_OF.isoformat())
        base = int(native) * 1000
        n = 0
        for leg in out["LEGS"]:
            for f in leg["CASH_FLOWS"]:
                n += 1
                f["FLOW_ID"] = base + n
        for k, e in enumerate(out["EVENTS"], start=1):
            e["EVENT_ID"] = base + k
        return self._keep(d, TS.text_ids(out))

    def _endur(self) -> list[dict]:
        book = TE.Endur(as_of=AS_OF).samples(3)              # the pack's 15 sample deals, ids 1000001 ...
        gen = TE.Endur(seed=23, as_of=AS_OF)
        rng = random.Random("testdata/endur")
        n = 0
        while len(book) < self.per_system:
            d = gen.deal(1000 + n)
            n += 1
            d["TRADE_ID"] = str(TE.FIRST_ID + len(book))
            role = ROLE_OF.get(d["PRODUCT_SUB_TYPE"]) or ROLE_OF.get(d["PRODUCT_TYPE"])
            if role and rng.random() < ROLE_SHARE:
                cp = "CP-" + rng.choice(role)
                d["COUNTERPARTY"] = cp
                d["COUNTERPARTY_UEN"] = R.CPTY[cp]["uen"]
                d["COUNTERPARTY_COUNTRY_OF_RISK"] = R.CPTY[cp]["country"]
                if cp == "CP-HOUSE":
                    d["INTERNAL_EXTERNAL_CODE"] = "Portfolio"
            book.append(d)
        for d in book:                                        # what the risk engine needs of a deal (data_results reads the slim model)
            self.rich[str(d["TRADE_ID"])] = self._endur_slim(d)
        return book

    @staticmethod
    def _endur_slim(d: dict) -> dict:
        leg = (d.get("LEGS") or [{}])[0]
        notional = abs(_num(leg.get("NOTIONAL")))
        g = random.Random(f"tval/{d['TRADE_ID']}")
        mtm = round(notional * g.gauss(0, 0.015))
        le = d.get("LEGAL_ENTITY") or R.legal_entity_of("DESK-COMM")
        short = {"LE-NY": "NY", "LE-LDN": "LDN", "LE-FRA": "FRA", "LE-TKY": "TKY"}[le]
        return {"tradeId": str(d["TRADE_ID"]), "sourceSystem": "ENDUR", "productType": d["PRODUCT_TYPE"],
                "productName": d.get("PRODUCT_SUB_TYPE") or d["PRODUCT_TYPE"], "assetClass": "Commodity",
                "family": {"ComSwap": "swap", "ComFut": "future"}.get(d["PRODUCT_TYPE"], "linear"), "currency": d.get("CURRENCY") or "USD",
                "book": d["PORTFOLIO"], "notional": notional, "maturityDate": d.get("MATURITY_DATE") or (AS_OF.replace(year=AS_OF.year + 1)).isoformat(),
                "mtm": mtm, "pnl1d": round(mtm * g.gauss(0, 0.04)), "nettingSet": f"NS-{d['COUNTERPARTY'][3:]}-{short}",
                "direction": "Buy" if d["BUY_SELL_CODE"] == TS.BUY else "Sell", "desk": "DESK-COMM", "trader": d["TRADER_ID"],
                "risk": {"delta": round(notional * 0.01)}, "counterparty": {"id": d["COUNTERPARTY"], "name": R.CPTY[d["COUNTERPARTY"]]["name"]},
                "legalEntity": le, "status": d.get("TRADE_STATUS")}

    # ---- the documents ----------------------------------------------------------------------------------------------------------------
    def _risk(self) -> None:
        import data_market
        import data_results
        import upper as U
        import walks
        import make_data as MD
        docs = {k: dict(v) for k, v in data_market.build().items()}
        # a netting set nets the trades that are still alive: a MATURED or CANCELLED trade is neither a member nor in its MTM, EE or CVA
        status = {str(t["TRADE_ID"]): str(t.get("TRADE_STATUS") or "").upper() for book in self.trades.values() for t in book}
        live = {tid: r for tid, r in self.rich.items() if status.get(tid) not in DEAD}
        for kind, ds in data_results.build(live).items():
            docs.setdefault(kind, {}).update(ds)
        for ds in docs.values():
            for d in ds.values():
                for k in [k for k, v in d.items() if v is None]:
                    del d[k]
        docs = {kind: {i: U.record(d) for i, d in ds.items()} for kind, ds in docs.items()}
        members: dict[str, list[str]] = {}
        for tid, t in live.items():
            members.setdefault(t["nettingSet"], []).append(tid)
        for ns, d in docs.get("netting-set", {}).items():   # every trade of the set, not only the 40 largest rows
            d["TRADE_IDS"] = sorted(members.get(ns, []))
        for cp, d in docs.get("counterparty", {}).items():
            d["UEN"] = R.CPTY[cp]["uen"]
            sets = [docs["netting-set"][s["ID"]] for s in d.get("NETTING_SETS") or [] if s["ID"] in docs["netting-set"]]
            biggest = max(sets, key=lambda s: s["TRADE_COUNT"], default=None)
            d["LEGAL_ENTITY"] = biggest["LEGAL_ENTITY"] if biggest else "LE-NY"
        every = {str(t["TRADE_ID"]): t for book in self.trades.values() for t in book}
        rich_like = {tid: {**r, "risk": r.get("risk") or {}} for tid, r in self.rich.items()}
        docs["trade-valuation"] = MD.valuations(rich_like, every)
        for ds in docs.values():
            for d in ds.values():
                d.pop("_meta", None)
        _ = walks                                             # imported for the banking generator's side effects only
        self.docs = docs

    def summary(self) -> dict:
        return {"trades": {s: len(b) for s, b in self.trades.items()},
                "products": {s: len(Counter(t["PRODUCT_TYPE"] for t in b)) for s, b in self.trades.items()},
                "kinds": {k: len(v) for k, v in sorted(self.docs.items())}}
