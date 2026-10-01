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
# description: The European swaption priced with the Bachelier (normal) model: the forward swap rate from the projection curve and the annuity from the discount curve (annual fixed leg), the normal vol read off the currency's vol cube at its expiry and swap tenor (linear in both), premium, delta as DV01, gamma and vega per bp of vol for the position; a ladder of the premium against forward-rate moves.
# kinds: trade
# example: TRD MX-20000101

from datetime import date
import numpy as np
import pandas as pd
from drishti import quant as q

doc, t = view.doc, view.doc.get("terms") or {}
if "underlyingTenor" not in t or "strike" not in t:
    raise ValueError(f"{view.id} ({doc.get('productType')}) is not a swaption")
disc = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", doc["discountCurve"]))["points"])
proj = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", doc["forwardCurve"]))["points"]) if doc.get("forwardCurve") else disc
cube = await drishti.get_async("ir-vol-cube", doc.get("swaptionVolCube") or f"IRV-{doc['currency']}")
te = max((date.fromisoformat(t["expiryDate"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365, 1 / 365)
n = q.tenor_years(t["underlyingTenor"])
payer = t.get("payerReceiver", "Payer") == "Payer"
sign = 1 if doc["direction"] in ("Buy", "Long") else -1
N, K = doc["notional"] * sign, t["strike"]

grid = pd.DataFrame(cube["grid"])
exp_t = grid["expiry"].map(q.tenor_years).to_numpy()
cols = [c for c in grid.columns if c.startswith("t")]
ten_t = np.array([q.tenor_years(c[1:]) for c in cols])
by_tenor = [np.interp(te, exp_t, grid[c]) for c in cols]                 # each tenor's vol at our expiry
vol_bp = float(np.interp(n, ten_t, by_tenor))

ann = q.annuity(disc, te + n, 1, start=te)
F = q.par_swap_rate(disc, te + n, 1, start=te, projection=proj)
price = lambda f, v: ann * q.bachelier(f, K, te, v / 1e4, 1.0, payer)   # noqa: E731 - per unit of notional
h = 1e-4
pv = price(F, vol_bp)
delta = (price(F + h, vol_bp) - price(F - h, vol_bp)) / 2                # per +1 bp of the forward rate
gamma = price(F + h, vol_bp) - 2 * pv + price(F - h, vol_bp)
vega = (price(F, vol_bp + 1) - price(F, vol_bp - 1)) / 2                 # per +1 bp of normal vol
show(pd.DataFrame({
    "measure": ["option", "expiry (years)", "swap tenor", "forward swap rate", "strike", "moneyness bp", "annuity", "normal vol bp",
                "premium (position)", "premium bp of notional", "DV01 (per +1 bp)", "gamma (per bp squared)", "vega per bp vol",
                "booked MTM", "booked dv01", "booked vega"],
    "value": [f"{doc['direction']} {'payer' if payer else 'receiver'}", te, n, f"{F:.4%}", f"{K:.4%}", (F - K) * 1e4, ann, vol_bp,
              pv * N, pv * 1e4, delta * N, gamma * N, vega * N, doc["mtm"], (doc.get("risk") or {}).get("dv01"),
              (doc.get("risk") or {}).get("vega")],
}), title=f"{view.id}: {te:.2f}y into {t['underlyingTenor']} on {abs(N):,.0f} {doc['currency']} (Bachelier, {cube['cubeId']})")
moves = np.arange(-100, 101, 25)
chart(pd.DataFrame({"forward move bp": moves, "premium": [price(F + m / 1e4, vol_bp) * N for m in moves]}),
      kind="line", x="forward move bp", y="premium", title="Premium against a move in the forward swap rate")
