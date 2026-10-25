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

"""The synthetic market behind the demo P&L vectors (VAR_DATA_MODEL.md section 16): daily moves of about forty risk factors over the
509 scenario days, plus one more day (the business date's own move, for the hypothetical P&L the backtest compares with VaR).

What makes the vectors realistic enough for a VaR demo:

- **Correlated by risk factor:** each factor moves with a global factor and its group's factor (rates, FX, credit, equity, commodity,
  inflation, volatility) plus its own noise, so a rates desk and a credit desk diversify, two USD rates books barely do.
- **Fat left tail:** every shock is Student-t with four degrees of freedom, a stress episode (four weeks, two and a half times the
  volatility) sits inside the window, and a few crash days move everything down at once.
- **Volatility clustering:** a GARCH(1,1) variance per group.

Deterministic: the same seed gives the same moves on every machine (Python's own Random, no numpy)."""
from __future__ import annotations

import hashlib
import math
import random

N_SCENARIOS = 509
#: factor -> (group, weight of the global factor, weight of the group factor)
FACTORS: dict[str, tuple[str, float, float]] = {}
for _c in ("USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "EM"):
    FACTORS[f"IR.{_c}"] = ("IR", 0.25, 0.75 if _c in ("USD", "EUR", "GBP") else 0.55)
for _c in ("EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNH", "BRL", "MXN", "EM"):
    FACTORS[f"FX.{_c}"] = ("FX", 0.30, 0.55)
for _c in ("IG", "HY", "EM"):
    FACTORS[f"CR.{_c}"] = ("CR", 0.55, 0.65)
for _c in ("US", "EU", "JP", "EM"):
    FACTORS[f"EQ.{_c}"] = ("EQ", 0.70, 0.55)
for _c in ("ENERGY", "METALS", "AGRI", "POWER"):
    FACTORS[f"CO.{_c}"] = ("CO", 0.35, 0.60)
for _c in ("USD", "EUR", "GBP"):
    FACTORS[f"INFL.{_c}"] = ("INFL", 0.15, 0.70)
for _c in ("FX", "EQ", "IR"):
    FACTORS[f"VOL.{_c}"] = ("VOL", -0.60, 0.55)          # volatility rises when markets fall
GROUPS = sorted({g for g, _, _ in FACTORS.values()})


def seeded(*parts) -> random.Random:
    """A generator seeded by text: the same parts give the same draws everywhere."""
    return random.Random(int(hashlib.sha256("/".join(map(str, parts)).encode()).hexdigest()[:12], 16))


def student_t(r: random.Random, dof: int = 4) -> float:
    """A Student-t draw scaled to unit variance."""
    z = r.gauss(0.0, 1.0)
    chi = sum(r.gauss(0.0, 1.0) ** 2 for _ in range(dof)) / dof
    return z / math.sqrt(chi) / math.sqrt(dof / (dof - 2))


class Market:
    """Daily factor moves: ``moves[f][j]`` for scenario day j (0 the oldest, ``n - 1`` the newest) and ``n`` the business date."""

    def __init__(self, seed: str = "drishti-var-demo", n: int = N_SCENARIOS):
        self.n = n
        days = n + 1
        r = seeded(seed, "market")
        stress_start = int(n * 0.62)                      # a stress episode inside the last 250 days
        stress = {stress_start + i for i in range(20)}
        crashes = {stress_start + 3, stress_start + 9, int(n * 0.31), int(n * 0.86)}
        scale = [2.5 if j in stress else 1.0 for j in range(days)]
        global_ = self._garch(r, days, scale, crashes, skew=True)
        group = {g: self._garch(seeded(seed, "group", g), days, scale, crashes, skew=g in ("CR", "EQ", "CO")) for g in GROUPS}
        self.moves: dict[str, list[float]] = {}
        for f, (g, wg, wh) in FACTORS.items():
            fr = seeded(seed, "factor", f)
            own = math.sqrt(max(0.05, 1.0 - wg * wg - wh * wh))
            self.moves[f] = [wg * global_[j] + wh * group[g][j] + own * student_t(fr) * scale[j] for j in range(days)]

    @staticmethod
    def _garch(r: random.Random, days: int, scale: list[float], crashes: set[int], skew: bool) -> list[float]:
        """Unit-variance moves with GARCH(1,1) clustering, Student-t shocks and, when ``skew``, crash days that only go down."""
        omega, alpha, beta = 0.05, 0.10, 0.85
        var = 1.0
        out = []
        prev = 0.0
        for j in range(days):
            var = omega + alpha * prev * prev + beta * var
            x = student_t(r) * math.sqrt(var) * scale[j]
            if j in crashes:
                x = -abs(x) - 3.5 * scale[j] if skew else x * 2.0
            out.append(x)
            prev = x / scale[j]
        return out


#: words in a product type that make it an option (convex: short gamma, its P&L is skewed to the left)
OPTION_WORDS = ("OPTION", "OPT", "SWAPTION", "BARRIER", "DIGITAL", "TOUCH", "CAP", "FLOOR", "TARF", "ACCUMULATOR", "VARIANCE", "CALLNOT",
                "CALLABLE", "PUTTABLE")
EM = {"CNH", "BRL", "MXN", "INR", "ZAR", "TRY", "KRW", "SGD", "HKD"}


def is_option(product_type: str) -> bool:
    p = (product_type or "").upper()
    return any(w in p for w in OPTION_WORDS)


def _ccy(c: str, known: tuple[str, ...]) -> str:
    c = (c or "USD").upper()
    return c if c in known else ("EM" if c in EM else "USD" if "USD" in known else known[0])


def loadings(group: str, product_type: str, sub_type: str, currency: str) -> dict[str, float]:
    """The factors one unit of an instrument moves with, by product group, type and currency (synthetic but plausible)."""
    p, s = (product_type or "").upper(), (sub_type or "").upper()
    ir = "IR." + _ccy(currency, ("USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "EM"))
    fx = "FX." + _ccy(currency if (currency or "USD").upper() != "USD" else "EUR", ("EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "CNH", "BRL", "MXN", "EM"))
    infl = "INFL." + _ccy(currency, ("USD", "EUR", "GBP"))
    eq = "EQ." + {"USD": "US", "EUR": "EU", "GBP": "EU", "CHF": "EU", "JPY": "JP"}.get((currency or "USD").upper(), "EM")
    g = (group or "").lower()
    if p in ("COMSWAP", "COMFUT", "COMMODITY", "CALLNOT") or "comm" in g:
        sector = "CO.METALS" if any(w in s for w in ("METAL", "GOLD", "SILV", "COPP", "ALUM")) else \
            "CO.AGRI" if any(w in s for w in ("AGRI", "CORN", "WHEAT", "SOY")) else "CO.POWER" if "POW" in s else "CO.ENERGY"
        return {sector: 1.0, "FX.EUR": 0.05} if p != "CALLNOT" else {sector: 0.6, "VOL.EQ": 0.3}
    if p == "CASH":
        return {ir: 0.15, fx: 0.25}
    if g == "fx":
        out = {fx: 1.0}
        if is_option(p):
            out["VOL.FX"] = 0.45
        return out
    if g == "credit":
        hy = any(w in p for w in ("TRANCHE", "LOAN", "CLN", "REVOLVER", "HY", "RISK_PART"))
        return {"CR.HY" if hy else "CR.IG": 1.0, ir: 0.2}
    if g in ("equity", "equities"):
        out = {eq: 1.0}
        if is_option(p) or "VAR" in p:
            out["VOL.EQ"] = 0.4
        return out
    if g == "inflation":
        return {infl: 1.0, ir: 0.35}
    if g in ("fixed income", "bonds"):
        out = {ir: 1.0}
        if any(w in p for w in ("CORP", "FRN", "COVERED", "CALLABLE", "PUTTABLE")):
            out["CR.IG"] = 0.5
        if any(w in p for w in ("ABS", "MBS", "CMO", "CLO")):
            out["CR.HY"] = 0.45
        return out
    if g in ("structured", "structured products"):
        return {eq: 0.6, ir: 0.5, "VOL.EQ": 0.35}
    if g in ("money markets", "securities financing", "sft", "repo"):
        return {ir: 0.4, "CR.IG": 0.15}
    out = {ir: 1.0}
    if is_option(p):
        out["VOL.IR"] = 0.35
    return out


#: one-sigma daily P&L of a typical position, in USD, by product group (scaled per instrument by a lognormal draw)
BASE_RISK = {"rates": 160_000, "fixed income": 120_000, "credit": 130_000, "fx": 110_000, "equity": 150_000, "equities": 150_000,
             "inflation": 90_000, "money markets": 18_000, "securities financing": 25_000, "structured": 240_000, "structured products": 240_000,
             "commodity": 210_000, "": 210_000}


def unit_path(market: Market, load: dict[str, float], option: bool, r: random.Random) -> list[float]:
    """One unit of an instrument over every day of the market (scenario days and the business date's move): its factor moves,
    with a short-gamma term for an option (losses grow faster than gains)."""
    days = market.n + 1
    out = [0.0] * days
    for f, w in load.items():
        mv = market.moves[f]
        for j in range(days):
            out[j] += w * mv[j]
    if option:
        g = 0.18 + 0.12 * r.random()
        for j in range(days):
            out[j] -= g * out[j] * out[j]
    return out
