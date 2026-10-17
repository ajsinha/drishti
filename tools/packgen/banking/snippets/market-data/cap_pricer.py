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

# title: Cap prices from the normal-vol surface
# description: Caps of 1Y to 10Y priced as strips of quarterly caplets with the Bachelier formula: forwards from the currency's projection curve, discounting on its OIS curve, each cap at its own flat normal vol from the surface (ATM at the cap's par rate, and the fixed strikes K_2 to K_5 read as 2% to 5%); premium in bp of notional and per 100m. Flat cap vols, not stripped caplet vols: a simplification.
# kinds: cap-vol-surface
# example: CPV CPV-USD

import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
ccy = doc["CURRENCY"]
disc = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{ccy}-OIS"))["POINTS"])
try:
    proj = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{ccy}-PROJ"))["POINTS"])
except drishti.DrishtiError:
    proj = disc                                                    # single curve when there is no projection curve
NOTIONAL, TAU = 100e6, 0.25


def cap(T, K, vol_bp):
    ends = np.arange(2 * TAU, T + 1e-9, TAU)                       # the first period's fixing is known: no optionality
    starts = ends - TAU
    fwd = proj.forward(starts, ends)
    K = fwd.mean() if K is None else K                             # ATM: about the cap's par rate
    caplets = q.bachelier(fwd, K, starts, vol_bp / 1e4, disc.df(ends) * TAU, True)
    return float(np.sum(caplets)), K


strike_cols = [c for c in doc["GRID"][0] if c.startswith("K_") and c[2:].isdigit()]
rows = []
for g in doc["GRID"]:
    T = q.tenor_years(g["MATURITY"])
    prem, k_atm = cap(T, None, g["ATM"])
    row = {"MATURITY": g["MATURITY"], "ATM strike %": k_atm * 100, "ATM vol bp": g["ATM"], "ATM premium bp": prem * 1e4,
           "ATM per 100m": prem * NOTIONAL}
    for c in strike_cols:
        row[f"{c[2:]}% cap bp"] = cap(T, int(c[2:]) / 100, g[c])[0] * 1e4
    rows.append(row)
out = pd.DataFrame(rows).set_index("MATURITY")
show(out, title=f"{view.id}: {ccy} caps, quarterly caplets, Bachelier ({doc.get('MODEL', '')})")
chart(out.reset_index(), kind="line", x="MATURITY", y=["ATM premium bp"] + [f"{c[2:]}% cap bp" for c in strike_cols],
      title="Cap premium by maturity and strike, bp of notional")
