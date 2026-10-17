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

"""ENDUR trades in the common schema, modelled on an Endur commodity book's extract: five product types (ComSwap, ComFut, Cash, CallNot,
Commodity), deal -> LEGS[] (INDEX, CASH_FLOWS[] -> RESETS[]) and EVENTS[], blanks spelt as empty strings, digit codes as text, a
production vocabulary (VALIDATED, B/S, FIXED/FLOAT, Cash Settlement), and realistic, consistent amounts.

The shape and the amounts come from tools/packgen/comswap_synth.py (its mixed book); this module books those deals in the bank's
universe: TRADE_ID numbers of their own (1000001 ...), counterparties, books, traders and legal entities of the core pack, the
commodity desk's books, every deal with EVENTS (empty when it has none) and cash events signed by the leg that pays (negative) or
receives them."""
from __future__ import annotations

import random

import refdata as R
import schema as S
from comswap_synth import Generator  # tools/packgen on the path (refdata puts it there)

FIRST_ID = 1_000_001
PRODUCTS = ("ComSwap", "ComFut", "Cash", "CallNot", "Commodity")
STATUS = {"Validated": "VALIDATED", "New": "NEW", "Amended": "AMENDED", "Matured": "MATURED", "Cancelled": "CANCELLED"}
SUB_TYPE = {"Fixed swap": "ENGY-SWAP", "Fixed float swap": "ENGY-SWAP", "Basis swap": "ENGY-B-SWAP", "Location swap": "ENGY-SWAP",
            "EXCH-FUTURE": "ENGY-EXCH-FUT", "CASH-PAYMENT": "CASH", "CASH-FEE": "NOSTRO", "CALL-DEPOSIT": "CALL-NOTICE",
            "PHYS-COMMODITY": "COMM-PHYS", "WARRANT-INVENTORY": "COMM-WARRANTS-INV"}
UNITS = {"MT": "MT", "BBL": "BBL", "MMBTU": "MMBTU", "MWH": "MWH", "OZ": "OZ", "BU": "BU", "Currency": ""}


class Endur:
    """Deterministic: the same seed and count give the same deals. ``product`` forces one product type (a sample per type)."""

    def __init__(self, seed: int = 11, as_of=R.AS_OF):
        self.gen = Generator(seed=seed, profile="mixed", base_date=as_of)
        self.rng = random.Random(seed * 7 + 1)
        self.as_of = as_of
        desk = "DESK-COMM"
        self.books = R.books_of(desk)
        self.traders = R.traders_of(desk)
        self.entity = R.legal_entity_of(desk)

    def deal(self, n: int, product: str | None = None) -> dict:
        g = self.gen
        if product:
            g.product_of = lambda _n, p=product: p                                    # one product type on request
        doc = g.mixed(n)
        if product:
            del g.product_of
        return self.book(n, doc)

    def book(self, n: int, doc: dict) -> dict:
        rng = self.rng
        cp = rng.choice(R.BILATERAL)
        ref = R.CPTY[cp]
        orig = rng.choice([c for c in R.BILATERAL if c != cp]) if doc.get("ORIGINAL_COUNTERPARTY") else ""
        trader = rng.choice(self.traders)
        first, last = R.trader_names(trader)
        rich = bool(doc.get("LEGAL_ENTITY"))
        legs = doc["LEGS"]
        for leg in legs:
            leg["LEG_TYPE"] = {"Fixed": S.FIXED, "Float": S.FLOAT, "Physical": "PHYSICAL"}.get(leg["LEG_TYPE"], leg["LEG_TYPE"])
            leg["SETTLEMENT_TYPE"] = S.PHYSICAL if leg["SETTLEMENT_TYPE"] == "Physical" else S.CASH_SETTLED
            leg["PRICE_UNIT"] = UNITS.get(leg["PRICE_UNIT"], leg["PRICE_UNIT"])
            econ = leg.get("LEG_ECONOMIC_TYPE") or ""
            leg["LEG_ECONOMIC_TYPE"] = econ
        buy = doc["BUY_SELL_CODE"] == "Buy"
        events = doc.get("EVENTS") or []
        pays = {leg["LEG_ID"]: (leg["LEG_TYPE"] == S.FIXED) == buy for leg in legs}       # a buyer pays the fixed leg
        for e in events:
            if isinstance(e.get("AMOUNT"), (int, float)):
                e["AMOUNT"] = round(-abs(e["AMOUNT"]) if pays.get(e["LEG_ID"]) else abs(e["AMOUNT"]), 2)
            if e.get("EVENT_TYPE"):
                e["EVENT_TYPE"] = {"Payment": "CASH_SETTLEMENT", "Amend": "AMENDMENT", "Novate": "NOVATION", "Reset": "RESET"}.get(e["EVENT_TYPE"], e["EVENT_TYPE"])
        version = doc.get("TRADE_VERSION")
        out = dict(doc)
        out.update({
            "SOURCE_SYSTEM": "ENDUR", "SOURCE_UPDATE_TIME": "", "TRADE_ID": FIRST_ID + n,
            "TRADE_STATUS": STATUS.get(doc["TRADE_STATUS"], doc["TRADE_STATUS"]), "TRADE_ACTION": "",
            "TRADE_VERSION": str(version) if version != "" else "", "TRANSACTION_ID": str(int(doc["TRANSACTION_ID"]) + n * 3),
            "BUY_SELL_CODE": S.BUY if buy else S.SELL, "PRODUCT_SUB_TYPE": SUB_TYPE.get(doc["PRODUCT_SUB_TYPE"], doc["PRODUCT_SUB_TYPE"]),
            "COUNTERPARTY": cp, "COUNTERPARTY_UEN": ref["uen"] if rich else "", "COUNTERPARTY_COUNTRY_OF_RISK": ref["country"] if rich else None,
            "ORIGINAL_COUNTERPARTY": orig, "ORIGINAL_COUNTERPARTY_UEN": R.CPTY[orig]["uen"] if orig and rich else "",
            "INTERNAL_EXTERNAL_CODE": "External" if doc["INTERNAL_EXTERNAL_CODE"] == "External" else "Portfolio",
            "TRANSIT": R.TRANSIT[self.entity], "LEGAL_ENTITY": self.entity if rich else "", "PORTFOLIO": rng.choice(self.books),
            "TRADER_ID": R.trader_id(trader), "TRADER_FIRST_NAME": first if rich else "", "TRADER_LAST_NAME": last if rich else "",
            "DELIVERY_VERSUS_PAYMENT": "YES" if doc["DELIVERY_VERSUS_PAYMENT"] == "Y" else "NO", "TT_GROUP": "", "TT_BOOK": "",
            "EVENTS": events})
        return {k: out[k] for k in S.TOP}

    def deals(self, count: int, start: int = 0):
        for n in range(start, start + count):
            yield self.deal(n)

    def samples(self, per_product: int = 3) -> list[dict]:
        """``per_product`` deals of each of the five product types (the pack's samples and tests)."""
        out, n = [], 0
        for p in PRODUCTS:
            for _ in range(per_product):
                out.append(self.deal(n, p))
                n += 1
        return out
