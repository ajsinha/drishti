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

"""ICI: a mocked generic cross-asset booking system, in its own native style (PascalCase sections: Header, Instrument, Parties, Dates; a
numeric ``Rev``; dates as YYYYMMDD text; one ticket a document). What ICI trades is not stated by its owner, so it is mocked as a generic
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
    KEY = "TicketNo"
    COUNT = 320

    def new_trade(self, n: int, booked_on: date) -> dict:
        r = self.rng
        cls = r.choice(list(CLASSES))
        kind = r.choice(CLASSES[cls])
        cp = r.choice(COUNTERPARTIES)
        tenor = r.choice([30, 90, 180, 365, 730, 1825, 3650])
        return {"TicketNo": f"ICI{9100000 + n}",
                "Header": {"Rev": 1, "State": "BOOKED", "EnteredBy": r.choice(TRADERS)[0], "LastChange": ymd(booked_on) + "183000"},
                "Instrument": {"AssetClass": cls, "Kind": kind, "Ccy": r.choice(CURRENCIES), "Units": money(r, 1_000, 5_000_000, 1_000),
                               "Px": round(r.uniform(0.5, 250), 4), "Side": r.choice(["B", "S"])},
                "Parties": {"Cpty": cp[0], "CptyName": cp[1], "Entity": r.choice(ENTITIES), "Book": f"ICI-{cls[:3]}-{r.randint(1, 3)}"},
                "Dates": {"Trade": ymd(booked_on), "Start": ymd(booked_on + timedelta(days=2)), "End": ymd(booked_on + timedelta(days=tenor))}}

    def amend(self, doc: dict, day: date, stamp: str) -> dict:
        d = {k: (dict(v) if isinstance(v, dict) else v) for k, v in doc.items()}
        d["Header"]["Rev"] += 1
        d["Header"]["State"] = "AMENDED"
        d["Header"]["LastChange"] = ymd(day) + "183000"
        d["Instrument"]["Px"] = round(d["Instrument"]["Px"] * (1 + self.rng.uniform(-0.003, 0.003)), 4)
        return d

    def cancel(self, doc: dict, day: date, stamp: str) -> dict:
        d = self.amend(doc, day, stamp)
        d["Header"]["State"] = "CANCELLED"
        return d

    def is_cancelled(self, doc: dict) -> bool:
        return doc["Header"]["State"] == "CANCELLED"
