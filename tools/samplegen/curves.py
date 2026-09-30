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

"""Yield curves: pillars of continuously compounded zero rates, log-linear discount factors, forward rates, and
the pillar table (instrument, quote, zero, DF) a curve-building system would publish."""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date

from .dates import add_tenor, tenor_years


@dataclass
class Curve:
    curve_id: str
    currency: str
    as_of: date
    tenors: list[str]
    zeros: list[float]              # decimal, continuously compounded, ACT/365F

    def _t(self, d: date) -> float:
        return max((d - self.as_of).days / 365.0, 0.0)

    def zero(self, t: float) -> float:
        ts = [tenor_years(x) for x in self.tenors]
        if t <= ts[0]:
            return self.zeros[0]
        for i in range(1, len(ts)):
            if t <= ts[i]:
                w = (t - ts[i - 1]) / (ts[i] - ts[i - 1])
                return self.zeros[i - 1] + w * (self.zeros[i] - self.zeros[i - 1])
        return self.zeros[-1]

    def df(self, d: date) -> float:
        t = self._t(d)
        return math.exp(-self.zero(t) * t)

    def forward(self, a: date, b: date, basis_days: float = 360.0) -> float:
        """Simple forward rate for [a, b] on the given money-market basis."""
        tau = max((b - a).days, 1) / basis_days
        return (self.df(a) / self.df(b) - 1) / tau

    def pillars(self, instruments: dict[str, str] | None = None) -> list[dict]:
        out = []
        for tenor, z in zip(self.tenors, self.zeros):
            d = add_tenor(self.as_of, tenor)
            t = tenor_years(tenor)
            kind = (instruments or {}).get(tenor) or ("Deposit" if t < 0.3 else "Future" if t < 1.5 else "Swap")
            par = (1 - math.exp(-z * t)) / max(t, 1e-9)
            out.append({"tenor": tenor, "maturity": d.isoformat(), "instrument": kind, "quote": round(par, 6),
                        "zeroRate": round(z * 100, 4), "df": round(math.exp(-z * t), 6)})
        return out
