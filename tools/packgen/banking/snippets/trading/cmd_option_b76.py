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

# title: Commodity option on a future: Black-76
# description: The option on a commodity future priced with Black-76: the future for the contract month nearest the expiry from the commodity curve, the vol at that month and the strike's moneyness from the commodity vol surface (linear between 90%, 100% and 110%), USD OIS discounting; premium and Greeks per unit and for the position (notional over the future as units; American exercise priced as European), against the booked figures.
# kinds: trade
# example: TRD END-1000027

from datetime import date
import numpy as np
import pandas as pd
from drishti import quant as q

doc, t = view.doc, view.doc.get("terms") or {}
if not doc.get("commodityCurve") or "strike" not in t:
    raise ValueError(f"{view.id} ({doc.get('productType')}) is not a commodity option")
as_of = view.business_date or "2026-09-30"
curve = await drishti.get_async("commodity-curve", doc["commodityCurve"])
surf = await drishti.get_async("commodity-vol-surface", doc.get("commodityVolSurface") or doc["commodityCurve"].replace("CMDC", "CMDV"))
T = max((date.fromisoformat(t["expiryDate"]) - date.fromisoformat(as_of)).days / 365, 1 / 365)
months = pd.DataFrame(curve["points"]).assign(years=lambda d: [q.month_code_years(m, as_of) for m in d["month"]])
row = months.iloc[int(np.argmin(np.abs(months["years"] - T)))]           # the nearest contract (the strip is a year long)
F, K = row["price"], t["strike"]
if not 0.3 < K / F < 3:
    print(f"The booked strike {K:g} is {K / F:.1f}x the future {F:g} (sample data): priced at the money instead")
    K = F
g = next((x for x in surf["grid"] if x["month"] == row["month"]), surf["grid"][-1])
vol = float(np.interp(K / F, [0.9, 1.0, 1.1], [g["m90"], g["m100"], g["m110"]])) / 100
r = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", "CRV-USD-OIS"))["points"]).zero(T)
call = t.get("optionType", "Call") == "Call"
gk = q.black76_greeks(F, K, T, r, vol, call)
units = doc["notional"] / F * (1 if doc["direction"] in ("Buy", "Long") else -1)
show(pd.DataFrame({
    "measure": ["contract", "future", "strike", "years", "vol", "rate", "price", "delta", "gamma", "vega per vol pt", "theta per day"],
    "per unit": [row["month"], F, K, T, vol, r, gk["price"], gk["delta"], gk["gamma"], gk["vega"] / 100, gk["theta"] / 365],
    f"position ({units:,.0f} units)": [None] * 6 + [gk["price"] * units, gk["delta"] * units, gk["gamma"] * units,
                                                    gk["vega"] / 100 * units, gk["theta"] / 365 * units],
}), title=f"{view.id}: {doc['direction']} {t.get('optionType')} on {curve.get('commodity', '')} ({surf['surfaceId']})")
show(pd.DataFrame([{"booked MTM": doc["mtm"], **{f"booked {k}": v for k, v in (doc.get("risk") or {}).items()}}]),
     title="Booked by the source system")
