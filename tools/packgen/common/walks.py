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

"""The live-demo walk each sample carries in `_meta.walk`, one builder per kind.

The demo plugin (plugins/drishti-plugin-demo, DemoWalk) reads this spec on every tick. A plain `{"field": step}` walks one
number; a coherent entry moves a GROUP with one random step (`with`, `scale`, `shape`, `jitter`, `mode: rel`), `derive`
recomputes what depends on the moved numbers, and `append` keeps a time series current. See docs/connectors/DEMO_CONNECTOR.md.

Each builder reads the finished sample, so the spec names exactly the paths that sample has. What is left static on purpose
(a coupon schedule, a closed statement, a day's large payments) is listed in STATIC_ON_PURPOSE and checked by the pack test.
"""

from __future__ import annotations

import math


def sig1(x: float) -> float:
    """One significant figure: a step size that reads like a tick (0.0004, 0.02, 4)."""
    return float(f"{x:.1g}")


def _derive(set_: str, expr: str, places: int | None = None) -> dict:
    d = {"set": set_, "expr": expr}
    if places is not None:
        d["places"] = places
    return d


def _has(d: dict, key: str) -> bool:
    return bool(d.get(key))


# ---- market data -----------------------------------------------------------------------------------------------
def ir_curve(d: dict) -> dict:
    """One shift for the whole curve (zero rates in %, quotes as decimals, forwards in %), a small tilt and per-tenor jitter;
    discount factors, the 10Y headline and the 2s10s slope are recomputed from the moved zero rates."""
    tenors = {p["tenor"] for p in d["points"]}
    with_ = ["points.*.zeroRate"]
    if d["points"] and "quote" in d["points"][0]:
        with_.append({"path": "points.*.quote", "scale": 1})
    if _has(d, "forwards"):
        with_.append("forwards.*.rate")
    derive = [_derive("points.*.df", "exp(-zeroRate / 100 * tenor(tenor))", 6)]
    if "10Y" in tenors:
        derive.append(_derive("tenY", "points.tenor=10Y.zeroRate / 100", 6))
        if "2Y" in tenors:
            derive.append(_derive("slope2s10s", "(points.tenor=10Y.zeroRate - points.tenor=2Y.zeroRate) * 100", 1))
    return {"tenY": {"step": 0.0003, "scale": 100, "shape": "twist", "jitter": 0.15, "with": with_}, "derive": derive}


def fx_spot(d: dict) -> dict:
    mid = d["mid"]
    places = 3 if mid >= 20 else 5          # the smallest step worth drawing
    return {"mid": {"step": max(sig1(mid * 0.0006), 10 ** -places), "places": 5, "with": [{"path": "bid", "places": 5}, {"path": "ask", "places": 5}]},
            "derive": [_derive("change1d", "mid / history.-2.mid - 1", 5), _derive("history.-1.mid", "$.mid", 5)]}


def _day_bar(price: str) -> list[dict]:
    """Today's bar follows the price: close is the price, high and low stretch, volume builds."""
    return [_derive("ohlc.-1.close", f"$.{price}", 2), _derive("ohlc.-1.high", f"max(high, $.{price})", 2),
            _derive("ohlc.-1.low", f"min(low, $.{price})", 2), _derive("ohlc.-1.volume", "volume + abs(noise(volume * 0.0004))")]


def equity(d: dict) -> dict:
    px = d["price"]
    shares_fx = d["marketCap"] / px if px else 0           # shares outstanding x the currency's rate to USD
    return {"price": {"step": max(sig1(px * 0.0008), 0.01), "places": 2, "min": 0.01},
            "derive": [_derive("change1d", "price / history.-2.close - 1", 5), _derive("history.-1.close", "$.price", 2),
                       _derive("marketCap", f"$.price * {shares_fx!r}")] + _day_bar("price")}


def equity_index(d: dict) -> dict:
    lvl = d["level"]
    return {"level": {"step": max(sig1(lvl * 0.00015), 0.01), "places": 2, "min": 1},
            "derive": [_derive("change1d", "level / history.-2.close - 1", 5), _derive("history.-1.close", "$.level", 2)]}


