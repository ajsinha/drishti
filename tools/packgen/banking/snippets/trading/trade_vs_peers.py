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

# title: The trade among its peers
# description: Every trade of the same product (drishti.search_async): notional, MTM, 1-day P&L and MTM as a share of notional; where this trade ranks (percentiles) in each, the peers by book and currency, and a scatter of MTM against notional with this trade marked.
# kinds: trade
# example: TRD MX-20000001

import pandas as pd

doc = view.doc
peers = await drishti.search_async(f"TRD where productType = '{doc['productType']}' and pnl1d != 0 and desk != '' limit 1000")
peers["mtm % notional"] = peers["mtm"] / peers["notional"] * 100
me = peers[peers["id"] == view.id]
if me.empty:                                       # the trade itself may not match (pnl1d = 0): add it from its document
    me = pd.DataFrame([{"id": view.id, "notional": doc["notional"], "mtm": doc["mtm"], "pnl1d": doc["pnl1d"], "book": doc["book"],
                        "currency": doc["currency"], "desk": doc.get("desk"), "mtm % notional": doc["mtm"] / doc["notional"] * 100}])
    peers = pd.concat([peers, me], ignore_index=True)

rank = []
for col in ["notional", "mtm", "pnl1d", "mtm % notional"]:
    rank.append({"measure": col, "this trade": me[col].iloc[0], "peer median": peers[col].median(),
                 "peer min": peers[col].min(), "peer max": peers[col].max(),
                 "percentile": (peers[col] < me[col].iloc[0]).mean() * 100})
show(pd.DataFrame(rank), title=f"{view.id} among {len(peers)} {doc.get('productName', doc['productType'])} trades")
show(peers.groupby(["book", "currency"]).agg(trades=("id", "size"), notional=("notional", "sum"), mtm=("mtm", "sum"),
                                             pnl1d=("pnl1d", "sum")).reset_index(), title="Peers by book and currency")
chart(peers.assign(who=["this trade" if i == view.id else "peer" for i in peers["id"]]),
      kind="scatter", x="notional", y="mtm", title=f"MTM against notional: {doc['productType']}")
