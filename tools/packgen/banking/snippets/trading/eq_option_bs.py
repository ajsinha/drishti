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

# title: Equity option: Black-Scholes with the surface, and implied vol
# description: The equity or index option priced with Black-Scholes-Merton (European; an American put or call is priced as European, a lower bound): spot from the underlying, the dividend yield from its dividend curve, the rate from the currency's OIS curve, and the vol read off the equity vol surface at the strike's moneyness and the expiry (bilinear in moneyness and total variance); Greeks per unit and for the position, and the implied vol solved back from the model price (Brent).
# kinds: trade
# example: TRD IMG-400037

from datetime import date
import math
import numpy as np
import pandas as pd
from drishti import quant as q

doc, t = view.doc, view.doc.get("terms") or {}
und = doc.get("underlyingEquity") or doc.get("underlyingIndex")
if "strike" not in t or not und:
    raise ValueError(f"{view.id} ({doc.get('productType')}) is not an equity or index option")
u = await drishti.get_async("equity" if doc.get("underlyingEquity") else "equity-index", und)
S = u.get("price") or u.get("level")
surf = await drishti.get_async("equity-vol-surface", doc.get("equityVolSurface") or u["equityVolSurface"])
div_id = doc.get("dividendCurve") or u.get("dividendCurve")
dy = (await drishti.get_async("dividend-curve", div_id)).get("dividendYield", 0.0) if div_id else 0.0
T = max((date.fromisoformat(t["expiryDate"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365, 1 / 365)
r = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{doc['currency']}-OIS"))["points"]).zero(T)
K, call = t["strike"], t.get("optionType", "Call") == "Call"
if not 0.3 < K / S < 3:
    print(f"The booked strike {K:g} is {K / S:.1f}x spot {S:g} (sample data): priced at the money instead")
    K = S

g = pd.DataFrame(surf["grid"])
cols = [c for c in g.columns if c.startswith("m") and c[1:].isdigit()]
m = np.array([int(c[1:]) / 100 for c in cols])
Ts = g["expiry"].map(q.tenor_years).to_numpy()
w_at_T = [np.interp(T, Ts, (g[c] / 100) ** 2 * Ts) for c in cols]          # total variance at T, per moneyness
vol = math.sqrt(np.interp(K / S, m, w_at_T) / T)                             # flat beyond the 80-120% grid

gk = q.bs_greeks(S, K, T, r, vol, dy, call)
iv = q.implied_vol(gk["price"], lambda s: q.black_scholes(S, K, T, r, s, dy, call))
units = (t.get("quantity") or t.get("lots") or t.get("contracts") or 0) * (1 if doc["direction"] in ("Buy", "Long") else -1)
show(pd.DataFrame({
    "measure": ["spot", "strike", "moneyness", "years", "rate (OIS)", "dividend yield", "vol from surface", "implied vol (solved back)",
                "price", "delta", "gamma", "vega per vol pt", "theta per day", "rho per bp"],
    "per unit": [S, K, K / S, T, r, dy, vol, iv, gk["price"], gk["delta"], gk["gamma"], gk["vega"] / 100, gk["theta"] / 365,
                 gk["rho"] / 1e4],
    f"position ({units:,.0f} units)": [None] * 8 + [gk["price"] * units, gk["delta"] * units, gk["gamma"] * units,
                                                    gk["vega"] / 100 * units, gk["theta"] / 365 * units, gk["rho"] / 1e4 * units],
}), title=f"{view.id}: {doc['direction']} {t.get('optionType')} on {und}, {t.get('exerciseStyle', '')} (priced as European)")
show(pd.DataFrame([{"booked MTM": doc["mtm"], **{f"booked {k}": v for k, v in (doc.get("risk") or {}).items()}}]),
     title="Booked by the source system")
