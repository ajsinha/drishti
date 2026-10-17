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

"""The common trade schema: what every source system's trade looks like when it reaches Drishti (the trading pack's kind ``trade``).

Production feeds several booking systems (Endur, Calypso, Murex) into one trade view. Each writes the same 47 top-level attributes, in
upper snake case, in this order, with a nested ``LEGS[]`` (each with an ``INDEX`` object and ``CASH_FLOWS[]``, each flow with
``RESETS[]``) and ``EVENTS[]`` (cash and lifecycle events, each naming its leg by ``LEG_ID``). Missing values are written the way
such extracts write them: an empty string (most), ``null`` (a few), ``0`` (amounts). The legal entity is ``LEGAL_ENTITY`` (an
extract that writes it under another name is mapped on load: :data:`RENAMED`).

    TRADE_ID          unique across the systems: ENDUR a number (1000001 ...), CALYPSO CLY-..., MUREX MX-...
    SOURCE_SYSTEM     ENDUR, CALYPSO or MUREX
    PRODUCT_TYPE      selects the deal's Sutra (one per product type)
    COUNTERPARTY      a counterparty id of the core pack (its COUNTERPARTY_ID); PORTFOLIO a book id; LEGAL_ENTITY a legal entity id

Every check here is about names and nesting, never about values: :func:`problems` lists what a document lacks or has extra.
"""
from __future__ import annotations

SYSTEMS = ("ENDUR", "CALYPSO", "MUREX")

TOP = ["SOURCE_SYSTEM", "SOURCE_UPDATE_TIME", "TRADE_DATE", "INPUT_DATE", "TRADE_ID", "TRADE_STATUS", "TRADE_ACTION", "TRADE_VERSION",
       "TRANSACTION_ID", "PACKAGE_ID", "QUANTITY", "PRICE", "BUY_SELL_CODE", "PRODUCT_TYPE", "PRODUCT_SUB_TYPE", "PRODUCT_GROUP",
       "PRODUCT_SUB_GROUP", "OPTION_TYPE", "STRIKE_PRICE", "COUNTERPARTY", "COUNTERPARTY_UEN", "COUNTERPARTY_COUNTRY_OF_RISK",
       "ORIGINAL_COUNTERPARTY", "ORIGINAL_COUNTERPARTY_UEN", "INTERNAL_EXTERNAL_CODE", "TRANSIT", "LEGAL_ENTITY", "PORTFOLIO", "TRADER_ID",
       "TRADER_FIRST_NAME", "TRADER_LAST_NAME", "START_DATE", "MATURITY_DATE", "REGISTRY_DELIVERY_DATE", "CURRENCY",
       "DELIVERY_VERSUS_PAYMENT", "STRATEGY", "INTERNAL_TRADING_STRATEGY", "NY_CURVE_FLAG", "VAULT_LOCATION_ALLOCATION",
       "PRECIOUS_METAL_FLAG", "TT_GROUP", "TT_BOOK", "LEASE_PERPETUITY_FLAG", "SETTLEMENT_DATE", "LEGS", "EVENTS"]
LEG = ["LEG_ID", "LEG_TYPE", "LEG_ECONOMIC_TYPE", "CURRENCY", "LEASE_CURRENCY", "FLOAT_SPREAD", "PRICE_UNIT", "NOTIONAL", "DISCOUNT_INDEX",
       "SPOT_CONVERSION_FACTOR", "PHYSICAL_LOCATION_NAME", "INDEX_LOCATION_NAME", "RESET_FIXING_SHIFT", "RESET_HOLIDAY_SCHEDULE",
       "RESET_CONVENTION", "EMISSIONS_VINTAGE_MIN", "EMISSIONS_VINTAGE_MAX", "EMISSIONS_VINTAGE_DELIVERY_TYPE", "SETTLEMENT_TYPE",
       "NY_CURVE_FLAG", "NUM_RRDC_REC", "NUM_PROFILE_REC", "DAILY_VOLUME", "INDEX", "CASH_FLOWS"]
INDEX = ["NAME", "GROUP", "SUB_GROUP"]
FLOW = ["FLOW_ID", "FLOW_TYPE", "NOTIONAL", "RATE", "FLOAT_SPREAD", "START_DATE", "END_DATE", "PAYMENT_DATE", "RATE_DETERMINATION_DATE",
        "FIRST_RESET_DATE", "LAST_RESET_DATE", "STRIKE_PRICE", "RESETS"]
