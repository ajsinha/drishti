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

# title: FX swap: points, carry and mark to market
# description: The FX swap's near and far legs; the booked swap points against today's points at the far date interpolated on the forward curve, the yield differential they imply (ln(F/S)/T) against the two OIS curves, the MTM recomputed from the cashflows (each currency discounted on its own curve, converted at spot), and the MTM for spot and points moves.
# kinds: trade

from datetime import date
import math
import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
if doc.get("productType") != "FXSWAP":
    raise ValueError(f"{view.id} is not an FX swap (TRD FXS-20931)")
as_of = date.fromisoformat(view.business_date or "2026-09-30")
yrs = lambda d: (date.fromisoformat(d) - as_of).days / 365.0          # noqa: E731
fwd = await drishti.get_async("curve", doc["forwardCurve"])
S, pip = fwd["spot"], fwd.get("pipFactor", 10000)
pts = sorted((yrs(o["valueDate"]), o["points"]) for o in fwd["outrights"])
T = yrs(doc["farValue"])
mkt_points = float(np.interp(T, [p[0] for p in pts], [p[1] for p in pts]))
curves = {}
for cid in doc.get("discountCurves") or []:
    c = await drishti.get_async("curve", cid)
    curves[c["currency"]] = q.ZeroCurve.from_points(c["pillars"])
base, quote = doc["pair"].split("/")
ois_diff = curves[quote].zero(T) - curves[base].zero(T) if base in curves and quote in curves else None

cf = pd.DataFrame(doc["cashflows"])
cf["years"] = cf["payDate"].map(yrs)
cf["df (curve)"] = [curves[c].df(max(t, 0.0)) if c in curves else 1.0 for c, t in zip(cf["currency"], cf["years"])]
cf["pv USD (recomputed)"] = cf["amount"] * cf["df (curve)"] * np.where(cf["currency"] == base, S, 1.0)
show(cf, title=f"{view.id}: {doc['direction']} {doc['pair']}, near {doc['nearRate']} far {doc['farRate']}")
show(pd.DataFrame({
    "measure": ["booked swap points", "market points at the far date", "points P&L (pips)", "implied r_quote - r_base",
                "OIS r_quote - r_base", "MTM recomputed (USD)", "MTM booked (USD)"],
    "value": [doc["swapPoints"], mkt_points, mkt_points - doc["swapPoints"], f"{math.log((S + mkt_points / pip) / S) / T:.3%}",
              f"{ois_diff:.3%}" if ois_diff is not None else None, cf["pv USD (recomputed)"].sum(), doc["mtm"]],
}), title="Points and value")
far_eur = cf[(cf["leg"] == "Far") & (cf["currency"] == base)]["amount"].sum()
moves = pd.DataFrame({"points move": np.arange(-20, 21, 5)})
moves["MTM change (USD)"] = -far_eur * moves["points move"] / pip * curves.get(quote, q.ZeroCurve([1, 2], [0, 0])).df(T)
chart(moves, kind="line", x="points move", y="MTM change (USD)", title="MTM against a move in the far points")
