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

"""SA-CCR (Basel Framework CRE52) by exactly the rules of the server's engine (com.ash.drishti.compute.saccr.Saccr;
docs/guides/SACCR_GUIDE.md), in plain Python, for the tests only: the test data's reference figures and the hand-built cases are checked
against it, and the Java engine's answers equal it to the cent (drishti-compute's parity test reads reference-saccr.json). Every risk
figure the product shows is the Java engine's (the server computes risk); this module is never imported by the console.

A trade is a dict of the ``saccr-trade`` fields with its times already in years: ``S``, ``E``, ``M``, ``T`` (ACT/365 from the business
date), ``D_NOTIONAL`` (the adjusted-notional basis: NOTIONAL_USD, or QUANTITY x UNDERLYING_PRICE) and ``MTM``. A netting set is a dict
with ``V`` (None: the sum of the trades' MTM), ``C``, ``NICA``, ``TH``, ``MTA``, ``MARGINED``, ``CLEARED``, ``MPOR_DAYS``,
``MARGIN_FREQUENCY_DAYS``, ``DISPUTES``, ``ILLIQUID``, ``TRADES_IN_SET``.
"""
from __future__ import annotations

import math

ALPHA, FLOOR, RATE = 1.4, 0.05, 0.05
MIN_YEARS = 10 / 250                      # 10 business days, a year of 250 business days
SF = {"IR": 0.005, "FX": 0.04}
SIGMA = {"IR": 0.50, "FX": 0.15}
CREDIT_SINGLE = {"AAA": 0.0038, "AA": 0.0038, "A": 0.0042, "BBB": 0.0054, "BB": 0.0106, "B": 0.016, "CCC": 0.06}
CREDIT_INDEX = {"IG": 0.0038, "SG": 0.0106}


def phi(x: float) -> float:
    """The standard normal distribution function (math.erfc: double precision, as Excel's NORM.S.DIST)."""
    return 0.5 * math.erfc(-x / math.sqrt(2))


def sd(s: float, e: float) -> float:
    return (math.exp(-RATE * s) - math.exp(-RATE * e)) / RATE


def supervisory(t: dict) -> tuple[float, float, float]:
    """(SF, rho, sigma) of a trade, before the basis (x0.5) and volatility (x5) factors."""
    ac, idx = t["ASSET_CLASS"], bool(t.get("IS_INDEX"))
    if ac in ("IR", "FX"):
        return SF[ac], 1.0, SIGMA[ac]
    if ac == "CREDIT":
        q = t.get("CREDIT_QUALITY")
        return (CREDIT_INDEX[q], 0.8, 0.80) if idx else (CREDIT_SINGLE[q], 0.5, 1.00)
    if ac == "EQUITY":
        return (0.20, 0.8, 0.75) if idx else (0.32, 0.5, 1.20)
    return (0.40, 0.4, 1.50) if t.get("REFERENCE") == "ELECTRICITY" else (0.18, 0.4, 0.70)


def delta(t: dict, sigma: float) -> float:
    sign = 1.0 if t["DIRECTION"] == "LONG" else -1.0
    tt = t["TRANSACTION_TYPE"]
    if tt == "OPTION":
        lam = t.get("SHIFT") or 0.0
        p, k, T = t["UNDERLYING_FORWARD"] + lam, t["STRIKE"] + lam, t["T"]
        d1 = (math.log(p / k) + 0.5 * sigma * sigma * T) / (sigma * math.sqrt(T))
        bought = t["BOUGHT_SOLD"] == "BOUGHT"
        if t["OPTION_TYPE"] == "CALL":
            return phi(d1) if bought else -phi(d1)
        return -phi(-d1) if bought else phi(-d1)
    if tt == "CDO_TRANCHE":
        v = 15 / ((1 + 14 * t["ATTACHMENT"]) * (1 + 14 * t["DETACHMENT"]))
        return v if t["BOUGHT_SOLD"] == "SOLD" else -v
    return sign


def mpor(ns: dict) -> int:
    floor = 5 if ns.get("CLEARED") else 10
    if ns.get("TRADES_IN_SET", 0) > 5000 or ns.get("ILLIQUID"):
        floor = 20
    if ns.get("DISPUTES", 0) > 2:
        floor *= 2
    return max(floor, int(ns.get("MPOR_DAYS") or 0)) + int(ns.get("MARGIN_FREQUENCY_DAYS") or 1) - 1