def commodity(d: dict) -> dict:
    return {"front": {"step": max(sig1(d["front"] * 0.0007), 0.005), "places": 3, "min": 0.01},
            "derive": [_derive("ohlc.-1.close", "$.front", 2), _derive("ohlc.-1.high", "max(high, $.front)", 2),
                       _derive("ohlc.-1.low", "min(low, $.front)", 2), _derive("ohlc.-1.volume", "volume + abs(noise(volume * 0.0004))")]}


def commodity_curve(d: dict) -> dict:
    return {"front": {"step": max(sig1(d["front"] * 0.0007), 0.005), "places": 3, "min": 0.01, "jitter": 0.2, "with": ["points.*.price"]},
            "derive": [_derive("front", "points.0.price", 3), _derive("roll", "(points.1.price / points.0.price - 1) * 100", 2),
                       _derive("shape", "points.1.price < points.0.price ? 'backwardation' : 'contango'")]}


def credit_curve(d: dict) -> dict:
    s5 = d["spread5y"]
    derive = [_derive("points.*.spread", "max(spread, 0.5)", 1), _derive("points.*.hazard", "spread / 10000 / (1 - $.recovery)", 6),
              _derive("points.*.survival", "exp(-hazard * tenor(tenor))", 6)]
    tenors = {p["tenor"] for p in d["points"]}
    if "5Y" in tenors:
        derive.append(_derive("spread5y", "points.tenor=5Y.spread", 1))
    if "1Y" in tenors:
        derive.append(_derive("pd1y", "1 - points.tenor=1Y.survival", 6))
    return {"spread5y": {"step": max(sig1(s5 * 0.012), 0.05), "places": 1, "min": 0.5, "jitter": 0.2, "with": ["points.*.spread"]}, "derive": derive}


def bond(d: dict) -> dict:
    px = d["price"]
    dur = d.get("modDuration") or 5
    z0 = d["zSpread"]
    return {"price": {"step": max(sig1(px * 0.0002), 0.005), "places": 3, "min": 1},
            "derive": [_derive("yield", "coupon - (price - 100) / (modDuration * 100)", 5),
                       _derive("zSpread", f"{z0} + ({px} - price) * 100 / modDuration", 1),       # a price move is a spread move of -100/duration bp per point
                       _derive("history.-1.price", "$.price", 3)]}


# ---- trading ---------------------------------------------------------------------------------------------------
def trade(d: dict, old: dict) -> dict:
    """MTM and everything that reads it. With legs whose PVs add up to the MTM, the floating leg's PV walks (the other legs
    move against it a little) and the MTM is their sum, each leg's cashflow PVs rescaled to the leg; otherwise the MTM walks
    and any leg PVs follow in proportion. The day's P&L is MTM less the opening MTM, and the P&L explain keeps adding up
    (the largest driver absorbs the move). Risk and sensitivities are end-of-day figures and stay put."""
    mtm = d["mtm"]
    step = max(500, round(abs(mtm) * 0.002 + d["notional"] * 1e-5))
    legs = d.get("legs") or []
    walk: dict = {k: v for k, v in old.items() if k != "mtm"}     # keep a fixing walked deep in a cashflow
    derive: list[dict] = []
    pv_legs = [i for i, leg in enumerate(legs) if isinstance(leg.get("pv"), (int, float))]
    if pv_legs and len(pv_legs) == len(legs) and any(legs[i]["pv"] for i in pv_legs):
        adds_up = abs(sum(leg["pv"] for leg in legs) - mtm) <= max(2, abs(mtm) * 1e-6)
        live_legs = [i for i in pv_legs if legs[i]["pv"] != 0] or pv_legs        # a leg worth nothing cannot be rescaled
        float_i = next((i for i in live_legs if str(legs[i].get("type", legs[i].get("rateType", ""))).upper().startswith("FLOAT")), live_legs[-1])
        if adds_up:
            walk[f"legs.{float_i}.pv"] = {"step": step, "places": 2,
                                          "with": [{"path": f"legs.{i}.pv", "scale": -0.3, "places": 2} for i in live_legs if i != float_i]}
            derive.append(_derive("mtm", "sum(legs.*.pv)"))
        else:
            walk["mtm"] = {"step": step, "with": [{"path": f"legs.{i}.pv", "scale": round(max(-50, min(50, legs[i]["pv"] / mtm)), 3) if mtm else 0, "places": 2}
                                                  for i in live_legs]}
        for i, leg in enumerate(legs):
            flows = leg.get("cashflows")
            if flows and leg["pv"] != 0 and all(isinstance(f.get("pv"), (int, float)) for f in flows):
                derive.append({"set": f"legs.{i}.cashflows.*.pv", "scaleTo": f"$.legs.{i}.pv", "places": 2})
    else:
        walk["mtm"] = step
    derive += [_derive("valuation.npv", "$.mtm"), _derive("valuation.cleanPv", "$.mtm - valuation.accrued"),
               _derive("pnl1d", "$.mtm - pnlExplain.0.pnl")]
    explain = d.get("pnlExplain") or []
    if len(explain) > 2:
        k = max(range(1, len(explain)), key=lambda i: abs(explain[i]["pnl"]))
        derive.append(_derive(f"pnlExplain.{k}.pnl", f"pnlExplain.{k}.pnl + ($.mtm - sum(pnlExplain.*.pnl))"))
    walk["derive"] = derive
    return walk


