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

# title: Stress scenario P&L rebuilt from trade sensitivities
# description: The scenario's shocks applied to every trade's booked sensitivities (one search for all of them), first order only: rate moves times DV01 (a currency's rates, or 2Y and 10Y interpolated by maturity), spread moves times CS01, breakevens times IE01, equity, oil and gas moves times delta, equity vol times vega, USD moves times FX delta; by desk, set beside the engine's stress results. Assumes delta and FX delta are P&L per 1% move; ignores convexity, cross effects and basis.
# kinds: stress-scenario
# example: SCN SCN-GFC2008

import numpy as np
import pandas as pd

shocks = {s["factor"]: s["shock"] for s in view.doc["shocks"]}
cols = ["risk.dv01", "risk.cs01", "risk.ie01", "risk.delta", "risk.vega", "risk.fxDelta"]
# each condition names a field (a missing field reads as not 0, so nothing is filtered out)
t = await drishti.search_async("TRD where desk != '' and assetClass != '' and " + " and ".join(f"{c} != 0" for c in cols) + " limit 1000")
t[cols] = t[cols].apply(pd.to_numeric, errors="coerce").fillna(0.0)
years = (pd.to_datetime(t["maturityDate"]) - pd.Timestamp(view.business_date or "2026-09-30")).dt.days / 365.25
EM = {"BRL", "CNH", "INR", "KRW", "MXN", "ZAR", "TRY"}
pnl = pd.Series(0.0, index=t.index)
for factor, shock in shocks.items():
    f = factor.lower()
    if f.endswith(" rates") and f.split()[0].upper() in set(t["currency"]):        # "USD rates": that currency, shock in %pts
        pnl += np.where(t["currency"] == f.split()[0].upper(), t["risk.dv01"] * shock * 100, 0)
    elif f in ("2y rates", "10y rates"):
        continue                                                                   # a twist: applied below, by maturity
    elif "credit spreads" in f:
        pnl += t["risk.cs01"] * shock * 100
    elif f == "breakevens":
        pnl += t["risk.ie01"] * shock * 100
    elif f == "equities":
        pnl += np.where(t["assetClass"].str.contains("Equit"), t["risk.delta"] * shock * 100, 0)
    elif f == "equity vol":
        pnl += np.where(t["assetClass"].str.contains("Equit"), t["risk.vega"] * shock, 0)
    elif f in ("oil", "natural gas"):
        pnl += np.where(t["assetClass"].str.contains("Commod"), t["risk.delta"] * shock * 100, 0)
    elif f.startswith("usd vs"):                                                   # USD up: the other currency down
        em = t["currency"].isin(EM)
        pnl += np.where(em if f.endswith("em") else ~em, -t["risk.fxDelta"] * shock * 100, 0)
if "2Y rates" in shocks and "10Y rates" in shocks:
    move = np.interp(years.clip(2, 10), [2, 10], [shocks["2Y rates"], shocks["10Y rates"]])
    pnl += t["risk.dv01"] * move * 100
t["stress P&L"] = pnl

by_desk = t.groupby("desk").agg(trades=("id", "size"), rebuilt=("stress P&L", "sum"))
engine = await drishti.search_async(f"STR where scenario = '{view.id}' and desk != '' and pnl < 1000bn limit 100")
by_desk["engine"] = by_desk.index.map(dict(zip(engine["desk"], engine["pnl"])))
show(by_desk.sort_values("rebuilt"), title=f"{view.id}: {view.doc.get('name', '')}, {view.doc.get('horizon', '')}: first-order P&L by desk")
show(pd.DataFrame({"factor": list(shocks), "shock": list(shocks.values())}), title="Shocks (rates and spreads in % points, others relative)")
chart(by_desk.reset_index(), kind="bar", x="desk", y=["rebuilt", "engine"], title="Rebuilt against engine stress P&L")
show(t.nsmallest(10, "stress P&L")[["id", "desk", "productType", "currency", "stress P&L"]].reset_index(drop=True), title="Ten worst trades")
