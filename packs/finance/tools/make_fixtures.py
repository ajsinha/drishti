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

"""Generates the finance pack's sample documents: the four reference entities of the mockups, the entities they
link to, and the rest of the Northbridge netting set. Documents look like what a trade store, a curve builder
and a counterparty master would really hold: identifiers with check digits (LEI, UTI, UPI, ISIN), full
calculation periods with fixings and PVs, execution, lifecycle, confirmation, clearing, regulatory,
settlement and valuation blocks. The values the mockups show are kept exactly; everything else is derived
consistently from them (the thin trades' fixed rates are solved so their cashflows reprice to their MTM).

Run from the repository root:  python3 packs/finance/tools/make_fixtures.py
"""
import json
import math
import pathlib
import sys
from datetime import date

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools"))
from samplegen import blocks as B, ids, legs as L  # noqa: E402
from samplegen.curves import Curve  # noqa: E402
from samplegen.dates import Calendar, add_tenor, iso  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parent.parent / "samples"
AS_OF = date(2026, 9, 30)
USNY, TARGET, LDN_NY = Calendar.of("USNY"), Calendar.of("EUTA"), Calendar.of("GBLO", "USNY")
catalog = []


def put(kind, id_, doc, source, gen, title, subtitle, live=False):
    doc["_meta"] = {"source": source, "generation": gen, "live": live}
    d = OUT / kind
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{id_}.json").write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n")
    catalog.append({"kind": kind, "id": id_, "title": title, "subtitle": subtitle})


# ---- market data -------------------------------------------------------------------------------------------
SOFR_T = ["1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"]
SOFR_R = [3.93, 3.88, 3.80, 3.70, 3.58, 3.54, 3.60, 3.66, 3.80]
ESTR_T = ["1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y"]
ESTR_R = [2.02, 2.00, 1.97, 1.95, 2.01, 2.22, 2.51]
USD = Curve("USD-SOFR", "USD", AS_OF, SOFR_T + ["15Y", "20Y", "30Y"], [r / 100 for r in SOFR_R + [3.92, 3.97, 3.95]])
EUR = Curve("EUR-ESTR", "EUR", AS_OF, ESTR_T + ["20Y", "30Y"], [r / 100 for r in ESTR_R + [2.68, 2.64]])
EURUSD = 1.17400


def sofr_fixing(d: date) -> float:
    """Historical SOFR: a smooth path from 5.31% (2024) down to about 3.94% (today)."""
    years = (AS_OF - d).days / 365
    return round(0.0394 + min(years, 2.2) * 0.0062 + 0.0004 * math.sin(d.toordinal() / 9), 6)


def estr_fixing(d: date) -> float:
    years = (AS_OF - d).days / 365
    return round(0.0193 + min(years, 2.2) * 0.0085 + 0.0003 * math.sin(d.toordinal() / 7), 6)


# ---- parties ----------------------------------------------------------------------------------------------
NB = {"id": "CP-NORTHBRIDGE", "name": "Northbridge Capital LLP"}
HP = {"id": "CP-HARBOR-POINT", "name": "Harbor Point Bank"}
SC = {"id": "CP-SUMMIT", "name": "Summit Clearing LLC"}
US_ENTITY, UK_ENTITY = B.entity("New York"), B.entity("London")


def cp_ref(cp):
    return {**cp, "lei": ids.lei(cp["name"])}


def trade_blocks(trade_id, trade_date, asset, product, ccy, cp, trader, location="New York", cleared=False, conf="MarkitWire",
                 events=None, sales=None):
    ent = B.entity(location)
    return {
        "tradeDate": iso(trade_date), "legalEntity": ent, "execution": B.execution(trade_id, trade_date, asset, trader, sales),
        "lifecycle": B.lifecycle(trade_id, trade_date, AS_OF, events), "confirmation": B.confirmation(trade_id, trade_date, conf),
        "clearing": B.clearing(trade_id, ccy, cleared), "regulatory": B.regulatory(trade_id, ent["lei"], asset, product, ent["country"], cleared),
        "settlementInstructions": B.settlement(ccy, cp["name"]), "trader": trader}