def _rows_walk(rows: list[dict], rows_key: str) -> dict:
    """One step per listed row (a trade in a netting set or a book), each about 0.2% of its MTM."""
    return {f"{rows_key}.{i}.mtm": max(400, round(abs(r["mtm"]) * 0.002 + 400)) for i, r in enumerate(rows)}


def netting_set(d: dict) -> dict:
    """Each listed trade walks; the asset-class totals and the net MTM are the rows added up (plus whatever of them is not
    listed, which stays put); the exposure profile scales as one, with the peaks, the utilisation and the CVA following."""
    rows = d["trades"]
    walk: dict = dict(_rows_walk(rows, "trades"))
    derive: list[dict] = []
    for k, a in enumerate(d["byAsset"]):
        idx = [i for i, r in enumerate(rows) if r["assetClass"] == a["asset"]]
        hidden = a["mtm"] - sum(rows[i]["mtm"] for i in idx)
        terms = ([str(hidden)] if hidden else []) + [f"trades.{i}.mtm" for i in idx]
        if terms:
            derive.append(_derive(f"byAsset.{k}.mtm", " + ".join(terms).replace("+ -", "- ")))
    hidden_net = d["netMtm"] - sum(r["mtm"] for r in rows)
    derive.append(_derive("netMtm", (f"{hidden_net} + " if hidden_net else "") + "sum(trades.*.mtm)"))
    walk["eePeak"] = {"step": 0.004, "mode": "rel", "with": ["profile.*.ee", "profile.*.pfe"]}
    derive += [_derive("eePeak", "max(profile.*.ee)"), _derive("pfePeak", "max(profile.*.pfe)"), _derive("utilisation", "pfePeak / limit", 4)]
    walk["derive"] = derive
    return walk


def book(d: dict) -> dict:
    rows = d["topTrades"]
    hidden = d["mtm"] - sum(r["mtm"] for r in rows)
    walk: dict = dict(_rows_walk(rows, "topTrades"))
    walk["derive"] = [_derive("mtm", (f"{hidden} + " if hidden else "") + "sum(topTrades.*.mtm)")]
    return walk


# ---- other packs -----------------------------------------------------------------------------------------------
def key_risk_indicator(d: dict) -> dict:
    """The reading walks (never below zero); the status is re-judged against the amber and red thresholds and today's
    point in the history follows the reading."""
    return {"value": {"step": max(sig1(d["red"] * 0.01), 0.01), "places": 2, "min": 0},
            "derive": [_derive("status", "value >= red ? 'Red' : value >= amber ? 'Amber' : 'Green'"), _derive("history.-1.value", "$.value", 2)]}


def card_account(d: dict) -> dict:
    return {"balance": {"step": 12.0, "places": 2, "min": 0}, "derive": [_derive("utilisation", "$.balance / $.limit", 3)]}


def deposit_account(d: dict) -> dict:
    """The balance walks; the latest ledger line is whatever got it there (amount = balance less the line before) and this
    month's point in the history follows."""
    return {"balance": {"step": 25.0, "places": 2},
            "derive": [_derive("transactions.0.balance", "$.balance", 2), _derive("transactions.0.amount", "$.balance - transactions.1.balance", 2),
                       _derive("history.-1.balance", "$.balance", 2)]}


