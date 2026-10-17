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

# title: VaR and expected shortfall from the scenario P&L
# description: Historical-simulation VaR (99%) and ES (97.5%) recomputed with numpy from the scenario P&Ls, set beside the engine's figures, and the P&L histogram.
# kinds: var
# example: VAR VAR-RATES

import numpy as np
import pandas as pd

pnl = np.asarray(view.doc["SCENARIO_PNL"], dtype=float)
worst = np.sort(pnl)
n = len(pnl)
var99 = -worst[n // 100 - 1]                  # the 1% quantile: the 5th worst of 500 scenarios
es975 = -worst[: int(np.ceil(n * 0.025))].mean()   # the mean of the worst 2.5%

show(pd.DataFrame({
    "MEASURE": ["VaR 99%", "ES 97.5%", "Mean P&L", "Worst scenario"],
    "recomputed": [var99, es975, pnl.mean(), -worst[0]],
    "engine": [view.doc.get("VAR_99"), view.doc.get("ES_975"), view.doc.get("MEAN_PNL"), None],
}), title=f"{view.id}: {n} scenarios ({view.doc.get('METHOD', '')})")
chart(pd.DataFrame({"PNL": pnl}), kind="hist", y="PNL", bins=40, title="Scenario P&L")
print(f"VaR uses {var99 / view.doc['LIMIT']:.0%} of the limit {view.doc['LIMIT']:,.0f}")
