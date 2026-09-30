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

"""Generates the demo plugin's JSON fixtures: the four reference entities of the mockups and the
entities they link to. Run from the repository root:  python3 plugins/drishti-plugin-demo/tools/make_fixtures.py
"""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/demo"
catalog = []


def put(kind, id_, doc, source, gen, title, subtitle, live=False):
    doc["_meta"] = {"source": source, "generation": gen, "live": live}
    d = OUT / kind
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{id_}.json").write_text(json.dumps(doc, indent=1) + "\n")
    catalog.append({"kind": kind, "id": id_, "title": title, "subtitle": subtitle})


NB = {"id": "CP-NORTHBRIDGE", "name": "Northbridge Capital LLP"}
HP = {"id": "CP-HARBOR-POINT", "name": "Harbor Point Bank"}
SC = {"id": "CP-SUMMIT", "name": "Summit Clearing LLC"}

# ---- IRS-48213 ------------------------------------------------------------------------------
dates = [("2026-10-02", "2027-10-04", "2027-10-06"), ("2027-10-04", "2028-10-02", "2028-10-04"),
         ("2028-10-02", "2029-10-02", "2029-10-04"), ("2029-10-02", "2030-10-02", "2030-10-04"),
         ("2030-10-02", "2031-10-02", "2031-10-06")]
amounts = [-1962430.56, -1946388.89, -1951736.11, -1951736.11, -1951736.11]
dfs = [0.9632, 0.9280, 0.8943, 0.8620, 0.8310]
pvs = [-1890213.11, -1806248.89, -1745437.60, -1682396.53, -1621892.71]
flows = [{"n": i + 1, "accrualStart": a, "accrualEnd": b, "payDate": c, "notional": 50000000, "rate": 0.0385,
          "amount": amounts[i], "df": dfs[i], "pv": pvs[i]} for i, (a, b, c) in enumerate(dates)]
put("trade", "IRS-48213", {
    "tradeId": "IRS-48213", "productType": "IRS", "product": "Interest rate swap", "counterparty": NB,
    "notional": 50000000, "currency": "USD", "direction": "PAY_FIXED", "effectiveDate": "2026-10-02",
    "maturityDate": "2031-10-02", "maturityTenor": "5Y", "fixedRate": 0.0385, "mtm": -412580, "dv01": 22310,
    "book": "RATES-NY-3", "nettingSet": "NS-NORTH-01", "agreement": "ISDA-2002-0417", "csa": "CSA-VM-0417",
    "discountCurve": "USD-SOFR", "index": "SOFR",
    "legs": [
        {"leg": 1, "label": "Pay fixed 3.8500%", "payer": True, "type": "FIXED", "currency": "USD",
         "notional": 50000000, "rate": 0.0385, "dayCount": "ACT/360", "frequency": "Annual",
         "businessDay": "Mod. following", "calendar": "USNY", "paymentLag": "2 business days", "cashflows": flows},
        {"leg": 2, "label": "Receive SOFR compounded", "payer": False, "type": "FLOAT", "currency": "USD",
         "notional": 50000000, "index": "SOFR", "spread": 0.0, "nextReset": "2026-10-02", "projectedFlows": 5,
         "pv": 8333608.84}],
    "dv01ByTenor": [{"tenor": t, "dv01": v} for t, v in [("1Y", 1180), ("2Y", 3420), ("3Y", 4760), ("4Y", 5920), ("5Y", 7030)]],
}, "aero-risk", 1742, "IRS-48213", "Interest rate swap · Northbridge Capital · USD 50m · 5Y", live=True)

