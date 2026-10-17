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

# title: CVA from the exposure profile and the counterparty's spread
# description: Unilateral CVA approximated as (1 - R) x sum of EE x discount factor x marginal default probability over the profile's periods: default probabilities from the counterparty's CDS curve when there is one (term hazards as forward hazards) or else from its one-year PD as a flat hazard; discounting on the USD OIS curve; CVA by period, its CS01 for a 1 bp spread bump, beside the engine's CVA. EE at each period's end and no wrong-way risk: a simplification.
# kinds: netting-set, exposure-profile, cva
# example: NSET NS-HALCYON-NY

import numpy as np
import pandas as pd
from drishti import quant as q

ns_id = view.id if view.kind == "netting-set" else view.doc["NETTING_SET"]
ns = view.doc if view.kind == "netting-set" else await drishti.get_async("netting-set", ns_id)
prof = await drishti.get_async("exposure-profile", ns["EXPOSURE_PROFILE"])
cp = await drishti.get_async("counterparty", ns["COUNTERPARTY"])
b = pd.DataFrame(prof["BUCKETS"]).assign(years=lambda d: d["TENOR"].map(q.tenor_years))
b = b[b["years"] > 0].sort_values("years")
try:                                                    # CP-HALCYON -> CDS-HALCYON, where the market quotes one
    cds = await drishti.get_async("credit-curve", "CDS-" + ns["COUNTERPARTY"].removeprefix("CP-"))
    knots = np.array([q.tenor_years(p["TENOR"]) for p in cds["POINTS"]])
    lam, R, source = q.forward_hazards(knots, [p["HAZARD"] for p in cds["POINTS"]]), cds.get("RECOVERY", 0.4), cds["CURVE_ID"]
except drishti.DrishtiError:
    pd1 = cp["KYC"]["PD_1Y"]
    knots, lam, R, source = np.array([30.0]), np.array([-np.log(1 - pd1)]), 0.4, f"flat hazard from PD 1Y {pd1:.2%}"
ois = q.ZeroCurve.from_points((await drishti.get_async("ir-curve", "CRV-USD-OIS"))["POINTS"])

out = q.cva_from_profile(b["years"].to_numpy(), b["EE"].to_numpy(), knots, lam, R, discount=ois)
up = q.cva_from_profile(b["years"].to_numpy(), b["EE"].to_numpy(), knots, lam + 1e-4 / (1 - R), R, discount=ois)
table = pd.DataFrame({"TENOR": b["TENOR"], "EE": b["EE"], "SURVIVAL": out["SURVIVAL"], "PD in period": out["pd"],
                      "DF": out["DF"], "CVA contribution": out["contrib"]})
show(table, title=f"{ns_id}: CVA by period ({cp['NAME']}, {cp.get('RATING', '')}; {source}, recovery {R:.0%})")
engine = (await drishti.get_async("cva", ns["CVA_RESULT"])) if ns.get("CVA_RESULT") else {}
show(pd.DataFrame({"MEASURE": ["CVA (approximation)", "CVA (engine)", "CS01 +1 bp (approximation)", "CS01 (engine)"],
                   "VALUE": [-out["CVA"], engine.get("CVA"), -(up["CVA"] - out["CVA"]), engine.get("CS01")]}),
     title="CVA as a charge (negative), against the engine")
chart(table, kind="bar", x="TENOR", y="CVA contribution", title="Where the CVA comes from")
