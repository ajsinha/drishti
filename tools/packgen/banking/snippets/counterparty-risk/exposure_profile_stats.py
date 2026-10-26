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

# title: Exposure profile: EPE, effective EE and EEPE, checked
# description: From the exposure engine's profile of a scope (exposure-result, computed from the paths): expected exposure (EE), negative exposure and PFE at 95% and 99% by primary date; EPE (the time-weighted average of EE over the first year, or the scope's longest maturity when shorter), effective EE (EE never allowed to fall, the Basel rule) and effective EPE recomputed step by step and set beside the engine's own figures, the peak PFE and its date, and the profile drawn.
# kinds: exposure-result
# example: EXP NSET:NS-ALDERSHOT-FRA

import numpy as np
import pandas as pd

doc = view.doc
if doc.get("STATUS") != "ok":
    raise ValueError(doc.get("NOTE") or "no exposure profile for this scope")
b = pd.DataFrame(doc["PROFILE"]).sort_values("T").reset_index(drop=True)
b["effective EE"] = np.maximum.accumulate(np.maximum(b["EE"], doc.get("CE") or 0))   # non-decreasing from today's exposure


def time_average(col, horizon):
    """Average of a step-wise profile over [0, horizon]: each date's value held back to the date before (the engine's rule)."""
    t = np.minimum(b["T"].to_numpy(), horizon)
    dt = np.diff(np.concatenate([[0.0], t]))
    return float(np.sum(b[col].to_numpy() * dt) / horizon) if horizon > 0 else 0.0


T = doc.get("HORIZON_YEARS") or 1.0
epe, eepe = time_average("EE", T), time_average("effective EE", T)
peak = b.loc[b["PFE_99"].idxmax()]
show(pd.DataFrame({
    "MEASURE": ["EPE (recomputed)", "EPE (engine)", "effective EPE (recomputed)", "EEPE (engine)", "peak EE", "peak PFE (engine)",
                "peak PFE date", "peak PFE 99%", "PFE 99 / PFE 95 at that date", "EAD (IMM, informational)"],
    "VALUE": [epe, doc.get("EPE"), eepe, doc.get("EEPE"), b["EE"].max(), doc.get("PEAK_PFE"), doc.get("PEAK_PFE_DATE"), peak["PFE_99"],
              peak["PFE_99"] / peak["PFE_95"] if peak["PFE_95"] else None, doc.get("EAD_IMM")],
}), title=f"{doc.get('CANONICAL')}: run {doc.get('RUN_ID')}, {doc.get('PATHS')} paths, horizon {T:.2f} years, {doc.get('COLLATERAL_MODEL')} collateral")
show(b, title="Profile by primary date")
chart(b.set_index("TENOR")[["EE", "effective EE", "PFE_95", "PFE_99", "ENE"]], kind="line", title="Exposure profile")
print(f"Regulatory EAD under the internal model method would be alpha x EEPE = 1.4 x {eepe:,.0f} = {1.4 * eepe:,.0f}")