# ---- FXS-20931 ------------------------------------------------------------------------------
put("trade", "FXS-20931", {
    "tradeId": "FXS-20931", "productType": "FXSWAP", "product": "FX swap", "counterparty": HP, "pair": "EUR/USD",
    "nearValue": "2026-10-02", "farValue": "2027-01-04", "nearRate": 1.17400, "farRate": 1.17958, "swapPoints": 55.8,
    "mtm": 18420, "book": "FX-LDN-2", "nettingSet": "NS-HARB-02", "csa": "CSA-VM-0288", "spot": "EURUSD",
    "discountCurves": ["EUR-ESTR", "USD-SOFR"], "forwardCurve": "EURUSD-FWD",
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
    "confirmation": {"status": "Confirmed", "matched": "2026-09-30 13:41", "clsEligible": True, "trader": "L. Moreau"},
    "risk": [{"factor": "EUR IR", "usd": -610}, {"factor": "USD IR", "usd": 730}, {"factor": "FX Δ", "usd": 2150}],
}, "aero-fx", 88, "FXS-20931", "FX swap · Harbor Point Bank · EUR/USD 25m", live=True)

# ---- CFT-77120 ------------------------------------------------------------------------------
settles = [("2026-09-24", 69.10, 0.68), ("2026-09-25", 69.85, 0.75), ("2026-09-28", 70.40, 0.55),
           ("2026-09-29", 70.62, 0.22), ("2026-09-30", 71.15, 0.53)]
cum, ladder = 0, []
for d, px, ch in settles:
    vm = round(ch * 150 * 1000)
    cum += vm
    ladder.append({"date": d, "settle": px, "change": ch, "variationMargin": vm, "cumulative": cum,
                   "intraday": d == "2026-09-30"})
put("trade", "CFT-77120", {
    "tradeId": "CFT-77120", "productType": "FUT", "product": "Commodity future", "counterparty": SC,
    "contract": "NYMEX CL Z6", "contractSpec": "CL-Z6", "direction": "Long", "lots": 150, "tradePrice": 68.42,
    "lastPrice": 71.15, "mtm": 409500, "lastTrade": "2026-11-20", "book": "COMM-NY-1", "clearingAccount": "CLR-NY-07",
    "forwardCurve": "WTI-NYMEX",
    "contractTerms": {"exchange": "NYMEX", "product": "WTI crude oil (CL)", "deliveryMonth": "December 2026",
                      "contractSize": "1,000 bbl", "priceUnit": "USD/bbl", "tick": "0.01 = USD 10", "settlement": "Physical"},
    "settlements": ladder,
    "margin": {"initialMargin": "[IM PER LOT] × 150", "variationMarginToday": 79500, "deltaBbl": 150000, "usdPerDollarMove": 150000},
}, "eod-futures", 20260930, "CFT-77120", "Commodity future · NYMEX CL Z6 · Long 150")

# ---- Netting set and member trades ------------------------------------------------------------
members = [("IRS-48213", "Interest rate swap", "USD", 50000000, "2031-10-02", -412580),
           ("IRS-47102", "Interest rate swap", "USD", 80000000, "2033-03-15", 1106420),
           ("XCS-11820", "Cross-currency swap", "EUR", 40000000, "2030-06-20", -2318900),
           ("SWPT-3301", "Swaption", "USD", 25000000, "2027-09-30", 340760)]
extra = [("IRS-4%d" % (7200 + i), "Interest rate swap", "USD", (10 + 5 * i) * 1000000, "20%d-0%d-15" % (28 + i % 6, 1 + i % 9),
          [2210, -1840, 1500, -960, 480, 2210, -1310, 90, -330, 390][i] * 10) for i in range(10)]
