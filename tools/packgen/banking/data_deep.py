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

"""Deep trade documents, as a trading system such as Endur holds them: trade, then legs[], then each leg's own
cashflows[], then each floating cashflow's fixings[] (the fourth level).

    trade                                    terms, risk, lifecycle ... (data_trades.py)
      legs[]                                 payReceive, currency, notional, index, spread, dayCount, frequency,
                                             businessDayConvention, calendar, resetFrequency
        cashflows[]                          periodStart, periodEnd, payDate, accrualFactor, rate, amount,
                                             discountFactor, pv, status (+ type, notional, quantity)
          fixings[]                          fixingDate, index, tenor, rate, source, status

Products built here (the others keep the shape data_trades.py gives them): the interest-rate swap family's fixings
(modernise, fixings_for), basis swap, cross-currency swap with initial and final notional exchanges, cap and floor
(one leg of caplets or floorlets, each with its fixing), European swaption (option terms and the underlying swap's
legs), and the multi-leg commodity swap (fixed price, index average with daily fixings, fee and, on some trades, a
location differential). Everything is deterministic: a separate random stream per trade, so the other products'
values do not move.
"""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from samplegen import legs as L  # noqa: E402
from samplegen.curves import Curve  # noqa: E402
from samplegen.dates import Calendar, add_months, iso, schedule, tenor_years, year_fraction  # noqa: E402

import data_market as M  # noqa: E402
import data_names as N  # noqa: E402

DEEP = {"BASIS_SWAP", "XCCY_SWAP", "CAP", "FLOOR", "SWAPTION_EUR", "CMD_SWAP"}
BDC = {"Mod. following": "MODFOLLOWING", "Following": "FOLLOWING"}
FREQ_OF = {1: "Monthly", 3: "Quarterly", 6: "Semi-annual", 12: "Annual"}
SOURCE = {"SOFR": "NY Fed SOFR", "ESTR": "ECB EUR short-term rate", "SONIA": "BoE SONIA", "TONA": "BoJ TONA", "SARON": "SIX SARON",
          "AONIA": "RBA AONIA", "CORRA": "BoC CORRA"}
NORMAL_VOL_BP = 88.0


def curve(ccy: str) -> Curve:
    return Curve(f"CRV-{ccy}-OIS", ccy, N.AS_OF, M.CURVE_T, [z / 100 for z in M.zero_curve(ccy, "OIS")])


def usd_per(ccy: str) -> float:
    """US dollars for one unit of ccy."""
    if ccy == "USD":
        return 1.0
    return N.FX[ccy + "USD"] if ccy + "USD" in N.FX else 1.0 / N.FX["USD" + ccy]


def index_name(ccy: str, tenor_m: int) -> str:
    """The rate index of a currency: its overnight rate (tenor 0) or a term rate, TERM-SOFR-3M."""
    rfr = N.CCY[ccy][3]
    return rfr if tenor_m == 0 else f"TERM-{rfr}-{tenor_m}M"


def tenor_of(index: str) -> int:
    """Months in a rate index's tenor: TERM-SOFR-3M is 3, SOFR (overnight) is 0."""
    last = index.rsplit("-", 1)[-1]
    return int(last[:-1]) if last.endswith("M") and last[:-1].isdigit() else 0


def source_of(ccy: str, tenor_m: int) -> str:
    rfr = N.CCY[ccy][3]
    return SOURCE[rfr] if tenor_m == 0 else f"{'CME Term SOFR' if rfr == 'SOFR' else 'Refinitiv ' + rfr + ' term'}"


def published(ccy: str, tenor_m: int):
    """Past fixings of an index: the curve's short rate plus a small wobble, and a term premium for longer tenors."""
    base = curve(ccy).zero(0.1)
    return lambda d: round(base + 0.0004 * tenor_m / 3 + 0.0005 * math.sin(d.toordinal() / 11), 6)


def _meta(leg: dict, tid: str, cal: Calendar) -> dict:
    leg["legId"] = f"{tid}-L{leg['leg']}"
    leg["payReceive"] = "Pay" if leg["payer"] else "Receive"
    leg["rateType"] = leg["type"].title()
    leg.setdefault("index", None)
    leg.setdefault("spread", 0.0)
    leg.setdefault("calendar", cal.name)
    return leg


