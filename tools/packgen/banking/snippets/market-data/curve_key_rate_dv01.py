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

# title: Key-rate DV01 by bump and reprice
# description: A 10Y receive-fixed swap at par (100m) repriced with each pillar of the curve moved 1 bp in turn (triangular key-rate bumps): DV01 by pillar, their sum against a parallel 1 bp move, and the convexity of a 25 bp move.
# kinds: ir-curve
# example: CRV CRV-USD-OIS

import pandas as pd
from drishti import quant as q

TENOR, NOTIONAL, FREQ = 10.0, 100e6, 1           # change these: a 5Y semi-annual, a 30Y, ...
base = q.ZeroCurve.from_points(view.doc["points"])
par = q.par_swap_rate(base, TENOR, FREQ)


def pv(curve):                                   # the receiver's PV of the swap struck at today's par rate
    return q.swap_pv(curve, par, NOTIONAL, TENOR, FREQ, receive_fixed=True)


pv0 = pv(base)                                   # zero by construction
labels = sorted((p["tenor"] for p in view.doc["points"] if q.tenor_years(p["tenor"]) > 0), key=q.tenor_years)
rows = []
for i, (label, t) in enumerate(zip(labels, base.times)):
    up, down = pv(base.key_rate_bumped(i, +1)), pv(base.key_rate_bumped(i, -1))
    rows.append({"pillar": label, "years": t, "DV01": (up - down) / 2})   # central difference, per +1 bp at the pillar
krd = pd.DataFrame(rows)
krd = krd[krd["DV01"].abs() > 0.5].reset_index(drop=True)
parallel = (pv(base.shifted(+1)) - pv(base.shifted(-1))) / 2
krd["share"] = krd["DV01"] / krd["DV01"].sum()
show(krd, title=f"Receive fixed {TENOR:g}Y at par {par:.4%} on {NOTIONAL / 1e6:,.0f}m: key-rate DV01 ({view.id})")
chart(krd, kind="bar", x="pillar", y="DV01", title="Key-rate DV01 (per +1 bp)")

up25, down25 = pv(base.shifted(+25)), pv(base.shifted(-25))
show(pd.DataFrame({
    "measure": ["sum of key rates", "parallel +1 bp", "difference", "P&L +25 bp", "P&L -25 bp", "convexity (+25 and -25)"],
    "value": [krd["DV01"].sum(), parallel, krd["DV01"].sum() - parallel, up25 - pv0, down25 - pv0, up25 + down25 - 2 * pv0],
}), title="Key rates against a parallel move")
