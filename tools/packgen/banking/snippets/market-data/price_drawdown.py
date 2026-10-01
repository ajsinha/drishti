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

# title: Drawdown, worst days and the return histogram
# description: From the closes the view holds (daily bars, or the price history): cumulative return, the running drawdown from the high, the largest peak-to-trough fall and whether it has recovered, the five worst and best days, and a histogram of daily returns with the normal density of the same mean and volatility (matplotlib).
# kinds: equity, equity-index, commodity, bond, fx-spot
# example: EQX EQX-SPX

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from drishti import quant as q

doc = view.doc
if doc.get("ohlc"):
    px = pd.Series({b["date"]: b["close"] for b in doc["ohlc"]})
else:                                                     # history: close (equity, index), price (bond), mid (FX)
    field = next(f for f in ("close", "price", "mid") if f in (doc.get("history") or [{}])[0])
    px = pd.Series({p["date"]: p[field] for p in doc["history"]})
px = px.sort_index().astype(float)
rets = np.log(px).diff().dropna()

dd = q.max_drawdown(px.to_numpy())
peak, trough = px.index[dd["peak"]], px.index[dd["trough"]]
recovered = bool((px.iloc[dd["trough"]:] >= px.iloc[dd["peak"]]).any())
show(pd.DataFrame({
    "measure": ["first", "last", "return", "max drawdown", "peak", "trough", "recovered", "annualised vol"],
    "value": [px.iloc[0], px.iloc[-1], f"{px.iloc[-1] / px.iloc[0] - 1:.2%}", f"{dd['drawdown'] / px.iloc[dd['peak']]:.2%}",
              peak, trough, "yes" if recovered else "no", f"{rets.std(ddof=1) * np.sqrt(252):.2%}"],
}), title=f"{view.id}: {len(px)} closes, {px.index[0]} to {px.index[-1]}")

worst, best = rets.nsmallest(5), rets.nlargest(5)
show(pd.DataFrame({"worst day": worst.index, "worst return": worst.to_numpy(), "best day": best.index, "best return": best.to_numpy()}),
     title="Five worst and best days (log returns)")
chart(pd.DataFrame({"drawdown %": dd["series"] / np.maximum.accumulate(px.to_numpy()) * 100}, index=px.index),
      kind="line", title="Drawdown from the running high, %")

fig, ax = plt.subplots(figsize=(6.5, 3))
ax.hist(rets * 100, bins=15, density=True, alpha=0.6, label="daily returns")
grid = np.linspace(rets.min() * 100, rets.max() * 100, 100)
mu, sd = rets.mean() * 100, rets.std(ddof=1) * 100
ax.plot(grid, q.norm_pdf((grid - mu) / sd) / sd, label="normal, same mean and vol")
ax.set_xlabel("daily return %")
ax.set_title(f"{view.id}: return distribution")
ax.legend(fontsize=7)
