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

"""SwapOne: a mocked OTC swaps system, in its own native style (snake_case, two legs a swap as pay_leg and rec_leg objects, our side as
``pay_receive``, dates as ``*_dt``, the update time as epoch milliseconds, a numeric ``revision``). A synthetic look-alike: no real
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
    KEY = "swap_id"
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
        fixed_leg = {"ccy": ccy, "notional": notional, "fixed_rate_pct": round(r.uniform(0.5, 5.5), 4), "freq": r.choice(["A", "S"]),
                     "daycount": "30/360"}
        float_leg = {"ccy": ccy if fam != "XCCY" else r.choice([c for c in CURRENCIES if c != ccy]), "notional": notional,
                     "index": INDEXES[ccy], "spread_bp": r.choice([0, 0, 5, 10, 25]) if fam != "OIS" else 0, "freq": "Q", "daycount": "ACT/360"}
        if fam == "BASIS":
            fixed_leg = {"ccy": ccy, "notional": notional, "index": "TERM-" + INDEXES[ccy], "spread_bp": 0, "freq": "Q", "daycount": "ACT/360"}
        doc = {"swap_id": f"S1-{700000 + n}", "revision": 1, "lifecycle": "LIVE", "swap_family": fam, "book": f"S1-BOOK-{r.randint(1, 6)}",
               "legal_entity": r.choice(ENTITIES), "counterparty_id": cp[0], "counterparty_name": cp[1], "trade_dt": iso(booked_on),
               "effective_dt": iso(eff), "termination_dt": iso(end), "pay_receive": "PAY" if pay_fixed else "REC",
               "pay_leg": fixed_leg if pay_fixed else float_leg, "rec_leg": float_leg if pay_fixed else fixed_leg,
               "usr": r.choice(TRADERS)[0], "upd_ts": millis(iso(booked_on))}
        if fam == "CDS":
            doc["reference_entity"] = r.choice(COUNTERPARTIES)[0]
            doc["upfront_pct"] = round(r.uniform(-2, 4), 3)
        return doc

    def amend(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["revision"] = int(d["revision"]) + 1
        d["upd_ts"] = millis(iso(day))
        for leg in ("pay_leg", "rec_leg"):
            if "fixed_rate_pct" in d[leg]:
                d[leg] = {**d[leg], "fixed_rate_pct": round(d[leg]["fixed_rate_pct"] + self.rng.choice([-0.01, 0.01, 0.02]), 4)}
        return d

    def cancel(self, doc: dict, day: date, stamp: str) -> dict:
        d = dict(doc)
        d["revision"] = int(d["revision"]) + 1
        d["lifecycle"] = "VOID"
        d["upd_ts"] = millis(iso(day))
        return d

    def is_cancelled(self, doc: dict) -> bool:
        return doc["lifecycle"] == "VOID"
