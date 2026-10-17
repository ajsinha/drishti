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

"""The reference data every source system's trades point at, taken from the banking generator's fictitious universe so a trade's
COUNTERPARTY, PORTFOLIO, LEGAL_ENTITY and TRADER_ID open the core pack's entities: counterparties (with the UEN and country of risk
the core pack gives them), books by desk, traders, legal entities and their transit numbers."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "banking"))
sys.path.insert(0, str(HERE.parents[1]))
sys.path.insert(0, str(HERE.parent))

import data_names as N  # noqa: E402

AS_OF = N.AS_OF


def counterparties() -> dict[str, dict]:
    """counterparty id -> {name, uen, country}: the same numbering as the core pack's counterparties (data_results.py)."""
    out = {}
    for n, (cp, (name, _typ, _sector, country, _rating, _grp)) in enumerate(sorted(N.COUNTERPARTIES.items()), start=1):
        out[f"CP-{cp}"] = {"name": name, "uen": f"UEN{100000 + n * 37:07d}", "country": country}
    return out


CPTY = counterparties()
BILATERAL = [c for c in CPTY if c != "CP-SUMMIT"]
LEGAL_ENTITIES = list(N.LEGAL_ENTITIES)
# a transit number per legal entity: digit text, leading zeros kept (an extract writes it as text)
TRANSIT = {le: f"{(i + 1) * 107:05d}" for i, le in enumerate(LEGAL_ENTITIES)}
DESK_OF = {"Rates": "DESK-RATES", "FX": "DESK-FX", "Credit": "DESK-CREDIT", "Equity": "DESK-EQD", "Commodity": "DESK-COMM",
           "Inflation": "DESK-INFL", "Fixed income": "DESK-FI", "Securities financing": "DESK-SFT", "Money market": "DESK-MM",
           "Structured": "DESK-STRUCT"}


def books_of(desk: str) -> list[str]:
    return [f"BOOK-{desk[5:]}-{k}" for k in (1, 2, 3)]


def traders_of(desk: str) -> list[str]:
    i = list(N.DESKS).index(desk)
    return [N.TRADERS[(2 * i) % len(N.TRADERS)], N.TRADERS[(2 * i + 1) % len(N.TRADERS)]]


def trader_id(name: str) -> str:
    return "TRDR-" + "".join(c for c in name if c.isalpha()).upper()


def trader_names(name: str) -> tuple[str, str]:
    """'A. Shah' -> ('A.', 'Shah'): first and last name as a booking system keeps them."""
    first, _, last = name.partition(" ")
    return first, last


def legal_entity_of(desk: str) -> str:
    return N.DESKS[desk][1]
