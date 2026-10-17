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

# title: Book DV01 by currency and tenor, and the par-swap hedge
# description: Every trade of the book read for its bucketed DV01, summed by currency and tenor; then the hedge: for each bucket, the notional of a par swap at that tenor (on the currency's OIS curve, annual fixed, its DV01 from the annuity) that offsets the bucket's DV01, receive or pay. Each hedge is sized on its own bucket; the spill of a hedge's own risk into shorter buckets is ignored, a simplification.
# kinds: book
# example: BOOK BOOK-RATES-2

import pandas as pd
from drishti import quant as q

trades = await drishti.search_async(f"TRD where book = '{view.id}' and risk.dv01 != 0 limit 1000")
rows = []
for tid in trades["id"]:
    d = await drishti.get_async("trade", tid)
    for s in d.get("sensitivities") or []:
        if s.get("dv01"):
            rows.append({"currency": d["currency"], "bucket": s["bucket"], "dv01": s["dv01"]})
if not rows:
    raise ValueError(f"{view.id} holds no bucketed DV01")
risk = pd.DataFrame(rows).groupby(["currency", "bucket"], as_index=False)["dv01"].sum()
risk["years"] = risk["bucket"].map(q.tenor_years)
order = risk.drop_duplicates("bucket").sort_values("years")["bucket"].tolist()
grid = risk.pivot_table(index="currency", columns="bucket", values="dv01", aggfunc="sum", fill_value=0).reindex(columns=order)
show(grid.assign(total=grid.sum(axis=1)), title=f"{view.id}: DV01 by currency and tenor, from {len(trades)} trades (per +1 bp)")

hedges = []
for ccy, g in risk.groupby("currency"):
    try:
        curve = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{ccy}-OIS"))["points"])
    except drishti.DrishtiError:
        continue
    for _, r in g.iterrows():
        T = max(r["years"], 0.25)
        swap_dv01 = -q.annuity(curve, T, 1) * 1e6 / 1e4        # a receiver of 1m loses this for +1 bp
        n = -r["dv01"] / swap_dv01 * 1e6                        # receiver notional that offsets the bucket
        hedges.append({"currency": ccy, "tenor": r["bucket"], "book DV01": r["dv01"], "par rate %": q.par_swap_rate(curve, T, 1) * 100,
                       "hedge": "receive fixed" if n > 0 else "pay fixed", "notional": abs(n)})
h = pd.DataFrame(hedges)
h = h.assign(years=h["tenor"].map(q.tenor_years)).sort_values(["currency", "years"]).drop(columns="years").reset_index(drop=True)
show(h, title="Par-swap hedges, bucket by bucket")
show(h.groupby(["currency", "hedge"], as_index=False)["notional"].sum(), title="Hedge notional by currency and side")
chart(risk.groupby("bucket")["dv01"].sum().reindex(order).to_frame("DV01").reset_index(), kind="bar", x="bucket", y="DV01",
      title="Book DV01 by tenor, all currencies")