def modernise(leg: dict) -> dict:
    """The names a trading system gives a calculation period (periodStart, accrualFactor, discountFactor) and the leg's
    businessDayConvention, in place of samplegen's accrualStart, yearFraction, df and businessDay."""
    if "businessDay" in leg:
        leg["businessDayConvention"] = leg.pop("businessDay")
    for f in leg["cashflows"]:
        f["periodStart"], f["periodEnd"] = f.pop("accrualStart"), f.pop("accrualEnd")
        f["accrualFactor"], f["discountFactor"] = f.pop("yearFraction"), f.pop("df")
        f.setdefault("type", "Interest")
    return leg


def fixings_for(leg: dict, ccy: str, as_of: date = N.AS_OF) -> dict:
    """The fixings under each floating cashflow of a leg built by samplegen: the overnight rate compounded in arrears is
    read in monthly sub-periods, and the sub-period rates average (by days) to the cashflow's own rate."""
    if leg["type"] != "FLOAT":
        return leg
    leg["resetFrequency"] = "Monthly"
    for f in leg["cashflows"]:
        a, b = date.fromisoformat(f["periodStart"]), date.fromisoformat(f["periodEnd"])
        cuts, k = [a], 1
        while cuts[-1] < b:
            cuts.append(min(add_months(a, k), b))
            k += 1
        spans = [(s, e) for s, e in zip(cuts[:-1], cuts[1:]) if e > s]
        days = [(e - s).days for s, e in spans]
        wobble = [0.0002 * math.sin(s.toordinal() / 5) for s, _ in spans]
        shift = sum(w * d for w, d in zip(wobble, days)) / sum(days)
        f["fixings"] = [{"fixingDate": iso(s), "index": leg["index"], "tenor": "1M", "rate": round(f["rate"] + w - shift, 6),
                         "source": SOURCE.get(leg["index"], "Refinitiv") if e <= as_of else "Forward curve",
                         "status": "Fixed" if e <= as_of else "Projected"} for (s, e), w in zip(spans, wobble)]
    return leg


def float_leg(n: int, pay: bool, notional: float, ccy: str, tenor_m: int, spread: float, start: date, end: date, pay_m: int,
              cal: Calendar, disc: Curve, as_of: date = N.AS_OF, basis: str = "ACT/360") -> dict:
    """A floating leg with one fixing per index period (tenor_m months; 0 for an overnight rate, read monthly). The cashflow's
    rate is the day-weighted average of its fixings."""
    sign = -1 if pay else 1
    step = tenor_m or 1
    index = index_name(ccy, tenor_m)
    known = published(ccy, tenor_m)
    flows = []
    for i, (a, b) in enumerate(schedule(start, end, FREQ_OF[pay_m], cal), 1):
        paydate = cal.add_business_days(b, 2)
        subs, s, k = [], a, 1
        while s < b:
            e = min(add_months(a, step * k), b)
            k += 1
            if e <= s:
                break
            fix = cal.add_business_days(s, -2)
            fixed = fix <= as_of
            rate = known(fix) if fixed else disc.forward(max(s, as_of), e, 360.0)
            subs.append({"fixingDate": iso(fix), "index": index, "tenor": f"{step}M", "rate": round(rate, 6),
                         "source": source_of(ccy, tenor_m) if fixed else "Forward curve", "status": "Fixed" if fixed else "Projected",
                         "_days": (e - s).days})
            s = e
        total = sum(x["_days"] for x in subs)
        rate = sum(x["rate"] * x["_days"] for x in subs) / total
        for x in subs:
            del x["_days"]
        dcf = year_fraction(a, b, basis)
        amount = sign * notional * (rate + spread) * dcf
        df = disc.df(paydate) if paydate > as_of else 1.0
        done = [x["status"] == "Fixed" for x in subs]
        status = "Settled" if paydate <= as_of else "Fixed" if all(done) else "Projected" if not any(done) else "Partially fixed"
        flows.append({"n": i, "type": "Interest", "periodStart": iso(a), "periodEnd": iso(b), "fixingDate": subs[0]["fixingDate"],
                      "payDate": iso(paydate), "notional": notional, "index": index, "rate": round(rate, 6), "spread": spread,
                      "accrualFactor": round(dcf, 6), "amount": round(amount, 2), "discountFactor": round(df, 6),
                      "pv": round(amount * df if paydate > as_of else 0.0, 2), "status": status, "fixings": subs})
    label = index + ("" if not spread else f" {'+' if spread > 0 else '−'} {abs(spread) * 1e4:.1f} bp")
    leg = {"leg": n, "label": f"{'Pay' if pay else 'Receive'} {label}", "payer": pay, "type": "FLOAT", "currency": ccy, "notional": notional,
           "index": index, "spread": spread, "dayCount": basis, "frequency": FREQ_OF[pay_m],
           "resetFrequency": FREQ_OF[step] if step in FREQ_OF else f"{step}M", "businessDayConvention": "Mod. following",
           "calendar": cal.name, "paymentLag": "2 business days", "compounding": "Simple average of the fixings", "cashflows": flows}
    return finish(leg)


