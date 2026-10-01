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

"""Trades for the trading pack: every product of the taxonomy, several trades each, in the shape trade_shape.py
fixes. Rate swaps carry real calculation periods (samplegen legs over the market-data curves), so their MTM is
the sum of their cashflow PVs; every trade carries execution, lifecycle, confirmation, clearing, regulatory,
settlement and valuation blocks, and links to the market data, book, trader, counterparty and netting set."""
from __future__ import annotations

import functools
import math
import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from samplegen import blocks as B, legs as L  # noqa: E402
from samplegen.curves import Curve  # noqa: E402
from samplegen.dates import Calendar, add_months, iso, tenor_years  # noqa: E402

import booking as BK  # noqa: E402
import data_market as M  # noqa: E402
import data_names as N  # noqa: E402
import taxonomy as T  # noqa: E402
import trade_shape as S  # noqa: E402
from risk_model import parse_gen  # noqa: E402

DESK_OF = {"Rates": "DESK-RATES", "FX": "DESK-FX", "Credit": "DESK-CREDIT", "Equity": "DESK-EQD", "Commodity": "DESK-COMM",
           "Inflation": "DESK-INFL", "Fixed income": "DESK-FI", "Securities financing": "DESK-SFT", "Money market": "DESK-MM",
           "Structured": "DESK-STRUCT"}
LE_SHORT = {"LE-NY": "NY", "LE-LDN": "LDN", "LE-FRA": "FRA", "LE-TKY": "TKY"}
BILATERAL = [c for c in N.COUNTERPARTIES if c not in ("SUMMIT",)]
SOURCE = {"Rates": "murex-rates", "FX": "calypso-fx", "Credit": "murex-credit", "Equity": "sophis-eqd", "Commodity": "endur-comm",
          "Inflation": "murex-rates", "Fixed income": "summit-fi", "Securities financing": "globalone-sft", "Money market": "summit-mm",
          "Structured": "sophis-struct"}


@functools.lru_cache(maxsize=1)
def all_bonds() -> dict:
    return M.bonds()


def trader_id(name: str) -> str:
    return "TRDR-" + "".join(c for c in name if c.isalpha()).upper()


def books_of(desk: str) -> list[str]:
    return [f"BOOK-{desk[5:]}-{k}" for k in (1, 2, 3)]


def traders_of(desk: str) -> list[str]:
    i = list(N.DESKS).index(desk)
    return [N.TRADERS[(2 * i) % len(N.TRADERS)], N.TRADERS[(2 * i + 1) % len(N.TRADERS)]]


def gen_value(gen: str, r, ctx: dict):
    name, args = parse_gen(gen)
    if name == "rate":
        return round(r.uniform(float(args[0]), float(args[1])), 6)
    if name == "num":
        return round(r.uniform(float(args[0]), float(args[1])), int(args[2]) if len(args) > 2 else 2)
    if name == "int":
        return r.randint(int(args[0]), int(args[1]))
    if name in ("choice", "tenor"):
        return r.choice(args)
    if name == "date":
        return iso(N.AS_OF + timedelta(days=int(365 * r.uniform(float(args[0]), float(args[1])))))
    if name == "bool":
        return r.random() < float(args[0] if args else 0.5)
    if name == "pick":
        return ctx.get("pick_" + args[0])
    return args[0] if args else None


