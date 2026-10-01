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

# title: Collateralised exposure by Monte Carlo: threshold, MTA and MPoR
# description: A Monte Carlo of the netting set's value as a driftless Brownian motion whose volatility is read from the engine's 1Y expected exposure (EE = sigma sqrt(t / 2 pi)), with daily margining under the CSA: the collateral follows the value less the threshold, moves only above the minimum transfer amount, and lags by the margin period of risk. EE and PFE 95% with and without collateral by tenor (2,000 paths, a fixed seed). A teaching model, not the engine's.
# kinds: netting-set
# example: NSET NS-HALCYON-NY

import numpy as np
import pandas as pd
from drishti import quant as q

ns = view.doc
csa = await drishti.get_async("csa", ns["csa"]) if ns.get("csa") else {}
TH, MTA = csa.get("thresholdThem", 0) or 0, csa.get("mta", 0) or 0
MPOR = int(str(csa.get("mpor", "10")).split()[0]) if csa else 10        # business days
prof = {p["tenor"]: p["ee"] for p in ns.get("profile") or []}
ee1 = prof.get("1Y") or ns.get("eePeak")
sigma = ee1 * np.sqrt(2 * np.pi)                                         # a year's standard deviation of the value
v0 = ns.get("netMtm", 0)

rng = np.random.default_rng(7)
DAYS, PATHS = 252 * 2, 2000
steps = rng.normal(0.0, sigma / np.sqrt(252), (PATHS, DAYS))
value = v0 + np.cumsum(steps, axis=1)                                    # the netting set's value, path by path, day by day
collateral = np.zeros_like(value)
held = max(v0 - TH, 0.0)
for d in range(DAYS):
    stale = value[:, d - MPOR] if d >= MPOR else np.full(PATHS, float(v0))  # margined on the value MPoR days ago
    target = np.maximum(stale - TH, 0.0)
    move = target - (collateral[:, d - 1] if d else held)
    collateral[:, d] = (collateral[:, d - 1] if d else held) + np.where(np.abs(move) >= MTA, move, 0.0)
exposure = np.maximum(value, 0.0)
collat_exposure = np.maximum(value - collateral, 0.0)

rows = []
for tenor in ["1M", "3M", "6M", "1Y", "2Y"]:
    d = min(int(round(q.tenor_years(tenor) * 252)), DAYS) - 1
    rows.append({"tenor": tenor, "EE engine": prof.get(tenor), "EE uncollateralised": exposure[:, d].mean(),
                 "EE collateralised": collat_exposure[:, d].mean(), "PFE95 uncollateralised": np.percentile(exposure[:, d], 95),
                 "PFE95 collateralised": np.percentile(collat_exposure[:, d], 95)})
out = pd.DataFrame(rows).set_index("tenor")
show(out, title=f"{view.id}: sigma {sigma:,.0f} a year, threshold {TH:,.0f}, MTA {MTA:,.0f}, MPoR {MPOR} days")
chart(out.reset_index(), kind="bar", x="tenor", y=["EE uncollateralised", "EE collateralised"], title="EE with and without the CSA")
print(f"Collateral cuts EE at 1Y by {1 - out.loc['1Y', 'EE collateralised'] / out.loc['1Y', 'EE uncollateralised']:.0%}; "
      "what is left is the threshold plus the MPoR gap risk")
