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

# title: Swaption: Bachelier price, DV01 and vega
# description: The European swaption priced with the Bachelier (normal) model from its own underlying swap: expiry at MATURITY_DATE, the forward swap rate and the annuity from the fixed leg's flows (LEGS[].CASH_FLOWS start and end dates) on the leg's DISCOUNT_INDEX curve (single-curve), the strike STRIKE_PRICE, payer or receiver from OPTION_TYPE, the normal vol read off the currency's vol cube (IRV-<CURRENCY>) at its expiry and swap tenor (linear in both); premium, delta as DV01, gamma and vega per bp of vol for the position (BUY_SELL_CODE, QUANTITY), against the risk engine's MTM; a ladder of the premium against forward-rate moves.
# kinds: trade
# example: TRD MX-20000097

from datetime import date
import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
fixed = next((leg for leg in doc.get("LEGS") or [] if leg.get("LEG_TYPE") == "FIXED"), None)
if not doc.get("OPTION_TYPE") or doc.get("STRIKE_PRICE") in (None, "") or not fixed or not fixed.get("CASH_FLOWS"):
    raise ValueError(f"{view.id} ({doc.get('PRODUCT_TYPE')}) is not a swaption with an underlying fixed leg")
as_of = date.fromisoformat(view.business_date or "2026-09-30")
yrs = lambda d: (date.fromisoformat(d) - as_of).days / 365.0         # noqa: E731
disc = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", fixed.get("DISCOUNT_INDEX") or f"CRV-{doc['CURRENCY']}-OIS"))["POINTS"])
te = max(yrs(doc["MATURITY_DATE"]), 1 / 365)
periods = [(max(yrs(c["START_DATE"]), 0), yrs(c["END_DATE"])) for c in fixed["CASH_FLOWS"]]
ann = sum((t1 - t0) * disc.df(t1) for t0, t1 in periods)
start, end = periods[0][0], periods[-1][1]
F = (disc.df(start) - disc.df(end)) / ann                             # the forward swap rate, single-curve
K, payer = float(doc["STRIKE_PRICE"]), doc["OPTION_TYPE"] == "Payer"
N = float(doc.get("QUANTITY") or fixed.get("NOTIONAL") or 0) * (1 if doc.get("BUY_SELL_CODE") == "B" else -1)
n = end - start

cube = await drishti.get_async("ir-vol-cube", f"IRV-{doc['CURRENCY']}")
grid = pd.DataFrame(cube["GRID"])
exp_t = grid["EXPIRY"].map(q.tenor_years).to_numpy()
cols = [c for c in grid.columns if c.startswith("T_")]
ten_t = np.array([q.tenor_years(c[2:]) for c in cols])
vol_bp = float(np.interp(n, ten_t, [np.interp(te, exp_t, grid[c]) for c in cols]))

price = lambda f, v: ann * q.bachelier(f, K, te, v / 1e4, 1.0, payer)   # noqa: E731 - per unit of notional
h = 1e-4
pv = price(F, vol_bp)
delta = (price(F + h, vol_bp) - price(F - h, vol_bp)) / 2
gamma = price(F + h, vol_bp) - 2 * pv + price(F - h, vol_bp)
vega = (price(F, vol_bp + 1) - price(F, vol_bp - 1)) / 2
try:
    booked = (await drishti.get_async("trade-valuation", f"TVAL-{doc['TRADE_ID']}"))["MTM"]
except Exception:                                                     # market-risk not installed
    booked = None
show(pd.DataFrame({
    "measure": ["option", "expiry (years)", "swap tenor (years)", "forward swap rate", "strike", "moneyness bp", "annuity", "normal vol bp",
                "premium (position)", "premium bp of notional", "DV01 (per +1 bp)", "gamma (per bp squared)", "vega per bp vol", "risk engine MTM"],
    "value": [f"{'long' if N > 0 else 'short'} {doc['OPTION_TYPE'].lower()}", te, n, f"{F:.4%}", f"{K:.4%}", (F - K) * 1e4, ann, vol_bp,
              pv * N, pv * 1e4, delta * N, gamma * N, vega * N, booked],
}), title=f"{view.id}: {te:.2f}y into {n:.1f}y on {abs(N):,.0f} {doc['CURRENCY']} (Bachelier, {cube['CUBE_ID']})")
moves = np.arange(-100, 101, 25)
chart(pd.DataFrame({"forward move bp": moves, "premium": [price(F + m / 1e4, vol_bp) * N for m in moves]}),
      kind="line", x="forward move bp", y="premium", title="Premium against a move in the forward swap rate")
