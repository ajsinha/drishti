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

"""The exposure simulator of the test data (docs/guides/EXPOSURE_GUIDE.md section 23): what an upstream engine would deliver, small and
deterministic. It does not price anything: a trade's value on a path is its MtM today moved by a factor walk of its asset class and an
idiosyncratic walk of its own, amortising to zero at maturity:

    MTM_i(p, k) = A_i(t_k) * (MTM_i + s_c * N_i * d_i * (rho X_c(p, k) + sqrt(1 - rho^2) e_i(p, k)))

with A_i(t) = max(0, 1 - t / M_i), s_c a volatility per unit notional and year of the class, N_i the notional in USD, d_i the trade's
direction (+1 or -1, drawn once per trade), rho 0.7 (0.95 and every trade the same way for the wrong-way set), X_c a standard Brownian
walk per class correlated across classes by a fixed matrix, e_i the trade's own walk. Python's Random seeded by text: the classes'
walks by ``ccr|<RUN_ID>|walks``, a trade by ``ccr|<TRADE_ID>``, so any subset of trades gets the same paths it gets in the whole run.

The grid: 13 primary dates (1W .. 10Y, business days of New York) and a look-back date 10 business days before each that falls after the
business date (1W and 2W have none: their collateral is today's)."""
from __future__ import annotations

import math
import random
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
from samplegen.dates import Calendar, add_tenor  # noqa: E402

TENORS = ["1W", "2W", "1M", "2M", "3M", "6M", "9M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"]
LOOKBACK_DAYS = 10
CAL = Calendar.of("USNY")
CLASSES = ("rates", "fx", "credit", "equity", "commodity")
#: the asset classes of the trades (netting-set TRADES[].ASSET_CLASS) by factor
CLASS_OF = {"Rates": "rates", "Inflation": "rates", "Money market": "rates", "Fixed income": "rates", "Securities financing": "rates",
            "FX": "fx", "Credit": "credit", "Equity": "equity", "Structured": "equity", "Commodity": "commodity"}
#: volatility of a trade's value per unit notional and square-root year, by factor
VOL = {"rates": 0.012, "fx": 0.045, "credit": 0.025, "equity": 0.09, "commodity": 0.08}
#: the factors' correlations (rates, fx, credit, equity, commodity)
CORR = [[1.0, 0.3, 0.2, -0.1, 0.1],
        [0.3, 1.0, 0.1, 0.2, 0.3],
        [0.2, 0.1, 1.0, -0.4, 0.1],
        [-0.1, 0.2, -0.4, 1.0, 0.3],
        [0.1, 0.3, 0.1, 0.3, 1.0]]
RHO = 0.7
RHO_WRONG_WAY = 0.95


def cholesky(a: list[list[float]]) -> list[list[float]]:
    n = len(a)
    low = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(i + 1):
            s = math.fsum(low[i][k] * low[j][k] for k in range(j))
            low[i][j] = math.sqrt(a[i][i] - s) if i == j else (a[i][j] - s) / low[j][j]
    return low


def years(cob: date, d: date) -> float:
    return (d - cob).days / 365.0


def grid(cob: date) -> dict:
    """The run's grid: dates in order, roles, look-back map, times (years ACT/365 from the business date) and the tenors' labels."""
    primary = {CAL.adjust(add_tenor(cob, t)): t for t in TENORS}
    look = {}
    for d in primary:
        lb = CAL.add_business_days(d, -LOOKBACK_DAYS)
        if lb > cob:
            look[lb] = d
    dates = sorted(set(primary) | set(look))
    index = {d: i for i, d in enumerate(dates)}
    roles = ["PRIMARY" if d in primary else "LOOKBACK" for d in dates]
    lookback_of = [index[look[d]] if d in look and d not in primary else -1 for d in dates]
    return {"dates": dates, "roles": roles, "lookback_of": lookback_of, "times": [years(cob, d) for d in dates],
            "tenors": [primary.get(d) for d in dates]}


class Walks:
    """The classes' correlated standard Brownian walks on the grid, one per path (seeded by the run)."""

    def __init__(self, run_id: str, times: list[float], paths: int):
        r = random.Random(f"ccr|{run_id}|walks")
        low = cholesky(CORR)
        self.x = {c: [[0.0] * paths for _ in times] for c in CLASSES}
        for p in range(paths):
            level = [0.0] * len(CLASSES)
            prev = 0.0
            for k, t in enumerate(times):
                z = [r.gauss(0.0, 1.0) for _ in CLASSES]
                dt = math.sqrt(max(t - prev, 0.0))
                for i in range(len(CLASSES)):
                    level[i] += dt * math.fsum(low[i][j] * z[j] for j in range(i + 1))
                    self.x[CLASSES[i]][k][p] = level[i]
                prev = t


def trade_paths(trade: dict, cob: date, g: dict, walks: Walks, paths: int, wrong_way: bool = False) -> list[list[int]]:
    """A trade's MtM on every path at every grid date (whole USD), zero after its maturity."""
    r = random.Random(f"ccr|{trade['TRADE_ID']}")
    direction = 1 if wrong_way or r.random() < 0.5 else -1
    cls = CLASS_OF.get(trade.get("ASSET_CLASS"), "rates")
    rho = RHO_WRONG_WAY if wrong_way else RHO
    side = math.sqrt(1 - rho * rho)
    mtm = float(trade.get("MTM") or 0.0)
    scale = VOL[cls] * abs(float(trade.get("NOTIONAL") or 0.0)) * direction
    life = max(years(cob, date.fromisoformat(trade["MATURITY"])), 1 / 365)
    out = []
    eps = [0.0] * paths
    prev = 0.0
    for k, t in enumerate(g["times"]):
        dt = math.sqrt(max(t - prev, 0.0))
        for p in range(paths):
            eps[p] += dt * r.gauss(0.0, 1.0)
        prev = t
        a = max(0.0, 1.0 - t / life)
        x = walks.x[cls][k]
        out.append([round(a * (mtm + scale * (rho * x[p] + side * eps[p]))) if a > 0 else 0 for p in range(paths)])
    return out
