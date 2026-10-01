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

# title: FRTB charges across books: delta, vega, curvature
# description: Every book's FRTB standardised result (one search, then each document): delta, vega and curvature charges and the total by book and by risk class, each component's share, a check that the three components add up to the total, and the books ranked by charge with this one marked.
# kinds: frtb-sensitivity
# example: FRTB FRTB-FX-1

import pandas as pd

found = await drishti.search_async("FRTB where total > 0 and book != '' limit 1000")
rows = []
for rid in found["id"]:
    d = await drishti.get_async("frtb-sensitivity", rid)
    classes = ", ".join(sorted({c["riskClass"] for c in d.get("byClass") or []}))
    rows.append({"book": d["book"], "risk class": classes, "delta": d["deltaCharge"], "vega": d["vegaCharge"],
                 "curvature": d["curvatureCharge"], "total": d["total"]})
df = pd.DataFrame(rows).set_index("book").sort_values("total", ascending=False)
df["components - total"] = df[["delta", "vega", "curvature"]].sum(axis=1) - df["total"]
df["delta share"] = df["delta"] / df["total"]
df["rank"] = range(1, len(df) + 1)
show(df, title=f"FRTB SBM charges, {len(df)} books, firm total {df['total'].sum():,.0f} (simple sum, no diversification)")
by_class = df.groupby("risk class")[["delta", "vega", "curvature", "total"]].sum().sort_values("total", ascending=False)
show(by_class.assign(share=by_class["total"] / by_class["total"].sum()), title="By risk class")
chart(by_class.reset_index(), kind="bar", x="risk class", y=["delta", "vega", "curvature"], title="Charge by risk class and component")
mismatch = df[df["components - total"].abs() > 1]
print("Delta + vega + curvature equals the total for every book" if mismatch.empty
      else f"{len(mismatch)} books where the components do not add up to the total: {', '.join(mismatch.index)}")
me = view.doc.get("book")
if me in df.index:
    print(f"{me}: {df.loc[me, 'total']:,.0f}, rank {df.loc[me, 'rank']} of {len(df)}; delta {df.loc[me, 'delta share']:.0%} of it")
