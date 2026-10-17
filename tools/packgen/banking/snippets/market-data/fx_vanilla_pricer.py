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

# title: Price straddles, risk reversals and strangles on the surface
# description: Garman-Kohlhagen premiums and Greeks, per expiry, of the ATM straddle, the 25 delta risk reversal and the 25 delta strangle on 10m of the base currency, at the surface's vols, with forwards from the forward curve and the quote currency's OIS rate: premium in quote currency, in pips and in % of base notional; delta, vega per vol point.
# kinds: fx-vol-surface
# example: FXV FXV-EURUSD

import math
import pandas as pd
from drishti import quant as q

doc = view.doc
NOTIONAL = 10e6                                          # in the base currency
spot_doc = await drishti.get_async("fx-spot", doc.get("FX_SPOT") or f"FX-{doc['PAIR']}")
fwd_doc = await drishti.get_async("fx-forward-curve", spot_doc.get("FX_FORWARD_CURVE") or f"FXF-{doc['PAIR']}")
c = spot_doc["CONVENTIONS"]
S, pip = spot_doc["MID"], c.get("PIP_FACTOR", 10000)
diffs = [(q.tenor_years(p["TENOR"]), math.log((S + p["PIPS"] / pip) / S) / q.tenor_years(p["TENOR"])) for p in fwd_doc["POINTS"]]
try:
    rd_curve = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{c['QUOTE']}-OIS"))["POINTS"])
except drishti.DrishtiError:
    rd_curve = q.ZeroCurve([1, 30], [0.03, 0.03])


def leg(K, T, rd, rf, vol, call, units):                 # one option: premium and Greeks for `units` of the base
    g = q.gk_greeks(S, K, T, rd, rf, vol, call)
    return {k: g[k] * units for k in ("PRICE", "DELTA", "vega")}


rows = []
for g in doc["GRID"]:
    T = q.tenor_years(g["EXPIRY"])
    diff = min(diffs, key=lambda p: abs(p[0] - T))[1]
    rd = rd_curve.zero(T)
    rf = rd - diff
    atm_v, c25, p25 = g["ATM"] / 100, g["C_25"] / 100, g["P_25"] / 100
    K_atm = S * math.exp(diff * T + 0.5 * atm_v ** 2 * T)
    K_c = q.fx_strike_from_delta(S, T, rd, rf, c25, 0.25, True)
    K_p = q.fx_strike_from_delta(S, T, rd, rf, p25, 0.25, False)
    books = {"ATM straddle": [leg(K_atm, T, rd, rf, atm_v, True, NOTIONAL), leg(K_atm, T, rd, rf, atm_v, False, NOTIONAL)],
             "25D risk reversal (long call)": [leg(K_c, T, rd, rf, c25, True, NOTIONAL), leg(K_p, T, rd, rf, p25, False, -NOTIONAL)],
             "25D strangle": [leg(K_c, T, rd, rf, c25, True, NOTIONAL), leg(K_p, T, rd, rf, p25, False, NOTIONAL)]}
    for name, legs in books.items():
        prem = sum(x["PRICE"] for x in legs)
        rows.append({"EXPIRY": g["EXPIRY"], "structure": name, f"premium ({c['QUOTE']})": prem,
                     "PIPS": prem / NOTIONAL * pip, "% of notional": prem / (NOTIONAL * S) * 100,
                     f"delta ({c['BASE']})": sum(x["DELTA"] for x in legs),
                     f"vega per vol pt ({c['QUOTE']})": sum(x["vega"] for x in legs) / 100})
out = pd.DataFrame(rows)
show(out, title=f"{doc['PAIR']}: {NOTIONAL / 1e6:,.0f}m {c['BASE']} per structure, spot {S}")
chart(out[out["structure"] == "ATM straddle"], kind="bar", x="EXPIRY", y="% of notional", title="ATM straddle premium, % of notional")