def fixed_leg(n: int, pay: bool, notional: float, ccy: str, rate: float, start: date, end: date, pay_m: int, cal: Calendar,
              disc: Curve, basis: str = "30/360", as_of: date = N.AS_OF) -> dict:
    leg = L.fixed_leg(n, pay, notional, ccy, rate, start, end, FREQ_OF[pay_m], basis, cal, disc, as_of)
    return finish(modernise(leg))


def finish(leg: dict) -> dict:
    leg["pv"] = round(sum(f["pv"] for f in leg["cashflows"]), 2)
    leg["projectedFlows"] = sum(1 for f in leg["cashflows"] if f["status"] in ("Projected", "Partially fixed"))
    return leg


def exchange(leg: dict, disc: Curve, start: date, end: date, cal: Calendar, as_of: date = N.AS_OF) -> dict:
    """Initial and final exchange of the leg's notional, as cashflows: the payer of the leg's interest receives the principal at
    the start and returns it at the end."""
    sign = 1 if leg["payer"] else -1
    out = []
    for kind, d, amt in (("Initial exchange", start, sign * leg["notional"]), ("Final exchange", end, -sign * leg["notional"])):
        paydate = cal.adjust(d)
        df = disc.df(paydate) if paydate > as_of else 1.0
        out.append({"n": 0, "type": kind, "periodStart": iso(paydate), "periodEnd": iso(paydate), "payDate": iso(paydate),
                    "notional": leg["notional"], "rate": None, "accrualFactor": 0.0, "amount": round(amt, 2), "discountFactor": round(df, 6),
                    "pv": round(amt * df if paydate > as_of else 0.0, 2), "status": "Settled" if paydate <= as_of else "Scheduled"})
    leg["cashflows"] = [out[0]] + leg["cashflows"] + [out[1]]
    for i, f in enumerate(leg["cashflows"], 1):
        f["n"] = i
    return finish(leg)


def to_usd(ccy: str, amount: float) -> float:
    return amount * usd_per(ccy)


# ---- basis swap -----------------------------------------------------------------------------------------------------------
def basis_swap(tid, notional, ccy, start, mat, pay, terms, cal):
    ts = [tenor_of(terms["index1"]), tenor_of(terms["index2"])]
    pay_m = 3 if max(ts) <= 3 else 6
    terms["index1"], terms["index2"] = index_name(ccy, ts[0]), index_name(ccy, ts[1])
    disc = curve(ccy)
    spread = round(terms["spread"] / 1e4, 6)
    legs = [float_leg(1, pay, notional, ccy, ts[0], 0.0, start, mat, pay_m, cal, disc),
            float_leg(2, not pay, notional, ccy, ts[1], spread, start, mat, pay_m, cal, disc)]
    return {"legs": [_meta(x, tid, cal) for x in legs], "mtm": round(sum(x["pv"] for x in legs))}


# ---- cross-currency swap --------------------------------------------------------------------------------------------------
def xccy_swap(tid, notional, ccy, start, mat, pay, terms, cal):
    other = terms["otherCurrency"] if terms["otherCurrency"] != ccy else ("USD" if ccy != "USD" else "EUR")
    terms["otherCurrency"] = other
    fx = usd_per(other) / usd_per(ccy)                                  # units of the base currency for one unit of the other
    terms["fxRateAtStart"] = round(fx, 5)
    cal2 = Calendar.of(N.CCY[other][2])
    basis = round(terms["xccyBasis"] / 1e4, 6)
    notional2 = round(notional / fx / 1e6) * 1e6 or 1e6
    d1, d2 = curve(ccy), curve(other)
    leg1 = exchange(float_leg(1, pay, notional, ccy, 3, 0.0, start, mat, 3, cal, d1), d1, start, mat, cal)
    leg2 = exchange(float_leg(2, not pay, notional2, other, 3, basis, start, mat, 3, cal2, d2), d2, start, mat, cal2)
    mtm = round(to_usd(ccy, leg1["pv"]) + to_usd(other, leg2["pv"]))
    return {"legs": [_meta(leg1, tid, cal), _meta(leg2, tid, cal2)], "mtm": mtm}