def context(p, r) -> dict:
    """The underlier and currencies a trade in product p is on."""
    ctx = {"ccy": r.choice(["USD", "USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD"])}
    u = p.underlier
    if u in ("pair", "ndf"):
        pair = r.choice(list(N.FX) if u == "pair" else ["USDINR", "USDBRL", "USDKRW", "USDCNH"])
        ctx["pair"] = pair
        ctx["ccy"] = pair[3:] if pair[3:] in N.CCY else "USD"
    if u in ("issuer",):
        ctx["issuer"] = r.choice([i for i, v in N.ISSUERS.items() if not v[5]])
    if u == "bond":
        bonds = list(all_bonds().values())
        b = r.choice(bonds)
        ctx["bond"] = b["isinCode"]
        ctx["issuer"] = b["issuer"][4:]
        ctx["ccy"] = b["terms"]["currency"]
        ctx["pick_bond"] = b["isin"]
    if u == "equity":
        t = r.choice(list(N.EQUITIES))
        ctx["equity"], ctx["ccy"] = t, N.EQUITIES[t][3]
        ctx["index"] = N.EQUITIES[t][7] or "SPX"
    if u in ("index", "basket"):
        i = r.choice(list(N.INDICES))
        ctx["index"], ctx["ccy"] = i, N.INDICES[i][1]
        ctx["equity"] = i
    if u == "commodity":
        ctx["commodity"] = r.choice(list(N.COMMODITIES))
        ctx["ccy"] = "USD"
    if u == "inflation":
        k = r.choice(list(N.INFLATION))
        ctx["infl"], ctx["ccy"] = k, N.INFLATION[k][1]
    if "pair" not in ctx:
        pair = "EURUSD" if ctx["ccy"] == "USD" else ctx["ccy"] + "USD" if ctx["ccy"] + "USD" in N.FX else "USD" + ctx["ccy"]
        ctx["pair"] = pair if pair in N.FX else "EURUSD"
    ctx.setdefault("issuer", "SOLARIS")
    ctx.setdefault("equity", "NVTK")
    ctx.setdefault("index", "SPX")
    ctx.setdefault("infl", "USCPI")
    ctx.setdefault("commodity", "WTI")
    ctx.setdefault("corr", r.choice(list(N.CORRELATIONS)))
    ctx.setdefault("bond", next(iter(all_bonds().values()))["isinCode"])
    return ctx


def resolve_md(p, ctx) -> dict:
    out = {}
    for fld, kind, sel in p.md:
        s = sel.format(**ctx)
        if kind == "equity-vol-surface" and "{equity}" not in sel and "{index}" in sel:
            s = ctx["index"]
        out[fld] = M.md_id(kind, s)
    return out


def curve(ccy: str) -> Curve:
    return Curve(f"CRV-{ccy}-OIS", ccy, N.AS_OF, M.CURVE_T, [z / 100 for z in M.zero_curve(ccy, "OIS")])


def maturity_years(p, r) -> float:
    return {"money": r.uniform(0.1, 0.9), "future": r.uniform(0.1, 1.2), "option": r.uniform(0.3, 3), "linear": r.uniform(0.2, 2),
            "sft": r.uniform(0.02, 0.5), "position": 0.0}.get(p.family, r.uniform(2, 12))


def rates_legs(p, trade_id, notional, ccy, start, mat, direction_pay, r, terms):
    cal = Calendar.of(N.CCY[ccy][2])
    disc = curve(ccy)
    freq = terms.get("payFrequency") or "Annual"
    fixed_rate = terms.get("fixedRate") or terms.get("zeroRate") or round(disc.zero(5) + 0.001, 6)
    fixing = lambda d: round(disc.zero(0.1) + 0.0005 * math.sin(d.toordinal() / 11), 6)  # noqa: E731
    spread = terms.get("spread")
    spread = round(spread / 1e4, 6) if isinstance(spread, (int, float)) else 0.0
    leg1 = L.fixed_leg(1, direction_pay, notional, ccy, fixed_rate, start, mat, freq, terms.get("dayCount") or "ACT/360", cal, disc, N.AS_OF)
    leg2 = L.float_leg(2, not direction_pay, notional, ccy, N.CCY[ccy][3], spread, start, mat, "Quarterly", "ACT/360", cal, disc, disc,
                       N.AS_OF, fixing)
    for leg in (leg1, leg2):
        leg["payReceive"] = "Pay" if leg["payer"] else "Receive"
        leg["rateType"] = leg["type"].title()
        leg.setdefault("index", None)
        leg.setdefault("spread", 0.0)
    return [leg1, leg2]