# ---- IRS-48213 (mockup) ------------------------------------------------------------------------------------
def irs_48213():
    leg1 = L.fixed_leg(1, True, 50000000, "USD", 0.0385, date(2026, 10, 2), date(2031, 10, 2), "Annual", "ACT/360", USNY, USD, AS_OF)
    golden = [(-1962430.56, 0.9632, -1890213.11), (-1946388.89, 0.9280, -1806248.89), (-1951736.11, 0.8943, -1745437.60),
              (-1951736.11, 0.8620, -1682396.53), (-1951736.11, 0.8310, -1621892.71)]
    for f, (amount, df, pv) in zip(leg1["cashflows"], golden):
        f.update(amount=amount, df=df, pv=pv)
    leg1["pv"] = round(sum(g[2] for g in golden), 2)
    leg2 = L.float_leg(2, False, 50000000, "USD", "SOFR", 0.0, date(2026, 10, 2), date(2031, 10, 2), "Annual", "ACT/360",
                       USNY, USD, USD, AS_OF, sofr_fixing)
    L.scale_projected(leg2, 8333608.84)
    leg2.update(label="Receive SOFR compounded", nextReset="2026-10-02", projectedFlows=5)
    doc = {
        "tradeId": "IRS-48213", "productType": "IRS", "product": "Interest rate swap", "counterparty": cp_ref(NB),
        "notional": 50000000, "currency": "USD", "direction": "PAY_FIXED", "effectiveDate": "2026-10-02",
        "maturityDate": "2031-10-02", "maturityTenor": "5Y", "fixedRate": 0.0385, "mtm": -412580, "dv01": 22310,
        "book": "RATES-NY-3", "nettingSet": "NS-NORTH-01", "agreement": "ISDA-2002-0417", "csa": "CSA-VM-0417",
        "discountCurve": "USD-SOFR", "index": "SOFR", "legs": [leg1, leg2],
        "dv01ByTenor": [{"tenor": t, "dv01": v} for t, v in [("1Y", 1180), ("2Y", 3420), ("3Y", 4760), ("4Y", 5920), ("5Y", 7030)]],
        "desk": "USD Rates · Swaps", "strategy": "Client hedge: fixed-rate funding swap", "portfolio": "RATES-NY-3/CLIENT",
        "productTaxonomy": {"isda": "InterestRate:IRSwap:FixedFloat", "cfi": "SRCCSP", "fpml": "swap"},
        "fees": [{"type": "Execution fee", "payer": "Us", "amount": 1250.00, "currency": "USD", "date": "2026-10-02"}],
        "risk": {"dv01": 22310, "gamma": -41.2, "vega": 0, "theta": -312, "pv01Discount": 1830, "pv01Projection": 20480},
        "valuation": B.valuation(AS_OF, -412580, "USD", "Multi-curve OIS discounting", ["USD-SOFR"], [leg1["pv"], leg2["pv"]]),
        "pnl": {"today": -18240, "mtd": 61300, "ytd": -412580, "explain": [{"factor": "Rates (curve shift)", "usd": -21510},
                {"factor": "Carry and roll-down", "usd": 3120}, {"factor": "New trades / amendments", "usd": 0}, {"factor": "Unexplained", "usd": 150}]},
        "pnlHistory": B.pnl_history("IRS-48213", AS_OF, -412580),
    }
    doc.update(trade_blocks("IRS-48213", date(2026, 9, 28), "Rates", "IRS Fixed-Float USD SOFR", "USD", NB, "A. Shah", sales="R. Patel"))
    return doc


# ---- a vanilla IRS whose fixed rate reprices it to a known MTM ----------------------------------------------------
def solved_irs(trade_id, notional, maturity, mtm, trade_date, frequency="Annual", trader="A. Shah", events=None, pay_first=True):
    start = USNY.adjust(add_tenor(trade_date, "2D"))
    mat = date.fromisoformat(maturity)
    for pay in ((True, False) if pay_first else (False, True)):
        fixed = L.fixed_leg(1, pay, notional, "USD", 0.035, start, mat, frequency, "ACT/360", USNY, USD, AS_OF)
        flt = L.float_leg(2, not pay, notional, "USD", "SOFR", 0.0, start, mat, frequency, "ACT/360", USNY, USD, USD, AS_OF, sofr_fixing)
        settled = sum(f["pv"] for f in fixed["cashflows"] if f["status"] == "Settled")
        a = L.annuity(fixed) * (-1 if pay else 1)
        rate = (mtm - flt["pv"] - settled) / a
        if 0.005 < rate < 0.07:
            L.reprice_fixed(fixed, round(rate, 6))
            break
    dv = round(abs(L.annuity(fixed)) * 1e-4 * (1 if pay else -1))
    years = (mat - AS_OF).days / 365
    doc = {
        "tradeId": trade_id, "productType": "IRS", "product": "Interest rate swap", "counterparty": cp_ref(NB),
        "notional": notional, "currency": "USD", "direction": "PAY_FIXED" if pay else "RECEIVE_FIXED",
        "effectiveDate": iso(start), "maturityDate": maturity, "maturityTenor": f"{round((mat - start).days / 365)}Y",
        "fixedRate": fixed["rate"], "mtm": mtm, "dv01": dv, "book": "RATES-NY-3", "nettingSet": "NS-NORTH-01",
        "agreement": "ISDA-2002-0417", "csa": "CSA-VM-0417", "discountCurve": "USD-SOFR", "index": "SOFR",
        "legs": [fixed, flt], "dv01ByTenor": L.dv01_by_tenor(dv, ["1Y", "2Y", "3Y", "5Y", "7Y", "10Y"], years),
        "desk": "USD Rates · Swaps", "portfolio": "RATES-NY-3/CLIENT",
        "productTaxonomy": {"isda": "InterestRate:IRSwap:FixedFloat", "cfi": "SRCCSP", "fpml": "swap"},
        "risk": {"dv01": dv, "gamma": round(-dv * 0.0019, 1), "vega": 0, "theta": round(-abs(mtm) * 0.0004)},
        "valuation": B.valuation(AS_OF, mtm, "USD", "Multi-curve OIS discounting", ["USD-SOFR"],
                                 [round(fixed["pv"] + (fixed["cashflows"] and 0), 2), flt["pv"]]),
        "pnlHistory": B.pnl_history(trade_id, AS_OF, mtm),
    }
    doc.update(trade_blocks(trade_id, trade_date, "Rates", "IRS Fixed-Float USD SOFR", "USD", NB, trader, events=events))
    return doc


