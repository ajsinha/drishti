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

# title: Carry and roll-down of receivers
# description: Three months' carry and roll-down of receiving par swaps along the curve, in bp and per 100m, and per unit of DV01: carry is the fixed rate against the 3M forward it is funded at, roll-down the fall in par rate as the swap ages on an unchanged curve. A simplified, curve-only view (no fixing, no bid-offer).
# kinds: ir-curve
# example: CRV CRV-USD-OIS

import pandas as pd
from drishti import quant as q

curve = q.ZeroCurve.from_points(view.doc["points"])
H = 0.25                                   # the horizon: three months
NOTIONAL = 100e6

fund = curve.forward(0.0, H)               # the floating rate the receiver pays for the first 3 months (simple)
rows = []
for T in [2, 3, 5, 7, 10, 15, 20, 30]:
    if T > curve.times[-1]:
        break
    par_now = q.par_swap_rate(curve, T, 1)
    par_rolled = q.par_swap_rate(curve, T - H, 1)        # the same swap in 3 months, priced off today's curve
    dv01 = q.annuity(curve, T - H, 1) * NOTIONAL / 1e4    # what 1 bp on the remaining swap is worth
    carry = (par_now - fund) * H * NOTIONAL               # fixed accrued less float paid over the horizon
    roll = (par_now - par_rolled) * 1e4 * dv01            # the swap rolls down to a lower par rate: a gain
    rows.append({"tenor": f"{T}Y", "par %": par_now * 100, "par in 3M %": par_rolled * 100,
                 "carry per 100m": carry, "roll per 100m": roll, "DV01 per 100m": dv01,
                 "carry bp": carry / dv01, "roll-down bp": roll / dv01,
                 "breakeven bp": (carry + roll) / dv01})   # how far par may rise in 3M before the receiver loses
out = pd.DataFrame(rows).set_index("tenor")
show(out, title=f"{view.id}: 3M carry and roll-down of receiving par swaps (funded at the 3M forward {fund:.3%})")
chart(out.reset_index(), kind="bar", x="tenor", y=["carry bp", "roll-down bp"], title="3M carry and roll-down, bp of DV01")
best = out["breakeven bp"].idxmax()
print(f"Most carry and roll per unit of risk: receive {best}, {out.loc[best, 'breakeven bp']:.1f} bp of breakeven over 3 months")
