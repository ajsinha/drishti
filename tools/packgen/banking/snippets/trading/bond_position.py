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

# title: Bond position: yield risk and scenarios
# description: The bond position's yield from its booked PRICE (clean, per 100), the coupon from its first interest flow's RATE and the coupon frequency from the flows' spacing, its modified duration and convexity, DV01 of the face held (the leg's NOTIONAL, long or short by BUY_SELL_CODE) against the risk engine's DV01 (TVAL-<TRADE_ID>), and the P&L of the position for yield moves of -100 to +100 bp by full repricing.
# kinds: trade
# example: TRD CLY-3000157

from datetime import date
import pandas as pd
from drishti import quant as q

doc = view.doc
leg = (doc.get("LEGS") or [{}])[0]
rates = [float(c["RATE"]) for c in leg.get("CASH_FLOWS") or [] if c.get("RATE") not in (None, "")]
if doc.get("PRICE") in (None, "") or not rates:
    raise ValueError(f"{view.id} ({doc.get('PRODUCT_TYPE')}) is not a bond position with a coupon and a clean PRICE")
flows = leg["CASH_FLOWS"]
gap = (date.fromisoformat(flows[0]["END_DATE"]) - date.fromisoformat(flows[0]["START_DATE"])).days if len(flows) else 182
FREQ = min((1, 2, 4, 12), key=lambda f: abs(365 / f - gap))
years = (date.fromisoformat(doc["MATURITY_DATE"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365.25
face = float(leg.get("NOTIONAL") or doc.get("QUANTITY") or 0)
sign = 1 if doc.get("BUY_SELL_CODE") == "B" else -1
c, clean = rates[0], float(doc["PRICE"])

y = q.bond_yield(clean, c, years, FREQ)
r = q.bond_risk(y, c, years, FREQ)
dv01 = r["dv01"] * face / 100 * sign                   # value of the position for -1 bp
try:
    booked = (await drishti.get_async("trade-valuation", f"TVAL-{doc['TRADE_ID']}"))["DV01"]
except Exception:                                       # market-risk not installed
    booked = None
show(pd.DataFrame({
    "measure": ["face held", "coupon", "coupons a year", "years to maturity", "clean price", "yield", "modified duration", "convexity",
                "DV01 of the position", "risk engine DV01", "market value (dirty)"],
    "value": [face * sign, f"{c:.3%}", FREQ, years, clean, f"{y:.4%}", r["modified"], r["convexity"], dv01, booked,
              r["dirty"] * face / 100 * sign],
}), title=f"{view.id}: {'long' if sign > 0 else 'short'} {doc.get('PRODUCT_SUB_TYPE', '')} {c:.3%} {doc['MATURITY_DATE']} ({doc['CURRENCY']})")

rows = []
for bp in [-100, -50, -25, -10, 0, 10, 25, 50, 100]:
    p = q.bond_price(y + bp / 1e4, c, years, FREQ)
    rows.append({"yield move bp": bp, "clean price": p, "P&L": (p - clean) * face / 100 * sign, "DV01 estimate": -bp * dv01})
pnl = pd.DataFrame(rows)
show(pnl, title="Position P&L for yield moves (full repricing)")
chart(pnl, kind="line", x="yield move bp", y=["P&L", "DV01 estimate"], title="P&L against the linear DV01 estimate")
