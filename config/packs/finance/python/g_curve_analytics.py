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

# title: Curve analytics: rates, futures or FX forwards
# description: Whatever the curve holds: for a rates curve, zero rates, discount factors, 3M forwards and par swap rates with their PV01 per 10m (log-linear on discount factors); for a futures strip, contango or backwardation and the annualised roll yield month by month; for an FX forward curve, outrights and the yield differential the points imply (ln(F/S)/T).
# kinds: curve

import math
import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
if doc.get("pillars"):                                                    # a rates curve
    c = q.ZeroCurve.from_points(doc["pillars"])
    tenors = [t for t in (1, 2, 3, 5, 7, 10, 15, 20, 30) if t <= c.times[-1]]
    out = pd.DataFrame({"years": tenors, "zero %": c.zero(tenors) * 100, "df": c.df(tenors),
                        "3M fwd %": [c.forward(t - 0.25, t) * 100 for t in tenors],
                        "par swap %": [q.par_swap_rate(c, t, 1) * 100 for t in tenors],
                        "PV01 per 10m": [q.annuity(c, t, 1) * 10e6 / 1e4 for t in tenors]})
    show(out, title=f"{view.id}: {doc.get('purpose', '')} ({doc.get('interpolation', '')})")
    chart(out, kind="line", x="years", y=["zero %", "par swap %", "3M fwd %"], title="Zero, par and forward rates")
elif doc.get("contracts"):                                                # a futures strip, monthly contracts
    f = pd.DataFrame(doc["contracts"])
    f["spread to next"] = f["settle"] - f["settle"].shift(-1)
    f["roll yield %/yr"] = np.log(f["settle"] / f["settle"].shift(-1)) * 12 * 100
    show(f, title=f"{view.id}: {doc.get('commodity', '')}, {doc.get('shape', '')} ({doc.get('unit', '')})")
    print(f"Front to 12th contract: {f['settle'].iloc[0] - f['settle'].iloc[-1]:+.2f}; "
          f"average roll yield {f['roll yield %/yr'].mean():+.2f}% a year for a long")
    chart(f, kind="bar", x="code", y="openInterest", title="Open interest by contract")
elif doc.get("outrights"):                                                # an FX forward curve
    S, asof = doc["spot"], pd.Timestamp(view.business_date or "2026-09-30")
    o = pd.DataFrame(doc["outrights"])
    o["years"] = (pd.to_datetime(o["valueDate"]) - asof).dt.days / 365
    o["implied r_quote - r_base %"] = [math.log(F / S) / t * 100 if t > 0 else None for F, t in zip(o["outright"], o["years"])]
    show(o, title=f"{view.id}: {doc.get('pair', '')} spot {S}, points in 1/{doc.get('pipFactor', 10000):,}")
    chart(o, kind="line", x="tenor", y="points", title="Forward points by tenor")
else:
    print(f"{view.id}: not a curve shape this snippet knows (pillars, contracts or outrights)")
