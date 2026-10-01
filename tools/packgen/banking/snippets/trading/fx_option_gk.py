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

# title: FX option: Garman-Kohlhagen price and Greeks
# description: The FX option repriced with Garman-Kohlhagen on today's market: spot, the forward curve's yield differential, the quote currency's OIS rate, and a vol read off the surface at the expiry (ATM interpolated in total variance) and at the strike's delta (linear in call delta between the 10 and 25 delta pillars and ATM); premium, delta, gamma, vega and theta for the position (notional read as units of the base currency), beside the booked MTM and risk.
# kinds: trade
# example: TRD WSS-1500026

from datetime import date
import math
import numpy as np
import pandas as pd
from drishti import quant as q

doc, t = view.doc, view.doc.get("terms") or {}
if "strike" not in t or not doc.get("fxVolSurface"):
    raise ValueError(f"{view.id} ({doc.get('productType')}) is not a vanilla FX option")
spot = await drishti.get_async("fx-spot", doc["fxSpot"])
fwd = await drishti.get_async("fx-forward-curve", doc.get("fxForwardCurve") or spot["fxForwardCurve"])
surf = await drishti.get_async("fx-vol-surface", doc["fxVolSurface"])
c = spot["conventions"]
S, K, pip = spot["mid"], t["strike"], c.get("pipFactor", 10000)
T = max((date.fromisoformat(t["expiryDate"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365.0, 1 / 365)
call, sign = t.get("optionType", "Call") == "Call", (1 if doc["direction"] in ("Buy", "Long") else -1)

pts = sorted((q.tenor_years(p["tenor"]), math.log((S + p["pips"] / pip) / S) / q.tenor_years(p["tenor"])) for p in fwd["points"])
diff = float(np.interp(T, [p[0] for p in pts], [p[1] for p in pts]))          # r_quote - r_base, flat beyond the last
try:
    rd = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{c['quote']}-OIS"))["points"]).zero(T)
except drishti.DrishtiError:
    rd = 0.03
rf = rd - diff

g = pd.DataFrame(surf["grid"])
g["T"] = g["expiry"].map(q.tenor_years)
at = lambda col: math.sqrt(np.interp(T, g["T"], (g[col] / 100) ** 2 * g["T"]) / T)   # noqa: E731 - in total variance
atm = at("atm")
d_call = q.gk_greeks(S, K, T, rd, rf, atm, True)["delta"] * math.exp(rf * T)     # forward call delta of the strike
pillars = [(0.10, at("c10")), (0.25, at("c25")), (0.50, atm), (0.75, at("p25")), (0.90, at("p10"))]
vol = float(np.interp(d_call, [p[0] for p in pillars], [p[1] for p in pillars]))

g1 = q.gk_greeks(S, K, T, rd, rf, vol, call)
N = doc["notional"] * sign
show(pd.DataFrame({
    "measure": ["spot", "strike", "years to expiry", "forward", "r quote (OIS)", "r base (implied)", "ATM vol", "vol at strike",
                f"premium per unit ({c['quote']})", f"position PV ({c['quote']})", f"delta ({c['base']})",
                f"gamma ({c['base']} per 1 move)", f"vega per vol pt ({c['quote']})", f"theta per day ({c['quote']})"],
    "value": [S, K, T, S * math.exp(diff * T), f"{rd:.3%}", f"{rf:.3%}", f"{atm:.2%}", f"{vol:.2%}", g1["price"],
              g1["price"] * N, g1["delta"] * N, g1["gamma"] * N, g1["vega"] * N / 100, g1["theta"] * N / 365],
}), title=f"{view.id}: {doc['direction']} {c['base']}/{c['quote']} {t.get('optionType')} {K} exp {t['expiryDate']}")
show(pd.DataFrame([{"booked MTM (USD)": doc["mtm"], **{f"booked {k}": v for k, v in (doc.get("risk") or {}).items()}}]),
     title="Booked by the source system")
