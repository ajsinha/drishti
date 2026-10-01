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

# title: FX correlations and principal components
# description: Daily log returns of every currency against USD from the spot rates' price history, their correlation matrix (a heatmap), and a principal component analysis with numpy: how much the first component (the dollar factor) explains, each currency's loadings, and this pair's realised volatility.
# kinds: fx-spot
# example: FX FX-EURUSD

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

found = await drishti.search_async("FX where mid > 0 limit 100")
series = {}
for pid in found["id"]:
    d = await drishti.get_async("fx-spot", pid)
    c = d.get("conventions") or {}
    h = pd.Series({p["date"]: p["mid"] for p in d.get("history") or []}, dtype=float)
    if len(h) < 10:
        continue
    ccy = c["base"] if c.get("quote") == "USD" else c.get("quote")
    if c.get("quote") == "USD":
        series[ccy] = h                                   # USD per unit: EUR, GBP, AUD
    elif c.get("base") == "USD":
        series[ccy] = 1.0 / h                             # USD per unit of JPY, CAD, ...: a rise is the currency gaining
prices = pd.DataFrame(series).sort_index().dropna()
rets = np.log(prices).diff().dropna()                     # one row a day, one column a currency, vs USD

corr = rets.corr().rename_axis("currency")
show(corr.round(3), title=f"Correlation of daily returns against USD ({len(rets)} days, {prices.index[0]} to {prices.index[-1]})")

z = (rets - rets.mean()) / rets.std(ddof=1)               # PCA on standardised returns: the correlation matrix
vals, vecs = np.linalg.eigh(np.cov(z.T.to_numpy()))
order = np.argsort(vals)[::-1]
vals, vecs = vals[order], vecs[:, order]
if vecs[:, 0].sum() < 0:
    vecs[:, 0] *= -1                                      # make the first component "every currency up against USD"
explained = vals / vals.sum()
show(pd.DataFrame({"component": [f"PC{i + 1}" for i in range(len(vals))], "variance explained": explained,
                   "cumulative": np.cumsum(explained)}).head(5), title="Principal components")
show(pd.DataFrame(vecs[:, :3], index=rets.columns.rename("currency"), columns=["PC1 (dollar)", "PC2", "PC3"]).round(3), title="Loadings")

me = view.doc.get("conventions") or {}
mine = me.get("base") if me.get("quote") == "USD" else me.get("quote")
if mine in rets:
    vol = rets[mine].std(ddof=1) * np.sqrt(252)
    print(f"{view.id}: realised volatility {vol:.2%} a year; PC1 explains {explained[0]:.0%} of all currencies' variance")

fig, ax = plt.subplots(figsize=(5.5, 4.5))
im = ax.imshow(corr.to_numpy(), cmap="RdBu_r", vmin=-1, vmax=1)
ax.set_xticks(range(len(corr)), corr.columns, rotation=90, fontsize=7)
ax.set_yticks(range(len(corr)), corr.index, fontsize=7)
fig.colorbar(im, ax=ax, shrink=0.8)
ax.set_title("Correlation of returns against USD")
