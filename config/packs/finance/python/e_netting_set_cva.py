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

# title: Netting set: EPE, EEPE, CVA and collateral
# description: The netting set's exposure profile: EPE and effective EPE over the first year, peak PFE; CVA approximated as (1 - R) x sum of EE x discount factor x marginal PD with a flat hazard from the counterparty's one-year PD (recovery 40%, USD SOFR discounting; a simplification); and the CSA's terms turned into today's collateral position against the net MTM.
# kinds: netting-set

import math
import numpy as np
import pandas as pd
from drishti import quant as q

ns = view.doc
b = pd.DataFrame(ns["exposure"]).assign(years=lambda d: d["tenor"].map(q.tenor_years)).sort_values("years")
b["effective EE"] = np.maximum.accumulate(b["ee"])
t1 = np.clip(b["years"].to_numpy(), 0, 1.0)
dt = np.diff(np.append(t1, 1.0))
epe, eepe = float(np.sum(b["ee"] * dt)), float(np.sum(b["effective EE"] * dt))

cp = await drishti.get_async("counterparty", ns["counterparty"]["id"])
pd1 = (cp.get("ratings") or {}).get("pd1y") or (cp.get("kyc") or {}).get("pd1y") or 0.01
lam = -math.log(1 - pd1)
sofr = q.ZeroCurve.from_points((await drishti.get_async("curve", "USD-SOFR"))["pillars"])
later = b[b["years"] > 0]
cva = q.cva_from_profile(later["years"].to_numpy(), later["ee"].to_numpy(), [30.0], [lam], 0.4, discount=sofr)
show(pd.DataFrame({
    "measure": ["EPE (1Y)", "EPE (booked)", "effective EPE (1Y)", "EEPE (booked)", "peak PFE 95%", "PD 1Y", "CVA (approximation)",
                "CVA (booked)", "EAD booked (SA-CCR)"],
    "value": [epe, ns["metrics"].get("epe"), eepe, ns["metrics"].get("eepe"), b["pfe95"].max(), f"{pd1:.2%}", -cva["cva"],
              ns["metrics"].get("cva"), ns["metrics"].get("ead")],
}), title=f"{view.id}: {ns['counterparty']['name']}, {ns['trades']} trades")
chart(b.set_index("tenor")[["ee", "effective EE", "pfe95"]], kind="line", title="Exposure profile")

terms = ns.get("csaTerms") or {}
mta = drishti.number(terms.get("minTransfer", "0")) or 0
posted = drishti.number((ns.get("collateral") or {}).get("posted", "0")) or 0   # as the netting set reports it
need = max(ns["netMtm"] - (terms.get("threshold") or 0), 0) - max(-ns["netMtm"] - (terms.get("threshold") or 0), 0)
print(f"Net MTM {ns['netMtm']:,.0f}; collateral {'we should hold' if need > 0 else 'we should post'} {abs(need):,.0f}; "
      f"the netting set shows {posted:,.0f} posted; MTA {mta:,.0f}, MPoR {terms.get('mpor', '')}")
