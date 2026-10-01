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

# title: CDS par spreads, risky annuity and upfront
# description: Standard CDS of 1Y to 10Y priced on the curve's hazards (term hazards turned into piecewise-flat forward hazards), discounted on the currency's OIS curve: protection and premium legs (quarterly, accrual on default as half a period), RPV01, par spread against the quote, the upfront for a 100 bp (or 500 bp) coupon, and CS01 per 10m from a 1 bp spread bump (hazards moved by the credit triangle).
# kinds: credit-curve
# example: CDS CDS-GRANITE

import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
R = doc.get("recovery", 0.4)
pts = pd.DataFrame(doc["points"])
t_knots = pts["tenor"].map(q.tenor_years).to_numpy()
lam = q.forward_hazards(t_knots, pts["hazard"].to_numpy())       # the curve's hazards are flat to each tenor
disc = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", f"CRV-{doc.get('currency', 'USD')}-OIS"))["points"])
COUPON = 0.05 if doc.get("spread5y", 0) > 300 else 0.01      # the standard running coupon: 500 bp high yield, 100 bp otherwise
NOTIONAL = 10e6


def legs(T, hazards):
    pay = np.arange(0.25, T + 1e-9, 0.25)                          # quarterly premium dates
    s = q.survival(pay, t_knots, hazards)
    s_prev = np.concatenate([[1.0], s[:-1]])
    rpv01 = np.sum(0.25 * disc.df(pay) * (s + s_prev) / 2)        # premium paid while alive, half a period if default inside
    fine = np.linspace(0, T, int(T * 24) + 1)                      # protection: default in each half-month, paid at its middle
    sf = q.survival(fine, t_knots, hazards)
    prot = (1 - R) * np.sum(disc.df((fine[1:] + fine[:-1]) / 2) * (sf[:-1] - sf[1:]))
    return prot, rpv01


rows = []
for tenor, quoted in zip(pts["tenor"], pts["spread"]):
    T = q.tenor_years(tenor)
    prot, rpv01 = legs(T, lam)
    par = prot / rpv01
    prot_up, rpv01_up = legs(T, lam + 1e-4 / (1 - R))            # +1 bp of spread, as hazard
    upfront = prot - COUPON * rpv01                                 # paid by the protection buyer, fraction of notional
    cs01 = ((prot_up - COUPON * rpv01_up) - upfront) * NOTIONAL     # protection buyer's gain for +1 bp
    rows.append({"tenor": tenor, "quoted bp": quoted, "par spread bp": par * 1e4, "difference bp": par * 1e4 - quoted,
                 "RPV01": rpv01, f"upfront % ({COUPON * 1e4:.0f} bp coupon)": upfront * 100,
                 "CS01 per 10m (buyer)": cs01})
out = pd.DataFrame(rows).set_index("tenor")
show(out, title=f"{view.id}: {doc.get('issuerName', '')} CDS, recovery {R:.0%}, discounted on CRV-{doc.get('currency')}-OIS")
chart(out.reset_index(), kind="line", x="tenor", y=["quoted bp", "par spread bp"], title="Quoted against recomputed par spreads")
print("Par spreads sit close to the quotes: the curve's hazards follow the credit triangle, which ignores discounting "
      "and premium accrual, so the differences are those simplifications.")
