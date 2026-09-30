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

"""The shape of a risk-pack trade document: the contract between make_sutras.py (which binds to it) and
make_data.py (which produces it). Every product shares the common fields; product-specific terms live under
`terms`, risk measures under `risk`, and the schedule (if the product has one) under `schedule`.

    tradeId, productType, productName, assetClass, family, status, direction, tradeDate, effectiveDate,
    maturityDate, currency, notional, mtm, mtmCurrency, pnl1d,
    counterparty {id, name}, nettingSet, book, trader, clearingAccount?, issuer?,
    <market-data reference fields, e.g. discountCurve>,
    terms {<product terms>}, risk {<measure>: value}, sensitivities [{bucket, <measure>...}],
    legs [{leg, label, payReceive, rateType, index, rate, spread, frequency, dayCount, currency, notional}],
    schedule [...], nextIndex, pnlHistory [{date, pnl}]
"""
from __future__ import annotations

RISK_LABELS = {"dv01": "DV01", "cs01": "CS01", "delta": "Delta", "gamma": "Gamma", "vega": "Vega", "theta": "Theta",
               "fxDelta": "FX delta", "ie01": "IE01", "jtd": "Jump to default", "exposure": "Exposure"}
RISK_MEANING = {"dv01": "value change for a 1 bp parallel fall in rates", "cs01": "value change for a 1 bp widening of credit spreads",
                "delta": "value change per unit move in the underlier", "gamma": "change in delta per unit move in the underlier",
                "vega": "value change for a 1 point rise in implied volatility", "theta": "value change over one day, all else equal",
                "fxDelta": "value change for a 1% move in the FX rate", "ie01": "value change for a 1 bp rise in breakeven inflation",
                "jtd": "loss if the reference entity defaults now", "exposure": "current exposure to the counterparty"}
BUCKETS = {"dv01": ["3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "15Y", "20Y", "30Y"], "cs01": ["6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y"],
           "ie01": ["1Y", "2Y", "5Y", "10Y", "20Y", "30Y"], "vega": ["1M", "3M", "6M", "1Y", "2Y", "5Y"]}
DEFAULT_BUCKETS = ["Spot", "1M", "3M", "6M", "1Y", "2Y"]

LEG_COLUMNS = [("Pay/receive", "@.payReceive", None, None), ("Rate type", "@.rateType", None, None), ("Index", "@.index", None, None),
               ("Rate", "@.rate", "pct4", None), ("Spread", "@.spread", "bp1", None), ("Frequency", "@.frequency", None, None),
               ("Day count", "@.dayCount", None, None), ("Currency", "@.currency", None, None), ("Notional", "@.notional", "amount0", None)]

# schedule name -> (panel kind, title, code, columns (label, field, fmt, tone, total)); rows are $.schedule
SCHEDULES = {
    "cashflows": ("ladder", "Cashflows", "CF", [("Pay date", "payDate", "date", None, False), ("Leg", "leg", None, None, False),
                  ("Type", "type", None, None, False), ("Rate", "rate", "pct4", None, False), ("Amount", "amount", "signed0", "sign", False),
                  ("PV", "pv", "signed0", "sign", True)]),
    "coupons": ("ladder", "Coupons", "CPN", [("Date", "date", "date", None, False), ("Coupon", "rate", "pct4", None, False),
                ("Amount", "amount", "amount0", None, True), ("Status", "status", None, "status", False)]),
    "fixings": ("table", "Fixings", "FIX", [("Fixing date", "fixingDate", "date", None, False), ("Period", "period", None, None, False),
                ("Index", "index", None, None, False), ("Rate", "rate", "pct4", None, False), ("Payoff", "payoff", "signed0", "sign", True),
                ("Status", "status", None, "status", False)]),
    "exercise": ("table", "Exercise schedule", "EXR", [("Exercise date", "date", "date", None, False), ("Type", "type", None, None, False),
                 ("Strike", "strike", "pct4", None, False), ("Status", "status", None, "status", False)]),
    "observations": ("ladder", "Observations", "OBS", [("Date", "date", "date", None, False), ("Level", "level", "price2", None, False),
                     ("Trigger", "trigger", "price2", None, False), ("Performance", "performance", "pct2", "sign", False),
                     ("Outcome", "outcome", None, "status", False)]),
    "amortization": ("table", "Amortisation", "AMT", [("Date", "date", "date", None, False), ("Opening notional", "opening", "amount0", None, False),
                     ("Principal", "principal", "amount0", None, True), ("Closing notional", "closing", "amount0", None, False),
                     ("Interest", "interest", "amount0", None, True)]),
    "collateral": ("table", "Collateral", "COL", [("Asset", "asset", None, None, False), ("ISIN", "isin", None, None, False),
                   ("Quantity", "quantity", "amount0", None, False), ("Market value", "marketValue", "amount0", None, True),
                   ("Haircut", "haircut", "pct2", None, False), ("Value after haircut", "value", "amount0", None, True)]),
    "constituents": ("table", "Constituents", "CON", [("Name", "name", None, None, False), ("Weight", "weight", "pct2", None, False),
                     ("Spread / level", "level", "price2", None, False), ("Status", "status", None, "status", False)]),
}