# ---- cap, floor -----------------------------------------------------------------------------------------------------------
def _phi(x: float) -> float:
    return math.exp(-0.5 * x * x) / math.sqrt(2 * math.pi)


def _big_phi(x: float) -> float:
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def bachelier(fwd: float, strike: float, vol: float, t: float, call: bool) -> float:
    """Undiscounted value of one unit of a normal-model option on a rate."""
    if t <= 0 or vol <= 0:
        return max(fwd - strike, 0.0) if call else max(strike - fwd, 0.0)
    d = (fwd - strike) / (vol * math.sqrt(t))
    return (fwd - strike) * _big_phi(d) + vol * math.sqrt(t) * _phi(d) if call else (strike - fwd) * _big_phi(-d) + vol * math.sqrt(t) * _phi(d)


def cap_floor(tid, notional, ccy, start, mat, buy, terms, cal, call):
    disc = curve(ccy)
    strike = terms["strike"]
    pay_m = 3 if terms.get("capFrequency", terms.get("floorFrequency")) == "Quarterly" else 6
    tenor = 3 if pay_m == 3 else 6
    index = index_name(ccy, tenor)
    sign = 1 if buy else -1
    known = published(ccy, tenor)
    vol = NORMAL_VOL_BP / 1e4
    flows = []
    for i, (a, b) in enumerate(schedule(start, mat, FREQ_OF[pay_m], cal), 1):
        paydate = cal.add_business_days(b, 2)
        fix = cal.add_business_days(a, -2)
        fixed = fix <= N.AS_OF
        fwd = known(fix) if fixed else disc.forward(max(a, N.AS_OF), b, 360.0)
        tau = year_fraction(a, b, "ACT/360")
        if fixed:
            unit = max(fwd - strike, 0.0) if call else max(strike - fwd, 0.0)
        else:
            unit = bachelier(fwd, strike, vol, (fix - N.AS_OF).days / 365.0, call)
        amount = sign * notional * unit * tau
        df = disc.df(paydate) if paydate > N.AS_OF else 1.0
        flows.append({"n": i, "type": "Caplet" if call else "Floorlet", "periodStart": iso(a), "periodEnd": iso(b), "fixingDate": iso(fix),
                      "payDate": iso(paydate), "notional": notional, "index": index, "strike": strike, "rate": round(fwd, 6),
                      "accrualFactor": round(tau, 6), "volatilityBp": None if fixed else NORMAL_VOL_BP, "amount": round(amount, 2),
                      "discountFactor": round(df, 6), "pv": round(amount * df if paydate > N.AS_OF else 0.0, 2),
                      "status": "Settled" if paydate <= N.AS_OF else "Fixed" if fixed else "Projected",
                      "fixings": [{"fixingDate": iso(fix), "index": index, "tenor": f"{tenor}M", "rate": round(fwd, 6),
                                   "source": source_of(ccy, tenor) if fixed else "Forward curve", "status": "Fixed" if fixed else "Projected"}]})
    leg = {"leg": 1, "label": f"{'Buy' if buy else 'Sell'} {'cap' if call else 'floor'} {strike * 100:.4f}% on {index}", "payer": not buy,
           "type": "CAPLETS" if call else "FLOORLETS", "currency": ccy, "notional": notional, "index": index, "spread": 0.0, "strike": strike,
           "dayCount": "ACT/360", "frequency": FREQ_OF[pay_m], "resetFrequency": FREQ_OF[pay_m], "businessDayConvention": "Mod. following",
           "calendar": cal.name, "paymentLag": "2 business days", "cashflows": flows}
    leg["rateType"] = "Option"
    leg["payReceive"] = "Receive" if buy else "Pay"
    leg["legId"] = f"{tid}-L1"
    finish(leg)
    rows = [{"fixingDate": f["fixingDate"], "period": f"{f['periodStart']}..{f['periodEnd']}", "index": index, "rate": f["rate"],
             "payoff": f["amount"], "status": "Fixed" if f["status"] in ("Fixed", "Settled") else "Pending"} for f in flows]
    return {"legs": [leg], "mtm": round(leg["pv"]), "schedule": rows}


