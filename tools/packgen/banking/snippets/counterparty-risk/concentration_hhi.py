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

# title: Exposure concentration: HHI by name, sector, rating and country
# description: Every counterparty's peak PFE 95% (the exposure engine's exposure-result at CPTY:<id>, computed from the paths; counterparties the run does not cover are left out) as the exposure measure: the Herfindahl-Hirschman index by counterparty, sector, rating and country (and the equivalent number of equal names, 1/HHI), the top five names' share, expected loss as PD x LGD 60% x peak PFE from each counterparty's one-year PD, and exposure by rating grade. Peak PFE overstates expected exposure; it is the limit measure.
# kinds: credit-limit, counterparty-group, counterparty
# example: LIM LIM-SUMMIT

import pandas as pd
from drishti import quant as q

cps = await drishti.search_async("CPTY where RATING != '' and SECTOR != '' and COUNTRY != '' limit 1000")
pds, pfe = {}, {}
for cid in cps["id"]:
    pds[cid] = ((await drishti.get_async("counterparty", cid)).get("KYC") or {}).get("PD_1Y", 0.0)
    x = await drishti.get_async("exposure-result", "CPTY:" + cid)
    pfe[cid] = x.get("PEAK_PFE") if x.get("STATUS") == "ok" else None
cps["PFE_PEAK"] = cps["id"].map(pfe)
cps = cps[cps["PFE_PEAK"].fillna(0) > 0].copy()
cps["PD 1Y"] = cps["id"].map(pds)
cps["expected loss"] = cps["PD 1Y"] * 0.6 * cps["PFE_PEAK"]
total = cps["PFE_PEAK"].sum()

rows = []
for by in ["ID", "SECTOR", "RATING", "COUNTRY"]:
    g = cps.groupby(by)["PFE_PEAK"].sum()
    h = q.hhi(g)
    rows.append({"BY": "COUNTERPARTY" if by == "ID" else by, "groups": len(g), "HHI": h, "equivalent equal groups": 1 / h,
                 "largest": g.idxmax(), "largest share": g.max() / total})
show(pd.DataFrame(rows), title=f"Concentration of peak PFE, {len(cps)} counterparties, {total:,.0f} in all")

top = cps.sort_values("PFE_PEAK", ascending=False).head(10).reset_index(drop=True)
top["share"] = top["PFE_PEAK"] / total
top["cumulative share"] = top["share"].cumsum()
show(top[["ID", "NAME", "RATING", "SECTOR", "COUNTRY", "PFE_PEAK", "share", "cumulative share", "PD 1Y", "expected loss"]],
     title=f"Top ten names: the top five hold {top['share'].head(5).sum():.0%}")
order = ["AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB+", "BBB", "BBB-", "BB+", "BB", "BB-", "B+", "B", "B-"]
by_rating = cps.groupby("RATING").agg(names=("ID", "size"), exposure=("PFE_PEAK", "sum"), expected_loss=("expected loss", "sum"))
by_rating = by_rating.reindex([r for r in order if r in by_rating.index])
show(by_rating, title="By rating grade")
chart(by_rating.reset_index(), kind="bar", x="RATING", y="exposure", title="Peak PFE by rating")
HHI_SECTOR = rows[1]["HHI"]
print(f"Sector HHI {HHI_SECTOR:.3f}: {'concentrated (above 0.25)' if HHI_SECTOR > 0.25 else 'moderate (0.15-0.25)' if HHI_SECTOR > 0.15 else 'diversified'}")
