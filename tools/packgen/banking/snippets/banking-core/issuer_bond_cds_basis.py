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

# title: Issuer: bond spreads against CDS, the basis
# description: The issuer's bonds read in full: yield, Z-spread over their benchmark curve recomputed from price (bullet, street convention), and the CDS spread at each bond's maturity interpolated on the issuer's credit curve; the CDS-bond basis (CDS less Z-spread, negative: the bond is cheap to the CDS), with the term structures drawn. Z-spread against a government curve, not the par-equivalent spread a desk would use: an approximation.
# kinds: issuer
# example: ISS ISS-GRANITE

from datetime import date
import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
as_of = date.fromisoformat(view.business_date or "2026-09-30")
cds = await drishti.get_async("credit-curve", doc["creditCurve"]) if doc.get("creditCurve") else None
cds_t = [q.tenor_years(p["tenor"]) for p in cds["points"]] if cds else []
cds_s = [p["spread"] for p in cds["points"]] if cds else []
rows = []
for b in doc.get("bonds") or []:
    bond = await drishti.get_async("bond", "BND-" + b["isin"])
    freq = {"Annual": 1, "Semi-annual": 2, "Quarterly": 4}.get((bond.get("terms") or {}).get("couponFrequency"), 2)
    years = (date.fromisoformat(bond["maturity"]) - as_of).days / 365.25
    ytm = q.bond_yield(bond["price"], bond["coupon"], years, freq)
    times, amounts, accrued = q.bond_cashflows(bond["coupon"], years, freq)
    bench = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", bond["benchmarkCurve"]))["points"])
    zs = q.z_spread(bond["price"] + accrued, times, amounts, bench) * 1e4
    cds_bp = float(np.interp(years, cds_t, cds_s)) if cds else None
    rows.append({"bond": bond["isin"], "coupon %": bond["coupon"] * 100, "maturity": bond["maturity"], "years": years,
                 "price": bond["price"], "yield %": ytm * 100, "Z-spread bp": zs, "Z-spread (master) bp": bond.get("zSpread"),
                 "CDS bp": cds_bp, "basis bp": cds_bp - zs if cds_bp is not None else None})
out = pd.DataFrame(rows).sort_values("years").reset_index(drop=True)
show(out, title=f"{view.id}: {doc.get('name', '')} ({doc.get('rating', '')}, {doc.get('sector', '')}), CDS curve {doc.get('creditCurve')}")
if cds:
    curve = pd.DataFrame({"years": cds_t, "CDS spread bp": cds_s})
    chart(curve, kind="line", x="years", y="CDS spread bp", title="CDS term structure")
    print("; ".join(f"{r['bond']}: basis {r['basis bp']:+.0f} bp ({'bond cheap to CDS' if r['basis bp'] < 0 else 'bond rich to CDS'})"
                    for _, r in out.iterrows()))
