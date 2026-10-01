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

# title: SVI fit of the smile, expiry by expiry
# description: A raw SVI curve (Gatheral) fitted to each expiry's total variance in log-moneyness with scipy's least squares: the five parameters, the fit error in vol points, a check of the wings against Lee's moment bound (total variance rising at most 2 per unit of log-moneyness), and the fitted smiles drawn over the quotes. Spot moneyness is used as forward moneyness, a simplification.
# kinds: equity-vol-surface, commodity-vol-surface
# example: EQV EQV-GRNT

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from scipy.optimize import least_squares
from drishti import quant as q

doc = view.doc
rows_in = doc["grid"]
cols = [c for c in rows_in[0] if c.startswith("m") and c[1:].isdigit()]
k = np.log(np.array([int(c[1:]) / 100 for c in cols]))         # log-moneyness of each quoted strike
fits, fig = [], plt.figure(figsize=(7, 3.4))
ax = fig.gca()


def resid(p, w):                                                  # fitted less quoted total variance
    return q.svi_total_variance(k, *p) - w


for g in rows_in:
    T = q.tenor_years(g["expiry"]) if "expiry" in g else q.month_code_years(g["month"], view.business_date or "2026-09-30")
    iv = np.array([g[c] for c in cols]) / 100
    w = iv ** 2 * T                                               # total variance: what SVI models
    a0 = max(w.min() * 0.9, 1e-6)
    sol = least_squares(resid, x0=[a0, 0.1, -0.3, 0.0, 0.1], args=(w,),
                        bounds=([-1.0, 1e-6, -0.999, -1.0, 1e-4], [1.0, 5.0, 0.999, 1.0, 2.0]))
    a, b, rho, m, s = sol.x
    fitted = np.sqrt(np.maximum(q.svi_total_variance(k, *sol.x), 0) / T)
    fits.append({"expiry": g.get("expiry") or g.get("month"), "years": T, "a": a, "b": b, "rho": rho, "m": m, "sigma": s,
                 "rmse vol pts": float(np.sqrt(np.mean((fitted - iv) ** 2)) * 100),
                 "min variance ok": a + b * s * np.sqrt(1 - rho ** 2) >= 0,      # w stays positive everywhere
                 "Lee wing ok": b * (1 + abs(rho)) <= 2})                      # wing slope of total variance at most 2
    kk = np.linspace(k.min() - 0.1, k.max() + 0.1, 60)
    line, = ax.plot(np.exp(kk) * 100, np.sqrt(np.maximum(q.svi_total_variance(kk, *sol.x), 0) / T) * 100, lw=1,
                    label=fits[-1]["expiry"])
    ax.scatter(np.exp(k) * 100, iv * 100, s=10, color=line.get_color())
out = pd.DataFrame(fits).set_index("expiry")
show(out, title=f"{view.id}: raw SVI per expiry (w(k) = a + b(rho(k - m) + sqrt((k - m)^2 + sigma^2)))")
print(f"Worst fit: {out['rmse vol pts'].max():.3f} vol points ({out['rmse vol pts'].idxmax()})")
ax.set_xlabel("moneyness %")
ax.set_ylabel("implied vol %")
ax.set_title(f"{view.id}: SVI fits (lines) and quotes (dots)")
ax.legend(fontsize=7)
