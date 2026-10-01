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

# title: Realised volatility: close-to-close, Parkinson, Garman-Klass
# description: Annualised realised volatility from the daily bars by three estimators (close-to-close, Parkinson's high-low, Garman-Klass's OHLC; the range estimators ignore overnight gaps), a rolling 20-day volatility, and the gap to the front implied volatility of the linked surface: the volatility risk premium.
# kinds: equity, commodity
# example: EQ EQ-NVTK

import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
bars = pd.DataFrame(doc["ohlc"]).set_index("date").sort_index()
WINDOW = 20

est = pd.DataFrame({
    "estimator": ["close-to-close", "Parkinson (high-low)", "Garman-Klass (OHLC)"],
    f"all {len(bars)} days %": [q.close_to_close_vol(bars["close"]) * 100, q.parkinson_vol(bars["high"], bars["low"]) * 100,
                                q.garman_klass_vol(bars["open"], bars["high"], bars["low"], bars["close"]) * 100],
    f"last {WINDOW} days %": [q.close_to_close_vol(bars["close"].tail(WINDOW + 1)) * 100,
                              q.parkinson_vol(bars["high"].tail(WINDOW), bars["low"].tail(WINDOW)) * 100,
                              q.garman_klass_vol(*(bars[c].tail(WINDOW) for c in ("open", "high", "low", "close"))) * 100],
})

# the front implied vol of the linked surface: equity -> equityVolSurface (1M ATM), commodity -> commodityVolSurface
implied = None
if doc.get("equityVolSurface"):
    implied = (await drishti.get_async("equity-vol-surface", doc["equityVolSurface"])).get("atm1m")
elif doc.get("commodityVolSurface"):
    implied = (await drishti.get_async("commodity-vol-surface", doc["commodityVolSurface"])).get("atmFront")
if implied is not None:
    est["implied - realised pts"] = implied - est[f"last {WINDOW} days %"]
show(est, title=f"{view.id}: realised volatility, annualised (252 days)" + (f"; front implied {implied:.2f}%" if implied else ""))

rets = np.log(bars["close"]).diff()
rolling = (rets.rolling(WINDOW).std(ddof=1) * np.sqrt(252) * 100).dropna().to_frame(f"{WINDOW}d close-to-close %")
hl = np.log(bars["high"] / bars["low"]) ** 2 / (4 * np.log(2))
rolling[f"{WINDOW}d Parkinson %"] = (np.sqrt(hl.rolling(WINDOW).mean() * 252) * 100).reindex(rolling.index)
if implied is not None:
    rolling["front implied %"] = implied
chart(rolling, kind="line", title=f"Rolling {WINDOW}-day realised volatility")
if implied is not None:
    vrp = implied - est.iloc[0][f"last {WINDOW} days %"]
    print(f"Implied is {abs(vrp):.1f} points {'above' if vrp > 0 else 'below'} {WINDOW}-day realised: "
          f"{'options look rich (sellers paid)' if vrp > 0 else 'options look cheap against recent moves'}")
