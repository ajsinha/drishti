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

# title: Scenario P&L tails: QQ plot, fat tails and the Hill estimator
# description: How far the scenario P&L is from normal: skew, excess kurtosis and the Jarque-Bera test (scipy), the ratio of each tail quantile to the normal one, a Hill estimate of the loss tail index (the 25 largest losses; a lower index is a fatter tail), and a normal QQ plot (matplotlib).
# kinds: var
# example: VAR VAR-EQD

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from scipy import stats
from drishti import quant as q

pnl = np.asarray(view.doc["scenarioPnl"], dtype=float)
n, mu, sd = len(pnl), pnl.mean(), pnl.std(ddof=1)
jb = stats.jarque_bera(pnl)

rows = []
for c in (0.95, 0.99, 0.995):
    hist = -np.quantile(pnl, 1 - c)
    normal = -(mu + q.norm_ppf(1 - c) * sd)
    rows.append({"confidence": f"{c:.1%}", "historical loss": hist, "normal loss": normal, "historical / normal": hist / normal})
show(pd.DataFrame(rows).set_index("confidence"), title=f"{view.id}: {n} scenarios against a normal of the same mean and volatility")

losses = np.sort(-pnl[pnl < 0])[::-1]                               # largest loss first
K = min(25, len(losses) - 1)
hill = 1.0 / np.mean(np.log(losses[:K] / losses[K]))                 # tail index alpha: P(L > x) ~ x^-alpha
show(pd.DataFrame({
    "measure": ["skew", "excess kurtosis", "Jarque-Bera", "JB p-value", f"Hill tail index (k = {K})", "largest loss / sd"],
    "value": [stats.skew(pnl), stats.kurtosis(pnl), jb.statistic, jb.pvalue, hill, losses[0] / sd],
}), title="Shape of the distribution")
print("Normal tails: Jarque-Bera does not reject" if jb.pvalue > 0.05 else
      f"Not normal (JB p {jb.pvalue:.1e}): a parametric VaR would {'understate' if rows[1]['historical / normal'] > 1 else 'overstate'} the 99% loss")

theory = q.norm_ppf((np.arange(1, n + 1) - 0.5) / n)
fig, ax = plt.subplots(figsize=(4.8, 4))
ax.scatter(theory, np.sort((pnl - mu) / sd), s=6)
ax.plot([-3.5, 3.5], [-3.5, 3.5], color="grey", lw=1)
ax.set_xlabel("normal quantile")
ax.set_ylabel("standardised scenario P&L")
ax.set_title(f"{view.id}: normal QQ plot")
