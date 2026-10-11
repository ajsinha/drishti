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

"""The stand-in quant engine: Black-Scholes (European option), a fixed-coupon bond price and NPV, in plain Python (``math`` only).

Good enough for tests, demos and teaching; not a risk system. Conventions: rates, yields and volatilities are decimals (0.05, not 5), times are in
years, a bond pays ``freq`` coupons a year and its yield is compounded at that frequency."""
from __future__ import annotations

import math

from core.quant.engine import QuantEngine, QuantError


def _num(params: dict, name: str, default=None) -> float:
    v = params.get(name, default)
    if v is None:
        raise QuantError(f"missing parameter '{name}'")
    if isinstance(v, bool) or not isinstance(v, (int, float)) or math.isnan(v) or math.isinf(v):
        raise QuantError(f"parameter '{name}' must be a finite number")
    return float(v)


def _cdf(x: float) -> float:
    return 0.5 * math.erfc(-x / math.sqrt(2.0))


class StandInQuantEngine(QuantEngine):
    name = "stand-in"

    def instruments(self) -> dict[str, list[str]]:
        return {"black_scholes": ["spot", "strike", "rate", "vol", "years", "kind (call or put, default call)", "div (default 0)"],
                "bond": ["face (default 100)", "coupon", "yield_", "years", "freq (default 2)"],
                "npv": ["rate", "cashflows (a list; the first at time 0)"]}

    def price(self, instrument: str, **params) -> float:
        fn = {"black_scholes": self._black_scholes, "bond": self._bond, "npv": self._npv}.get(instrument)
        if fn is None:
            raise QuantError(f"unknown instrument '{instrument}' (this engine prices: {', '.join(self.instruments())})")
        return fn(params)

    @staticmethod
    def _black_scholes(p: dict) -> float:
        s, k, r, v, t, q = _num(p, "spot"), _num(p, "strike"), _num(p, "rate"), _num(p, "vol"), _num(p, "years"), _num(p, "div", 0.0)
        kind = str(p.get("kind", "call")).lower()
        if kind not in ("call", "put"):
            raise QuantError("kind is 'call' or 'put'")
        if s <= 0 or k <= 0 or v <= 0 or t <= 0:
            raise QuantError("spot, strike, vol and years must be positive")
        d1 = (math.log(s / k) + (r - q + 0.5 * v * v) * t) / (v * math.sqrt(t))
        d2 = d1 - v * math.sqrt(t)
        if kind == "call":
            return s * math.exp(-q * t) * _cdf(d1) - k * math.exp(-r * t) * _cdf(d2)
        return k * math.exp(-r * t) * _cdf(-d2) - s * math.exp(-q * t) * _cdf(-d1)

    @staticmethod
    def _bond(p: dict) -> float:
        face, coupon, y, years, freq = _num(p, "face", 100.0), _num(p, "coupon"), _num(p, "yield_"), _num(p, "years"), int(_num(p, "freq", 2))
        n = int(round(years * freq))
        if freq < 1 or n < 1 or face <= 0:
            raise QuantError("years and freq must give at least one coupon, and face must be positive")
        c, i = face * coupon / freq, y / freq
        if i <= -1:
            raise QuantError("yield_ is too low")
        return sum(c / (1 + i) ** k for k in range(1, n + 1)) + face / (1 + i) ** n

    @staticmethod
    def _npv(p: dict) -> float:
        flows = p.get("cashflows")
        if not isinstance(flows, (list, tuple)) or not flows or len(flows) > 10_000:
            raise QuantError("cashflows is a list of 1 to 10,000 numbers")
        r = _num(p, "rate")
        if r <= -1:
            raise QuantError("rate must be above -1")
        return sum(_num({"x": f}, "x") / (1 + r) ** t for t, f in enumerate(flows))
