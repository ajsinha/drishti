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

"""Booking systems: where each trade lives before Drishti sees it, and how that system numbers its trades.

A bank books each asset class in a system suited to it, and every system numbers its own trades. A trade store that
lands several systems in one book keeps each system's number behind a prefix, so ids stay unique and a user who
knows a Murex trade number types it as it is: MX-20000017.

    Murex (MX.3)             rates, inflation                       MX-…    trade number
    Calypso                  credit                                 CLY-…   trade id
    Endur (Openlink)         commodities                            END-…   deal tracking number
    Imagine Trading System   equity, structured products            IMG-…   trade id
    Bloomberg TOMS           fixed income, securities financing     BBG-…   ticket number
    Wall Street Systems      FX, money markets                      WSS-…   deal number

The sample trades take the first numbers of each system; tools/samplegen/bulk_trades.py continues from a separate,
higher range per system, so its clones never collide with the samples. The ranges are illustrative: real
installations start and format their numbers as they are configured.
"""
from __future__ import annotations

SYSTEMS = {
    #  system                   prefix   samples from   bulk clones from
    "Murex":                    ("MX",   20_000_000,    30_000_000),
    "Calypso":                  ("CLY",  3_000_000,     4_000_000),
    "Endur":                    ("END",  1_000_000,     1_100_000),
    "Imagine":                  ("IMG",  400_000,       500_000),
    "Bloomberg TOMS":           ("BBG",  60_000_000,    70_000_000),
    "Wall Street Systems":      ("WSS",  1_500_000,     2_000_000),
}
BY_ASSET = {"Rates": "Murex", "Inflation": "Murex", "Credit": "Calypso", "Commodity": "Endur", "Equity": "Imagine",
            "Structured": "Imagine", "Fixed income": "Bloomberg TOMS", "Securities financing": "Bloomberg TOMS",
            "FX": "Wall Street Systems", "Money market": "Wall Street Systems"}
# identifiers that name a trade without its kind (pack.yaml graph.id-patterns)
ID_PATTERN = "^(" + "|".join(p for p, _, _ in SYSTEMS.values()) + r")-\d+$"


def system_of(asset_class: str) -> str:
    return BY_ASSET.get(asset_class, "Murex")


def sample_id(system: str, ordinal: int) -> tuple[str, str]:
    """The ordinal-th (1-based) sample trade of a system: (id, the system's own number)."""
    prefix, first, _ = SYSTEMS[system]
    native = str(first + ordinal)
    return f"{prefix}-{native}", native


def clone_id(system: str, ordinal: int) -> tuple[str, str]:
    """The ordinal-th (0-based) bulk clone booked in a system: (id, the system's own number)."""
    prefix, _, first = SYSTEMS[system]
    native = str(first + ordinal)
    return f"{prefix}-{native}", native
