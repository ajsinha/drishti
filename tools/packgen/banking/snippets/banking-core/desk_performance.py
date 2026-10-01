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

# title: Desk performance: Sharpe, hit rate and return on VaR
# description: The desk's daily P&L series (from its VaR result): cumulative P&L, annualised Sharpe ratio (no risk-free deduction), hit rate, average win against average loss, the worst day, maximum drawdown and the days to recover, and return on VaR (cumulative P&L over today's VaR); a rolling 20-day Sharpe and the cumulative P&L drawn.
# kinds: desk
# example: DESK DESK-RATES

import numpy as np
import pandas as pd
from drishti import quant as q

var = await drishti.get_async("var", view.doc.get("varResult") or f"VAR-{view.id.removeprefix('DESK-')}")
pnl = pd.DataFrame(var["pnlSeries"]).set_index("date")["pnl"].astype(float)
cum = pnl.cumsum()
dd = q.max_drawdown(cum.to_numpy())
after = cum.iloc[dd["trough"]:]
recovered = after[after >= cum.iloc[dd["peak"]]]
wins, losses = pnl[pnl > 0], pnl[pnl < 0]
show(pd.DataFrame({
    "measure": ["days", "cumulative P&L", "mean daily", "daily volatility", "Sharpe (annualised)", "hit rate", "average win",
                "average loss", "win / loss", "worst day", "max drawdown", "drawdown peak", "drawdown trough", "recovered on",
                "VaR 99% today", "return on VaR"],
    "value": [len(pnl), cum.iloc[-1], pnl.mean(), pnl.std(ddof=1), q.sharpe(pnl), f"{(pnl > 0).mean():.0%}", wins.mean(),
              losses.mean(), abs(wins.mean() / losses.mean()), f"{pnl.min():,.0f} on {pnl.idxmin()}", dd["drawdown"],
              cum.index[dd["peak"]], cum.index[dd["trough"]], recovered.index[0] if len(recovered) else "not yet",
              var["var99"], cum.iloc[-1] / var["var99"]],
}), title=f"{view.id}: {view.doc.get('name', '')}, {pnl.index[0]} to {pnl.index[-1]}")
rolling = (pnl.rolling(20).mean() / pnl.rolling(20).std(ddof=1) * np.sqrt(252)).dropna()
chart(pd.DataFrame({"cumulative P&L": cum}), kind="line", title="Cumulative P&L")
chart(rolling.to_frame("rolling 20-day Sharpe"), kind="line", title="Rolling 20-day Sharpe (annualised)")
