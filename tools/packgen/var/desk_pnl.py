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

"""The demo's daily desk P&L (kind ``desk-pnl``, docs/guides/VAR_GUIDE.md section 15.16): what a bank's finance and front-office
systems send for regulatory backtesting, one row per desk and business date, generated (the product owner's decision of
REGULATORY_VAR_REPORTING.md section 8.2.9).

For the business date D the desk's **risk-theoretical P&L** is its scenario P&L of the scenario day before D (today's trades under the
move of that day: the figure the official record keeps as ``hypoPnl``, the same convention). From it:

- ``HYPOTHETICAL_PNL``: yesterday's positions revalued on today's market by front office: the risk-theoretical P&L plus a small
  pricing difference (front-office models are not the risk model's), 4% of the desk's daily P&L deviation;
- ``ACTUAL_PNL``: finance's clean P&L (fees, commissions, reserves, net interest income and intraday trading left out, as the US rule
  defines it): the hypothetical plus the day's new business and the positions' changes, 12% of the deviation;
- ``FEES_AND_COMMISSIONS``: what finance left out of the actual P&L, for the record (never part of the backtest).

The dates are the last 260 business days to the business date: the 259 scenario days whose 250-day window lies inside the scenario set,
and the business date itself (the synthetic official history a demo server may seed covers the same days, VAR_GUIDE.md section 18.10).
Deterministic: the same trades give the same rows.
"""
from __future__ import annotations

import statistics

import factors as F

#: the days of P&L written: 260 business days to the business date (a 250-day backtest needs 251 with the VaR of the day before)
DAYS = 260
HYPO_NOISE = 0.04
ACTUAL_NOISE = 0.12
SOURCE = "demo-finance"


def desk_vectors(demo) -> dict[str, list[float]]:
    """Each desk's scenario P&L in USD, as the server computes it (scenario PVs converted, P&L vectors as sent)."""
    b = demo.batch
    out: dict[str, list[float]] = {}
    for p in b.positions:
        desk = b.desk_of_book[p["TT_BOOK"]]
        acc = out.setdefault(desk, [0.0] * demo.n)
        for t in p["TRADE_IDS"]:
            v = b.engine_vectors.get(t) or b.trade_vectors[t]
            for j in range(demo.n):
                acc[j] += v[j]
    return dict(sorted(out.items()))


def rows(demo) -> list[dict]:
    """The desk-pnl rows, oldest date first, desks in order."""
    sd = demo.scenario_dates                                 # s_0 .. s_508, the last the business day before the business date
    n = len(sd)
    days = [(sd[j], j - 1) for j in range(n - DAYS + 1, n)] + [(demo.cob, n - 1)]    # (date, the scenario of its risk-theoretical P&L)
    out = []
    for desk, vec in desk_vectors(demo).items():
        dev = statistics.pstdev(vec) or 1.0
        le = demo.batch.le_of_desk.get(desk) or None
        for day, j in days:
            r = F.seeded("desk-pnl", desk, day)
            rtpl = vec[j]
            hypo = rtpl + r.gauss(0.0, HYPO_NOISE * dev)
            actual = hypo + r.gauss(0.0, ACTUAL_NOISE * dev)
            fees = abs(r.gauss(0.0, 0.02 * dev))
            out.append({"DESK_PNL_ID": f"DPNL-{desk}-{day}", "BUSINESS_DATE": day, "DESK_ID": desk, "LEGAL_ENTITY": le,
                        "ACTUAL_PNL": round(actual), "HYPOTHETICAL_PNL": round(hypo), "FEES_AND_COMMISSIONS": round(fees),
                        "CURRENCY": "USD", "SOURCE": SOURCE, "AS_OF": f"{day}T22:00:00Z"})
    out.sort(key=lambda x: (x["BUSINESS_DATE"], x["DESK_ID"]))
    return out
