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

# title: FRTB GIRR delta charge from the book's trades (simplified SBM)
# description: The sensitivities-based method for interest-rate delta, built bottom-up: every trade of the book read, DV01 by tenor bucket summed by currency (a GIRR bucket is a currency), weighted by the Basel tenor risk weights, aggregated within each currency with the tenor correlation max(exp(-3% |Tk - Tl| / min(Tk, Tl)), 40%) and across currencies at 50%, under the low, medium and high correlation scenarios. Simplified: one curve per currency, no basis or inflation risk factors, no reduced weights for major currencies.
# kinds: frtb-sensitivity, book
# example: FRTB FRTB-RATES-1

import numpy as np
import pandas as pd
from drishti import quant as q

RW = {0.25: 0.017, 0.5: 0.017, 1: 0.016, 2: 0.013, 3: 0.012, 5: 0.011, 10: 0.011, 15: 0.011, 20: 0.011, 30: 0.011}
book = view.doc.get("book") or view.id
trades = await drishti.search_async(f"TRD where book = '{book}' limit 1000")
rows = []
for tid in trades["id"]:
    d = await drishti.get_async("trade", tid)
    for s in d.get("sensitivities") or []:
        if s.get("dv01"):
            rows.append({"currency": d["currency"], "tenor": q.tenor_years(s["bucket"]), "dv01": s["dv01"]})
if not rows:
    raise ValueError(f"{book} holds no interest-rate sensitivities (DV01 by tenor)")
s = pd.DataFrame(rows).groupby(["currency", "tenor"], as_index=False)["dv01"].sum()
vertices = np.array(sorted(RW))
s["vertex"] = [vertices[np.argmin(np.abs(vertices - t))] for t in s["tenor"]]       # map each bucket to the nearest vertex
s = s.groupby(["currency", "vertex"], as_index=False)["dv01"].sum()
s["WS"] = [RW[v] * 1e4 * d for v, d in zip(s["vertex"], s["dv01"])]                 # s_k = DV01 / 1 bp; WS = RW x s_k


def charge(scale):                                       # scale the correlations: 1 medium, 1.25 high, low as Basel
    adj = lambda r: min(1.0, r * 1.25) if scale == "high" else max(2 * r - 1, 0.75 * r) if scale == "low" else r   # noqa: E731
    kb, sb = [], []
    for ccy, g in s.groupby("currency"):
        T = g["vertex"].to_numpy(dtype=float)
        rho = np.array([[adj(max(np.exp(-0.03 * abs(a - b) / min(a, b)), 0.4)) for b in T] for a in T])
        kb.append(q.frtb_bucket_charge(g["WS"].to_numpy(), rho))
        sb.append(g["WS"].sum())
    return q.frtb_across_buckets(kb, sb, adj(0.5)), dict(zip(s["currency"].unique(), kb))


results = {sc: charge(sc) for sc in ("low", "medium", "high")}
show(s.pivot_table(index="vertex", columns="currency", values="WS", aggfunc="sum", fill_value=0),
     title=f"{book}: weighted sensitivities (WS) by vertex and currency, from {len(trades)} trades")
show(pd.DataFrame({sc: r[1] for sc, r in results.items()}).rename_axis("currency"), title="Bucket charge K_b by currency and correlation scenario")
best = max(results, key=lambda sc: results[sc][0])
show(pd.DataFrame({"scenario": list(results), "GIRR delta charge": [r[0] for r in results.values()]}),
     title=f"GIRR delta capital: the {best} scenario binds at {results[best][0]:,.0f}")
if view.kind == "frtb-sensitivity":
    print(f"The engine's delta charge (all risk classes) is {view.doc.get('deltaCharge'):,.0f}; its inputs differ from this rebuild")
