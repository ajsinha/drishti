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

# title: Top movers and P&L contribution
# description: The desk's trades from one search: the ten best and worst by today's P&L, P&L by product with each product's share of the absolute move, and a Pareto view (how few trades make most of the day's P&L), with the desk's total against its derived P&L.
# kinds: desk, book
# example: DESK DESK-EQD

import numpy as np
import pandas as pd

field = "DESK" if view.kind == "desk" else "PORTFOLIO"
t = await drishti.search_async(f"TVAL where {field} = '{view.id}' and PNL_1D > -1000bn and PRODUCT_TYPE != '' limit 1000")
t = t.sort_values("PNL_1D").reset_index(drop=True)
cols = ["id", "PRODUCT_TYPE", "PORTFOLIO", "CURRENCY", "NOTIONAL", "MTM", "PNL_1D"]
show(t.tail(10).iloc[::-1][cols].reset_index(drop=True), title=f"{view.id}: ten best trades today")
show(t.head(10)[cols], title="Ten worst trades today")

by_product = t.groupby("PRODUCT_TYPE").agg(trades=("id", "size"), pnl1d=("PNL_1D", "sum"), mtm=("MTM", "sum"))
by_product["share of |P&L|"] = by_product["PNL_1D"].abs() / by_product["PNL_1D"].abs().sum()
show(by_product.sort_values("PNL_1D"), title=f"P&L by product ({len(t)} trades, total {t['PNL_1D'].sum():,.0f})")
chart(by_product.sort_values("PNL_1D").reset_index(), kind="bar", x="PRODUCT_TYPE", y="PNL_1D", title="1-day P&L by product")

absolute = np.sort(t["PNL_1D"].abs().to_numpy())[::-1]
cum = np.cumsum(absolute) / absolute.sum()
n80 = int(np.searchsorted(cum, 0.8)) + 1
show(pd.DataFrame({"TRADES": ["making 80% of the move", "all"], "count": [n80, len(t)], "share of trades": [n80 / len(t), 1.0],
                   "share of |P&L|": [cum[n80 - 1], 1.0]}), title=f"Pareto: {n80} of {len(t)} trades make 80% of the absolute P&L")
chart(pd.DataFrame({"cumulative share of |P&L|": cum}, index=pd.Index(np.arange(1, len(cum) + 1), name="trades, largest first")),
      kind="line", title="Pareto curve of the day's P&L")
