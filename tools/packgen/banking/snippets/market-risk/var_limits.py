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

# title: VaR, ES and stressed VaR against limits, every desk
# description: Every desk's VaR result in one table (drishti.search_async): VaR 99%, ES 97.5%, stressed VaR and the limit; limit use, ES over VaR and stressed over current VaR as tail and regime indicators, and a simplified Basel 2.5-style capital figure (3 x (VaR + sVaR) x the square root of 10, using today's figures for the 60-day averages).
# kinds: var
# example: VAR VAR-CREDIT

import numpy as np
import pandas as pd

# a search returns the fields its condition names: "> -1000bn" names a field without filtering anything out
df = await drishti.search_async("VAR where var99 > 0 and desk != '' and es975 > -1000bn and svar > -1000bn and limit > 0 limit 100")
df = df.set_index("desk")[["var99", "es975", "svar", "limit"]].astype(float)
df["limit use"] = df["var99"] / df["limit"]
df["ES / VaR"] = df["es975"] / df["var99"]                 # about 1.0 for a normal tail; higher: a fatter one
df["sVaR / VaR"] = df["svar"] / df["var99"]                # how far today is from the stressed period
df["capital (simplified)"] = 3 * (df["var99"] + df["svar"]) * np.sqrt(10)
df["status"] = np.where(df["limit use"] >= 1, "breach", np.where(df["limit use"] >= 0.8, "early warning", "ok"))
df = df.sort_values("limit use", ascending=False)
show(df, title=f"VaR against limits: {len(df)} desks")
chart(df.reset_index(), kind="bar", x="desk", y="limit use", title="VaR 99% as a share of the limit")
me = view.doc.get("desk")
if me in df.index:
    r = df.loc[me]
    print(f"{me}: VaR {r['var99']:,.0f} uses {r['limit use']:.0%} of its limit ({r['status']}); ES/VaR {r['ES / VaR']:.2f}, "
          f"sVaR/VaR {r['sVaR / VaR']:.2f}; rank {list(df.index).index(me) + 1} of {len(df)} by limit use")
