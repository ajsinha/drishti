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

"""How the banking taxonomy is split into packs, and which data domain (Delta Lake folder) holds each kind.

Data domains and packs are many-to-many: a domain's connector (one Delta Lake folder, later a database) serves
every pack that uses its kinds, and a pack may read several domains. A pack's `routes` tell the server which
connector answers each of its kinds; packs `require` the packs whose kinds they link to.
"""
from __future__ import annotations

# data domain -> lake folder name (data/delta/<domain>/<kind>/)
DOMAINS = {"reference": "Reference data: parties, organisation, legal agreements, calendars",
           "market": "Market data: curves, surfaces, prices, fixings",
           "trading": "Trades in every product",
           "risk": "Market-risk results: VaR, stress, FRTB, P&L explain",
           "credit": "Counterparty-credit results: exposure, CVA, SA-CCR, limits",
           "collateral": "Collateral and margin: balances, calls, SIMM"}

PACKS = {
    "banking-core": {
        "title": "Banking core", "requires": [],
        "description": "The parties, organisation and legal agreements every banking pack builds on.",
        "kinds": {"reference": ["counterparty", "counterparty-group", "issuer", "agreement", "ccp", "legal-entity", "book", "desk",
                                "trader", "calendar", "csa", "clearing-account"]}},
    "market-data": {
        "title": "Market data", "requires": ["banking-core"],
        "description": "Curves, volatility surfaces, FX, equities, credit, inflation, commodities, fixings and bonds.",
        "kinds": {"market": ["ir-curve", "repo-curve", "fx-spot", "fx-forward-curve", "fx-vol-surface", "ir-vol-cube", "cap-vol-surface",
                             "equity", "equity-index", "dividend-curve", "equity-vol-surface", "credit-curve", "inflation-index",
                             "inflation-curve", "commodity", "commodity-curve", "commodity-vol-surface", "rate-fixing", "bond",
                             "correlation-matrix"]}},
    "trading": {
        "title": "Trading", "requires": ["banking-core", "market-data"],
        "description": "Trades in 125 products across rates, FX, credit, equity, commodities, inflation, fixed income, "
                       "securities financing, money markets and structured products.",
        "kinds": {"trading": ["trade"]}},
    "market-risk": {
        "title": "Market risk", "requires": ["trading"],
        "description": "Value at risk, stress testing, FRTB sensitivities and P&L explain.",
        "kinds": {"risk": ["var", "stress-scenario", "stress-result", "frtb-sensitivity", "pnl-explain"]}},
    "counterparty-risk": {
        "title": "Counterparty credit risk", "requires": ["trading"],
        "description": "Netting sets, exposure and PFE, CVA/XVA, SA-CCR, credit limits, collateral, margin calls and SIMM.",
        "kinds": {"credit": ["netting-set", "credit-limit", "exposure-profile", "cva", "sa-ccr"],
                  "collateral": ["collateral-balance", "margin-call", "simm"]}},
}


def pack_of(kind: str) -> str:
    for name, p in PACKS.items():
        if any(kind in ks for ks in p["kinds"].values()):
            return name
    raise KeyError(kind)


def domain_of(kind: str) -> str:
    for p in PACKS.values():
        for d, ks in p["kinds"].items():
            if kind in ks:
                return d
    raise KeyError(kind)


def check(all_kinds: list[str]) -> None:
    placed = [k for p in PACKS.values() for ks in p["kinds"].values() for k in ks]
    assert len(placed) == len(set(placed)), "a kind is in two packs"
    missing = set(all_kinds) - set(placed)
    extra = set(placed) - set(all_kinds)
    assert not missing and not extra, f"layout mismatch: missing {missing}, unknown {extra}"
    for name, p in PACKS.items():
        for r in p["requires"]:
            assert r in PACKS, f"{name} requires unknown pack {r}"
