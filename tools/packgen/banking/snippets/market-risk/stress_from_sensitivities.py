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
# description: The scenario's rate shocks applied to every trade's DV01 from the risk engine's trade valuations (one search over TVAL, every source system): a currency's rate move times the DV01 of the trades in that currency, and a 2Y/10Y twist interpolated by MATURITY_DATE; by desk and by source system, beside the engine's stress results. A trade valuation carries MTM, PNL_1D and DV01 only, so the scenario's other factors (spreads, equities, commodities, FX, vol) are listed as not rebuilt; first order, no convexity, cross effects or basis.
# kinds: stress-scenario
# example: SCN SCN-GFC2008

import numpy as np
import pandas as pd

shocks = {s["FACTOR"]: s["SHOCK"] for s in view.doc["SHOCKS"]}
t = await drishti.search_async("TVAL where DESK != '' and DV01 != 0 limit 5000")
t["DV01"] = pd.to_numeric(t["DV01"], errors="coerce").fillna(0.0)
years = (pd.to_datetime(t["MATURITY_DATE"]) - pd.Timestamp(view.business_date or "2026-09-30")).dt.days / 365.25
pnl = pd.Series(0.0, index=t.index)
used, skipped = [], []
for factor, shock in shocks.items():
    f = factor.lower()
    if f.endswith(" rates") and f.split()[0].upper() in set(t["CURRENCY"]):        # "USD rates": that currency, shock in % points
        pnl += np.where(t["CURRENCY"] == f.split()[0].upper(), t["DV01"] * shock * 100, 0)
        used.append(factor)
    elif f not in ("2y rates", "10y rates"):
        skipped.append(factor)                                                     # no per-trade sensitivity for it in TVAL
if "2Y rates" in shocks and "10Y rates" in shocks:                                 # a twist, by maturity
    pnl += t["DV01"] * np.interp(years.clip(2, 10), [2, 10], [shocks["2Y rates"], shocks["10Y rates"]]) * 100
    used += ["2Y rates", "10Y rates"]
t["stress P&L"] = pnl

by_desk = t.groupby("DESK").agg(trades=("ID", "size"), rebuilt=("stress P&L", "sum"))
engine = await drishti.search_async(f"STR where SCENARIO = '{view.id}' and DESK != '' and PNL < 1000bn limit 100")
by_desk["engine"] = by_desk.index.map(dict(zip(engine["DESK"], engine["PNL"])))
show(by_desk.sort_values("rebuilt"), title=f"{view.id}: {view.doc.get('NAME', '')}, {view.doc.get('HORIZON', '')}: rate P&L by desk")
show(t.groupby("SOURCE_SYSTEM").agg(trades=("ID", "size"), rebuilt=("stress P&L", "sum")).reset_index(), title="By source system")
show(pd.DataFrame({"FACTOR": list(shocks), "SHOCK": list(shocks.values()),
                   "rebuilt": ["yes" if f in used else "no: TVAL has DV01 only" for f in shocks]}), title="Shocks (rates in % points)")
chart(by_desk.reset_index(), kind="bar", x="DESK", y=["rebuilt", "engine"], title="Rebuilt rate P&L against the engine's full stress P&L")
show(t.nsmallest(10, "stress P&L")[["ID", "SOURCE_SYSTEM", "DESK", "PRODUCT_TYPE", "CURRENCY", "stress P&L"]].reset_index(drop=True), title="Ten worst trades")
