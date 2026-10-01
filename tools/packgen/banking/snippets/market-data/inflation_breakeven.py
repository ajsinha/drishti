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

# title: Breakevens, forward inflation and real rates
# description: The inflation curve's zero-coupon breakevens by tenor, forward breakevens between tenors (the 5y5y and its kin, from compounded breakevens), real rates as the currency's OIS zero rate less the breakeven (Fisher, ignoring the risk premium and convexity), the latest index fixings' year-on-year rate, and the seasonality the curve applies by month.
# kinds: inflation-curve
# example: INFC INFC-USCPI

import pandas as pd
from drishti import quant as q

doc = view.doc
index = await drishti.get_async("inflation-index", doc.get("inflationIndex") or doc["index"])
ccy = index.get("currency", "USD")
ois = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{ccy}-OIS"))["points"])

be = pd.DataFrame(doc["points"])
be["years"] = be["tenor"].map(q.tenor_years)
be["breakeven"] = be["breakeven"] / 100
growth = (1 + be["breakeven"]) ** be["years"]                         # index growth implied to each tenor
be["forward from previous tenor %"] = ((growth / growth.shift(1, fill_value=1.0))
                                       ** (1 / be["years"].diff().fillna(be["years"])) - 1) * 100
be[f"{ccy} OIS zero %"] = ois.zero(be["years"].to_numpy()) * 100
be["real rate %"] = be[f"{ccy} OIS zero %"] - be["breakeven"] * 100   # Fisher: nominal = real + expected inflation
be["breakeven %"] = be["breakeven"] * 100
show(be.drop(columns=["breakeven"]), title=f"{view.id}: {index.get('name', '')} breakevens ({ccy})")


def fwd(a, b):                                                       # the a-year-forward b-year breakeven, e.g. 5y5y
    ga = (1 + q.ZeroCurve(be["years"], be["breakeven"], "linear").zero(a)) ** a
    gb = (1 + q.ZeroCurve(be["years"], be["breakeven"], "linear").zero(a + b)) ** (a + b)
    return ((gb / ga) ** (1 / b) - 1) * 100


show(pd.DataFrame({"forward": ["1y1y", "2y3y", "5y5y", "10y10y"],
                   "breakeven %": [fwd(1, 1), fwd(2, 3), fwd(5, 5), fwd(10, 10)]}),
     title="Forward breakevens (linear in the breakeven between tenors)")
fix = pd.DataFrame(index.get("fixings") or [])
if len(fix):
    print(f"Latest {index.get('name', '')} {fix['month'].iloc[0] if 'month' in fix else ''}: {index.get('latest')} "
          f"({index.get('yoy', 0):.2%} on the year); 1Y breakeven {be['breakeven %'].iloc[0]:.2f}%")
chart(be, kind="line", x="tenor", y=["breakeven %", "real rate %", f"{ccy} OIS zero %"], title="Nominal = real + breakeven")
if doc.get("seasonality"):
    chart(pd.DataFrame(doc["seasonality"]).assign(bp=lambda d: d["factor"] * 1e4), kind="bar", x="month", y="bp",
          title="Seasonality applied by month, bp")
