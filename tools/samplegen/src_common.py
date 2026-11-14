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

"""The common part of the mocked source-system generators (src_wss.py, src_swapone.py, src_ici.py).

Each generator writes one source system's trades in that system's OWN native field style (a synthetic look-alike: never real data, never a
real bank or counterparty), as JSON Lines, so the system's source module (modules/source-<system>/) has a real mapping to do into the
canonical trade kind. A system's book is a list of native documents; its history is sixty business days: a trade joins on its booking day,
about 6% of the live book is amended each later day (the system's own version counter and update time move), a few are cancelled.

These trades carry NO positions and NO scenario P&L: nothing the VaR reads is generated for them, so every VaR figure stays what it was.

A landing table shared by several systems (trading.trades_shared) needs to know whose row it is: the feed tags every document with
SOURCE_SYSTEM (the only canonical field in a native document); the module's where: predicate splits the table by it."""
from __future__ import annotations

import json
import random
from datetime import date, timedelta

# the demo's own synthetic counterparties, legal entities, books and traders (the same names the other systems' trades carry)
COUNTERPARTIES = [("CP-MERIDIAN-RE", "Meridian Re"), ("CP-HALCYON", "Halcyon Capital"), ("CP-CORAL", "Coral Bank"),
                  ("CP-VERDANT", "Verdant Holdings"), ("CP-KESTREL", "Kestrel Partners"), ("CP-BRAMBLE", "Bramble Funding"),
                  ("CP-HARBORPT", "Harbor Point"), ("CP-AURORA", "Aurora Trust"), ("CP-ALDERSHOT", "Aldershot Group"),
                  ("CP-PINNACLE", "Pinnacle Markets"), ("CP-NORTHBRIDGE", "Northbridge"), ("CP-LUMEN", "Lumen Capital"),
                  ("CP-CASCADIA", "Cascadia Funding"), ("CP-SOLARIS", "Solaris Energy"), ("CP-SUMMIT", "Summit Securities")]
ENTITIES = ["LE-NY", "LE-LDN", "LE-TKY"]
TRADERS = [("TRDR-ASHAH", "A. Shah"), ("TRDR-MOKAFOR", "M. Okafor"), ("TRDR-JWEISS", "J. Weiss"), ("TRDR-PLINDQVIST", "P. Lindqvist"),
           ("TRDR-FROSSI", "F. Rossi"), ("TRDR-TBRENNAN", "T. Brennan"), ("TRDR-GMENDES", "G. Mendes"), ("TRDR-HOKORO", "H. Okoro")]
CURRENCIES = ["USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF"]


def money(rng: random.Random, lo: int, hi: int, step: int = 100_000) -> int:
    return rng.randrange(lo, hi, step)


def iso(d: date) -> str:
    return d.isoformat()


class SourceBook:
    """One system's mocked book and history. A subclass sets SYSTEM, KEY (the native id field) and implements new_trade, amend, cancel and
    is_cancelled; ``states`` yields, for each business day, {native id: json text} of the live book (the history writer turns that into
    the snapshot and the daily changes)."""

    SYSTEM = ""
    KEY = ""
    COUNT = 300
    AMEND_SHARE = 0.06
    CANCEL_SHARE = 0.004

    def __init__(self, dates: list[str], count: int | None = None, seed: int = 11):
        self.dates = dates
        self.count = count or self.COUNT
        self.rng = random.Random(f"{self.SYSTEM}:{seed}")

    def new_trade(self, n: int, booked_on: date) -> dict:
        raise NotImplementedError

    def amend(self, doc: dict, day: date, stamp: str) -> dict:
        raise NotImplementedError

    def cancel(self, doc: dict, day: date, stamp: str) -> dict:
        raise NotImplementedError

    def is_cancelled(self, doc: dict) -> bool:
        return False

    def states(self):
        rng = self.rng
        n_days = len(self.dates)
        # 70% of the book exists on the first day; the rest is booked on a later day (their trade date is that day)
        joins = [0 if rng.random() < 0.7 else rng.randrange(1, n_days) for _ in range(self.count)]
        live: dict[str, dict] = {}
        for i, day in enumerate(self.dates):
            d = date.fromisoformat(day)
            stamp = f"{day}T18:30:00Z"
            for n, j in enumerate(joins):
                if j == i:
                    booked = d if i else d - timedelta(days=rng.randrange(1, 400))
                    doc = self.new_trade(n, booked)
                    doc["SOURCE_SYSTEM"] = self.SYSTEM
                    live[str(doc[self.KEY])] = doc
            if i:
                for k in sorted(live):
                    r = rng.random()
                    if self.is_cancelled(live[k]):
                        continue
                    if r < self.CANCEL_SHARE:
                        live[k] = self.cancel(live[k], d, stamp)
                    elif r < self.CANCEL_SHARE + self.AMEND_SHARE:
                        live[k] = self.amend(live[k], d, stamp)
            yield {k: json.dumps(v, separators=(",", ":"), ensure_ascii=False) for k, v in live.items()}
