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

# title: Trade P&L history: hit rate, Sharpe, drawdown and explain
# description: The trade's daily P&L history (and the MTM Drishti recorded, from drishti.history): cumulative P&L, mean and volatility, an annualised Sharpe ratio (no risk-free deduction), hit rate and win/loss ratio, the worst day, lag-1 autocorrelation and the deepest drawdown of cumulative P&L; then today's P&L explain as a waterfall-style bar chart with the unexplained share.
# kinds: trade
# example: TRD MX-20000001

import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
hist = pd.DataFrame(doc.get("pnlHistory") or [])
if hist.empty:
    raise ValueError(f"{view.id} has no pnlHistory")
pnl = hist.set_index("date")["pnl"].astype(float)
cum = pnl.cumsum()
wins, losses = pnl[pnl > 0], pnl[pnl < 0]
dd = q.max_drawdown(cum.to_numpy())
show(pd.DataFrame({
    "measure": ["days", "cumulative P&L", "mean daily", "daily volatility", "Sharpe (annualised)", "hit rate", "average win",
                "average loss", "win/loss ratio", "worst day", "best day", "lag-1 autocorrelation", "max drawdown of cumulative"],
    "value": [len(pnl), cum.iloc[-1], pnl.mean(), pnl.std(ddof=1), q.sharpe(pnl), f"{(pnl > 0).mean():.0%}", wins.mean(),
              losses.mean(), abs(wins.mean() / losses.mean()) if len(losses) and len(wins) else None,
              f"{pnl.min():,.0f} on {pnl.idxmin()}", f"{pnl.max():,.0f} on {pnl.idxmax()}", pnl.autocorr(1), dd["drawdown"]],
}), title=f"{view.id}: {doc.get('productName', '')}, daily P&L ({pnl.index[0]} to {pnl.index[-1]})")
chart(pd.DataFrame({"daily P&L": pnl, "cumulative": cum}), kind="line", title="Daily and cumulative P&L")

# the MTM Drishti itself recorded day by day (drishti.history): its daily change should match the booked P&L
h = await drishti.history_async("trade", view.id, "mtm", 30)
moves = h["mtm"].astype(float).diff().dropna()
if moves.abs().sum() > 0:
    both = pd.concat([moves.rename("MTM change"), pnl.rename("booked P&L").set_axis(pd.to_datetime(pnl.index))], axis=1).dropna()
    show(both.assign(difference=both["MTM change"] - both["booked P&L"]), title="MTM history against the booked P&L")
else:
    print(f"Drishti's MTM history for {view.id} is flat over {len(h)} days (the samples hold one valuation): "
          "the P&L history above is the booking's own")

steps = pd.DataFrame(doc.get("pnlExplain") or [])
if len(steps):
    show(steps, title="P&L explain, as booked")
    moves = steps[~steps.get("total", pd.Series(False, index=steps.index)).fillna(False).astype(bool)]
    chart(moves, kind="bar", x="step", y="pnl", title="P&L explain by step (opening and closing totals left out)")
    unexplained = moves.loc[moves["step"].str.contains("nexplained", na=False), "pnl"].sum()
    moved = moves["pnl"].abs().sum()
    if moved:
        print(f"Unexplained {unexplained:,.0f}: {abs(unexplained) / moved:.1%} of the day's absolute moves")
