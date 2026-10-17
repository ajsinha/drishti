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

The bank books its trades in three systems, each feeding the trading pack's trade view through a connector of its own (one Delta table
each: trade_endur, trade_calypso, trade_murex). The product range of the taxonomy is split between Murex and Calypso; Endur books the
commodity desk's own deals (tools/packgen/trades/endur.py), numbered by plain numbers (1000001 ...). Ids are unique across the systems,
so a user who knows a Murex trade number types it as it is: MX-20000017.

    MUREX     rates, inflation, commodities (derivatives), equity, structured     MX-...    trade number
    CALYPSO   credit, FX, money markets, fixed income, securities financing        CLY-...   trade id
    ENDUR     commodity swaps, futures, physical deals, cash, call deposits         1000001   deal tracking number

The sample trades take the first numbers of each system; tools/packgen/trades/make_trades.py clones them from a separate, higher range
per system, so its clones never collide with the samples. The ranges are illustrative: real installations number as they are configured.
"""
from __future__ import annotations

SYSTEMS = {
    #  system      prefix   samples from   clones from
    "MUREX":       ("MX",   20_000_000,    30_000_000),
    "CALYPSO":     ("CLY",  3_000_000,     4_000_000),
}
BY_ASSET = {"Rates": "MUREX", "Inflation": "MUREX", "Commodity": "MUREX", "Equity": "MUREX", "Structured": "MUREX",
            "Credit": "CALYPSO", "FX": "CALYPSO", "Money market": "CALYPSO", "Fixed income": "CALYPSO", "Securities financing": "CALYPSO"}
# identifiers that name a trade without its kind (pack.yaml graph.id-patterns)
ID_PATTERN = "^(" + "|".join(p for p, _, _ in SYSTEMS.values()) + r")-\d+$"


def system_of(asset_class: str) -> str:
    return BY_ASSET.get(asset_class, "MUREX")


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
