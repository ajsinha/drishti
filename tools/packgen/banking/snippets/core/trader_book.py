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

# title: A trader's book: mix, P&L and hit rate
# description: Every trade the trader booked (one search): count, notional and MTM by product and currency, today's P&L with the share of winning trades and the average win against the average loss, the ten largest positions by MTM, and how the trader's day compares with the desk's other traders.
# kinds: trader
# example: TRDR TRDR-ASHAH

import pandas as pd

doc = view.doc
t = await drishti.search_async(f"TRD where trader = '{view.id}' and pnl1d > -1000bn and productName != '' and desk != '' limit 1000")
if t.empty:
    raise ValueError(f"no trades booked by {view.id}")
mix = t.groupby("productName").agg(trades=("id", "size"), notional=("notional", "sum"), mtm=("mtm", "sum"), pnl1d=("pnl1d", "sum"))
show(mix.sort_values("notional", ascending=False), title=f"{view.id}: {doc.get('name', '')}, {len(t)} trades on {doc.get('desk', '')}")
show(t.pivot_table(index="currency", values=["notional", "mtm", "pnl1d"], aggfunc="sum").sort_values("notional", ascending=False),
     title="By currency")
wins, losses = t[t["pnl1d"] > 0]["pnl1d"], t[t["pnl1d"] < 0]["pnl1d"]
show(pd.DataFrame({
    "measure": ["1-day P&L", "winning trades", "losing trades", "hit rate", "average win", "average loss", "win / loss"],
    "value": [t["pnl1d"].sum(), len(wins), len(losses), f"{len(wins) / len(t):.0%}", wins.mean(), losses.mean(),
              abs(wins.mean() / losses.mean()) if len(losses) and len(wins) else None],
}), title="Today")
show(t.reindex(t["mtm"].abs().sort_values(ascending=False).index).head(10)[["id", "productName", "currency", "notional", "mtm", "pnl1d"]]
     .reset_index(drop=True), title="Ten largest positions by |MTM|")

desk = t["desk"].iloc[0]
peers = await drishti.search_async(f"TRD where desk = '{desk}' and trader != '' and pnl1d > -1000bn limit 1000")
by_trader = peers.groupby("trader").agg(trades=("id", "size"), pnl1d=("pnl1d", "sum"), mtm=("mtm", "sum")).sort_values("pnl1d")
show(by_trader, title=f"Traders on {desk}")
chart(by_trader.reset_index(), kind="bar", x="trader", y="pnl1d", title=f"1-day P&L by trader, {desk}")