# ---- IRS-47102: the same economics from another booking system (FpML-style), so it has no Sutra ------------------
def irs_47102():
    trade_date, mat = date(2023, 3, 13), date(2033, 3, 15)
    doc = solved_irs("IRS-47102", 80000000, "2033-03-15", 1106420, trade_date, "Semi-annual", "M. Okafor")
    fixed, flt = doc.pop("legs")
    for k in ("dv01ByTenor", "direction", "fixedRate", "index", "discountCurve", "maturityTenor"):
        doc.pop(k, None)

    def stream(leg, payer):
        return {"payerPartyReference": payer, "receiverPartyReference": "PARTY-B" if payer == "PARTY-A" else "PARTY-A",
                "calculationPeriodDates": {"effectiveDate": leg["cashflows"][0]["accrualStart"], "terminationDate": iso(mat),
                                           "frequency": leg["frequency"], "businessDayConvention": "MODFOLLOWING", "businessCenters": ["USNY"]},
                "calculation": {"notional": leg["notional"], "currency": "USD", "dayCountFraction": leg["dayCount"],
                                **({"fixedRate": leg["rate"]} if leg["type"] == "FIXED" else
                                   {"floatingRateIndex": "USD-SOFR-OIS Compound", "spread": 0.0, "lookbackDays": 2})},
                "paymentSchedule": [{"adjustedPaymentDate": f["payDate"], "periodStart": f["accrualStart"], "periodEnd": f["accrualEnd"],
                                     "rate": f["rate"], "amount": f["amount"], "presentValue": f["pv"], "state": f["status"]}
                                    for f in leg["cashflows"]]}

    doc["swapStream"] = [stream(fixed, "PARTY-A" if fixed["payer"] else "PARTY-B"), stream(flt, "PARTY-A" if flt["payer"] else "PARTY-B")]
    doc["partyReferences"] = {"PARTY-A": {"lei": US_ENTITY["lei"], "name": US_ENTITY["name"]}, "PARTY-B": {"lei": ids.lei(NB["name"]), "name": NB["name"]}}
    doc["product"] = "Interest rate swap"
    doc["sourceFormat"] = "FpML 5.12 confirmation view"
    lead = ["tradeId", "productType", "product", "counterparty", "notional", "currency", "effectiveDate", "maturityDate", "mtm", "dv01",
            "book", "nettingSet", "agreement", "csa", "swapStream", "partyReferences"]
    return {**{k: doc[k] for k in lead if k in doc}, **{k: v for k, v in doc.items() if k not in lead}}


# ---- XCS-11820: EUR/USD cross-currency swap, basis solved to the MTM ------------------------------------------------
def xcs_11820():
    start, mat, n_eur = date(2025, 6, 20), date(2030, 6, 20), 40000000
    n_usd = round(n_eur * 1.0850)
    cal = Calendar.of("EUTA", "USNY")
    eur = L.float_leg(1, True, n_eur, "EUR", "ESTR", 0.0, start, mat, "Quarterly", "ACT/360", cal, EUR, EUR, AS_OF, estr_fixing)
    usd = L.float_leg(2, False, n_usd, "USD", "SOFR", 0.0, start, mat, "Quarterly", "ACT/360", cal, USD, USD, AS_OF, sofr_fixing)
    exch = [{"leg": "EUR", "date": iso(start), "amount": n_eur, "status": "Settled"}, {"leg": "USD", "date": iso(start), "amount": -n_usd, "status": "Settled"},
            {"leg": "EUR", "date": iso(mat), "amount": -n_eur, "df": round(EUR.df(mat), 6), "status": "Projected"},
            {"leg": "USD", "date": iso(mat), "amount": n_usd, "df": round(USD.df(mat), 6), "status": "Projected"}]
    principal_usd = -n_eur * EUR.df(mat) * EURUSD + n_usd * USD.df(mat)
    target = -2318900
    eur_ann = L.annuity(eur) * EURUSD
    spread = (principal_usd + eur["pv"] * EURUSD + usd["pv"] - target) / eur_ann
    eur = L.float_leg(1, True, n_eur, "EUR", "ESTR", round(spread, 6), start, mat, "Quarterly", "ACT/360", cal, EUR, EUR, AS_OF, estr_fixing)
    doc = {"tradeId": "XCS-11820", "productType": "XCS", "product": "Cross-currency swap", "counterparty": cp_ref(NB),
           "notional": n_eur, "currency": "EUR", "otherNotional": n_usd, "otherCurrency": "USD", "fxRateAtInception": 1.0850,
           "direction": "PAY_EUR", "effectiveDate": iso(start), "maturityDate": iso(mat), "maturityTenor": "5Y",
           "principalExchange": "Initial and final", "resetting": False, "xccyBasis": round(spread * 1e4, 2),
           "mtm": target, "book": "RATES-NY-3", "nettingSet": "NS-NORTH-01", "agreement": "ISDA-2002-0417", "csa": "CSA-VM-0417",
           "discountCurves": ["EUR-ESTR", "USD-SOFR"], "fxSpot": "EURUSD", "legs": [eur, usd], "principalFlows": exch,
           "risk": {"dv01": 4180, "fxDelta": round(-n_eur * EUR.df(mat) * 0.01 * EURUSD), "basis01": -18650, "gamma": -6.1},
           "valuation": B.valuation(AS_OF, target, "USD", "Cross-currency OIS discounting (USD CSA)", ["EUR-ESTR", "USD-SOFR", "EURUSD"],
                                    [round(eur["pv"] * EURUSD), usd["pv"]]),
           "pnlHistory": B.pnl_history("XCS-11820", AS_OF, target), "desk": "USD Rates · Cross-currency"}
    doc.update(trade_blocks("XCS-11820", date(2025, 6, 18), "Rates", "XCCY Float-Float EUR ESTR USD SOFR", "USD", NB, "J. Weiss"))
    return doc