def schedule_rows(p, notional, start: date, mat: date, legs, r, ccy):
    kind = p.schedule
    rows = []
    if kind == "cashflows" and legs:
        for leg in legs:
            for f in leg["cashflows"]:
                rows.append({"payDate": f["payDate"], "leg": leg["leg"], "type": leg["type"].title(), "rate": f["rate"], "amount": f["amount"],
                             "pv": f["pv"], "status": f["status"]})
        rows.sort(key=lambda x: x["payDate"])
        return rows[:40]
    dates = []
    d = start
    step = 3 if kind in ("fixings", "observations") else 6 if kind in ("coupons", "amortization") else 12
    while d < mat and len(dates) < 24:
        d = add_months(d, step)
        dates.append(min(d, mat))
    for k, d in enumerate(dates or [mat]):
        past = d <= N.AS_OF
        if kind == "cashflows":
            amt = round(notional * 0.01 * r.uniform(0.8, 1.2) / 4, 2)
            rows.append({"payDate": iso(d), "leg": 1, "type": "Premium", "rate": 0.01, "amount": -amt, "pv": 0 if past else round(-amt * 0.97, 2),
                         "status": "Settled" if past else "Scheduled"})
        elif kind == "coupons":
            rows.append({"date": iso(d), "rate": round(r.uniform(0.02, 0.06), 6), "amount": round(notional * 0.02, 2), "status": "Paid" if past else "Scheduled"})
        elif kind == "fixings":
            rate = round(r.uniform(0.02, 0.05), 6)
            rows.append({"fixingDate": iso(d), "period": f"{iso(d)}..{iso(add_months(d, 3))}", "index": N.CCY[ccy][3], "rate": rate,
                         "payoff": round(max(0.0, rate - 0.035) * notional / 4, 2), "status": "Fixed" if past else "Pending"})
        elif kind == "exercise":
            rows.append({"date": iso(d), "type": "Bermudan call" if k else "First call", "strike": round(r.uniform(0.02, 0.05), 6),
                         "status": "Expired" if past else "Pending"})
        elif kind == "observations":
            # a live note has not been called: past observations sat below the 100% trigger; coupons depend on the 70% barrier
            lvl = round(min(99.4, 100 * (1 + r.gauss(-0.08, 0.05))), 2)
            rows.append({"date": iso(d), "level": lvl if past else None, "trigger": 100.0, "performance": round(lvl / 100 - 1, 4) if past else None,
                         "outcome": ("Coupon paid" if lvl >= 70 else "No coupon") if past else "Pending"})
        elif kind == "amortization":
            opening = notional * (1 - k / max(len(dates), 1))
            principal = notional / max(len(dates), 1)
            rows.append({"date": iso(d), "opening": round(opening), "principal": round(principal), "closing": round(opening - principal),
                         "interest": round(opening * 0.04 / 2)})
        elif kind == "collateral":
            break
        elif kind == "constituents":
            break
    if kind == "collateral":
        for b in r.sample(list(all_bonds().values()), 3):
            mv = round(notional / 3 * r.uniform(0.95, 1.05))
            hc = round(r.choice([0.005, 0.01, 0.02, 0.04]), 4)
            rows.append({"asset": b["issuerName"] + " " + b["maturity"][:4], "isin": b["isinCode"], "quantity": round(mv / b["price"] * 100),
                         "marketValue": mv, "haircut": hc, "value": round(mv * (1 - hc))})
    if kind == "constituents":
        names = [v[0] for v in N.ISSUERS.values() if not v[5]]
        for n in r.sample(names, min(8, len(names))):
            rows.append({"name": n, "weight": round(1 / 8, 4), "level": round(r.uniform(40, 450), 1), "status": "Active"})
    return rows


def risk_numbers(p, notional, years, sign, r) -> dict:
    out = {}
    for m in p.risk:
        if m in ("dv01", "cs01", "ie01"):
            out[m] = round(sign * notional * max(years, 0.1) * 0.9e-4 * r.uniform(0.85, 1.05))
        elif m == "delta":
            out[m] = round(sign * notional * r.uniform(0.2, 0.7) * 0.01)
        elif m == "gamma":
            out[m] = round(notional * r.uniform(0.001, 0.004) * 0.01)
        elif m == "vega":
            out[m] = round(sign * notional * max(years, 0.25) ** 0.5 * r.uniform(0.001, 0.003))
        elif m == "theta":
            out[m] = round(-notional * r.uniform(0.00002, 0.0001))
        elif m == "fxDelta":
            out[m] = round(sign * notional * 0.01)
        elif m == "jtd":
            out[m] = round(-notional * 0.6)
        else:
            out[m] = round(notional * r.uniform(0.005, 0.03))
    return out