RESET = ["RESET_ID", "ACCRUAL", "START_DATE", "END_DATE", "RESET_DATE", "ACCRUAL_START_DATE", "ACCRUAL_END_DATE", "RATE"]
EVENT = ["LEG_ID", "EVENT_ID", "EVENT_NUMBER", "EVENT_TYPE", "PAYMENT_TYPE", "EVENT_DATE", "AMOUNT", "CURRENCY", "SEQUENCE_NUMBER",
         "VAULT_LOCATION_ALLOCATION", "NY_CURVE_FLAG"]
# names some extracts use for a common attribute: mapped when a real file is loaded (the repository never uses them)
RENAMED = {"BMO_LEGAL_ENTITY": "LEGAL_ENTITY"}

# The vocabulary the systems share once their feeds are standardised (values, not names: a Sutra tests them)
STATUSES = ("VALIDATED", "NEW", "AMENDED", "MATURED", "CANCELLED")
BUY, SELL = "B", "S"
FIXED, FLOAT = "FIXED", "FLOAT"
CASH_SETTLED, PHYSICAL = "Cash Settlement", "Physical Settlement"
PAY, RECEIVE = "Pay", "Receive"                        # LEG_ECONOMIC_TYPE of a leg whose flows the bank pays or receives


def rename(doc: dict) -> dict:
    """A real extract's document with the common names (``BMO_LEGAL_ENTITY`` becomes ``LEGAL_ENTITY``), keys in the common order."""
    d = {RENAMED.get(k, k): v for k, v in doc.items()}
    d.setdefault("EVENTS", [])
    return {k: d[k] for k in TOP if k in d} | {k: v for k, v in d.items() if k not in TOP}


def blank_leg() -> dict:
    return {k: ("" if k not in ("LEG_ID", "NOTIONAL", "RESET_HOLIDAY_SCHEDULE", "INDEX", "CASH_FLOWS") else None) for k in LEG} | {
        "NOTIONAL": 0.0, "INDEX": {"NAME": "", "GROUP": "", "SUB_GROUP": ""}, "CASH_FLOWS": []}


def blank_flow() -> dict:
    return {k: "" for k in FLOW} | {"NOTIONAL": 0.0, "STRIKE_PRICE": 0.0, "RESETS": []}


def blank_reset() -> dict:
    return {k: "" for k in RESET}


def blank_event() -> dict:
    return {k: "" for k in EVENT}


def problems(doc: dict) -> list[str]:
    """What a trade document lacks or has beyond the common schema, at every level (names only); empty when it conforms."""
    out: list[str] = []

    def keys(where: str, got: dict, want: list[str]) -> None:
        if list(got) != want:
            missing, extra = [k for k in want if k not in got], [k for k in got if k not in want]
            out.append(f"{where}: missing {missing} extra {extra}" if missing or extra else f"{where}: keys out of order")

    tid = doc.get("TRADE_ID")
    keys(f"{tid}", doc, TOP)
    if doc.get("SOURCE_SYSTEM") not in SYSTEMS:
        out.append(f"{tid}: SOURCE_SYSTEM {doc.get('SOURCE_SYSTEM')!r} is not one of {SYSTEMS}")
    for i, leg in enumerate(doc.get("LEGS") or []):
        keys(f"{tid} LEGS[{i}]", leg, LEG)
        keys(f"{tid} LEGS[{i}].INDEX", leg.get("INDEX") or {}, INDEX)
        for j, f in enumerate(leg.get("CASH_FLOWS") or []):
            keys(f"{tid} LEGS[{i}].CASH_FLOWS[{j}]", f, FLOW)
            for k, r in enumerate(f.get("RESETS") or []):
                keys(f"{tid} LEGS[{i}].CASH_FLOWS[{j}].RESETS[{k}]", r, RESET)
    for i, e in enumerate(doc.get("EVENTS") or []):
        keys(f"{tid} EVENTS[{i}]", e, EVENT)
    for name in _names(doc):
        if name != name.upper():
            out.append(f"{tid}: attribute {name!r} is not upper snake case")
    return out


def _names(v, seen=None):
    if isinstance(v, dict):
        for k, x in v.items():
            yield k
            yield from _names(x)
    elif isinstance(v, list):
        for x in v:
            yield from _names(x)
