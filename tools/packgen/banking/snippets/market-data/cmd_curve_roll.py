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

# title: Futures curve: contango, backwardation and roll yield
# description: The futures strip by contract month: each month's spread to the next and to the front, the annualised roll yield ln(F1/F2)/(T2 - T1) that a long position earns (backwardation) or pays (contango) rolling forward, the 12-month carry, and the curve drawn with open interest beneath it.
# kinds: commodity-curve
# example: CMDC CMDC-WTI

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from drishti import quant as q

doc = view.doc
as_of = view.business_date or "2026-09-30"
pts = pd.DataFrame(doc["points"])
pts["years"] = [q.month_code_years(m, as_of) for m in pts["month"]]    # to the middle of each contract month
pts = pts.sort_values("years").reset_index(drop=True)
F, t = pts["price"].to_numpy(), pts["years"].to_numpy()

pts["vs front"] = F - F[0]
pts["spread to next"] = np.append(F[:-1] - F[1:], np.nan)            # positive: backwardation
pts["roll yield to next %/yr"] = np.append(np.log(F[:-1] / F[1:]) / np.diff(t), np.nan) * 100
pts["shape"] = np.where(pts["spread to next"] > 0, "backwardation", np.where(pts["spread to next"] < 0, "contango", ""))
show(pts, title=f"{view.id}: {doc.get('commodity', '')} futures ({doc.get('unit', '')}), the curve says {doc.get('shape')}")

i12 = int(np.argmin(np.abs(t - (t[0] + 1.0))))                        # the contract about a year after the front
annual = np.log(F[0] / F[i12]) / (t[i12] - t[0])
oi_weight = pts["openInterest"] / pts["openInterest"].sum()
print(f"Front {F[0]:g} against {pts['month'][i12]} {F[i12]:g}: annualised roll yield {annual:+.2%} for a long "
      f"({'earned in backwardation' if annual > 0 else 'paid in contango'}); "
      f"{oi_weight.iloc[:3].sum():.0%} of open interest is in the first three months")

fig, ax = plt.subplots(figsize=(7.5, 3.4))
ax2 = ax.twinx()
ax2.bar(pts["month"], pts["openInterest"] / 1e3, alpha=0.25, color="grey")
ax2.set_ylabel("open interest, thousands")
ax.plot(pts["month"], F, "o-", zorder=3)
ax.set_zorder(ax2.get_zorder() + 1)
ax.patch.set_visible(False)
ax.set_ylabel(doc.get("unit", "price"))
ax.set_title(f"{view.id}: {doc.get('shape', '')}, front roll {doc.get('roll', '')}")
