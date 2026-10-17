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
# description: The single-name CDS marked on a credit curve (term hazards as forward hazards, quarterly premium with half-period accrual on default, discounting on the leg's DISCOUNT_INDEX curve): the running coupon from the premium flows' RATE, the par spread at MATURITY_DATE, the risky annuity, the MTM for protection bought (BUY_SELL_CODE B) or sold, CS01 for a 1 bp spread bump and jump to default at the curve's RECOVERY. The common trade schema names no reference entity, so the curve is REFERENCE below, else the counterparty's own (CDS-<name>), else CDS-UST.
# kinds: trade
# example: TRD CLY-3000085

from datetime import date
import numpy as np
import pandas as pd
from drishti import quant as q

REFERENCE = None                                              # e.g. "CDS-SOLARIS": the reference entity's credit curve
doc = view.doc
leg = (doc.get("LEGS") or [{}])[0]
rates = [float(c["RATE"]) for c in leg.get("CASH_FLOWS") or [] if c.get("RATE") not in (None, "")]
if doc.get("PRODUCT_GROUP") != "Credit" or not rates:
    raise ValueError(f"{view.id} ({doc.get('PRODUCT_TYPE')}) is not a CDS with premium flows")
curve = None
for cid in [REFERENCE, "CDS-" + str(doc.get("COUNTERPARTY", "")).removeprefix("CP-"), "CDS-UST"]:
    try:
        curve = await drishti.get_async("credit-curve", cid) if cid else None
    except Exception:
        curve = None
    if curve:
        break
R = curve.get("RECOVERY", 0.4)
knots = np.array([q.tenor_years(p["TENOR"]) for p in curve["POINTS"]])
lam = q.forward_hazards(knots, [p["HAZARD"] for p in curve["POINTS"]])
disc = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", leg.get("DISCOUNT_INDEX") or f"CRV-{doc['CURRENCY']}-OIS"))["POINTS"])
T = max((date.fromisoformat(doc["MATURITY_DATE"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365, 0.25)
coupon, N, side = rates[0], float(leg.get("NOTIONAL") or doc.get("QUANTITY") or 0), (1 if doc.get("BUY_SELL_CODE") == "B" else -1)


def legs(hazards):
    pay = np.arange(T, 0, -0.25)[::-1]                       # quarterly back from maturity
    s = q.survival(pay, knots, hazards)
    rpv01 = np.sum(np.diff(np.concatenate([[0.0], pay])) * disc.df(pay) * (s + np.concatenate([[1.0], s[:-1]])) / 2)
    fine = np.linspace(0, T, max(int(T * 24), 2) + 1)
    sf = q.survival(fine, knots, hazards)
    return (1 - R) * np.sum(disc.df((fine[1:] + fine[:-1]) / 2) * (sf[:-1] - sf[1:])), rpv01


prot, rpv01 = legs(lam)
mtm_buyer = (prot - coupon * rpv01) * N                      # protection buyer: receives protection, pays the coupon
prot_up, rpv01_up = legs(lam + 1e-4 / (1 - R))
cs01_buyer = (prot_up - coupon * rpv01_up) * N - mtm_buyer
show(pd.DataFrame({
    "measure": ["protection", "credit curve", "years", "running coupon bp", "par spread bp", "RPV01", "MTM", "CS01 (+1 bp)", "jump to default", "recovery"],
    "value": ["bought" if side > 0 else "sold", curve["CURVE_ID"], T, coupon * 1e4, prot / rpv01 * 1e4, rpv01, side * mtm_buyer,
              side * cs01_buyer, side * N * (1 - R) - side * mtm_buyer, R],
}), title=f"{view.id}: CDS on {curve.get('ISSUER_NAME', curve['CURVE_ID'])}, {N:,.0f} {doc['CURRENCY']}")
print("Jump to default: the protection payment (1 - R) x notional, less the MTM already held")