def sensitivities(p, risk: dict, years: float) -> list[dict]:
    if not p.risk:
        return []
    m0 = p.risk[0]
    buckets = S.BUCKETS.get(m0, S.DEFAULT_BUCKETS)
    live = [b for b in buckets if b == "Spot" or tenor_years(b) <= max(years, 0.25) + 0.5] or buckets[:1]
    w = [1 + k for k in range(len(live))]
    tot = sum(w)
    rows = []
    for b, wi in zip(live, w):
        row = {"bucket": b}
        for m in p.risk:
            row[m] = round(risk[m] * wi / tot)
        rows.append(row)
    return rows


# the risk factors a day's P&L is explained by, before what is left unexplained
EXPLAIN = ["Carry", "Roll-down", "Rates delta", "FX delta", "Vol (vega)", "Theta"]


def pnl_explain(tid: str, mtm: float, pnl1d: float, p) -> list[dict]:
    """Yesterday's MTM, today's P&L by risk factor and the unexplained rest: the steps add up to today's MTM (the
    waterfall's closing bar). Options carry vega and theta, linear products mostly carry, roll-down and delta."""
    r = M.rng(tid + "explain")
    scale = max(abs(pnl1d), 1000.0)
    optional = p.family in ("option", "exotic")
    weights = {"Carry": 0.25, "Roll-down": 0.15, "Rates delta": 0.6, "FX delta": 0.3 if p.asset == "FX" else 0.12,
               "Vol (vega)": 0.5 if optional else 0.02, "Theta": 0.35 if optional else 0.04}
    unexplained = round(pnl1d * r.uniform(-0.06, 0.06))
    steps = [{"step": f, "pnl": round(r.gauss(0, scale * weights[f]))} for f in EXPLAIN]
    if optional:
        steps[-1]["pnl"] = -abs(steps[-1]["pnl"])                 # an option holder pays theta
    steps[2]["pnl"] += round(pnl1d - unexplained - sum(x["pnl"] for x in steps))   # rates delta takes the balance
    return [{"step": "Opening MTM", "pnl": round(mtm - pnl1d), "total": True}] + steps + [{"step": "Unexplained", "pnl": unexplained}]


def timeline(tid: str, doc: dict, cal: Calendar, mat: date, cleared: bool) -> list[dict]:
    """The trade's life as dated events, oldest first: booked, confirmed, cleared, amended, partially terminated,
    cash settled, the next payment and maturity, each with a status the timeline tones (Done, Matched and Settled are
    fine, Pending is a warning)."""
    r = M.rng(tid + "timeline")
    trade_date = date.fromisoformat(doc["tradeDate"])
    out = [{"date": doc["tradeDate"], "event": "Booked", "status": "Done",
            "description": f"Captured in {doc['sourceSystem']} by {doc['trader']}: {doc['direction'].lower()} {doc['productName']}"}]
    conf = doc["confirmation"]
    out.append({"date": conf["matched"][:10], "event": "Confirmed", "status": "Matched", "description": f"Matched on {conf['method']} ({conf['platformId']})"})
    if cleared:
        out.append({"date": iso(cal.add_business_days(trade_date, 1)), "event": "Cleared", "status": "Done",
                    "description": f"Novated to {doc['clearing'].get('ccp', 'the CCP')}"})
    for e in doc["lifecycle"]["events"][1:]:
        out.append({"date": e["at"][:10], "event": "Amended", "status": "Done", "description": f"{e['reason']} (version {e['version']}, by {e['by']})"})
    days = (min(mat, N.AS_OF) - trade_date).days
    if mat > N.AS_OF and days > 60 and r.random() < 0.12:
        d = trade_date + timedelta(days=r.randint(30, days - 10))
        cut = r.choice([10, 20, 25, 40])
        out.append({"date": iso(cal.adjust(d, "PRECEDING")), "event": "Partially terminated", "status": "Done",
                    "description": f"Notional reduced by {cut}%, unwind fee settled", "amount": round(doc["notional"] * cut / 100)})
    rows = doc.get("schedule") or []
    when = [(x.get("payDate") or x.get("date") or x.get("fixingDate"), x) for x in rows]
    paid = [(d, x) for d, x in when if d and d <= iso(N.AS_OF) and isinstance(x.get("amount", x.get("payoff")), (int, float))]
    if paid:
        d, x = paid[-1]
        out.append({"date": d, "event": "Cash settled", "status": "Settled", "description": f"{doc['currency']} payment settled",
                    "amount": x.get("amount", x.get("payoff"))})
    upcoming = [d for d, x in when if d and d > iso(N.AS_OF)]
    if upcoming:
        out.append({"date": upcoming[0], "event": "Next payment", "status": "Pending", "description": "Scheduled; settlement instructions confirmed"})
    if mat <= N.AS_OF:
        out.append({"date": iso(mat), "event": "Matured", "status": "Done", "description": "Final payment settled, trade closed"})
    else:
        out.append({"date": iso(mat), "event": "Maturity", "status": "Scheduled", "description": "Final payment and close-out"})
    return sorted(out, key=lambda e: e["date"])