def lcr(d: dict) -> dict:
    """HQLA and the run-off balances walk; every flow is balance x rate, the net outflow is outflows less capped inflows,
    and the ratio, the buffer and today's trend point follow from them."""
    out = "-sumneg(flows.*.flow)"
    return {"hqla": {"step": 0.0006, "mode": "rel", "jitter": 0.5, "with": ["hqlaByLevel.*.value"]},
            "netOutflows": {"step": 0.0008, "mode": "rel", "jitter": 1.0, "with": ["flows.*.balance"]},
            "derive": [_derive("hqla", "sum(hqlaByLevel.*.value)"),
                       _derive("flows.*.flow", "(flow < 0 ? -1 : 1) * balance * rate"),
                       _derive("netOutflows", f"{out} - min(sumpos(flows.*.flow), 0.75 * {out})"),
                       _derive("buffer", "hqla - netOutflows"), _derive("lcr", "hqla / netOutflows", 4), _derive("trend.-1.lcr", "$.lcr", 4)]}


def intraday_liquidity(d: dict) -> dict:
    """The latest hour's usage walks; the peak is the highest hour of the day and the usage ratio follows."""
    return {"usage.-1.used": {"step": 0.004, "mode": "rel", "min": 0},
            "derive": [_derive("peakUsage", "max(usage.*.used)"), _derive("usagePct", "peakUsage / available", 4)]}


def container(d: dict) -> dict:
    """The box temperature walks; the current hour's reading is its latest."""
    return {"currentC": 0.15,
            "append": [{"to": "readings", "key": "hour", "format": "'H'k", "keep": 24, "places": 1, "row": {"celsius": "$.currentC"}}]}


# What is deliberately NOT walked, per kind, and why (the pack test reads this so a new omission has to be argued here).
STATIC_ON_PURPOSE = {
    "port": "a congestion headline and a berth wait are single readings; there is no detail list to keep in step",
    "vessel": "position and speed are single readings; capacity figures are fixed by the ship",
    "shipment": "the ETA delay is a single reading; the route and milestones are a record of what has happened",
}

# Fields a kind leaves alone while it ticks (documented in docs/connectors/DEMO_DATA.md).
STATIC_FIELDS = {
    "bond": "the coupon schedule is fixed by the terms",
    "equity": "the 60-day bars before today are history",
    "equity-index": "constituent weights change at a rebalance, not on a tick",
    "commodity-curve": "open interest is end-of-day",
    "credit-curve": "recovery and the document clause are terms",
    "trade": "risk, sensitivities, schedules and the P&L history are end-of-day figures",
    "netting-set": "collateral moves on margin calls, not ticks",
    "book": "trade count and DV01 are end-of-day",
    "key-risk-indicator": "the thresholds and the definition are policy",
    "card-account": "statements and the category spend are closed periods",
    "deposit-account": "the ledger before the latest line is history",
    "lcr": "the minimum and the as-of date are fixed",
    "intraday-liquidity": "the large-payment log is what happened earlier in the day",
    "container": "the set point, humidity and power are settings",
}

BUILDERS = {"ir-curve": ir_curve, "fx-spot": fx_spot, "equity": equity, "equity-index": equity_index, "commodity": commodity,
            "commodity-curve": commodity_curve, "credit-curve": credit_curve, "bond": bond, "netting-set": netting_set, "book": book,
            "key-risk-indicator": key_risk_indicator, "card-account": card_account, "deposit-account": deposit_account, "lcr": lcr,
            "intraday-liquidity": intraday_liquidity, "container": container}


def walk_for(kind: str, doc: dict) -> dict | None:
    """The coherent walk for a finished sample of this kind, or None when the kind keeps its plain walk."""
    if kind == "trade":
        return trade(doc, (doc.get("_meta") or {}).get("walk") or {})
    b = BUILDERS.get(kind)
    return b(doc) if b else None


def apply(kind: str, doc: dict) -> None:
    """Replaces `_meta.walk` on a finished sample with its coherent walk (a sample that is not live keeps whatever it had)."""
    meta = doc.get("_meta")
    if not meta or not (meta.get("live") or meta.get("walk")):
        return
    w = walk_for(kind, doc)
    if w:
        meta["walk"] = w
