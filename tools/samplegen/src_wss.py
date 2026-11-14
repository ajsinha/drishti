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

"""WSS: a mocked treasury, FX and money-markets trading system, in its own native style (UPPER_SNAKE names like Endur, flat, one deal a document, amounts as
buy and sell legs, the date fields named ``TRADED_ON``/``VALUE_DATE``, the update time in ISO). A synthetic look-alike: no real system's
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
    KEY = "DEAL_REF"
    COUNT = 300

    def new_trade(self, n: int, booked_on: date) -> dict:
        r = self.rng
        dtype = r.choice(list(DEALS))
        family, (lo, hi) = DEALS[dtype]
        tenor = r.randint(lo, hi)
        cp = r.choice(COUNTERPARTIES)
        desk = {"FX": "FXDESK", "MONEY": "MMDESK", "TREASURY": "TSYDESK"}[family]
        doc = {"DEAL_REF": f"WSS-{4200000 + n}", "DEAL_VERSION": 1, "DEAL_STATE": "Verified", "DEAL_TYPE": dtype, "FAMILY": family,
               "DESK": desk, "BOOK_REF": f"{desk}-{r.randint(1, 4)}", "LEGAL_ENTITY": r.choice(ENTITIES),
               "CPTY": {"CODE": cp[0], "NAME": cp[1]}, "DIR": r.choice(["BUY", "SELL"]),
               "TRADED_ON": iso(booked_on), "VALUE_DATE": iso(booked_on + timedelta(days=2 if dtype != "MM_DEP" else 1)),
               "MATURITY_DATE": iso(booked_on + timedelta(days=tenor + 2)), "DEALER": r.choice(TRADERS)[0],
               "LAST_UPD": f"{iso(booked_on)}T17:{r.randint(10, 59)}:00Z"}
        if family == "FX":
            pair = r.choice(list(PAIRS))
            rate = round(PAIRS[pair] * (1 + r.uniform(-0.01, 0.01)), 5)
            amt = money(r, 1_000_000, 80_000_000)
            doc.update({"CCY_PAIR": pair, "BUY_CCY": pair[:3], "SELL_CCY": pair[3:], "BUY_AMT": amt, "SELL_AMT": round(amt * rate, 2), "RATE": rate})
            if dtype == "FX_SWAP":
                doc["NEAR_RATE"] = rate
                doc["FAR_RATE"] = round(rate * (1 + 0.0004 * tenor / 30), 5)
        else:
            ccy = r.choice(["USD", "EUR", "GBP", "CAD"])
            doc.update({"CCY": ccy, "PRINCIPAL": money(r, 5_000_000, 250_000_000, 500_000), "RATE_PCT": round(r.uniform(0.2, 5.4), 4),
                        "DAY_COUNT": "ACT/360" if ccy != "GBP" else "ACT/365"})
            if dtype in ("TBILL", "REPO"):
                doc["ISIN"] = f"XS{r.randint(10**9, 10**10 - 1)}"
        return doc

    def amend(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["DEAL_VERSION"] = int(d["DEAL_VERSION"]) + 1
        d["DEAL_STATE"] = "Amended"
        d["LAST_UPD"] = stamp
        if "RATE" in d:
            d["RATE"] = round(d["RATE"] * (1 + self.rng.uniform(-0.002, 0.002)), 5)
            d["SELL_AMT"] = round(d["BUY_AMT"] * d["RATE"], 2)
        else:
            d["RATE_PCT"] = round(d["RATE_PCT"] + self.rng.choice([-0.05, 0.05, 0.1]), 4)
        return d

    def cancel(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["DEAL_VERSION"] = int(d["DEAL_VERSION"]) + 1
        d["DEAL_STATE"] = "Cancelled"
        d["LAST_UPD"] = stamp
        return d

    def is_cancelled(self, doc: dict) -> bool:
        return doc["DEAL_STATE"] == "Cancelled"