# ---- European swaption ----------------------------------------------------------------------------------------------------
def swaption(tid, notional, ccy, trade_date, buy, terms, cal):
    expiry = cal.adjust(date.fromisoformat(terms["expiryDate"]))
    terms["expiryDate"] = iso(expiry)
    start = cal.adjust(expiry + timedelta(days=2))
    mat = cal.adjust(add_months(start, round(tenor_years(terms["underlyingTenor"]) * 12)))
    disc = curve(ccy)
    payer = terms["payerReceiver"] == "Payer"
    flt = float_leg(2, not payer, notional, ccy, 3, 0.0, start, mat, 3, cal, disc)
    fix_probe = fixed_leg(1, payer, notional, ccy, terms["strike"], start, mat, 6, cal, disc)
    annuity = sum(f["accrualFactor"] * f["discountFactor"] for f in fix_probe["cashflows"]) or 1.0
    swap_rate = sum(f["rate"] * f["accrualFactor"] * f["discountFactor"] for f in flt["cashflows"]) / annuity
    t = max((expiry - N.AS_OF).days / 365.0, 0.0)
    unit = bachelier(swap_rate, terms["strike"], NORMAL_VOL_BP / 1e4, t, payer)
    price = notional * annuity * unit
    mtm = round((1 if buy else -1) * price * (disc.df(expiry) if expiry > N.AS_OF else 1.0))
    exercise = {"style": "European", "expiryDate": iso(expiry), "noticeDate": iso(cal.add_business_days(expiry, -2)),
                "settlement": terms["settlement"], "premium": terms["premium"], "premiumPayDate": iso(cal.add_business_days(trade_date, 2)),
                "volatilityBp": NORMAL_VOL_BP, "forwardSwapRate": round(swap_rate, 6), "underlyingTenor": terms["underlyingTenor"],
                "underlyingStart": iso(start), "underlyingMaturity": iso(mat), "status": "Open" if expiry > N.AS_OF else "Expired"}
    return {"legs": [_meta(fix_probe, tid, cal), _meta(flt, tid, cal)], "mtm": mtm, "exercise": exercise, "maturity": expiry}