# ---- SWPT-3301: 1Y x 5Y payer swaption ----------------------------------------------------------------------------
def swpt_3301():
    expiry, trade_date = date(2027, 9, 30), date(2026, 9, 25)
    start = USNY.add_business_days(expiry, 2)
    und = L.fixed_leg(1, True, 25000000, "USD", 0.0375, start, add_tenor(start, "5Y"), "Annual", "ACT/360", USNY, USD, AS_OF)
    fwd = (USD.df(start) - USD.df(add_tenor(start, "5Y"))) / (L.annuity(und) / 25000000)
    vol_bp, t = 96.5, (expiry - AS_OF).days / 365
    annuity = L.annuity(und)
    doc = {"tradeId": "SWPT-3301", "productType": "SWPT", "product": "Swaption", "counterparty": cp_ref(NB),
           "notional": 25000000, "currency": "USD", "direction": "BUY", "optionType": "Payer", "exerciseStyle": "European",
           "expiryDate": iso(expiry), "maturityDate": "2027-09-30", "strike": 0.0375, "settlement": "Physical",
           "underlying": {"effectiveDate": iso(start), "terminationDate": iso(add_tenor(start, "5Y")), "tenor": "5Y", "fixedRate": 0.0375,
                          "fixedFrequency": "Annual", "fixedDayCount": "ACT/360", "floatIndex": "SOFR", "floatFrequency": "Annual",
                          "forwardSwapRate": round(fwd, 6), "annuity": round(annuity, 2)},
           "premium": {"amount": 412500.00, "currency": "USD", "paymentDate": "2026-09-29", "status": "Settled"},
           "volatility": {"type": "Normal", "value": vol_bp, "unit": "bp", "surface": "USD-SWPT-NVOL", "point": "1Y x 5Y"},
           "mtm": 340760, "book": "RATES-NY-3", "nettingSet": "NS-NORTH-01", "agreement": "ISDA-2002-0417", "csa": "CSA-VM-0417",
           "discountCurve": "USD-SOFR", "exerciseSchedule": [{"date": iso(expiry), "noticeDeadline": iso(expiry) + " 11:00 New York", "status": "Pending"}],
           "risk": {"delta": round(annuity * 1e-4 * 0.46), "dv01": round(annuity * 1e-4 * 0.46), "vega": round(annuity * math.sqrt(t) * 0.3989 * 1e-4),
                    "gamma": 3.8, "theta": -742},
           "valuation": B.valuation(AS_OF, 340760, "USD", "Bachelier (normal) on the forward swap rate", ["USD-SOFR", "USD-SWPT-NVOL"]),
           "pnlHistory": B.pnl_history("SWPT-3301", AS_OF, 340760), "desk": "USD Rates · Options"}
    doc.update(trade_blocks("SWPT-3301", trade_date, "Rates", "Swaption Payer European USD SOFR", "USD", NB, "E. Novak"))
    return doc


# ---- FXS-20931 (mockup) ----------------------------------------------------------------------------------------
def fx_swap():
    doc = {
        "tradeId": "FXS-20931", "productType": "FXSWAP", "product": "FX swap", "counterparty": cp_ref(HP), "pair": "EUR/USD",
        "nearValue": "2026-10-02", "farValue": "2027-01-04", "nearRate": 1.17400, "farRate": 1.17958, "swapPoints": 55.8,
        "mtm": 18420, "book": "FX-LDN-2", "nettingSet": "NS-HARB-02", "csa": "CSA-VM-0288", "agreement": "ISDA-2002-0288", "spot": "EURUSD",
        "discountCurves": ["EUR-ESTR", "USD-SOFR"], "forwardCurve": "EURUSD-FWD", "direction": "Buy/sell EUR",
        "legs": [
            {"leg": "Near", "valueDate": "2026-10-02", "buy": {"ccy": "EUR", "amount": 25000000.00},
             "sell": {"ccy": "USD", "amount": 29350000.00}, "rate": 1.17400, "settlement": "CLS"},
            {"leg": "Far", "valueDate": "2027-01-04", "sell": {"ccy": "EUR", "amount": 25000000.00},
             "buy": {"ccy": "USD", "amount": 29489500.00}, "rate": 1.17958, "settlement": "CLS"}],
        "cashflows": [
            {"payDate": "2026-10-02", "leg": "Near", "currency": "EUR", "direction": "Receive", "amount": 25000000.00, "df": 0.9998, "pvUsd": 29344130},
            {"payDate": "2026-10-02", "leg": "Near", "currency": "USD", "direction": "Pay", "amount": -29350000.00, "df": 0.9997, "pvUsd": -29341200},
            {"payDate": "2027-01-04", "leg": "Far", "currency": "EUR", "direction": "Pay", "amount": -25000000.00, "df": 0.9947, "pvUsd": -29194450},
            {"payDate": "2027-01-04", "leg": "Far", "currency": "USD", "direction": "Receive", "amount": 29489500.00, "df": 0.9906, "pvUsd": 29209940}],
        "confirmation": {"status": "Confirmed", "matched": "2026-09-30 13:41", "clsEligible": True, "trader": "L. Moreau",
                         "method": "SWIFT MT300", "platformId": "MT300-" + ids.uti(UK_ENTITY["lei"], "FXS-20931")[-10:], "breaks": []},
        "risk": [{"factor": "EUR IR", "usd": -610}, {"factor": "USD IR", "usd": 730}, {"factor": "FX Δ", "usd": 2150}],
        "spotRateAtTrade": 1.17385, "swapPointsMid": 55.6, "salesMargin": {"pips": 0.2, "usd": 500},
        "valuation": B.valuation(AS_OF, 18420, "USD", "FX forward: discounted cashflows (CSA USD)", ["EUR-ESTR", "USD-SOFR", "EURUSD-FWD"],
                                 [29344130 - 29341200, -29194450 + 29209940]),
        "pnlHistory": B.pnl_history("FXS-20931", AS_OF, 18420), "desk": "G10 FX · Swaps", "strategy": "Client funding roll",
    }
    blocks = trade_blocks("FXS-20931", date(2026, 9, 30), "FX", "FX Swap EUR USD", "USD", HP, "L. Moreau", "London", conf="SWIFT MT300",
                          sales="C. Dubois")
    blocks.pop("confirmation")
    blocks["settlementInstructions"] = {**B.settlement("EUR", HP["name"], "CLS"), "clsMember": True, "settlementSession": "CLS 07:00-09:00 CET"}
    doc.update(blocks)
    return doc


