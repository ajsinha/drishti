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

# title: Bond yield, duration, convexity and Z-spread
# description: The bond's yield to maturity solved from its clean price (street convention, bullet, coupons on a regular schedule back from maturity), Macaulay and modified duration, convexity and DV01 per 1m face, set beside the security master's yield and duration, and its Z-spread over the benchmark curve (continuously compounded), beside the master's.
# kinds: bond
# example: BND BND-GBGRAN031444

from datetime import date
import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
terms = doc.get("terms") or {}
FREQ = {"Annual": 1, "Semi-annual": 2, "Quarterly": 4, "Monthly": 12}.get(terms.get("couponFrequency"), 2)
as_of = date.fromisoformat(view.business_date or "2026-09-30")
years = (date.fromisoformat(doc["maturity"]) - as_of).days / 365.25
c, clean = doc["coupon"], doc["price"]

ytm = q.bond_yield(clean, c, years, FREQ)                              # clean price -> yield (Brent)
risk = q.bond_risk(ytm, c, years, FREQ)
times, amounts, accrued = q.bond_cashflows(c, years, FREQ)
show(pd.DataFrame({
    "measure": ["years to maturity", "clean price", "accrued", "dirty price", "yield (recomputed)", "yield (master)",
                "Macaulay duration", "modified duration", "modified duration (master)", "convexity", "DV01 per 1m face"],
    "value": [years, clean, accrued, risk["dirty"], f"{ytm:.4%}", f"{doc.get('yield', float('nan')):.4%}", risk["macaulay"],
              risk["modified"], doc.get("modDuration"), risk["convexity"], risk["dv01"] * 1e6 / 100],
}), title=f"{view.id}: {doc.get('issuerName', '')} {c:.3%} {doc['maturity']} ({terms.get('couponFrequency', '')}, {doc.get('rating', '')})")

if doc.get("benchmarkCurve"):
    bench = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", doc["benchmarkCurve"]))["points"])
    zs = q.z_spread(risk["dirty"], times, amounts, bench)
    flows = pd.DataFrame({"years": times, "cashflow": amounts, "benchmark zero %": bench.zero(times) * 100,
                          "df at Z-spread": np.exp(-(bench.zero(times) + zs) * times)})
    flows["PV"] = flows["cashflow"] * flows["df at Z-spread"]
    show(flows, title=f"Cashflows per 100 face on {doc['benchmarkCurve']} + Z-spread {zs * 1e4:.1f} bp "
                      f"(master: {doc.get('zSpread')} bp)")

ys = np.linspace(ytm - 0.03, ytm + 0.03, 25)
chart(pd.DataFrame({"yield %": ys * 100, "clean price": [q.bond_price(y, c, years, FREQ) for y in ys]}),
      kind="line", x="yield %", y="clean price", title="Price against yield: convexity")
