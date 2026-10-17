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

# title: Option P&L across the underlying and vol (full revaluation)
# description: Any trade with an OPTION_TYPE and a STRIKE_PRICE (equity, FX, commodity or rates options of every source system): Black-76 on the forward at zero rates (a scenario shape, not a booking price), with the forward at the strike unless FORWARD is set (the common trade schema names no underlying), expiry at MATURITY_DATE and QUANTITY units long or short by BUY_SELL_CODE; the P&L on a grid of forward moves (-20% to +20%) and vol moves (-10 to +10 points) against the delta-gamma approximation along the forward axis; a heatmap.
# kinds: trade
# example: TRD MX-20000187

from datetime import date
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from drishti import quant as q

FORWARD, VOL = None, 0.25                                     # set the underlying's forward and vol when you know them
doc = view.doc
if doc.get("OPTION_TYPE") in (None, "") or doc.get("STRIKE_PRICE") in (None, ""):
    raise ValueError(f"{view.id} ({doc.get('PRODUCT_TYPE')}) is not an option with an OPTION_TYPE and a STRIKE_PRICE")
K = float(doc["STRIKE_PRICE"])
F = FORWARD or K
T = max((date.fromisoformat(doc["MATURITY_DATE"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365, 1 / 365)
call = doc["OPTION_TYPE"] in ("Call", "Payer", "Cap")
units = float(doc.get("QUANTITY") or 0) * (1 if doc.get("BUY_SELL_CODE") == "B" else -1)
value = lambda f, v: q.black76(f, K, T, 0.0, v, call) * units          # noqa: E731
base = value(F, VOL)
g = q.black76_greeks(F, K, T, 0.0, VOL, call)

moves = np.linspace(-0.2, 0.2, 9)
vols = np.linspace(-0.10, 0.10, 5)
grid = pd.DataFrame([[value(F * (1 + m), max(VOL + dv, 0.01)) - base for m in moves] for dv in vols],
                    index=[f"{dv * 100:+.0f} vol pts" for dv in vols], columns=[f"{m:+.0%}" for m in moves])
show(grid, title=f"{view.id} ({doc['SOURCE_SYSTEM']} {doc['PRODUCT_TYPE']}): P&L, {units:,.0f} {doc['OPTION_TYPE'].lower()} K={K:,.4g}, {T:.2f}y")
approx = pd.DataFrame({"forward move": moves, "full": [value(F * (1 + m), VOL) - base for m in moves],
                       "delta-gamma": [units * (g["delta"] * F * m + 0.5 * g["gamma"] * (F * m) ** 2) for m in moves]})
chart(approx, kind="line", x="forward move", y=["full", "delta-gamma"], title="Full revaluation against delta-gamma")
fig, ax = plt.subplots(figsize=(7, 3))
im = ax.imshow(grid.to_numpy(), cmap="RdYlGn", aspect="auto")
ax.set_xticks(range(len(moves)), grid.columns)
ax.set_yticks(range(len(vols)), grid.index)
fig.colorbar(im, ax=ax)
ax.set_title("P&L by forward and vol move")
plt.show()
if FORWARD is None:
    print("Priced at the money (FORWARD unset): the shape of the risk, not the trade's value")