# ---- CFT-77120 (mockup) ----------------------------------------------------------------------------------------
def future():
    settles = [("2026-09-24", 69.10, 0.68), ("2026-09-25", 69.85, 0.75), ("2026-09-28", 70.40, 0.55),
               ("2026-09-29", 70.62, 0.22), ("2026-09-30", 71.15, 0.53)]
    cum, ladder = 0, []
    for d, px, ch in settles:
        vm = round(ch * 150 * 1000)
        cum += vm
        ladder.append({"date": d, "settle": px, "change": ch, "variationMargin": vm, "cumulative": cum, "intraday": d == "2026-09-30"})
    fills = [{"fillId": f"F{i}", "time": t, "lots": q, "price": px, "venue": "CME Globex", "mic": "XNYM"}
             for i, (t, q, px) in enumerate([("2026-09-23T14:02:11.215Z", 60, 68.39), ("2026-09-23T14:02:11.418Z", 50, 68.42),
                                             ("2026-09-23T14:05:37.004Z", 40, 68.46)], 1)]
    doc = {
        "tradeId": "CFT-77120", "productType": "FUT", "product": "Commodity future", "counterparty": cp_ref(SC),
        "contract": "NYMEX CL Z6", "contractSpec": "CL-Z6", "direction": "Long", "lots": 150, "tradePrice": 68.42,
        "lastPrice": 71.15, "mtm": 409500, "lastTrade": "2026-11-20", "book": "COMM-NY-1", "clearingAccount": "CLR-NY-07",
        "forwardCurve": "WTI-NYMEX",
        "contractTerms": {"exchange": "NYMEX", "product": "WTI crude oil (CL)", "deliveryMonth": "December 2026",
                          "contractSize": "1,000 bbl", "priceUnit": "USD/bbl", "tick": "0.01 = USD 10", "settlement": "Physical"},
        "settlements": ladder,
        "margin": {"initialMargin": "[IM PER LOT] × 150", "variationMarginToday": 79500, "deltaBbl": 150000, "usdPerDollarMove": 150000},
        "instrument": {"exchangeCode": "CLZ6", "bloombergTicker": "CLZ6 Comdty", "isin": ids.isin("US", "CLZ620260"), "cfi": "FCECPX",
                       "firstNotice": "2026-11-23", "lastTradingDay": "2026-11-20", "deliveryLocation": "Cushing, Oklahoma"},
        "fills": fills, "averagePrice": 68.42, "positionLimitUsage": {"limit": 6000, "used": 150, "regime": "CFTC spot-month (Part 150)"},
        "risk": {"delta": 150000, "deltaUnit": "bbl", "dv01": 0, "commodityDelta": 150000},
        "valuation": B.valuation(AS_OF, 409500, "USD", "Exchange settlement price", ["WTI-NYMEX"]),
        "pnlHistory": [{"date": d["date"], "pnl": d["variationMargin"]} for d in ladder], "desk": "Energy · Crude futures",
    }
    blocks = trade_blocks("CFT-77120", date(2026, 9, 23), "Commodity", "Future WTI NYMEX", "USD", SC, "T. Brennan", "Chicago", cleared=True)
    blocks["clearing"] = {"status": "Cleared", "mandatory": True, "ccp": "CME Clearing", "clearingBroker": SC["name"], "account": "CLR-NY-07",
                          "ccpTradeId": "CME" + ids.uti(US_ENTITY["lei"], "CFT-77120")[-9:], "origin": "House"}
    blocks.pop("confirmation")
    doc.update(blocks)
    return doc


