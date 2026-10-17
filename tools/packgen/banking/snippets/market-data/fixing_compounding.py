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

# title: Overnight rate compounded in arrears
# description: The overnight fixings compounded in arrears (each rate weighted by the calendar days it applies, ACT/360, as SOFR and ESTR swaps and the published averages do) against their simple average, the annualised compounded rate over the window, the interest on 100m, and the rate and volume drawn.
# kinds: rate-fixing
# example: FIX FIX-ESTR

import pandas as pd

doc = view.doc
fx = pd.DataFrame(doc["FIXINGS"])
fx["DATE"] = pd.to_datetime(fx["DATE"])
fx = fx.sort_values("DATE").reset_index(drop=True)
rate = fx["RATE"] if "RATE" in fx else fx["RATE_PCT"] / 100
# a rate set on a Friday applies over the weekend: its weight is the days until the next fixing (1 for the last one)
fx["days"] = fx["DATE"].diff().shift(-1).dt.days.fillna(1).astype(int)
BASIS = 365 if doc.get("CURRENCY") in ("GBP", "AUD", "CAD") else 360          # SONIA, AONIA, CORRA: ACT/365F
growth = (1 + rate * fx["days"] / BASIS).prod()
days = int(fx["days"].sum())
compounded = (growth - 1) * BASIS / days
simple = (rate * fx["days"]).sum() / days
NOTIONAL = 100e6

show(pd.DataFrame({
    "MEASURE": ["FROM", "TO", "calendar days", "compounded in arrears (annualised)", "simple day-weighted average",
                "compounding effect bp", f"interest on {NOTIONAL / 1e6:,.0f}m", "latest fixing", "change over window bp"],
    "VALUE": [fx["DATE"].iloc[0].date(), fx["DATE"].iloc[-1].date(), days, f"{compounded:.5%}", f"{simple:.5%}",
              f"{(compounded - simple) * 1e4:.3f}", f"{(growth - 1) * NOTIONAL:,.0f}", f"{rate.iloc[-1]:.4%}",
              f"{(rate.iloc[-1] - rate.iloc[0]) * 1e4:+.1f}"],
}), title=f"{view.id}: {doc.get('NAME', '')} ({doc.get('ADMINISTRATOR', '')}), ACT/{BASIS}")
series = pd.DataFrame({"rate %": rate.to_numpy() * 100}, index=fx["DATE"].dt.strftime("%Y-%m-%d"))
chart(series, kind="line", title=f"{doc.get('NAME', view.id)} fixings, %")
if "VOLUME_BN" in fx:
    chart(pd.DataFrame({"volume bn": fx["VOLUME_BN"].to_numpy()}, index=series.index), kind="bar", title="Volume, bn")
