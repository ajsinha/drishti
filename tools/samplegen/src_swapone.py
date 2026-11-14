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

"""SwapOne: a mocked OTC swaps system, in its own native style (UPPER_SNAKE names like Endur, two legs a swap as PAY_LEG and REC_LEG objects, our side as
``PAY_RECEIVE``, dates as ``*_DT``, the update time as epoch milliseconds, a numeric ``REVISION``). A synthetic look-alike: no real
system's layout, bank or counterparty. See src_common.py."""
from __future__ import annotations

import calendar
from datetime import date, datetime, timedelta, timezone

from samplegen.src_common import COUNTERPARTIES, CURRENCIES, ENTITIES, TRADERS, SourceBook, iso, money

FAMILIES = ["IRS", "OIS", "BASIS", "XCCY", "CDS", "INFL_ZC"]
INDEXES = {"USD": "SOFR", "EUR": "ESTR", "GBP": "SONIA", "JPY": "TONA", "CAD": "CORRA", "AUD": "AONIA", "CHF": "SARON"}


def millis(day_iso: str) -> int:
    return int(datetime.fromisoformat(day_iso + "T18:30:00").replace(tzinfo=timezone.utc).timestamp() * 1000)


class SwapOne(SourceBook):
    SYSTEM = "SWAPONE"
    KEY = "SWAP_ID"
    COUNT = 350

    def new_trade(self, n: int, booked_on: date) -> dict:
        r = self.rng
        fam = r.choice(FAMILIES)
        ccy = r.choice(CURRENCIES)
        years = r.choice([1, 2, 3, 5, 7, 10, 15, 20, 30])
        cp = r.choice(COUNTERPARTIES)
        notional = money(r, 5_000_000, 500_000_000, 1_000_000)
        eff = booked_on + timedelta(days=2)
        end = date(eff.year + years, eff.month, min(eff.day, calendar.monthrange(eff.year + years, eff.month)[1]))
        pay_fixed = r.random() < 0.5
        fixed_leg = {"CCY": ccy, "NOTIONAL": notional, "FIXED_RATE_PCT": round(r.uniform(0.5, 5.5), 4), "FREQ": r.choice(["A", "S"]),
                     "DAYCOUNT": "30/360"}
        float_leg = {"CCY": ccy if fam != "XCCY" else r.choice([c for c in CURRENCIES if c != ccy]), "NOTIONAL": notional,
                     "INDEX": INDEXES[ccy], "SPREAD_BP": r.choice([0, 0, 5, 10, 25]) if fam != "OIS" else 0, "FREQ": "Q", "DAYCOUNT": "ACT/360"}
        if fam == "BASIS":
            fixed_leg = {"CCY": ccy, "NOTIONAL": notional, "INDEX": "TERM-" + INDEXES[ccy], "SPREAD_BP": 0, "FREQ": "Q", "DAYCOUNT": "ACT/360"}
        doc = {"SWAP_ID": f"S1-{700000 + n}", "REVISION": 1, "LIFECYCLE": "LIVE", "SWAP_FAMILY": fam, "BOOK": f"S1-BOOK-{r.randint(1, 6)}",
               "LEGAL_ENTITY": r.choice(ENTITIES), "COUNTERPARTY_ID": cp[0], "COUNTERPARTY_NAME": cp[1], "TRADE_DT": iso(booked_on),
               "EFFECTIVE_DT": iso(eff), "TERMINATION_DT": iso(end), "PAY_RECEIVE": "PAY" if pay_fixed else "REC",
               "PAY_LEG": fixed_leg if pay_fixed else float_leg, "REC_LEG": float_leg if pay_fixed else fixed_leg,
               "USR": r.choice(TRADERS)[0], "UPD_TS": millis(iso(booked_on))}
        if fam == "CDS":
            doc["REFERENCE_ENTITY"] = r.choice(COUNTERPARTIES)[0]
            doc["UPFRONT_PCT"] = round(r.uniform(-2, 4), 3)
        return doc

    def amend(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["REVISION"] = int(d["REVISION"]) + 1
        d["UPD_TS"] = millis(iso(day))
        for leg in ("PAY_LEG", "REC_LEG"):
            if "FIXED_RATE_PCT" in d[leg]:
                d[leg] = {**d[leg], "FIXED_RATE_PCT": round(d[leg]["FIXED_RATE_PCT"] + self.rng.choice([-0.01, 0.01, 0.02]), 4)}
        return d

    def cancel(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["REVISION"] = int(d["REVISION"]) + 1
        d["LIFECYCLE"] = "VOID"
        d["UPD_TS"] = millis(iso(day))
        return d

    def is_cancelled(self, doc: dict) -> bool:
        return doc["LIFECYCLE"] == "VOID"