def build() -> dict[str, dict]:
    trades = {}
    booked: dict[str, int] = {}                  # trades so far per booking system
    for p in T.PRODUCTS:
        for k in range(N.TRADES_PER_PRODUCT):
            r = M.rng(f"{p.code}/{k}")
            system = BK.system_of(p.asset)
            booked[system] = booked.get(system, 0) + 1
            tid, native = BK.sample_id(system, booked[system])
            ctx = context(p, r)
            ccy = ctx["ccy"]
            lo, hi = p.notional
            notional = round(r.uniform(lo, hi) / 1e6) * 1e6 or 1e6
            years = maturity_years(p, r)
            cal = Calendar.of(N.CCY.get(ccy, N.CCY["USD"])[2])
            trade_date = cal.adjust(N.AS_OF - timedelta(days=r.randint(3, 900 if p.family not in ("sft", "money", "future") else 60)), "PRECEDING")
            start = cal.adjust(trade_date + timedelta(days=2))
            mat = cal.adjust(max(start + timedelta(days=int(365 * max(years, 0.05))), N.AS_OF + timedelta(days=7)))
            terms = {f.name: gen_value(f.gen, r, ctx) for f in p.terms}
            desk = DESK_OF[p.asset]
            le = N.DESKS[desk][1]
            cp = "SUMMIT" if p.listed else r.choice(BILATERAL)
            trader = r.choice(traders_of(desk))
            pay = r.random() < 0.5
            legs = rates_legs(p, tid, notional, ccy, start, mat, pay, r, terms) if p.legs and p.asset in ("Rates", "Inflation") and \
                p.schedule in ("cashflows", "amortization") else []
            if legs:
                mtm = round(sum(leg["pv"] for leg in legs))
            else:
                mtm = round(notional * r.gauss(0, 0.012))
            sign = 1 if pay else -1
            risk = risk_numbers(p, notional, years, sign, r)
            direction = {"swap": "Pay fixed" if pay else "Receive fixed", "option": "Buy" if pay else "Sell", "exotic": "Buy" if pay else "Sell",
                         "cds": "Buy protection" if pay else "Sell protection", "sft": "Repo (lend cash)" if pay else "Reverse repo"}.get(
                p.family, "Long" if pay else "Short")
            doc = {"tradeId": tid, "sourceSystem": system, "sourceTradeId": native, "productType": p.code, "productName": p.name, "assetClass": p.asset, "family": p.family,
                   "status": "Live" if mat > N.AS_OF else "Matured", "direction": direction, "tradeDate": iso(trade_date),
                   "effectiveDate": iso(start), "maturityDate": iso(mat), "currency": ccy, "notional": notional, "mtm": mtm,
                   "mtmCurrency": "USD", "pnl1d": round(mtm * r.gauss(0, 0.04)),
                   "counterparty": {"id": f"CP-{cp}", "name": N.COUNTERPARTIES[cp][0]},
                   "nettingSet": f"NS-{cp}-{LE_SHORT[le]}", "book": r.choice(books_of(desk)), "trader": trader_id(trader),
                   "desk": desk, "legalEntity": le}
            if p.listed:
                doc["clearingAccount"] = "CLR-CME-1" if p.asset in ("Rates", "Commodity") else "CLR-EUREX-1"
            if p.underlier in ("issuer", "bond"):
                doc["issuer"] = f"ISS-{ctx['issuer']}"
            doc.update(resolve_md(p, ctx))
            doc["terms"] = terms
            doc["risk"] = risk
            doc["sensitivities"] = sensitivities(p, risk, years)
            if legs:
                doc["legs"] = legs
            elif p.legs:
                doc["legs"] = [{"leg": n + 1, "label": ("Pay " if (n == 0) == pay else "Receive ") + lab, "payReceive": "Pay" if (n == 0) == pay else "Receive",
                                "rateType": rt, "index": idx, "rate": rate, "spread": 0.0, "frequency": "Quarterly", "dayCount": "ACT/360",
                                "currency": ccy, "notional": notional}
                               for n, (lab, rt, idx, rate) in enumerate([("performance", "Performance", ctx.get("equity") if p.asset == "Equity" else ctx["commodity"] if p.asset == "Commodity" else ctx["infl"], None),
                                                                        (N.CCY[ccy][3] + " + spread", "Float", N.CCY[ccy][3], round(M.zero_curve(ccy, "OIS")[3] / 100, 6))])]
            if p.schedule:
                doc["schedule"] = schedule_rows(p, notional, start, mat, legs, r, ccy)
                future = [i for i, row in enumerate(doc["schedule"]) if (row.get("payDate") or row.get("date") or row.get("fixingDate") or "") > iso(N.AS_OF)]
                doc["nextIndex"] = future[0] if future else -1
            doc["pnlHistory"] = B.pnl_history(tid, N.AS_OF, mtm)
            asset_for_blocks = {"Inflation": "Rates", "Fixed income": "Credit", "Money market": "Rates", "Securities financing": "Rates",
                                "Structured": "Equity"}.get(p.asset, p.asset)
            cleared = p.listed or (p.family == "swap" and p.asset == "Rates" and r.random() < 0.4)
            doc.update({
                "execution": B.execution(tid, trade_date, asset_for_blocks, trader),
                "lifecycle": B.lifecycle(tid, trade_date, N.AS_OF, [("Amendment", "Notional corrected")] if r.random() < 0.15 else None),
                "confirmation": B.confirmation(tid, trade_date, "MarkitWire" if p.asset in ("Rates", "Credit", "Inflation") else "DTCC CTM"),
                "clearing": B.clearing(tid, ccy, cleared),
                "regulatory": B.regulatory(tid, B.entity({"LE-NY": "New York", "LE-LDN": "London", "LE-FRA": "Frankfurt", "LE-TKY": "Tokyo"}[le])["lei"],
                                           asset_for_blocks, p.code, {"LE-NY": "US", "LE-LDN": "GB", "LE-FRA": "DE", "LE-TKY": "JP"}[le], cleared),
                "settlementInstructions": B.settlement(ccy, N.COUNTERPARTIES[cp][0]),
                "valuation": B.valuation(N.AS_OF, mtm, "USD", {"swap": "Multi-curve OIS discounting", "option": "Black / Bachelier",
                                                              "exotic": "Local-stochastic volatility Monte Carlo"}.get(p.family, "Discounted cashflows"),
                                         [v for f, v in resolve_md(p, ctx).items()])})
            doc["pnlExplain"] = pnl_explain(tid, mtm, doc["pnl1d"], p)
            doc["lifecycle"]["timeline"] = timeline(tid, doc, cal, mat, cleared)
            doc["_meta"] = {"source": SOURCE[p.asset], "generation": 1, "live": p.family in ("swap", "future", "option", "linear"),
                            "walk": {"mtm": max(500, round(abs(mtm) * 0.002 + notional * 1e-5))}}
            trades[tid] = doc
    return trades


if __name__ == "__main__":
    t = build()
    import json
    sizes = sorted(len(json.dumps(d)) for d in t.values())
    print(len(t), "trades; sizes min/median/max", sizes[0], sizes[len(sizes) // 2], sizes[-1])
    swaps = [d for d in t.values() if d.get("legs") and "cashflows" in d["legs"][0]]
    print("rate swaps with calculation periods:", len(swaps), "example MTM", swaps[0]["mtm"], sum(l["pv"] for l in swaps[0]["legs"]))
