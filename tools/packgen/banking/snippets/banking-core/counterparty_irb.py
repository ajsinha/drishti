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

# title: Counterparty capital: Basel IRB risk weight from PD
# description: The counterparty's exposure at default from its netting sets' SA-CCR results, its one-year PD from KYC (floored at 0.03%), LGD 45% (foundation IRB, senior unsecured) and an effective maturity of 2.5 years: the Basel IRB corporate formula for capital (asset correlation, maturity adjustment, 99.9% conditional PD), risk-weighted assets, expected loss, and how capital moves with PD. The corporate curve for every counterparty, a simplification (banks and sovereigns have their own treatment).
# kinds: counterparty
# example: CPTY CP-HALCYON

import math
import pandas as pd
from drishti import quant as q

doc = view.doc
PD = max((doc.get("kyc") or {}).get("pd1y", 0.0), 0.0003)
LGD, M = 0.45, 2.5
mine = [n["id"] for n in doc.get("nettingSets") or []]
ead = 0.0
for ns_id in mine:                                                    # each netting set names its SA-CCR result
    ns = await drishti.get_async("netting-set", ns_id)
    if ns.get("saccr"):
        ead += (await drishti.get_async("sa-ccr", ns["saccr"]))["ead"]


def irb(pd_):
    """Basel II/III IRB capital per unit of EAD for corporates (BCBS, paragraph 272)."""
    w = (1 - math.exp(-50 * pd_)) / (1 - math.exp(-50))
    r = 0.12 * w + 0.24 * (1 - w)                                     # asset correlation
    b = (0.11852 - 0.05478 * math.log(pd_)) ** 2                      # maturity adjustment slope
    cond = q.norm_cdf((q.norm_ppf(pd_) + math.sqrt(r) * q.norm_ppf(0.999)) / math.sqrt(1 - r))
    return LGD * (cond - pd_) * (1 + (M - 2.5) * b) / (1 - 1.5 * b), r


k, r = irb(PD)
show(pd.DataFrame({
    "measure": ["netting sets", "EAD (SA-CCR)", "PD 1Y", "LGD", "maturity", "asset correlation", "capital K per unit EAD",
                "risk weight", "RWA", "capital (8% of RWA = K x EAD)", "expected loss (PD x LGD x EAD)"],
    "value": [len(mine), ead, f"{PD:.2%}", f"{LGD:.0%}", M, r, k, f"{k * 12.5:.0%}", k * 12.5 * ead, k * ead, PD * LGD * ead],
}), title=f"{view.id}: {doc['name']} ({doc.get('rating')}, {doc.get('sector')})")
curve = pd.DataFrame({"PD %": [0.03, 0.1, 0.25, 0.5, 1, 2, 5, 10, 20]})
curve["risk weight %"] = [irb(p / 100)[0] * 12.5 * 100 for p in curve["PD %"]]
chart(curve, kind="line", x="PD %", y="risk weight %", title="IRB corporate risk weight against PD (LGD 45%, M 2.5)")
