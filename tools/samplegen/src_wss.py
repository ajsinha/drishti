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

"""WSS: a mocked treasury, FX and money-markets trading system, in its own native style (camelCase, flat, one deal a document, amounts as
buy and sell legs, the date fields named ``tradedOn``/``valueDate``, the update time in ISO). A synthetic look-alike: no real system's
layout, bank or counterparty. See src_common.py."""
from __future__ import annotations

from datetime import date, timedelta

from samplegen.src_common import COUNTERPARTIES, ENTITIES, TRADERS, SourceBook, iso, money

PAIRS = {"EURUSD": 1.0850, "GBPUSD": 1.2700, "USDJPY": 149.50, "USDCAD": 1.3600, "AUDUSD": 0.6600, "USDCHF": 0.8900}
# deal type -> (instrument family, tenor in days range)
DEALS = {"FX_SPOT": ("FX", (2, 2)), "FX_FWD": ("FX", (7, 365)), "FX_SWAP": ("FX", (7, 180)), "MM_DEP": ("MONEY", (1, 180)),
         "MM_LOAN": ("MONEY", (1, 180)), "TBILL": ("TREASURY", (30, 365)), "CP": ("MONEY", (14, 270)), "REPO": ("TREASURY", (1, 90))}


class Wss(SourceBook):
    SYSTEM = "WSS"
    KEY = "dealRef"
    COUNT = 300

    def new_trade(self, n: int, booked_on: date) -> dict:
        r = self.rng
        dtype = r.choice(list(DEALS))
        family, (lo, hi) = DEALS[dtype]
        tenor = r.randint(lo, hi)
        cp = r.choice(COUNTERPARTIES)
        desk = {"FX": "FXDESK", "MONEY": "MMDESK", "TREASURY": "TSYDESK"}[family]
        doc = {"dealRef": f"WSS-{4200000 + n}", "dealVer": 1, "dealState": "Verified", "dealType": dtype, "family": family,
               "desk": desk, "bookRef": f"{desk}-{r.randint(1, 4)}", "legalEntity": r.choice(ENTITIES),
               "cpty": {"code": cp[0], "name": cp[1]}, "dir": r.choice(["BUY", "SELL"]),
               "tradedOn": iso(booked_on), "valueDate": iso(booked_on + timedelta(days=2 if dtype != "MM_DEP" else 1)),
               "maturityDate": iso(booked_on + timedelta(days=tenor + 2)), "dealer": r.choice(TRADERS)[0],
               "lastUpd": f"{iso(booked_on)}T17:{r.randint(10, 59)}:00Z"}
        if family == "FX":
            pair = r.choice(list(PAIRS))
            rate = round(PAIRS[pair] * (1 + r.uniform(-0.01, 0.01)), 5)
            amt = money(r, 1_000_000, 80_000_000)
            doc.update({"ccyPair": pair, "buyCcy": pair[:3], "sellCcy": pair[3:], "buyAmt": amt, "sellAmt": round(amt * rate, 2), "rate": rate})
            if dtype == "FX_SWAP":
                doc["nearRate"] = rate
                doc["farRate"] = round(rate * (1 + 0.0004 * tenor / 30), 5)
        else:
            ccy = r.choice(["USD", "EUR", "GBP", "CAD"])
            doc.update({"ccy": ccy, "principal": money(r, 5_000_000, 250_000_000, 500_000), "ratePct": round(r.uniform(0.2, 5.4), 4),
                        "dayCount": "ACT/360" if ccy != "GBP" else "ACT/365"})
            if dtype in ("TBILL", "REPO"):
                doc["isin"] = f"XS{r.randint(10**9, 10**10 - 1)}"
        return doc

    def amend(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["dealVer"] = int(d["dealVer"]) + 1
        d["dealState"] = "Amended"
        d["lastUpd"] = stamp
        if "rate" in d:
            d["rate"] = round(d["rate"] * (1 + self.rng.uniform(-0.002, 0.002)), 5)
            d["sellAmt"] = round(d["buyAmt"] * d["rate"], 2)
        else:
            d["ratePct"] = round(d["ratePct"] + self.rng.choice([-0.05, 0.05, 0.1]), 4)
        return d

    def cancel(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["dealVer"] = int(d["dealVer"]) + 1
        d["dealState"] = "Cancelled"
        d["lastUpd"] = stamp
        return d

    def is_cancelled(self, doc: dict) -> bool:
        return doc["dealState"] == "Cancelled"