# ---- multi-leg commodity swap -----------------------------------------------------------------------------------------------
def commodity_swap(tid, ctx, terms, r):
    code = ctx["commodity"]
    name, unit, exch, _sector, spot, _vol = N.COMMODITIES[code]
    cal = Calendar.of("USNY")
    disc = curve("USD")
    first = date(N.AS_OF.year, N.AS_OF.month, 1)
    first = add_months(first, -r.randint(0, 5))
    n_periods = r.choice([6, 9, 12])
    qty = max(100, round(terms["quantityPerPeriod"] / max(1.0, spot / 50), -2))      # a gold swap trades fewer units than a corn swap
    terms["quantityPerPeriod"] = qty
    fixed_price = round(spot * (1 + r.uniform(-0.04, 0.05)), 2)
    terms["fixedPrice"] = fixed_price
    index = f"{exch} {name} settle"
    fee = round(max(spot * r.uniform(0.0015, 0.004), 0.01), 3)
    buyer = r.random() < 0.5                                   # we pay the fixed price and receive the index average
    periods = []
    for k in range(n_periods):
        a, b = add_months(first, k), add_months(first, k + 1)
        periods.append((a, b, cal.add_business_days(b - timedelta(days=1), 5)))

    def settle(d: date) -> float:
        return round(spot * (1 + 0.035 * math.sin(d.toordinal() / 23) + 0.0006 * (d - N.AS_OF).days / 30), 2)

    def flows(kind, price_of, sign, fixings_of=None):
        out = []
        for i, (a, b, paydate) in enumerate(periods, 1):
            fx = fixings_of(a, b) if fixings_of else None
            price = round(sum(x["rate"] for x in fx) / len(fx), 4) if fx else price_of
            amount = sign * qty * price
            df = disc.df(paydate) if paydate > N.AS_OF else 1.0
            done = [x["status"] == "Fixed" for x in fx] if fx else [True]
            status = "Settled" if paydate <= N.AS_OF else "Fixed" if all(done) else "Projected" if not any(done) else "Partially fixed"
            cf = {"n": i, "type": kind, "periodStart": iso(a), "periodEnd": iso(b - timedelta(days=1)), "payDate": iso(paydate),
                  "quantity": qty, "rate": price, "accrualFactor": 1.0, "amount": round(amount, 2), "discountFactor": round(df, 6),
                  "pv": round(amount * df if paydate > N.AS_OF else 0.0, 2), "status": status}
            if fx:
                cf["fixings"] = fx
            out.append(cf)
        return out

    def daily(a, b):
        out = []
        d = a
        while d < b:
            if cal.is_business_day(d):
                fixed = d <= N.AS_OF
                out.append({"fixingDate": iso(d), "index": index, "tenor": "1D", "rate": settle(d), "source": f"{exch} settlement" if fixed else "Forward curve",
                            "status": "Fixed" if fixed else "Projected"})
            d += timedelta(days=1)
        return out

    common = {"currency": "USD", "dayCount": "Actual", "frequency": "Monthly", "businessDayConvention": "Following", "calendar": cal.name,
              "paymentLag": "5 business days", "quantity": qty, "quantityUnit": unit.split("/")[1], "priceUnit": unit}
    legs = [
        {"leg": 1, "label": f"{'Pay' if buyer else 'Receive'} fixed {fixed_price:,.2f} {unit}", "payer": buyer, "type": "FIXED", "notional": round(qty * fixed_price),
         "rate": fixed_price, "index": None, "spread": 0.0, **common, "cashflows": flows("Fixed price", fixed_price, -1 if buyer else 1)},
        {"leg": 2, "label": f"{'Receive' if buyer else 'Pay'} {index} monthly average", "payer": not buyer, "type": "FLOAT", "notional": round(qty * spot),
         "index": index, "spread": 0.0, "averaging": terms["averaging"], "resetFrequency": "Daily", **common,
         "cashflows": flows("Index average", None, 1 if buyer else -1, daily)},
        {"leg": 3, "label": f"Pay fee {fee:.3f} {unit}", "payer": True, "type": "FEE", "notional": round(qty * fee), "rate": fee, "index": None,
         "spread": 0.0, **common, "cashflows": flows("Fee", fee, -1)}]
    if r.random() < 0.34:                                      # some trades also carry a location differential
        diff = round(spot * r.uniform(-0.03, 0.03), 3)
        legs.append({"leg": 4, "label": f"{'Receive' if diff > 0 else 'Pay'} location differential {diff:+.3f} {unit}", "payer": diff <= 0, "type": "DIFFERENTIAL",
                     "notional": round(qty * abs(diff)), "rate": diff, "index": None, "spread": 0.0, **common, "cashflows": flows("Differential", diff, 1)})
    for x in legs:
        x["rateType"] = x["type"].title()
        x["legId"] = f"{tid}-L{x['leg']}"
        x["payReceive"] = "Pay" if x["payer"] else "Receive"
        finish(x)
    last = periods[-1][1] - timedelta(days=1)
    return {"legs": legs, "mtm": round(sum(x["pv"] for x in legs)), "effective": cal.adjust(first), "maturity": last,
            "trade_date": cal.adjust(first - timedelta(days=r.randint(5, 40)), "PRECEDING")}


def live_fixing(legs: list[dict]) -> tuple[str, float] | None:
    """The path of the fixing a live demo moves (_meta.walk) and its step: the last still-projected fixing of the first floating
    cashflow that has one. None when no leg has a projected fixing."""
    for li, leg in enumerate(legs):
        for ci, cf in enumerate(leg["cashflows"]):
            fixes = cf.get("fixings") or []
            for fi in range(len(fixes) - 1, -1, -1):
                if fixes[fi]["status"] == "Projected":
                    return f"legs.{li}.cashflows.{ci}.fixings.{fi}.rate", round(max(abs(fixes[fi]["rate"]) * 0.004, 0.00005), 6)
    return None


def build(p, tid: str, notional, ccy, start, mat, pay, terms, ctx, trade_date):
    """The deep legs of product p's trade, or None for a product this module does not build. A dict with legs, mtm and optionally
    schedule, exercise, and the dates (effective, maturity, trade_date) the structure fixes. May fill terms to agree with the legs."""
    if p.code not in DEEP:
        return None
    r = M.rng(tid + "/deep")
    cal = Calendar.of(N.CCY[ccy][2])
    if p.code == "BASIS_SWAP":
        return basis_swap(tid, notional, ccy, start, mat, pay, terms, cal)
    if p.code == "XCCY_SWAP":
        return xccy_swap(tid, notional, ccy, start, mat, pay, terms, cal)
    if p.code in ("CAP", "FLOOR"):
        return cap_floor(tid, notional, ccy, start, mat, pay, terms, cal, p.code == "CAP")
    if p.code == "SWAPTION_EUR":
        return swaption(tid, notional, ccy, trade_date, pay, terms, cal)
    return commodity_swap(tid, ctx, terms, r)
