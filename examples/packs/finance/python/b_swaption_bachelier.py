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

# title: Swaption: Bachelier price, implied normal vol and Greeks
# description: The swaption repriced with the Bachelier (normal) model from its own forward swap rate, annuity and normal vol; the normal vol implied by the booked MTM (Brent); delta (DV01), gamma and vega per bp of vol; and the premium across forward-rate and vol moves.
# kinds: trade

import numpy as np
import pandas as pd
from drishti import quant as q

doc = view.doc
u, v = doc.get("underlying") or {}, doc.get("volatility") or {}
if "forwardSwapRate" not in u:
    raise ValueError(f"{view.id} is not a swaption with an underlying swap (TRD SWPT-3301)")
T = max((pd.Timestamp(doc["expiryDate"]) - pd.Timestamp(view.business_date or "2026-09-30")).days / 365, 1 / 365)
F, K, ann = u["forwardSwapRate"], doc["strike"], u["annuity"]                  # the annuity is per the notional, in USD
payer = doc.get("optionType", "Payer") == "Payer"
sign = 1 if doc.get("direction") == "BUY" else -1
vol = v["value"] / 1e4                                                          # bp -> rate units
price = lambda f, s: sign * ann * q.bachelier(f, K, T, s, 1.0, payer)            # noqa: E731

pv = price(F, vol)
implied = q.implied_vol(doc["mtm"], lambda s: price(F, s), lo=1e-5, hi=0.05) if doc["mtm"] * sign > 0 else float("nan")
h = 1e-4
show(pd.DataFrame({
    "measure": ["expiry (years)", "forward swap rate", "strike", "annuity", "normal vol bp", "premium (Bachelier)", "booked MTM",
                "implied normal vol from MTM bp", "DV01 (+1 bp forward)", "gamma (per bp squared)", "vega per bp vol",
                "booked delta", "booked vega"],
    "value": [T, f"{F:.4%}", f"{K:.4%}", ann, v["value"], pv, doc["mtm"], implied * 1e4, (price(F + h, vol) - price(F - h, vol)) / 2,
              price(F + h, vol) - 2 * pv + price(F - h, vol), (price(F, vol + h) - price(F, vol - h)) / 2,
              (doc.get("risk") or {}).get("delta"), (doc.get("risk") or {}).get("vega")],
}), title=f"{view.id}: {doc.get('direction')} {doc.get('optionType')} {v.get('point', '')}, {doc['notional']:,.0f} {doc['currency']}")
moves = np.arange(-50, 51, 10)
grid = pd.DataFrame({f"vol {dv:+d} bp": [price(F + m / 1e4, vol + dv / 1e4) for m in moves] for dv in (-20, 0, 20)},
                    index=pd.Index(moves, name="forward move bp"))
show(grid, title="Premium across forward and vol moves")
chart(grid, kind="line", title="Premium against the forward swap rate")
