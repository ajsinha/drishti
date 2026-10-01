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

# title: Zero, discount and forward curves
# description: From the curve's pillars: discount factors, 3M simple forwards and 1Y forwards on a quarterly grid under log-linear interpolation of discount factors (flat forwards between pillars), set beside the curve's own forwards.
# kinds: ir-curve
# example: CRV CRV-USD-OIS

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from drishti import quant as q

doc = view.doc
curve = q.ZeroCurve.from_points(doc["points"], method="loglinear")   # zeroRate in %, continuously compounded, ACT/365F

# a quarterly grid out to the last pillar: zero, discount factor and the 3M simple forward starting at each date
grid = np.round(np.arange(0.25, curve.times[-1] + 1e-9, 0.25), 2)
fwd3m = curve.forward(grid, grid + 0.25) * 100
table = pd.DataFrame({"years": grid, "zero %": curve.zero(grid) * 100, "df": curve.df(grid), "3M fwd %": fwd3m})
yearly = table[np.isclose(table["years"] % 1, 0)].reset_index(drop=True)
show(yearly, title=f"{view.id}: zero rates, discount factors and 3M forwards (yearly rows of a quarterly grid)")

# the curve's own forwards panel beside a recomputation: the 1Y simple forward starting at each tenor
own = pd.DataFrame(doc.get("forwards") or [])
if len(own):
    t = own["tenor"].map(q.tenor_years).to_numpy()
    own["1Y fwd from this curve %"] = curve.forward(t, t + 1.0) * 100
    own["difference bp"] = (own["1Y fwd from this curve %"] - own["rate"]) * 100
    show(own, title="Forwards: the curve's own (rate) and recomputed 1Y forwards at each start")

fig, ax = plt.subplots(figsize=(7.5, 3.4))
ax.plot(grid, table["zero %"], label="zero (cc)")
ax.step(grid, fwd3m, where="post", label="3M forward (simple)")
ax.scatter(curve.times, curve.zeros * 100, s=14, color="black", zorder=3, label="pillars")
ax.set_xlabel("years")
ax.set_ylabel("%")
ax.set_title(f"{view.id}: {doc.get('curveType', '')} ({doc.get('interpolation', '')})")
ax.legend(fontsize=8)
