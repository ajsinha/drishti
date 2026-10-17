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

# title: P&L by book: a pandas pivot
# description: The desk's positions pivoted by book and product family (MTM), and today's P&L by book and currency from the trades themselves (drishti.search_async()).
# kinds: desk
# example: DESK DESK-RATES

import pandas as pd

pos = pd.DataFrame(view.doc["POSITIONS"])     # TRADE_ID, BOOK, CURRENCY, FAMILY, MTM
mtm = pos.pivot_table(index="BOOK", columns="FAMILY", values="MTM", aggfunc="sum", fill_value=0,
                      margins=True, margins_name="Total")
show(mtm, title=f"{view.id}: MTM by book and product family")

trades = await drishti.search_async(f"TVAL where DESK = '{view.id}' and PNL_1D != 0 limit 1000")
pnl = trades.pivot_table(index="PORTFOLIO", columns="CURRENCY", values="PNL_1D", aggfunc="sum", fill_value=0,
                         margins=True, margins_name="Total")
show(pnl, title=f"1-day P&L by book and currency ({len(trades)} trades)")
chart(trades.groupby("PORTFOLIO", as_index=False)["PNL_1D"].sum(), kind="bar", x="PORTFOLIO", y="PNL_1D", title="1-day P&L by book")
