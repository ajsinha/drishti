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

# title: Forward points, implied yield differential and basis
# description: Outrights from spot and forward points, the yield differential the forwards imply (ln(F/S)/T, continuously compounded) against the two currencies' OIS curves, and the gap between them: a rough cross-currency basis (it ignores spot lag, day counts and the CSA).
# kinds: fx-forward-curve
# example: FXF FXF-EURUSD

import math
import pandas as pd
from drishti import quant as q

doc = view.doc
spot_doc = await drishti.get_async("fx-spot", doc.get("FX_SPOT") or f"FX-{doc['PAIR']}")
conv = spot_doc.get("CONVENTIONS") or {}
base, quote, pip = conv.get("BASE", doc["PAIR"][:3]), conv.get("QUOTE", doc["PAIR"][3:]), conv.get("PIP_FACTOR", 10000)
S = doc["SPOT"]


async def ois(ccy):                                   # the currency's OIS curve, if Drishti has one
    try:
        return q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{ccy}-OIS"))["POINTS"])
    except drishti.DrishtiError:
        return None

curve_b, curve_q = await ois(base), await ois(quote)
rows = []
for p in doc["POINTS"]:
    T = q.tenor_years(p["TENOR"])
    F = S + p["PIPS"] / pip
    implied = math.log(F / S) / T                     # r_quote - r_base under covered interest parity
    row = {"TENOR": p["TENOR"], "years": T, "POINTS": p["PIPS"], "OUTRIGHT": F, "outright (curve)": p.get("OUTRIGHT"),
           "implied r_quote - r_base %": implied * 100}
    if curve_b and curve_q:
        ois_diff = curve_q.zero(T) - curve_b.zero(T)
        row["OIS r_quote - r_base %"] = ois_diff * 100
        row["basis bp"] = (implied - ois_diff) * 1e4  # what the forwards add to the OIS differential
    rows.append(row)
out = pd.DataFrame(rows).set_index("TENOR")
show(out, title=f"{base}/{quote}: spot {S}, points in 1/{pip:,} of {quote}")
y = ["implied r_quote - r_base %"] + (["OIS r_quote - r_base %"] if curve_b and curve_q else [])
chart(out.reset_index(), kind="line", x="TENOR", y=y, title="Implied yield differential against OIS")
one_year = out.loc["1Y"] if "1Y" in out.index else out.iloc[-1]
print(f"Holding {base} against {quote} for 1Y earns {-one_year['POINTS']:+,.1f} points of forward carry "
      f"({-(one_year['OUTRIGHT'] / S - 1):+.3%} of spot): {'positive' if one_year['POINTS'] < 0 else 'negative'} carry for a long {base}")
if not (curve_b and curve_q):
    print(f"No OIS curve for {base if not curve_b else quote}: the basis is not computed")
