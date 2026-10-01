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

# title: Cross rates and a triangular arbitrage check
# description: Every FX spot read once, each pair re-derived through USD (EUR/JPY from EUR/USD and USD/JPY, ...) and compared with its own mid in pips, and a check on bids and asks: buying through the two legs and selling the direct pair (and the reverse) must not make money.
# kinds: fx-spot
# example: FX FX-EURJPY

import pandas as pd

found = await drishti.search_async("FX where mid > 0 limit 100")
quotes = {}
for pid in found["id"]:
    d = await drishti.get_async("fx-spot", pid)
    c = d.get("conventions") or {}
    quotes[(c["base"], c["quote"])] = {"bid": d["bid"], "ask": d["ask"], "mid": d["mid"], "pip": c.get("pipFactor", 10000)}


def vs_usd(ccy, side):
    """Units of USD per one unit of ccy, at the bid or ask of the pair that quotes it."""
    if ccy == "USD":
        return 1.0
    if (ccy, "USD") in quotes:                              # EUR/USD: USD per EUR
        return quotes[(ccy, "USD")][side]
    if ("USD", ccy) in quotes:                              # USD/JPY: JPY per USD, so invert (and swap the sides)
        return 1.0 / quotes[("USD", ccy)]["ask" if side == "bid" else "bid"]
    return None


rows = []
for (b, qc), x in quotes.items():
    if "USD" in (b, qc):
        continue                                            # a cross: neither side is USD
    mid_b, mid_q = vs_usd(b, "mid"), vs_usd(qc, "mid")
    implied = mid_b / mid_q
    via_bid = vs_usd(b, "bid") / vs_usd(qc, "ask")           # sell b for USD at the bid, buy qc with USD at the ask
    via_ask = vs_usd(b, "ask") / vs_usd(qc, "bid")
    rows.append({"pair": f"{b}/{qc}", "quoted mid": x["mid"], "via USD mid": implied,
                 "difference pips": (x["mid"] - implied) * x["pip"],
                 "direct bid": x["bid"], "direct ask": x["ask"], "synthetic bid": via_bid, "synthetic ask": via_ask,
                 "arbitrage": "buy direct, sell synthetic" if x["ask"] < via_bid else
                              "buy synthetic, sell direct" if via_ask < x["bid"] else "none"})
out = pd.DataFrame(rows).set_index("pair")
show(out, title=f"Crosses re-derived through USD ({len(quotes)} spot rates)")
chart(out.reset_index(), kind="bar", x="pair", y="difference pips", title="Quoted cross mid less the mid via USD, pips")
print(f"{(out['arbitrage'] != 'none').sum()} of {len(out)} crosses offer a riskless round trip at these bids and asks"
      " (sample quotes are not taken at the same instant)")
