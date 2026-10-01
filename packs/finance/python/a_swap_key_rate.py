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

# title: Swap repriced on the curve, with key-rate DV01
# description: The swap's projected cashflows repriced on its discount curve's pillars (log-linear on discount factors): fixed amounts as booked, floating amounts projected from the curve's simple forwards; each leg's PV against the booked one, then key-rate DV01 by bumping each pillar 1 bp in turn (full revaluation), set beside the booked DV01 by tenor.
# kinds: trade

from datetime import date
import pandas as pd
from drishti import quant as q

doc = view.doc
if not doc.get("legs") or not doc.get("discountCurve"):
    raise ValueError(f"{view.id} has no legs and discount curve: this snippet is for swaps (TRD IRS-48213)")
curve_doc = await drishti.get_async("curve", doc["discountCurve"])
base = q.ZeroCurve.from_points(curve_doc["pillars"])                  # zeroRate in %, df, ACT/365F
as_of = date.fromisoformat(view.business_date or "2026-09-30")
yrs = lambda d: (date.fromisoformat(d) - as_of).days / 365.0          # noqa: E731


def legs_pv(curve):
    out = {}
    for leg in doc["legs"]:
        sign = -1 if leg.get("payer") else 1
        pv = 0.0
        for cf in leg["cashflows"]:
            t = yrs(cf["payDate"])
            if t <= 0 or cf.get("status") == "Settled":
                continue
            amount = cf["amount"]
            if leg["type"] == "FLOAT" and yrs(cf["accrualStart"]) > 0:
                amount = sign * cf["notional"] * curve.forward(yrs(cf["accrualStart"]), yrs(cf["accrualEnd"])) * cf["yearFraction"]
            pv += amount * curve.df(t)
        out[leg["label"]] = pv
    return out


pv0 = legs_pv(base)
show(pd.DataFrame({"leg": list(pv0), "repriced PV": list(pv0.values()), "booked PV": [l["pv"] for l in doc["legs"]]}),
     title=f"{view.id}: {doc.get('product', '')} on {doc['discountCurve']}, MTM booked {doc['mtm']:,.0f}")
total0 = sum(pv0.values())
labels = [p["tenor"] for p in curve_doc["pillars"]]
krd = pd.DataFrame({"pillar": labels, "DV01": [(sum(legs_pv(base.key_rate_bumped(i, 1)).values()) - total0)
                                                  for i in range(len(labels))]})
krd = krd[krd["DV01"].abs() > 1].reset_index(drop=True)
booked = pd.DataFrame(doc.get("dv01ByTenor") or [])
show(krd, title=f"Key-rate DV01 (+1 bp), total {krd['DV01'].sum():,.0f}; booked DV01 {doc.get('dv01', '')}")
if len(booked):
    show(booked, title="Booked DV01 by tenor (the source's sign convention)")
chart(krd, kind="bar", x="pillar", y="DV01", title="Key-rate DV01 by pillar")