def terms(t: dict, mf_margined: float) -> dict:
    sf, rho, sigma = supervisory(t)
    tt = t["TRANSACTION_TYPE"]
    sf *= 0.5 if tt == "BASIS" else 5.0 if tt == "VOLATILITY" else 1.0
    ac = t["ASSET_CLASS"]
    s, e = max(t.get("S") or 0.0, 0.0), max(t.get("E") or 0.0, MIN_YEARS)
    m = max(t["M"], MIN_YEARS)
    sdv = sd(s, e) if ac in ("IR", "CREDIT") else 1.0
    d = t["D_NOTIONAL"] * sdv
    dl = delta(t, sigma)
    mfu = math.sqrt(min(m, 1.0))
    bucket = (1 if e < 1 else 2 if e <= 5 else 3) if ac == "IR" else 0
    key = t.get("REFERENCE") or ""
    return {"SD": sdv, "d": d, "delta": dl, "MF_U": mfu, "MF_M": mf_margined, "D_U": dl * d * mfu, "D_M": dl * d * mf_margined, "SF": sf,
            "RHO": rho, "BUCKET": bucket, "KEY": key}


def addons(trades: list[dict], tm: list[dict], basis: str, offset: bool = True) -> dict:
    """Add-ons per asset class and hedging set on one basis (``U`` unmargined or ``M`` margined)."""
    hs: dict = {}
    for t, x in zip(trades, tm):
        hs.setdefault((t["ASSET_CLASS"], t["HEDGING_SET"]), []).append((t, x))
    out = {"IR": 0.0, "FX": 0.0, "CREDIT": 0.0, "EQUITY": 0.0, "COMMODITY": 0.0, "SETS": {}}
    for (ac, h), rows in hs.items():
        dkey = "D_" + basis
        if ac == "IR":
            b = [0.0, 0.0, 0.0]
            for _, x in rows:
                b[x["BUCKET"] - 1] += x[dkey]
            if offset:
                en = math.sqrt(max(0.0, b[0] ** 2 + b[1] ** 2 + b[2] ** 2 + 1.4 * b[0] * b[1] + 1.4 * b[1] * b[2] + 0.6 * b[0] * b[2]))
            else:
                en = abs(b[0]) + abs(b[1]) + abs(b[2])
            a = rows[0][1]["SF"] * en
        elif ac == "FX":
            a = rows[0][1]["SF"] * abs(sum(x[dkey] for _, x in rows))
        else:
            per: dict = {}
            for t, x in rows:
                k = per.setdefault(x["KEY"], [0.0, x["SF"], x["RHO"]])
                k[0] += x[dkey]
            ak = [(v[1] * v[0], v[2]) for v in per.values()]
            if ac == "COMMODITY":
                rho = 0.4
                a = math.sqrt((rho * sum(v for v, _ in ak)) ** 2 + (1 - rho * rho) * sum(v * v for v, _ in ak))
            else:
                a = math.sqrt(sum(r * v for v, r in ak) ** 2 + sum((1 - r * r) * v * v for v, r in ak))
        out["SETS"][(ac, h)] = a
        out[ac] += a
    out["AGG"] = out["IR"] + out["FX"] + out["CREDIT"] + out["EQUITY"] + out["COMMODITY"]
    return out


def multiplier(v_minus_c: float, addon: float) -> float:
    if addon <= 0:
        return 1.0
    return min(1.0, FLOOR + (1 - FLOOR) * math.exp(v_minus_c / (2 * (1 - FLOOR) * addon)))


def ead(ns: dict, trades: list[dict], offset: bool = True, cap: bool = True) -> dict:
    """The netting set's SA-CCR: RC, add-ons, multiplier, PFE, EAD margined and unmargined, the cap."""
    v = ns.get("V")
    if v is None:
        v = sum(t.get("MTM") or 0.0 for t in trades)
    c, nica = ns.get("C") or 0.0, ns.get("NICA") or 0.0
    margined = bool(ns.get("MARGINED"))
    mp = mpor(ns) if margined else 0
    mfm = 1.5 * math.sqrt(mp / 250) if margined else 0.0
    tm = [terms(t, mfm) for t in trades]
    u = addons(trades, tm, "U", offset)
    rc_u = max(v - c, 0.0)
    mult_u = multiplier(v - c, u["AGG"])
    ead_u = ALPHA * (rc_u + mult_u * u["AGG"])
    res = {"V": v, "C": c, "RC_U": rc_u, "ADDON_U": u, "MULT_U": mult_u, "PFE_U": mult_u * u["AGG"], "EAD_U": ead_u, "TERMS": tm, "MPOR": mp,
           "MF_M": mfm, "MARGINED": margined}
    if not margined:
        res.update(EAD=ead_u, CAPPED=False)
        return res
    m = addons(trades, tm, "M", offset)
    rc_m = max(v - c, (ns.get("TH") or 0.0) + (ns.get("MTA") or 0.0) - nica, 0.0)
    mult_m = multiplier(v - c, m["AGG"])
    ead_m = ALPHA * (rc_m + mult_m * m["AGG"])
    res.update(RC_M=rc_m, ADDON_M=m, MULT_M=mult_m, PFE_M=mult_m * m["AGG"], EAD_M=ead_m)
    res["EAD"] = min(ead_m, ead_u) if cap else ead_m
    res["CAPPED"] = cap and ead_u < ead_m
    return res
