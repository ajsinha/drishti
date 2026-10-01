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

# title: Swap key-rate DV01 by bump and reprice
# description: The swap's unsettled cashflows repriced with each pillar of its curves moved 1 bp in turn (discount and projection curves bumped together, triangular bumps): key-rate DV01 by pillar from full revaluation, their sum against a parallel bump, and the booked DV01 and bucketed sensitivities beside them.
# kinds: trade
# example: TRD MX-20000001

from datetime import date
import pandas as pd
from drishti import quant as q

doc = view.doc
if not doc.get("legs") or not doc.get("discountCurve"):
    raise ValueError(f"{view.id} has no legs or discount curve: this snippet is for swaps")
as_of = date.fromisoformat(view.business_date or "2026-09-30")
yrs = lambda d: (date.fromisoformat(d) - as_of).days / 365.0                       # noqa: E731
disc_doc = await drishti.get_async("ir-curve", doc["discountCurve"])
proj_doc = await drishti.get_async("ir-curve", doc["forwardCurve"]) if doc.get("forwardCurve") else disc_doc
disc0, proj0 = q.ZeroCurve.from_points(disc_doc["points"]), q.ZeroCurve.from_points(proj_doc["points"])


def pv(disc, proj):
    total = 0.0
    for leg in doc["legs"]:
        sign = -1 if leg.get("payer") else 1
        for cf in leg["cashflows"]:
            t = yrs(cf["payDate"])
            if cf.get("status") == "Settled" or t <= 0:
                continue
            amount = cf["amount"]
            if leg["type"] == "FLOAT" and yrs(cf["accrualStart"]) > 0:
                fwd = proj.forward(yrs(cf["accrualStart"]), yrs(cf["accrualEnd"]))
                amount = sign * cf["notional"] * (fwd + (cf.get("spread") or 0)) * cf["yearFraction"]
            total += amount * disc.df(t)
    return total


labels = sorted((p["tenor"] for p in disc_doc["points"]), key=q.tenor_years)
rows = []
for i, label in enumerate(labels):                                                # same pillars on both curves
    up = pv(disc0.key_rate_bumped(i, 1), proj0.key_rate_bumped(i, 1) if len(proj0.times) == len(disc0.times) else proj0)
    down = pv(disc0.key_rate_bumped(i, -1), proj0.key_rate_bumped(i, -1) if len(proj0.times) == len(disc0.times) else proj0)
    rows.append({"pillar": label, "DV01": (up - down) / 2})
krd = pd.DataFrame(rows)
krd = krd[krd["DV01"].abs() > 1].reset_index(drop=True)
parallel = (pv(disc0.shifted(1), proj0.shifted(1)) - pv(disc0.shifted(-1), proj0.shifted(-1))) / 2
show(krd, title=f"{view.id}: key-rate DV01 by full revaluation, per +1 bp ({doc['currency']})")
chart(krd, kind="bar", x="pillar", y="DV01", title="Key-rate DV01")
show(pd.DataFrame({"measure": ["sum of key rates", "parallel +1 bp", "booked risk.dv01", "booked buckets (sum)"],
                   "value": [krd["DV01"].sum(), parallel, (doc.get("risk") or {}).get("dv01"),
                             sum(s.get("dv01", 0) for s in doc.get("sensitivities") or [])]}),
     title="Against the booked risk (the booked figures are the source system's, in its own conventions)")
