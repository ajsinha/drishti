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

"""The banking packs' starter snippets for Calc, Python in the browser (docs/guides/PYTHON_CALC.md): one file each under
packs/<pack>/python/, offered on the views of the kinds named in its `# kinds:` line. make_packs.py writes them, and
switches Calc on in the packs that have some (`python: { enabled: true }`). Each runs against the sample data as it is;
console tests run none of them (they need a browser), so a change here is checked in a browser (PYTHON_CALC.md)."""
from __future__ import annotations

# pack -> [(file name, title, description, kinds, code)]
SNIPPETS: dict[str, list[tuple[str, str, str, list[str], str]]] = {
    "trading": [("rate_shift.py", "MTM under parallel rate moves",
                 "The trade's MTM after parallel moves of -100 to +100 bp from its DV01 (first order), and the tenors that drive it.",
                 ["trade"], '''import pandas as pd

doc = view.doc
mtm = doc["mtm"]
dv01 = (doc.get("risk") or {}).get("dv01")
if not dv01:
    raise ValueError(f"{view.id} has no risk.dv01: this snippet is for rate-sensitive trades")

moves = pd.DataFrame({"shift_bp": [-100, -50, -25, -10, -1, 0, 1, 10, 25, 50, 100]})
moves["pnl"] = moves["shift_bp"] * dv01          # DV01: the change in value for +1 bp
moves["mtm"] = mtm + moves["pnl"]
show(moves, title=f"{view.id}: MTM {mtm:,.0f}, DV01 {dv01:,.0f} per bp")
chart(moves, kind="line", x="shift_bp", y="mtm", title="MTM after a parallel move (bp)")

buckets = pd.DataFrame(doc.get("sensitivities") or [])
if len(buckets):
    buckets["share"] = buckets["dv01"] / buckets["dv01"].sum()
    show(buckets, title="DV01 by tenor")
    chart(buckets, kind="bar", x="bucket", y="dv01", title="DV01 by tenor")
''')],
    "counterparty-risk": [("mtm_concentration.py", "MTM concentration by product and netting set",
                           "The counterparty's trades from drishti.search(): MTM by product and by netting set, each one's share of "
                           "gross MTM, and a Herfindahl index of how concentrated it is.",
                           ["netting-set", "counterparty"], '''cp = view.id if view.kind == "counterparty" else view.doc["counterparty"]
trades = drishti.search(f"TRD where counterparty.id = '{cp}' and nettingSet != '' limit 1000")
print(f"{cp}: {len(trades)} trades, net MTM {trades['mtm'].sum():,.0f}, gross {trades['mtm'].abs().sum():,.0f}")


def concentration(by):
    g = trades.groupby(by)["mtm"].agg(trades="count", net="sum", gross=lambda s: s.abs().sum())
    g["share"] = g["gross"] / g["gross"].sum()
    return g.sort_values("gross", ascending=False)


by_product = concentration("productType")
show(by_product, title="By product (share of gross MTM)")
chart(by_product, kind="bar", y="gross", title="Gross MTM by product")
show(concentration("nettingSet"), title="By netting set")
hhi = (by_product["share"] ** 2).sum()
print(f"Herfindahl index by product: {hhi:.3f} (1: all in one product; 1/{len(by_product)}: spread evenly)")
''')],
    "market-risk": [("var_es.py", "VaR and expected shortfall from the scenario P&L",
                     "Historical-simulation VaR (99%) and ES (97.5%) recomputed with numpy from the scenario P&Ls, set beside "
                     "the engine's figures, and the P&L histogram.",
                     ["var"], '''import numpy as np
import pandas as pd

pnl = np.asarray(view.doc["scenarioPnl"], dtype=float)
worst = np.sort(pnl)
n = len(pnl)
var99 = -worst[n // 100 - 1]                  # the 1% quantile: the 5th worst of 500 scenarios
es975 = -worst[: int(np.ceil(n * 0.025))].mean()   # the mean of the worst 2.5%

show(pd.DataFrame({
    "measure": ["VaR 99%", "ES 97.5%", "Mean P&L", "Worst scenario"],
    "recomputed": [var99, es975, pnl.mean(), -worst[0]],
    "engine": [view.doc.get("var99"), view.doc.get("es975"), view.doc.get("meanPnl"), None],
}), title=f"{view.id}: {n} scenarios ({view.doc.get('method', '')})")
chart(pd.DataFrame({"pnl": pnl}), kind="hist", y="pnl", bins=40, title="Scenario P&L")
print(f"VaR uses {var99 / view.doc['limit']:.0%} of the limit {view.doc['limit']:,.0f}")
''')],
    "banking-core": [("desk_pnl_by_book.py", "P&L by book: a pandas pivot",
                      "The desk's positions pivoted by book and product family (MTM), and today's P&L by book and currency from "
                      "the trades themselves (drishti.search()).",
                      ["desk"], '''import pandas as pd

pos = pd.DataFrame(view.doc["positions"])     # tradeId, book, currency, family, mtm
mtm = pos.pivot_table(index="book", columns="family", values="mtm", aggfunc="sum", fill_value=0,
                      margins=True, margins_name="Total")
show(mtm, title=f"{view.id}: MTM by book and product family")

trades = drishti.search(f"TRD where desk = '{view.id}' and pnl1d != 0 limit 1000")
pnl = trades.pivot_table(index="book", columns="currency", values="pnl1d", aggfunc="sum", fill_value=0,
                         margins=True, margins_name="Total")
show(pnl, title=f"1-day P&L by book and currency ({len(trades)} trades)")
chart(trades.groupby("book", as_index=False)["pnl1d"].sum(), kind="bar", x="book", y="pnl1d", title="1-day P&L by book")
''')],
    "market-data": [("curve_tenor.py", "Interpolate the curve at any tenor",
                     "Zero rates at tenors the curve does not quote: linear on zero rates, log-linear on discount factors (how "
                     "the curve is built) and a cubic spline (scipy), with a matplotlib figure.",
                     ["ir-curve"], '''import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from scipy.interpolate import CubicSpline

WANT = ["18M", "4Y", "12Y"]                  # change these: 3W, 9M, 7Y, ...
UNIT = {"D": 365.0, "W": 52.0, "M": 12.0, "Y": 1.0}


def years(tenor):
    return float(tenor[:-1]) / UNIT[tenor[-1].upper()]


pts = pd.DataFrame(view.doc["points"])
pts["years"] = pts["tenor"].map(years)
pts = pts.sort_values("years")
t, z, df = pts["years"].to_numpy(), pts["zeroRate"].to_numpy(), pts["df"].to_numpy()
spline = CubicSpline(t, z)


def log_linear(x):                            # linear in log(df), back to a continuously compounded zero rate in %
    return -np.interp(x, t, np.log(df)) / x * 100


want = pd.DataFrame({"tenor": WANT, "years": [years(w) for w in WANT]})
want["linear zero %"] = np.interp(want["years"], t, z)
want["log-linear df, zero %"] = log_linear(want["years"].to_numpy())
want["cubic spline %"] = spline(want["years"])
show(want, title=f"{view.id}: interpolated zero rates")

grid = np.linspace(t.min(), t.max(), 200)
fig, ax = plt.subplots(figsize=(7, 3.2))
ax.plot(grid, spline(grid), label="cubic spline")
ax.plot(grid, log_linear(grid), "--", label="log-linear df")
ax.scatter(t, z, color="black", s=12, zorder=3, label="quoted")
ax.scatter(want["years"], want["cubic spline %"], color="tab:red", s=30, zorder=4, label="asked")
ax.set_xlabel("years")
ax.set_ylabel("zero rate %")
ax.set_title(f"{view.id} ({view.doc.get('index', '')})")
ax.legend(fontsize=8)
''')],
}
