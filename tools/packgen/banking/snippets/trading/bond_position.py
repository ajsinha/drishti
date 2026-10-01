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

# title: Bond position: yield risk, spread risk and scenarios
# description: The bond position's yield from its booked clean price, its modified duration and convexity, DV01 of the face held (against the booked DV01), CS01 as the Z-spread equivalent of DV01 for a credit bond, and the P&L of the position for yield moves of -100 to +100 bp by full repricing; long or short as booked.
# kinds: trade
# example: TRD BBG-60000011

from datetime import date
import pandas as pd
from drishti import quant as q

doc, t = view.doc, view.doc.get("terms") or {}
if "coupon" not in t or "cleanPrice" not in t:
    raise ValueError(f"{view.id} ({doc.get('productType')}) is not a bond position with a coupon and a clean price")
FREQ = {"Annual": 1, "Semi-annual": 2, "Quarterly": 4, "Monthly": 12}.get(t.get("couponFrequency"), 2)
years = (date.fromisoformat(doc["maturityDate"]) - date.fromisoformat(view.business_date or "2026-09-30")).days / 365.25
face = t.get("faceAmount") or doc["notional"]
sign = 1 if doc["direction"] in ("Long", "Buy") else -1
c, clean = t["coupon"], t["cleanPrice"]

y = q.bond_yield(clean, c, years, FREQ)
r = q.bond_risk(y, c, years, FREQ)
dv01 = r["dv01"] * face / 100 * sign                   # value of the position for -1 bp
show(pd.DataFrame({
    "measure": ["face held", "years to maturity", "clean price", "yield (recomputed)", "yield (booked)", "modified duration",
                "convexity", "DV01 of the position", "booked risk.dv01", "CS01 (Z-spread, = DV01 for a bullet)", "booked risk.cs01",
                "market value (dirty)"],
    "value": [face * sign, years, clean, f"{y:.4%}", f"{t.get('yield', float('nan')):.4%}", r["modified"], r["convexity"], dv01,
              (doc.get("risk") or {}).get("dv01"), dv01 if "zSpread" in t else None, (doc.get("risk") or {}).get("cs01"),
              r["dirty"] * face / 100 * sign],
}), title=f"{view.id}: {doc['direction']} {doc.get('productName', '')} {c:.3%} {doc['maturityDate']} ({doc['currency']})")

rows = []
for bp in [-100, -50, -25, -10, 0, 10, 25, 50, 100]:
    p = q.bond_price(y + bp / 1e4, c, years, FREQ)
    rows.append({"yield move bp": bp, "clean price": p, "P&L": (p - clean) * face / 100 * sign,
                 "DV01 estimate": -bp * dv01})
pnl = pd.DataFrame(rows)
show(pnl, title="Position P&L for yield moves (full repricing)")
chart(pnl, kind="line", x="yield move bp", y=["P&L", "DV01 estimate"], title="P&L against the linear DV01 estimate")
