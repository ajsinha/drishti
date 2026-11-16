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

"""ICI: a mocked generic cross-asset booking system, in its own native style (UPPER_SNAKE names, sections HEADER, INSTRUMENT, PARTIES, DATES; a
numeric ``REV``; dates as YYYYMMDD text; one ticket a document). What ICI trades is not stated by its owner, so it is mocked as a generic
cross-asset booking system: rates, FX, equity, credit and commodity tickets from one blotter (modules/source-ici/README.md says so). A
synthetic look-alike: no real system's layout, bank or counterparty. See src_common.py."""
from __future__ import annotations

from datetime import date, timedelta

from samplegen.src_common import COUNTERPARTIES, CURRENCIES, ENTITIES, TRADERS, SourceBook, money

CLASSES = {"RATES": ["SWAP", "FRA", "BOND"], "FX": ["FWD", "OPTION"], "EQUITY": ["OPTION", "TRS", "FUT"], "CREDIT": ["CDS", "BOND"],
           "COMMODITY": ["SWAP", "FUT"]}


def ymd(d: date) -> str:
    return d.strftime("%Y%m%d")


class Ici(SourceBook):
    SYSTEM = "ICI"
    KEY = "TICKET_NO"
    COUNT = 320

    def new_trade(self, n: int, booked_on: date) -> dict:
        r = self.rng
        cls = r.choice(list(CLASSES))
        kind = r.choice(CLASSES[cls])
        cp = r.choice(COUNTERPARTIES)
        tenor = r.choice([30, 90, 180, 365, 730, 1825, 3650])
        return {"TICKET_NO": f"ICI{9100000 + n}",
                "HEADER": {"REV": 1, "STATE": "BOOKED", "ENTERED_BY": r.choice(TRADERS)[0], "LAST_CHANGE": ymd(booked_on) + "183000"},
                "INSTRUMENT": {"ASSET_CLASS": cls, "KIND": kind, "CCY": r.choice(CURRENCIES), "UNITS": money(r, 1_000, 5_000_000, 1_000),
                               "PX": round(r.uniform(0.5, 250), 4), "SIDE": r.choice(["B", "S"])},
                "PARTIES": {"CPTY": cp[0], "CPTY_NAME": cp[1], "ENTITY": r.choice(ENTITIES), "BOOK": f"ICI-{cls[:3]}-{r.randint(1, 3)}"},
                "DATES": {"TRADE": ymd(booked_on), "START": ymd(booked_on + timedelta(days=2)), "END": ymd(booked_on + timedelta(days=tenor))}}

    def amend(self, doc: dict, day: date, stamp: str) -> dict:
        d = {k: (dict(v) if isinstance(v, dict) else v) for k, v in doc.items()}
        d["HEADER"]["REV"] += 1
        d["HEADER"]["STATE"] = "AMENDED"
        d["HEADER"]["LAST_CHANGE"] = ymd(day) + "183000"
        d["INSTRUMENT"]["PX"] = round(d["INSTRUMENT"]["PX"] * (1 + self.rng.uniform(-0.003, 0.003)), 4)
        return d

    def cancel(self, doc: dict, day: date, stamp: str) -> dict:
        d = self.amend(doc, day, stamp)
        d["HEADER"]["STATE"] = "CANCELLED"
        return d

    def is_cancelled(self, doc: dict) -> bool:
        return doc["HEADER"]["STATE"] == "CANCELLED"
