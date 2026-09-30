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

"""Swap and bond legs with full calculation periods: accrual dates, fixing dates, year fractions, rates (fixed,
published fixings for past periods, projected forwards for future ones), amounts, discount factors and PVs."""
from __future__ import annotations

from datetime import date

from .curves import Curve
from .dates import Calendar, iso, schedule, year_fraction


def _status(pay: date, fix: date, as_of: date) -> str:
    return "Settled" if pay <= as_of else "Fixed" if fix <= as_of else "Projected"


def fixed_leg(n: int, pay: bool, notional: float, ccy: str, rate: float, start: date, end: date, frequency: str,
              basis: str, cal: Calendar, disc: Curve, as_of: date, pay_lag: int = 2, convention: str = "MODFOLLOWING") -> dict:
    sign = -1 if pay else 1
    flows = []
    for i, (a, b) in enumerate(schedule(start, end, frequency, cal, convention), 1):
        paydate = cal.add_business_days(b, pay_lag)
        dcf = year_fraction(a, b, basis)
        amount = sign * notional * rate * dcf
        df = disc.df(paydate) if paydate > as_of else 1.0
        flows.append({"n": i, "accrualStart": iso(a), "accrualEnd": iso(b), "payDate": iso(paydate), "notional": notional,
                      "rate": rate, "yearFraction": round(dcf, 6), "amount": round(amount, 2), "df": round(df, 6),
                      "pv": round(amount * df if paydate > as_of else 0.0, 2), "status": _status(paydate, a, as_of)})
    return {"leg": n, "label": f"{'Pay' if pay else 'Receive'} fixed {rate * 100:.4f}%", "payer": pay, "type": "FIXED",
            "currency": ccy, "notional": notional, "rate": rate, "dayCount": basis, "frequency": frequency,
            "businessDay": {"MODFOLLOWING": "Mod. following", "FOLLOWING": "Following"}[convention], "calendar": cal.name,
            "paymentLag": f"{pay_lag} business days", "rollConvention": str(end.day), "stub": "Short initial" if flows and
            flows[0]["accrualStart"] != iso(start) else "None", "cashflows": flows,
            "pv": round(sum(f["pv"] for f in flows), 2), "accrued": 0.0}


def float_leg(n: int, pay: bool, notional: float, ccy: str, index: str, spread: float, start: date, end: date,
              frequency: str, basis: str, cal: Calendar, disc: Curve, proj: Curve, as_of: date, fixings=None,
              compounding: str | None = "Compounded in arrears", lookback: int = 2, pay_lag: int = 2,
              convention: str = "MODFOLLOWING") -> dict:
    sign = -1 if pay else 1
    basis_days = 365.0 if basis.startswith("ACT/365") else 360.0
    flows, next_reset = [], None
    for i, (a, b) in enumerate(schedule(start, end, frequency, cal, convention), 1):
        paydate = cal.add_business_days(b, pay_lag)
        fix = cal.add_business_days(b if compounding else a, -lookback)
        dcf = year_fraction(a, b, basis)
        status = _status(paydate, fix, as_of)
        if status != "Projected" and fixings:
            rate = fixings(fix)
        else:
            rate = proj.forward(max(a, as_of), b, basis_days)
            next_reset = next_reset or iso(max(a, as_of))
        amount = sign * notional * (rate + spread) * dcf
        df = disc.df(paydate) if paydate > as_of else 1.0
        flows.append({"n": i, "accrualStart": iso(a), "accrualEnd": iso(b), "fixingDate": iso(fix), "payDate": iso(paydate),
                      "notional": notional, "index": index, "rate": round(rate, 6), "spread": spread, "yearFraction": round(dcf, 6),
                      "amount": round(amount, 2), "df": round(df, 6), "pv": round(amount * df if paydate > as_of else 0.0, 2),
                      "status": status})
    label_rate = index + ("" if not spread else f" {'+' if spread > 0 else '−'} {abs(spread) * 1e4:.0f} bp")
    return {"leg": n, "label": f"{'Pay' if pay else 'Receive'} {label_rate}" + (" compounded" if compounding else ""),
            "payer": pay, "type": "FLOAT", "currency": ccy, "notional": notional, "index": index, "spread": spread,
            "dayCount": basis, "frequency": frequency, "businessDay": {"MODFOLLOWING": "Mod. following", "FOLLOWING": "Following"}[convention],
            "calendar": cal.name, "paymentLag": f"{pay_lag} business days", "compounding": compounding,
            "observation": f"Lookback {lookback} business days, no observation shift" if compounding else f"Fixing {lookback} business days before start",
            "nextReset": next_reset, "projectedFlows": sum(1 for f in flows if f["status"] == "Projected"), "cashflows": flows,
            "pv": round(sum(f["pv"] for f in flows), 2)}


def annuity(leg: dict) -> float:
    """Sum of notional x year fraction x DF over unsettled periods: the PV of 1 (decimal) of fixed rate."""
    return sum(f["notional"] * f["yearFraction"] * f["df"] for f in leg["cashflows"] if f["status"] != "Settled")


def reprice_fixed(leg: dict, rate: float) -> dict:
    """Sets a fixed leg's rate and recomputes its amounts and PVs."""
    sign = -1 if leg["payer"] else 1
    leg["rate"] = rate
    leg["label"] = f"{'Pay' if leg['payer'] else 'Receive'} fixed {rate * 100:.4f}%"
    for f in leg["cashflows"]:
        f["rate"] = rate
        f["amount"] = round(sign * f["notional"] * rate * f["yearFraction"], 2)
        f["pv"] = round(f["amount"] * f["df"], 2) if f["status"] != "Settled" else 0.0
    leg["pv"] = round(sum(f["pv"] for f in leg["cashflows"]), 2)
    return leg


def scale_projected(leg: dict, target_pv: float) -> dict:
    """Scales the projected flows of a float leg so the leg PV equals a known valuation (keeps fixed/settled flows)."""
    fixed = sum(f["pv"] for f in leg["cashflows"] if f["status"] != "Projected")
    proj = sum(f["pv"] for f in leg["cashflows"] if f["status"] == "Projected")
    k = (target_pv - fixed) / proj if proj else 1.0
    for f in leg["cashflows"]:
        if f["status"] == "Projected":
            f["rate"] = round(f["rate"] * k, 6)
            f["amount"] = round(f["amount"] * k, 2)
            f["pv"] = round(f["pv"] * k, 2)
    leg["pv"] = round(target_pv, 2)
    return leg


def dv01_by_tenor(total: float, tenors: list[str], maturity_years: float) -> list[dict]:
    """Bucketed DV01 whose buckets sum to the total, weighted towards the maturity bucket."""
    from .dates import tenor_years
    live = [t for t in tenors if tenor_years(t) <= maturity_years + 0.01] or tenors[:1]
    weights = [tenor_years(t) ** 1.3 for t in live]
    s = sum(weights)
    out = [{"tenor": t, "dv01": round(total * w / s)} for t, w in zip(live, weights)]
    out[-1]["dv01"] += round(total) - sum(x["dv01"] for x in out)
    return out