all_members = members + extra
net = sum(m[5] for m in all_members)
put("netting-set", "NS-NORTH-01", {
    "nettingSetId": "NS-NORTH-01", "counterparty": NB, "agreement": "ISDA-2002-0417", "csa": "CSA-VM-0417",
    "creditLimit": "LIM-NORTH", "trades": len(all_members), "netMtm": net, "collateralPosted": 1300000,
    "eePeak": 4100000, "pfe95Peak": 9800000, "limit": 15000000, "utilisation": 0.65, "asOf": "14:01 NY",
    "exposure": [{"tenor": t, "ee": ee, "pfe95": pfe} for t, ee, pfe in [
        ("0", 1.2e6, 3.1e6), ("3M", 2.5e6, 5.8e6), ("6M", 3.1e6, 7.2e6), ("1Y", 3.8e6, 8.8e6), ("2Y", 4.1e6, 9.8e6),
        ("3Y", 3.7e6, 8.9e6), ("5Y", 2.6e6, 6.4e6), ("7Y", 1.1e6, 3.0e6), ("10Y", 0.4e6, 1.0e6)]],
    "memberTrades": [{"trade": t, "product": p, "currency": c, "notional": n, "maturity": m, "mtm": v}
                     for t, p, c, n, m, v in all_members],
    "csaTerms": {"marginType": "Variation", "threshold": 0, "minTransfer": "USD 250,000", "independentAmount": 0,
                 "eligibleCollateral": "USD cash", "frequency": "Daily", "mpor": "10 days", "rounding": "USD 10,000"},
    "collateral": {"posted": "USD 1,300,000", "callToday": "none", "disputes": "none", "lastMovement": "2026-09-29"},
}, "aero-risk", 1742, "NS-NORTH-01", "Netting set · Northbridge Capital · %d trades" % len(all_members), live=True)
for t, p, c, n, m, v in all_members[1:]:
    put("trade", t, {"tradeId": t, "productType": {"Interest rate swap": "IRS", "Cross-currency swap": "XCS", "Swaption": "SWPT"}[p],
                     "product": p, "counterparty": NB, "notional": n, "currency": c, "maturityDate": m, "mtm": v,
                     "book": "RATES-NY-3", "nettingSet": "NS-NORTH-01", "csa": "CSA-VM-0417"},
        "aero-risk", 1742, t, "%s · Northbridge Capital · %s %dm" % (p, c, n // 1000000))
put("netting-set", "NS-HARB-02", {"nettingSetId": "NS-HARB-02", "counterparty": HP, "csa": "CSA-VM-0288", "trades": 1,
                                  "netMtm": 18420, "eePeak": 900000,
                                  "memberTrades": [{"trade": "FXS-20931", "product": "FX swap", "currency": "EUR",
                                                    "notional": 25000000, "maturity": "2027-01-04", "mtm": 18420}]},
    "aero-risk", 1742, "NS-HARB-02", "Netting set · Harbor Point Bank · 1 trade")

# ---- Reference entities ----------------------------------------------------------------------
put("curve", "USD-SOFR", {"curveId": "USD-SOFR", "currency": "USD", "index": "SOFR", "status": "live", "unit": "%",
                          "points": [{"tenor": t, "rate": r} for t, r in zip(["1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"],
                                                                              [3.93, 3.88, 3.80, 3.70, 3.58, 3.54, 3.60, 3.66, 3.80])]},
    "aero-risk", 1742, "USD-SOFR", "Curve · USD SOFR discount", live=True)
put("curve", "EUR-ESTR", {"curveId": "EUR-ESTR", "currency": "EUR", "index": "ESTR", "status": "live", "unit": "%",
                          "points": [{"tenor": t, "rate": r} for t, r in zip(["1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y"],
                                                                              [2.02, 2.00, 1.97, 1.95, 2.01, 2.22, 2.51])]},
    "aero-fx", 88, "EUR-ESTR", "Curve · EUR €STR discount", live=True)
put("curve", "EURUSD-FWD", {"curveId": "EURUSD-FWD", "pair": "EUR/USD", "status": "live", "unit": "pips", "spot": 1.17400,
                            "points": [{"tenor": t, "points": p} for t, p in zip(["ON", "1W", "1M", "2M", "3M", "6M", "9M", "1Y"],
                                                                                  [0.6, 3.9, 17.1, 36.3, 55.8, 110.2, 163.5, 222.0])]},
    "aero-fx", 88, "EURUSD-FWD", "Curve · EUR/USD forward points", live=True)
put("curve", "WTI-NYMEX", {"curveId": "WTI-NYMEX", "commodity": "WTI crude", "status": "live", "unit": "USD/bbl",
                           "shape": "backwardation",
                           "points": [{"tenor": t, "price": p} for t, p in zip(["X6", "Z6", "F7", "G7", "H7", "J7", "K7", "M7", "N7", "Q7", "U7", "V7"],
                                                                                [71.62, 71.15, 70.78, 70.45, 70.10, 69.82, 69.55, 69.30, 69.08, 68.90, 68.74, 68.58])]},
    "eod-futures", 20260930, "WTI-NYMEX", "Curve · WTI crude futures (NYMEX)", live=True)
put("fx-spot", "EURUSD", {"pair": "EUR/USD", "spot": 1.17400, "status": "live"}, "aero-fx", 88, "EURUSD", "FX spot · EUR/USD", live=True)
put("index", "SOFR", {"index": "SOFR", "name": "Secured Overnight Financing Rate", "fixing": 3.94, "administrator": "FRBNY"},
    "aero-risk", 1742, "SOFR", "Index · Secured Overnight Financing Rate")
put("csa", "CSA-VM-0417", {"csaId": "CSA-VM-0417", "counterparty": NB, "agreement": "ISDA-2002-0417", "marginType": "VM",
                           "threshold": 0, "minTransfer": 250000, "currency": "USD"}, "aero-risk", 1742, "CSA-VM-0417", "CSA · VM · Northbridge Capital")
put("csa", "CSA-VM-0288", {"csaId": "CSA-VM-0288", "counterparty": HP, "marginType": "VM", "threshold": 0, "minTransfer": 500000,
                           "currency": "USD"}, "aero-fx", 88, "CSA-VM-0288", "CSA · VM · Harbor Point Bank")
put("agreement", "ISDA-2002-0417", {"agreementId": "ISDA-2002-0417", "type": "ISDA 2002 Master", "counterparty": NB,
                                    "governingLaw": "English", "signed": "2019-04-17"}, "aero-risk", 1742, "ISDA-2002-0417", "ISDA 2002 Master · Northbridge Capital")
put("credit-limit", "LIM-NORTH", {"limitId": "LIM-NORTH", "counterparty": NB, "limit": 15000000, "measure": "PFE95 peak",
                                  "utilisation": 0.65}, "aero-risk", 1742, "LIM-NORTH", "Credit limit · Northbridge · 15.0m")
for cp, sub in [(NB, "Hedge fund · London"), (HP, "Bank · Boston"), (SC, "Clearing broker · Chicago")]:
    put("counterparty", cp["id"], {"counterpartyId": cp["id"], "name": cp["name"], "type": sub}, "aero-risk", 1742, cp["id"], cp["name"] + " · " + sub)
put("clearing-account", "CLR-NY-07", {"accountId": "CLR-NY-07", "broker": SC, "imLimit": 0.60}, "eod-futures", 20260930, "CLR-NY-07", "Clearing account · Summit · IM limit 60%")
put("contract-spec", "CL-Z6", {"spec": "CL-Z6", "exchange": "NYMEX", "product": "WTI crude oil", "contractSize": 1000, "unit": "bbl"},
    "eod-futures", 20260930, "CL-Z6", "Contract spec · NYMEX CL December 2026")
for b, d in [("RATES-NY-3", "Rates · New York"), ("FX-LDN-2", "FX · London"), ("COMM-NY-1", "Commodities · New York")]:
    put("book", b, {"book": b, "desk": d}, "aero-risk", 1742, b, "Book · " + d)

(OUT / "catalog.json").write_text(json.dumps(catalog, indent=1) + "\n")
print(len(catalog), "entities")
