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

# title: CDS position: mark, CS01 and jump to default
# description: The single-name CDS marked on the issuer's credit curve (term hazards as forward hazards, quarterly premium with half-period accrual on default, discounting on the currency's OIS curve): the par spread at its maturity, the risky annuity, the MTM at its running coupon for protection bought or sold, CS01 for a 1 bp spread bump, and jump to default at the curve's recovery, beside the booked figures.
# kinds: trade
# example: TRD CLY-3000003

from datetime import date
import numpy as np
import pandas as pd
from drishti import quant as q

doc, t = view.doc, view.doc.get("terms") or {}
if not doc.get("creditCurve") or "coupon" not in t:
    raise ValueError(f"{view.id} ({doc.get('productType')}) is not a single-name CDS")
curve = await drishti.get_async("credit-curve", doc["creditCurve"])
R = curve.get("recovery", 0.4)
knots = np.array([q.tenor_years(p["tenor"]) for p in curve["points"]])
lam = q.forward_hazards(knots, [p["hazard"] for p in curve["points"]])
disc = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", doc.get("discountCurve") or f"CRV-{doc['currency']}-OIS"))["points"])
T = max((date.fromisoformat(doc["maturityDate"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365, 0.25)
coupon = float(t["coupon"]) / 1e4
bought = t.get("protection", "Bought") == "Bought" if "protection" in t else "Buy" in doc["direction"]
N = doc["notional"]


def legs(hazards):
    pay = np.arange(T, 0, -0.25)[::-1]                       # quarterly back from maturity
    s = q.survival(pay, knots, hazards)
    s_prev = np.concatenate([[1.0], s[:-1]])
    taus = np.diff(np.concatenate([[0.0], pay]))
    rpv01 = np.sum(taus * disc.df(pay) * (s + s_prev) / 2)
    fine = np.linspace(0, T, max(int(T * 24), 2) + 1)
    sf = q.survival(fine, knots, hazards)
    return (1 - R) * np.sum(disc.df((fine[1:] + fine[:-1]) / 2) * (sf[:-1] - sf[1:])), rpv01


prot, rpv01 = legs(lam)
mtm_buyer = (prot - coupon * rpv01) * N                      # protection buyer: receives protection, pays the coupon
prot_up, rpv01_up = legs(lam + 1e-4 / (1 - R))
cs01_buyer = (prot_up - coupon * rpv01_up) * N - mtm_buyer
side = 1 if bought else -1
show(pd.DataFrame({
    "measure": ["protection", "years", "running coupon bp", "par spread bp", "RPV01", "MTM", "booked MTM", "CS01 (+1 bp)",
                "booked cs01", "jump to default", "booked jtd", "recovery"],
    "value": ["bought" if bought else "sold", T, coupon * 1e4, prot / rpv01 * 1e4, rpv01, side * mtm_buyer, doc["mtm"],
              side * cs01_buyer, (doc.get("risk") or {}).get("cs01"), side * N * (1 - R) - side * mtm_buyer,
              (doc.get("risk") or {}).get("jtd"), R],
}), title=f"{view.id}: CDS on {curve.get('issuerName', doc.get('issuer'))}, {N:,.0f} {doc['currency']}, {t.get('docClause', '')}")
print("Jump to default: the protection payment (1 - R) x notional, less the MTM already held")
