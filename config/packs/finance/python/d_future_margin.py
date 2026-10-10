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

# title: Futures position: variation margin and the curve's roll
# description: The futures position's daily settlements: variation margin recomputed as lots x contract size x the settlement change, checked against the booked margin and the MTM; the value of a one-dollar move; and the futures curve it sits on: contango or backwardation month by month and the annualised roll yield from this contract to the next.
# kinds: trade

import math
import numpy as np
import pandas as pd

doc = view.doc
if not doc.get("settlements"):
    raise ValueError(f"{view.id} is not a futures position with settlements (TRD CFT-77120)")
spec = await drishti.get_async("contract-spec", doc["contractSpec"]) if doc.get("contractSpec") else {}
size = spec.get("contractSize", 1000)
lots = doc["lots"] * (1 if doc.get("direction") == "Long" else -1)
s = pd.DataFrame(doc["settlements"])
s["VM recomputed"] = lots * size * s["change"]
s["difference"] = s["VM recomputed"] - s["variationMargin"]
show(s, title=f"{view.id}: {doc['direction']} {doc['lots']} {doc.get('contract', '')} x {size:,} {spec.get('unit', '')}")
show(pd.DataFrame({
    "measure": ["trade price", "last settlement", "MTM recomputed", "MTM booked", "cumulative VM", "value of a 1.00 move",
                "tick value", "last trading day"],
    "value": [doc["tradePrice"], doc["lastPrice"], lots * size * (doc["lastPrice"] - doc["tradePrice"]), doc["mtm"],
              s["variationMargin"].sum(), lots * size, spec.get("tickValue"), spec.get("lastTradingDay")],
}), title="Position")

curve = await drishti.get_async("curve", doc["forwardCurve"])
c = pd.DataFrame(curve["contracts"])
c["next"] = c["settle"].shift(-1)
c["spread to next"] = c["settle"] - c["next"]
c["roll yield %/yr"] = np.log(c["settle"] / c["next"]) * 12 * 100            # consecutive monthly contracts: 1/12 year apart
c["shape"] = np.where(c["spread to next"] > 0, "backwardation", np.where(c["spread to next"] < 0, "contango", ""))
show(c.drop(columns="next"), title=f"{curve['curveId']}: {curve.get('shape', '')} ({curve.get('source', '')})")
mine = c[c["code"].str.endswith(doc["contract"].split()[-1])] if "contract" in doc else c.head(1)
if len(mine):
    r = mine.iloc[0]
    print(f"{r['code']}: roll to the next contract {'earns' if r['spread to next'] > 0 else 'costs'} "
          f"{abs(r['spread to next']):.2f} a barrel, {abs(r['roll yield %/yr']):.1f}% a year, "
          f"{abs(r['spread to next']) * lots * size:,.0f} on this position")
chart(c, kind="line", x="code", y="settle", title="Futures curve")
