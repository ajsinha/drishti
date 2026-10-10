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

# title: Overnight index: compounded in arrears and averages
# description: The index's daily fixings compounded in arrears (each rate weighted by the calendar days it applies, ACT/360) against the simple average, over the whole window and over the last 30 calendar days (as the published 30-day averages), the daily spread between the 1st and 99th percentile of transactions, and the latest fixing against the discount curve's 1-month zero rate.
# kinds: index

import pandas as pd
from drishti import quant as q

doc = view.doc
fx = pd.DataFrame(doc["fixings"]).assign(date=lambda d: pd.to_datetime(d["date"])).sort_values("date").reset_index(drop=True)
fx["days"] = fx["date"].diff().shift(-1).dt.days.fillna(1).astype(int)       # a Friday's rate runs over the weekend
BASIS = 360 if "360" in doc.get("dayCount", "ACT/360") else 365


def compounded(frame):
    growth = (1 + frame["rate"] / 100 * frame["days"] / BASIS).prod()
    days = frame["days"].sum()
    return (growth - 1) * BASIS / days * 100, (frame["rate"] * frame["days"]).sum() / days, int(days)


rows = []
for name, frame in [("whole window", fx), ("last 30 days", fx[fx["date"] > fx["date"].iloc[-1] - pd.Timedelta(days=30)])]:
    comp, avg, days = compounded(frame)
    rows.append({"window": name, "from": frame["date"].iloc[0].date(), "days": days, "compounded %": comp, "simple average %": avg,
                 "compounding bp": (comp - avg) * 100})
show(pd.DataFrame(rows), title=f"{doc.get('index', view.id)}: {doc.get('name', '')}, {doc.get('dayCount', '')}")
fx["p99 - p1 bp"] = (fx["p99"] - fx["p1"]) * 100 if "p99" in fx else None
try:
    curve = await drishti.get_async("curve", f"USD-{doc.get('index', view.id)}")
    z1m = q.ZeroCurve.from_points(curve["pillars"]).zero(1 / 12) * 100
    print(f"Latest fixing {fx['rate'].iloc[-1]:.4f}% against the {curve['curveId']} 1M zero rate {z1m:.4f}%: "
          f"{(z1m - fx['rate'].iloc[-1]) * 100:+.1f} bp priced in over a month")
except drishti.DrishtiError:
    pass
chart(fx.set_index(fx["date"].dt.strftime("%Y-%m-%d"))[["rate"]], kind="line", title="Fixings, %")
chart(fx.set_index(fx["date"].dt.strftime("%Y-%m-%d"))[["volumeBn"]], kind="bar", title="Volume, bn")
