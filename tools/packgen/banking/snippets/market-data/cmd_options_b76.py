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

# title: Black-76 options on each futures month
# description: For every contract month on the commodity vol surface: the future from the commodity curve, Black-76 prices of the ATM straddle and of 90% puts and 110% calls at the surface's vols, the Greeks (delta, gamma, vega per vol point, theta per day) per contract, and the skew; discounting at the USD OIS curve. Options are taken to expire at the middle of the contract month, a simplification.
# kinds: commodity-vol-surface
# example: CMDV CMDV-WTI

import pandas as pd
from drishti import quant as q

doc = view.doc
as_of = view.business_date or "2026-09-30"
curve_id = doc["surfaceId"].replace("CMDV-", "CMDC-")                  # CMDV-WTI -> CMDC-WTI
futures = {p["month"]: p["price"] for p in (await drishti.get_async("commodity-curve", curve_id))["points"]}
ois = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", "CRV-USD-OIS"))["points"])

rows = []
for g in doc["grid"]:
    if g["month"] not in futures:
        continue
    F, T = futures[g["month"]], q.month_code_years(g["month"], as_of)
    r = ois.zero(T)
    atm, v90, v110 = g["m100"] / 100, g["m90"] / 100, g["m110"] / 100
    call, put = q.black76_greeks(F, F, T, r, atm, True), q.black76_greeks(F, F, T, r, atm, False)
    rows.append({"month": g["month"], "future": F, "years": T, "ATM vol %": g["m100"],
                 "straddle": call["price"] + put["price"],
                 "straddle % of F": (call["price"] + put["price"]) / F * 100,
                 "90% put": q.black76(F, 0.9 * F, T, r, v90, False), "110% call": q.black76(F, 1.1 * F, T, r, v110, True),
                 "ATM call delta": call["delta"], "ATM gamma": call["gamma"], "ATM vega / pt": call["vega"] / 100,
                 "ATM theta / day": call["theta"] / 365, "skew 90-110 pts": g["m90"] - g["m110"]})
out = pd.DataFrame(rows).set_index("month")
show(out, title=f"{view.id}: {doc.get('commodity', '')} options per unit of the future (Black-76, USD OIS discounting)")
chart(out.reset_index(), kind="line", x="month", y=["ATM vol %"], title="ATM vol by contract month")
chart(out.reset_index(), kind="bar", x="month", y="straddle % of F", title="ATM straddle, % of the future")
print("Straddle ~ 0.8 x F x vol x sqrt(T) (a trader's rule of thumb): front "
      f"{0.8 * out['future'].iloc[0] * out['ATM vol %'].iloc[0] / 100 * out['years'].iloc[0] ** 0.5:.2f} against "
      f"{out['straddle'].iloc[0]:.2f} priced")