# ---- netting sets --------------------------------------------------------------------------------------------
def netting_sets():
    members = [("IRS-48213", "Interest rate swap", "USD", 50000000, "2031-10-02", -412580),
               ("IRS-47102", "Interest rate swap", "USD", 80000000, "2033-03-15", 1106420),
               ("XCS-11820", "Cross-currency swap", "EUR", 40000000, "2030-06-20", -2318900),
               ("SWPT-3301", "Swaption", "USD", 25000000, "2027-09-30", 340760)]
    extra = [("IRS-4%d" % (7200 + i), "Interest rate swap", "USD", (10 + 5 * i) * 1000000, "20%d-0%d-15" % (28 + i % 6, 1 + i % 9),
              [2210, -1840, 1500, -960, 480, 2210, -1310, 90, -330, 390][i] * 10) for i in range(10)]
    all_members = members + extra
    net = sum(m[5] for m in all_members)
    put("trade", "IRS-47102", irs_47102(), "feed-file", 311, "IRS-47102", "Interest rate swap · Northbridge Capital · USD 80m")
    put("trade", "XCS-11820", xcs_11820(), "aero-risk", 1742, "XCS-11820", "Cross-currency swap · Northbridge Capital · EUR 40m")
    put("trade", "SWPT-3301", swpt_3301(), "aero-risk", 1742, "SWPT-3301", "Swaption · Northbridge Capital · USD 25m")
    for i, (t, p, c, n, m, v) in enumerate(extra):
        td = date(2023 + i % 3, 1 + (i * 5) % 12, 10 + i)
        ev = [("Amendment", "Book transfer RATES-NY-1 to RATES-NY-3")] if i % 4 == 1 else None
        put("trade", t, solved_irs(t, n, m, v, td, "Annual" if i % 2 else "Semi-annual", ["A. Shah", "M. Okafor", "E. Novak"][i % 3], ev, pay_first=i % 3 != 1),
            "aero-risk", 1742, t, "%s · Northbridge Capital · %s %dm" % (p, c, n // 1000000))
    profile = [("0", 1.2e6, 3.1e6), ("3M", 2.5e6, 5.8e6), ("6M", 3.1e6, 7.2e6), ("1Y", 3.8e6, 8.8e6), ("2Y", 4.1e6, 9.8e6),
               ("3Y", 3.7e6, 8.9e6), ("5Y", 2.6e6, 6.4e6), ("7Y", 1.1e6, 3.0e6), ("10Y", 0.4e6, 1.0e6)]
    put("netting-set", "NS-NORTH-01", {
        "nettingSetId": "NS-NORTH-01", "counterparty": cp_ref(NB), "agreement": "ISDA-2002-0417", "csa": "CSA-VM-0417",
        "creditLimit": "LIM-NORTH", "trades": len(all_members), "netMtm": net, "collateralPosted": 1300000,
        "eePeak": 4100000, "pfe95Peak": 9800000, "limit": 15000000, "utilisation": 0.65, "asOf": "14:01 NY",
        "exposure": [{"tenor": t, "ee": ee, "pfe95": pfe} for t, ee, pfe in profile],
        "memberTrades": [{"trade": t, "product": p, "currency": c, "notional": n, "maturity": m, "mtm": v} for t, p, c, n, m, v in all_members],
        "csaTerms": {"marginType": "Variation", "threshold": 0, "minTransfer": "USD 250,000", "independentAmount": 0,
                     "eligibleCollateral": "USD cash", "frequency": "Daily", "mpor": "10 days", "rounding": "USD 10,000"},
        "collateral": {"posted": "USD 1,300,000", "callToday": "none", "disputes": "none", "lastMovement": "2026-09-29"},
        "legalEntity": US_ENTITY, "nettingEnforceable": True, "nettingOpinion": "ISDA netting opinion, England and Wales (2025)",
        "metrics": {"epe": 2.9e6, "eepe": 3.4e6, "ead": round(1.4 * (max(net - 1300000, 0) + 5.1e6)), "cva": -38400, "saccr": {
            "replacementCost": max(net - 1300000, 0), "pfeAddOn": 5.1e6, "multiplier": 1.0, "alpha": 1.4}},
        "marginCalls": [{"date": d, "direction": dr, "amount": a, "status": st} for d, dr, a, st in
                        [("2026-09-29", "We call", 410000, "Settled"), ("2026-09-24", "They call", 260000, "Settled"),
                         ("2026-09-18", "We call", 530000, "Settled")]],
        "simulation": {"model": "Hull-White 1F + GBM FX", "paths": 5000, "timeSteps": 120, "runId": "PFE-20260930-02", "confidence": 0.95},
    }, "aero-risk", 1742, "NS-NORTH-01", "Netting set · Northbridge Capital · %d trades" % len(all_members), live=True)
    put("netting-set", "NS-HARB-02", {
        "nettingSetId": "NS-HARB-02", "counterparty": cp_ref(HP), "agreement": "ISDA-2002-0288", "csa": "CSA-VM-0288", "trades": 1,
        "netMtm": 18420, "eePeak": 900000, "pfe95Peak": 2100000, "limit": 25000000, "utilisation": 0.08, "collateralPosted": 0,
        "exposure": [{"tenor": t, "ee": ee, "pfe95": pf} for t, ee, pf in [("0", 2.0e4, 6.0e4), ("1M", 3.1e5, 1.2e6), ("3M", 9.0e5, 2.1e6)]],
        "memberTrades": [{"trade": "FXS-20931", "product": "FX swap", "currency": "EUR", "notional": 25000000, "maturity": "2027-01-04", "mtm": 18420}],
        "legalEntity": UK_ENTITY, "nettingEnforceable": True},
        "aero-risk", 1742, "NS-HARB-02", "Netting set · Harbor Point Bank · 1 trade")
    return all_members


# ---- reference entities --------------------------------------------------------------------------------------
def reference(members):
    usd_pillars = USD.pillars({"1M": "SOFR OIS", "3M": "SR3 future", "6M": "SR3 future", "1Y": "SOFR OIS"})
    put("curve", "USD-SOFR", {"curveId": "USD-SOFR", "currency": "USD", "index": "SOFR", "status": "live", "unit": "%",
                              "points": [{"tenor": t, "rate": r} for t, r in zip(SOFR_T, SOFR_R)],
                              "purpose": "Discounting and projection (single-curve OIS)", "asOf": iso(AS_OF), "builtAt": "2026-09-30T18:02:41Z",
                              "interpolation": "Log-linear on discount factors", "extrapolation": "Flat forward", "dayCount": "ACT/365F",
                              "calendar": "USNY", "pillars": usd_pillars, "sources": ["CME SR3 settlements", "Tradeweb SOFR OIS composite"],
                              "fixingIndex": "SOFR", "quality": {"stale": 0, "outliers": 0, "repricingErrorBp": 0.02}},
        "aero-risk", 1742, "USD-SOFR", "Curve · USD SOFR discount", live=True)
    put("curve", "EUR-ESTR", {"curveId": "EUR-ESTR", "currency": "EUR", "index": "ESTR", "status": "live", "unit": "%",
                              "points": [{"tenor": t, "rate": r} for t, r in zip(ESTR_T, ESTR_R)],
                              "purpose": "EUR discounting (€STR OIS)", "asOf": iso(AS_OF), "builtAt": "2026-09-30T17:31:05Z",
                              "interpolation": "Log-linear on discount factors", "dayCount": "ACT/365F", "calendar": "EUTA",
                              "pillars": EUR.pillars({"1M": "€STR OIS", "3M": "€STR OIS"}), "sources": ["Eurex €STR OIS", "Broker composite"]},
        "aero-fx", 88, "EUR-ESTR", "Curve · EUR €STR discount", live=True)
    fwd = [("ON", 0.6), ("1W", 3.9), ("1M", 17.1), ("2M", 36.3), ("3M", 55.8), ("6M", 110.2), ("9M", 163.5), ("1Y", 222.0)]
    put("curve", "EURUSD-FWD", {"curveId": "EURUSD-FWD", "pair": "EUR/USD", "status": "live", "unit": "pips", "spot": EURUSD,
                                "points": [{"tenor": t, "points": p} for t, p in fwd],
                                "outrights": [{"tenor": t, "valueDate": iso(LDN_NY.adjust(add_tenor(date(2026, 10, 2), t if t != "ON" else "1D"))),
                                               "points": p, "outright": round(EURUSD + p / 1e4, 5)} for t, p in fwd],
                                "spotDate": "2026-10-02", "asOf": iso(AS_OF), "source": "Composite of 360T and FXall mid", "pipFactor": 10000},
        "aero-fx", 88, "EURUSD-FWD", "Curve · EUR/USD forward points", live=True)
    wti = list(zip(["X6", "Z6", "F7", "G7", "H7", "J7", "K7", "M7", "N7", "Q7", "U7", "V7"],
                   [71.62, 71.15, 70.78, 70.45, 70.10, 69.82, 69.55, 69.30, 69.08, 68.90, 68.74, 68.58]))
    put("curve", "WTI-NYMEX", {"curveId": "WTI-NYMEX", "commodity": "WTI crude", "status": "live", "unit": "USD/bbl", "shape": "backwardation",
                               "points": [{"tenor": t, "price": p} for t, p in wti],
                               "contracts": [{"code": "CL" + t, "settle": p, "openInterest": 180000 - 11000 * i, "volume": 240000 // (i + 1)}
                                             for i, (t, p) in enumerate(wti)],
                               "asOf": iso(AS_OF), "source": "NYMEX settlement prices", "settlementTime": "14:30 ET"},
        "eod-futures", 20260930, "WTI-NYMEX", "Curve · WTI crude futures (NYMEX)", live=True)
    put("fx-spot", "EURUSD", {"pair": "EUR/USD", "spot": EURUSD, "status": "live", "bid": 1.17396, "ask": 1.17404, "spotDate": "2026-10-02",
                              "change1d": 0.0021, "high": 1.17522, "low": 1.17108, "source": "Composite (EBS, 360T, FXall)",
                              "timestamp": "2026-09-30T18:00:00Z", "calendar": "GBLO+USNY"}, "aero-fx", 88, "EURUSD", "FX spot · EUR/USD", live=True)
    fixings = []
    d = AS_OF
    while len(fixings) < 20:
        if USNY.is_business_day(d):
            fixings.append({"date": iso(d), "rate": round(sofr_fixing(d) * 100, 4), "volumeBn": round(2150 + (d.day * 37) % 190, 0),
                            "p1": round(sofr_fixing(d) * 100 - 0.03, 2), "p99": round(sofr_fixing(d) * 100 + 0.06, 2)})
        d = date.fromordinal(d.toordinal() - 1)
    put("index", "SOFR", {"index": "SOFR", "name": "Secured Overnight Financing Rate", "fixing": 3.94, "administrator": "FRBNY",
                          "currency": "USD", "tenor": "ON", "dayCount": "ACT/360", "publication": "08:00 New York, next business day",
                          "fixings": fixings, "methodology": "Volume-weighted median of overnight Treasury repo (tri-party, GCF, DVP)",
                          "fallbackFor": "USD LIBOR (ISDA 2020 IBOR Fallbacks Protocol)"},
        "aero-risk", 1742, "SOFR", "Index · Secured Overnight Financing Rate")
    csa = {"marginType": "VM", "threshold": 0, "minTransfer": 250000, "currency": "USD", "independentAmount": 0, "rounding": 10000,
           "valuationAgent": "Us", "valuationTime": "17:00 London", "notificationTime": "13:00 London", "settlementLag": "T+1",
           "interestRate": "USD: SOFR flat, paid monthly", "eligible": [{"asset": "USD cash", "haircut": 0.0},
                                                                        {"asset": "US Treasuries < 1Y", "haircut": 0.005},
                                                                        {"asset": "US Treasuries 1-5Y", "haircut": 0.02},
                                                                        {"asset": "US Treasuries 5-10Y", "haircut": 0.04}],
           "disputeResolution": "ISDA 2016 VM CSA paragraph 12", "mpor": "10 business days"}
    put("csa", "CSA-VM-0417", {"csaId": "CSA-VM-0417", "counterparty": cp_ref(NB), "agreement": "ISDA-2002-0417", **csa,
                               "form": "2016 ISDA Credit Support Annex for Variation Margin (English law)", "signed": "2019-04-17"},
        "aero-risk", 1742, "CSA-VM-0417", "CSA · VM · Northbridge Capital")
    put("csa", "CSA-VM-0288", {"csaId": "CSA-VM-0288", "counterparty": cp_ref(HP), "agreement": "ISDA-2002-0288",
                               **{**csa, "minTransfer": 500000, "eligible": csa["eligible"][:2]},
                               "form": "2016 ISDA Credit Support Annex for Variation Margin (New York law)", "signed": "2017-11-02"},
        "aero-fx", 88, "CSA-VM-0288", "CSA · VM · Harbor Point Bank")
    put("agreement", "ISDA-2002-0417", {"agreementId": "ISDA-2002-0417", "type": "ISDA 2002 Master", "counterparty": cp_ref(NB),
                                        "governingLaw": "English", "signed": "2019-04-17", "legalEntity": US_ENTITY,
                                        "elections": {"closeOutNetting": True, "automaticEarlyTermination": False, "crossDefault": "3% of shareholders' equity",
                                                      "additionalTerminationEvents": ["NAV decline 25% in 12 months", "Key person"],
                                                      "setOff": True, "terminationCurrency": "USD", "creditSupport": "CSA-VM-0417"},
                                        "protocols": ["ISDA 2020 IBOR Fallbacks", "ISDA 2018 US Resolution Stay"],
                                        "nettingSets": ["NS-NORTH-01"], "status": "Active", "documentStore": "DOC-ISDA-0417.pdf"},
        "aero-risk", 1742, "ISDA-2002-0417", "ISDA 2002 Master · Northbridge Capital")
    put("credit-limit", "LIM-NORTH", {"limitId": "LIM-NORTH", "counterparty": cp_ref(NB), "limit": 15000000, "measure": "PFE95 peak",
                                      "utilisation": 0.65, "used": 9800000, "available": 5200000, "status": "Within limit",
                                      "approvedBy": "Credit Risk Committee", "approvedOn": "2026-03-12", "reviewDue": "2027-03-12",
                                      "buckets": [{"bucket": b, "limit": l, "used": u, "utilisation": round(u / l, 2)} for b, l, u in
                                                  [("0-1Y", 15e6, 8.8e6), ("1-3Y", 15e6, 9.8e6), ("3-5Y", 12e6, 6.4e6), ("5-10Y", 8e6, 3.0e6)]],
                                      "earlyWarning": 0.8, "breachHistory": []},
        "aero-risk", 1742, "LIM-NORTH", "Credit limit · Northbridge · 15.0m")
    cps = [(NB, "Hedge fund · London", "GB", "Asset manager (AIF)", {"sp": "NR", "moodys": "NR", "internal": "BB+", "pd1y": 0.0042},
            "Northbridge Capital Holdings Ltd", "64.30"),
           (HP, "Bank · Boston", "US", "Credit institution", {"sp": "A", "moodys": "A2", "internal": "A", "pd1y": 0.0006},
            "Harbor Point Financial Corp.", "64.19"),
           (SC, "Clearing broker · Chicago", "US", "Futures commission merchant", {"sp": "A-", "moodys": "A3", "internal": "A-", "pd1y": 0.0008},
            "Summit Holdings Inc.", "66.12")]
    for cp, sub, country, typ, ratings, parent, nace in cps:
        put("counterparty", cp["id"], {"counterpartyId": cp["id"], "name": cp["name"], "type": sub, "legalName": cp["name"],
                                       "lei": ids.lei(cp["name"]), "entityType": typ, "country": country, "nace": nace,
                                       "parent": {"name": parent, "lei": ids.lei(parent)}, "ratings": ratings,
                                       "kyc": {"status": "Approved", "riskRating": "Standard", "lastReview": "2026-02-11", "nextReview": "2027-02-11"},
                                       "regulatoryClassification": {"emir": "FC" if "Bank" in sub or "fund" in sub else "NFC-",
                                                                    "doddFrank": "Financial entity", "mifid": "Professional client"},
                                       "relationshipManager": "S. Iyer", "onboarded": "2019-03-01", "status": "Active",
                                       "addresses": {"registered": {"country": country}}},
            "aero-risk", 1742, cp["id"], cp["name"] + " · " + sub)
    put("clearing-account", "CLR-NY-07", {"accountId": "CLR-NY-07", "broker": cp_ref(SC), "imLimit": 0.60, "ccp": "CME Clearing",
                                          "origin": "House", "initialMargin": 1215000, "imLimitAmount": 2025000, "excessCollateral": 385000,
                                          "currency": "USD", "segregation": "US futures (4d)"},
        "eod-futures", 20260930, "CLR-NY-07", "Clearing account · Summit · IM limit 60%")
    put("contract-spec", "CL-Z6", {"spec": "CL-Z6", "exchange": "NYMEX", "product": "WTI crude oil", "contractSize": 1000, "unit": "bbl",
                                   "tickSize": 0.01, "tickValue": 10, "currency": "USD", "settlement": "Physical, Cushing OK",
                                   "lastTradingDay": "2026-11-20", "firstNoticeDay": "2026-11-23", "tradingHours": "Sun-Fri 18:00-17:00 ET",
                                   "positionLimit": 6000, "cfi": "FCECPX"},
        "eod-futures", 20260930, "CL-Z6", "Contract spec · NYMEX CL December 2026")
    for b, d, ent, trader in [("RATES-NY-3", "Rates · New York", US_ENTITY, "A. Shah"), ("FX-LDN-2", "FX · London", UK_ENTITY, "L. Moreau"),
                              ("COMM-NY-1", "Commodities · New York", US_ENTITY, "T. Brennan")]:
        put("book", b, {"book": b, "desk": d, "legalEntity": ent, "headTrader": trader, "pnlCurrency": "USD", "accountingTreatment": "Fair value (FVTPL)",
                        "regulatoryBook": "Trading book", "volcker": "Market making", "status": "Open", "costCentre": "CC-" + b[:4] + "-" + b[-1]},
            "aero-risk", 1742, b, "Book · " + d)


def main():
    put("trade", "IRS-48213", irs_48213(), "aero-risk", 1742, "IRS-48213", "Interest rate swap · Northbridge Capital · USD 50m · 5Y", live=True)
    fxs, fut = fx_swap(), future()
    put("trade", "FXS-20931", fxs, "aero-fx", 88, "FXS-20931", "FX swap · Harbor Point Bank · EUR/USD 25m", live=True)
    put("trade", "CFT-77120", fut, "eod-futures", 20260930, "CFT-77120", "Commodity future · NYMEX CL Z6 · Long 150")
    members = netting_sets()
    reference(members)
    (OUT / "catalog.json").write_text(json.dumps(catalog, indent=1, ensure_ascii=False) + "\n")
    print(len(catalog), "entities")


if __name__ == "__main__":
    main()
