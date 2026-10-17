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

"""A synthetic look-alike of a trading system's commodity swap export: deals with legs, each leg's monthly cash flows, each flow's resets,
and (on some deals) events. Every value is invented; the SHAPE is what a booking-system extract looks like, so packs, tests and docs can be
built and shown without any real data:

* upper-case keys, in a fixed order; integer ids (``TRADE_ID``, ``LEG_ID``, ``FLOW_ID``); digit-string codes that must stay text
  (``TRANSACTION_ID``, ``TRANSIT``, ``DAILY_VOLUME``); ISO date strings; numbers as JSON numbers;
* missing values spelt four ways: an empty string ``""`` (most of them), ``null``, ``0`` and a missing key (``EVENTS`` absent on some deals);
* deal -> ``LEGS[]`` (an ``INDEX`` object, ``CASH_FLOWS[]`` -> ``RESETS[]``) and ``EVENTS[]``: four levels;
* 1 to 4 legs (fixed/float, float/float basis, fee, location differential), 1 to 36 monthly flows, one reset per flow or one per pricing
  day (averaging swaps), a few packages of several deals, novations (an original counterparty).

    python3 tools/packgen/comswap_synth.py --deals 40 --out docs/guides/examples/comswap/comswap-sample.json
    python3 tools/packgen/comswap_synth.py --deals 5000 --out /tmp/comswap-5000.json            # a scale check (JSON array)
    python3 tools/packgen/comswap_synth.py --deals 5000 --jsonl --out /tmp/comswap-5000.jsonl   # one deal per line

Deterministic: the same ``--seed`` and ``--deals`` give the same file. ``--profile sparse`` blanks what such extracts often leave blank
(rates, labels and event details empty); ``rich`` fills them; ``mixed`` (the default) does both, deal by deal.

Amounts are consistent in every profile (unlike the real extract, whose notionals are all 0): a flow's ``NOTIONAL`` is the quantity of its
period times a plausible price per price unit of its index; a leg's ``NOTIONAL`` is the sum of its flows' notionals; a fixed leg's ``RATE``
and ``STRIKE_PRICE`` are its fixed price, a reset's ``RATE`` sits near the flow's price; an event's ``AMOUNT`` is a share of the flow or
leg it belongs to (a settlement, a fee, a fixing difference), dated by that flow.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import random
import sys

COUNTERPARTIES = ["CPTY-ALPHA", "CPTY-BRAVO", "CPTY-CEDAR", "CPTY-DELTA", "CPTY-EMBER", "CPTY-FALCON", "CPTY-GRANITE", "CPTY-HARBOR",
                  "CPTY-IRIS", "CPTY-JUNIPER", "CPTY-KESTREL", "CPTY-LUMEN", "CPTY-MERIDIAN", "CPTY-NIMBUS", "CPTY-ORCHID", "CPTY-PRAIRIE",
                  "CPTY-QUARRY", "CPTY-RIVET", "CPTY-SUMMIT", "CPTY-TUNDRA", "CPTY-UPLAND", "CPTY-VERTEX", "CPTY-WILLOW", "CPTY-ZEPHYR"]
PORTFOLIOS = ["PF-ENERGY-01", "PF-ENERGY-02", "PF-METALS-01", "PF-METALS-02", "PF-GAS-01", "PF-POWER-01", "PF-AGRI-01", "PF-CLIENT-01"]
TRADERS = [(f"TRDR{i:02d}", "Trader", chr(64 + i)) for i in range(1, 13)]          # (id, first name, last name): Trader A ... Trader L
ENTITIES = ["LE-NORTH", "LE-SOUTH", "LE-EAST"]
# (index name, group, sub group, price unit, currency, typical price, portfolio family)
INDEXES = [("IDX-CRUDE-M1", "Energy", "Crude oil", "BBL", "USD", 78.0, "ENERGY"), ("IDX-BRENT-M1", "Energy", "Crude oil", "BBL", "USD", 82.0, "ENERGY"),
           ("IDX-GASOIL-M1", "Energy", "Refined", "MT", "USD", 710.0, "ENERGY"), ("IDX-JET-M1", "Energy", "Refined", "MT", "USD", 760.0, "ENERGY"),
           ("IDX-NATGAS-HH", "Energy", "Natural gas", "MMBTU", "USD", 3.1, "GAS"), ("IDX-NATGAS-TTF", "Energy", "Natural gas", "MWH", "EUR", 34.0, "GAS"),
           ("IDX-POWER-BASE", "Energy", "Power", "MWH", "EUR", 88.0, "POWER"), ("IDX-ALU-3M", "Metals", "Base metals", "MT", "USD", 2350.0, "METALS"),
           ("IDX-COPPER-3M", "Metals", "Base metals", "MT", "USD", 9100.0, "METALS"), ("IDX-GOLD-PM", "Metals", "Precious", "OZ", "USD", 2400.0, "METALS"),
           ("IDX-CORN-M1", "Agriculture", "Grains", "BU", "USD", 4.4, "AGRI")]
SUB_TYPES = {1: "Fixed swap", 2: "Fixed float swap", 3: "Basis swap", 4: "Location swap"}
STATUSES = [("Validated", 60), ("New", 12), ("Amended", 10), ("Matured", 12), ("Cancelled", 6)]
EVENT_TYPES = [("Payment", "Cash settlement"), ("Amend", "Fee"), ("Novate", "Novation fee"), ("Reset", "Fixing")]
LOCATIONS = ["Houston", "Cushing", "Rotterdam", "Singapore", "Henry Hub"]


def _days(d: dt.date, n: int) -> dt.date:
    return d + dt.timedelta(days=n)


def _month_end(d: dt.date) -> dt.date:
    nxt = (d.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
    return nxt - dt.timedelta(days=1)


def _business_days(start: dt.date, end: dt.date) -> list:
    out, d = [], start
    while d <= end:
        if d.weekday() < 5:
            out.append(d)
        d += dt.timedelta(days=1)
    return out


def _weighted(rng: random.Random, pairs):
    total = sum(w for _, w in pairs)
    x = rng.uniform(0, total)
    for v, w in pairs:
        x -= w
        if x <= 0:
            return v
    return pairs[-1][0]


class Generator:
    def __init__(self, seed: int = 7, profile: str = "mixed", base_date: dt.date = dt.date(2026, 10, 16)):
        self.rng = random.Random(seed)
        self.profile = profile
        self.base = base_date
        self.flow_id = 5_000_001
        self.event_id = 9_000_001
        self.package = 0
        self.package_left = 0
        self.package_id = ""

    def rich(self) -> bool:
        return self.profile == "rich" or (self.profile == "mixed" and self.rng.random() < 0.7)

    # ---- one level at a time --------------------------------------------------------------------------------------------------
    def resets(self, flow_start: dt.date, flow_end: dt.date, averaging: bool, rich: bool, price: float) -> list:
        days = _business_days(flow_start, flow_end) if averaging else [flow_start]
        out = []
        for i, d in enumerate(days, 1):
            fixed = rich and d < self.base
            out.append({"RESET_ID": i if rich else "", "ACCRUAL": round(1 / len(days), 6) if rich else "",
                        "START_DATE": d.isoformat(), "END_DATE": (d if averaging else flow_end).isoformat(),
                        "RESET_DATE": d.isoformat() if rich else "", "ACCRUAL_START_DATE": d.isoformat() if rich and averaging else "",
                        "ACCRUAL_END_DATE": d.isoformat() if rich and averaging else "",
                        "RATE": f"{price * self.rng.uniform(0.985, 1.015):.4f}" if fixed else ""})
        return out

    def flows(self, start: dt.date, months: int, leg_type: str, qty: float, price: float, spread: float, averaging: bool, rich: bool,
              fee: bool = False) -> list:
        """One flow per month. The notional of a flow is its quantity times its price; a floating leg's price drifts a little month to month."""
        out, d, p = [], start, price
        for _ in range(months):
            end = _month_end(d)
            pay = _days(end, self.rng.choice([5, 7, 10, 12]))
            fixed_leg = leg_type == "Fixed"
            if not fixed_leg:
                p = max(price * 0.5, p * self.rng.uniform(0.985, 1.015))
            flow_price = price if fixed_leg else p + spread
            notional = round(price if fee else qty * flow_price, 2)               # a fee is an amount; every other flow is quantity x price
            out.append({"FLOW_ID": self.flow_id, "FLOW_TYPE": ("Fixed price" if fixed_leg else "Floating average") if rich else "",
                        "NOTIONAL": notional,
                        "RATE": f"{price:.4f}" if (rich and fixed_leg and not fee) else "",
                        "FLOAT_SPREAD": f"{spread:.4f}" if (rich and not fixed_leg and spread) else "",
                        "START_DATE": d.isoformat(), "END_DATE": end.isoformat(), "PAYMENT_DATE": pay.isoformat(),
                        "RATE_DETERMINATION_DATE": end.isoformat(), "FIRST_RESET_DATE": d.isoformat(),
                        "LAST_RESET_DATE": end.isoformat() if rich else "",
                        "STRIKE_PRICE": round(price, 4) if (fixed_leg and not fee) else 0.0,
                        "RESETS": [] if fixed_leg else self.resets(d, end, averaging, rich, p)})
            self.flow_id += 1
            d = _days(end, 1)
        return out

    def leg(self, leg_id: int, leg_type: str, idx: tuple, start: dt.date, months: int, qty: float, averaging: bool, rich: bool,
            economic: str, location: str = "", fee: bool = False, price: float | None = None) -> dict:
        name, group, sub, unit, ccy, base_price, _ = idx
        if price is None:
            price = round(base_price * self.rng.uniform(0.85, 1.15), 4)
        if fee:
            price = float(self.rng.choice([2_500, 5_000, 7_500, 10_000, 25_000]))      # the fee, an amount in the currency
        spread = round(self.rng.choice([0.0, 0.25, 0.5, -0.35, 1.1]) * (price / 80.0), 4) if leg_type == "Float" else 0.0
        floating = leg_type == "Float"
        flows = self.flows(start, months, leg_type, qty, price, spread, averaging, rich, fee)
        return {"LEG_ID": leg_id, "LEG_TYPE": leg_type, "LEG_ECONOMIC_TYPE": economic if rich else "", "CURRENCY": ccy, "LEASE_CURRENCY": ccy,
                "FLOAT_SPREAD": f"{spread:.4f}" if (rich and floating and spread) else "", "PRICE_UNIT": unit, "NOTIONAL": round(sum(f["NOTIONAL"] for f in flows), 2),
                "DISCOUNT_INDEX": f"DISC-{ccy}" if rich else "", "SPOT_CONVERSION_FACTOR": "1" if rich else "",
                "PHYSICAL_LOCATION_NAME": location, "INDEX_LOCATION_NAME": location if floating else "",
                "RESET_FIXING_SHIFT": "0" if (rich and floating) else "", "RESET_HOLIDAY_SCHEDULE": None,
                "RESET_CONVENTION": ("Daily average" if averaging else "Monthly") if (rich and floating) else "",
                "EMISSIONS_VINTAGE_MIN": "", "EMISSIONS_VINTAGE_MAX": "", "EMISSIONS_VINTAGE_DELIVERY_TYPE": "",
                "SETTLEMENT_TYPE": self.rng.choice(["Cash", "Cash", "Cash", "Physical"]), "NY_CURVE_FLAG": "", "NUM_RRDC_REC": "", "NUM_PROFILE_REC": "",
                "DAILY_VOLUME": str(int(qty / 21)) if rich and not fee else "",
                "INDEX": {"NAME": name if floating else "", "GROUP": group if (rich and floating) else "", "SUB_GROUP": sub if (rich and floating) else ""},
                "CASH_FLOWS": flows}

    def events(self, legs: list, trade_date: dt.date, rich: bool) -> list:
        """Events belong to a leg: a settlement is a share of one of its flows (dated by that flow's payment date); a fee is a few basis
        points of the leg's notional; a fixing difference is a small share of one flow."""
        out = []
        for leg in legs:
            flows = leg["CASH_FLOWS"]
            for n in range(1, self.rng.randint(1, 4) + 1):
                etype, ptype = self.rng.choice(EVENT_TYPES)
                flow = self.rng.choice(flows)
                if etype == "Payment":
                    amount, when = flow["NOTIONAL"] * self.rng.uniform(0.01, 0.05), dt.date.fromisoformat(flow["PAYMENT_DATE"])
                elif etype == "Reset":
                    amount, when = flow["NOTIONAL"] * self.rng.uniform(0.002, 0.02), dt.date.fromisoformat(flow["RATE_DETERMINATION_DATE"])
                else:                                                              # Amend / Novate: a fee
                    amount, when = leg["NOTIONAL"] * self.rng.uniform(0.0002, 0.001), _days(trade_date, 7 * n + self.rng.randint(0, 20))
                out.append({"LEG_ID": leg["LEG_ID"], "EVENT_ID": self.event_id if rich else "", "EVENT_NUMBER": n if rich else "",
                            "EVENT_TYPE": etype if rich else "", "PAYMENT_TYPE": ptype if rich else "", "EVENT_DATE": when.isoformat() if rich else "",
                            "AMOUNT": round(amount, 2) if rich else "", "CURRENCY": leg["CURRENCY"] if rich else "",
                            "SEQUENCE_NUMBER": n if rich else "", "VAULT_LOCATION_ALLOCATION": "", "NY_CURVE_FLAG": ""})
                self.event_id += 1
        return out

    def deal(self, n: int) -> dict:
        rng = self.rng
        rich = self.rich()
        trade_id = 100_001 + n
        legs_n = _weighted(rng, [(2, 70), (1, 12), (3, 10), (4, 8)])
        idx = rng.choice(INDEXES)
        trade_date = _days(self.base, -rng.randint(5, 720))
        start = _month_end(_days(trade_date, rng.randint(3, 60))) + dt.timedelta(days=1)
        months = rng.choice([1, 3, 6, 6, 12, 12, 12, 18, 24, 36])
        maturity = _month_end(_days(start, 31 * (months - 1)))
        qty = float(rng.choice([1, 2, 5, 10, 20, 50]) * {"BBL": 10_000, "MT": 1_000, "MMBTU": 10_000, "MWH": 100, "OZ": 100, "BU": 5_000}[idx[3]])
        averaging = rng.random() < 0.1 and months <= 12
        loc = rng.choice(LOCATIONS)
        legs = []
        if legs_n == 1:
            legs.append(self.leg(1, "Float", idx, start, months, qty, averaging, rich, "Floating price"))
        else:
            legs.append(self.leg(1, "Fixed", idx, start, months, qty, averaging, rich, "Fixed price"))
            other = rng.choice([i for i in INDEXES if i[3] == idx[3]] or [idx]) if legs_n >= 3 else idx
            legs.append(self.leg(2, "Float", other if legs_n == 3 else idx, start, months, qty, averaging, rich, "Floating price", loc))
            if legs_n >= 3:
                legs.append(self.leg(3, "Float", idx, start, min(months, 3), qty, False, rich, "Basis", loc))
            if legs_n == 4:
                legs.append(self.leg(4, "Fixed", idx, start, 1, 1.0, False, rich, "Fee", fee=True))
        fixed = next((l for l in legs if l["LEG_TYPE"] == "Fixed" and l["LEG_ECONOMIC_TYPE"] != "Fee"), None)
        status = "Matured" if maturity < self.base else _weighted(rng, [s for s in STATUSES if s[0] != "Matured"])
        cpty = rng.choice(COUNTERPARTIES)
        novated = rng.random() < 0.1
        trader = rng.choice(TRADERS)
        family = idx[6]
        portfolio = rng.choice([p for p in PORTFOLIOS if family in p] or PORTFOLIOS)
        if self.package_left == 0 and rng.random() < 0.06:
            self.package += 1
            self.package_left = rng.randint(2, 4)
            self.package_id = f"PKG-{self.package:04d}"
        package = self.package_id if self.package_left else ""
        if self.package_left:
            self.package_left -= 1
        doc = {"SOURCE_SYSTEM": "SYNTH-ETRM", "SOURCE_UPDATE_TIME": "", "TRADE_DATE": trade_date.isoformat(), "INPUT_DATE": "",
               "TRADE_ID": trade_id, "TRADE_STATUS": status, "TRADE_ACTION": "", "TRADE_VERSION": rng.randint(1, 4) if rich else "",
               "TRANSACTION_ID": str(700_000_000 + n * 7), "PACKAGE_ID": package, "QUANTITY": qty * months,
               "PRICE": float(fixed["CASH_FLOWS"][0]["STRIKE_PRICE"]) if fixed and rich else None,
               "BUY_SELL_CODE": rng.choice(["Buy", "Sell"]), "PRODUCT_TYPE": "ComSwap", "PRODUCT_SUB_TYPE": SUB_TYPES[legs_n],
               "PRODUCT_GROUP": "", "PRODUCT_SUB_GROUP": "", "OPTION_TYPE": None, "STRIKE_PRICE": None,
               "COUNTERPARTY": cpty, "COUNTERPARTY_UEN": "", "COUNTERPARTY_COUNTRY_OF_RISK": None,
               "ORIGINAL_COUNTERPARTY": rng.choice([c for c in COUNTERPARTIES if c != cpty]) if novated else "", "ORIGINAL_COUNTERPARTY_UEN": "",
               "INTERNAL_EXTERNAL_CODE": rng.choice(["External", "External", "External", "Internal"]), "TRANSIT": f"{rng.randint(1, 9999):05d}",
               "LEGAL_ENTITY": rng.choice(ENTITIES) if rich else "", "PORTFOLIO": portfolio, "TRADER_ID": trader[0],
               "TRADER_FIRST_NAME": trader[1] if rich else "", "TRADER_LAST_NAME": trader[2] if rich else "",
               "START_DATE": start.isoformat(), "MATURITY_DATE": maturity.isoformat(), "REGISTRY_DELIVERY_DATE": "", "CURRENCY": idx[4],
               "DELIVERY_VERSUS_PAYMENT": rng.choice(["N", "N", "Y"]), "STRATEGY": None, "INTERNAL_TRADING_STRATEGY": "", "NY_CURVE_FLAG": "",
               "VAULT_LOCATION_ALLOCATION": "", "PRECIOUS_METAL_FLAG": "Y" if idx[0] == "IDX-GOLD-PM" else "", "TT_GROUP": "", "TT_BOOK": "",
               "LEASE_PERPETUITY_FLAG": "", "SETTLEMENT_DATE": "", "LEGS": legs}
        r = rng.random()
        if r < 0.5:
            doc["EVENTS"] = self.events(legs, trade_date, rich)                       # present and filled (or blank, sparse)
        elif r < 0.6:
            doc["EVENTS"] = []                                                       # present and empty
        return doc                                                                   # else: the key is missing

    # ---- other products of the same book (--mix): one file, several record types told apart by PRODUCT_TYPE --------------------
    # The deal header and the LEGS -> CASH_FLOWS -> RESETS shape are shared by every product of the book; what differs is how many legs,
    # flows and resets a product has, which leg fields it fills, and whether it has EVENTS at all (only swaps do).
    def product_of(self, n: int) -> str:
        if n % 97 == 33:
            return "CallNot"                                                         # rare: about 1 in 100, at least one in 40 deals
        return _weighted(self.rng, [("ComSwap", 45), ("ComFut", 20), ("Cash", 17), ("Commodity", 17)])

    def simple_leg(self, leg_id: int, leg_type: str, ccy: str, unit: str, qty: float, start: dt.date, flows: int, rich: bool,
                   index: str = "", lease: bool = True, price: float = 1.0, rate: str = "") -> dict:
        """A leg of a non-swap product: its flows are periods (or one settlement), each with one reset that carries only its dates. A flow's
        notional is its quantity times ``price`` (1.0 for a leg that is an amount of currency); ``rate`` is a flow's quoted rate, if any."""
        out, d = [], start
        for _ in range(flows):
            end = _month_end(d) if flows > 1 else d
            out.append({"FLOW_ID": self.flow_id, "FLOW_TYPE": "", "NOTIONAL": round(qty * price, 2), "RATE": rate if rich else "", "FLOAT_SPREAD": "",
                        "START_DATE": d.isoformat(), "END_DATE": end.isoformat(), "PAYMENT_DATE": _days(end, 2).isoformat(),
                        "RATE_DETERMINATION_DATE": end.isoformat(), "FIRST_RESET_DATE": d.isoformat(), "LAST_RESET_DATE": "", "STRIKE_PRICE": 0.0,
                        "RESETS": [{"RESET_ID": "", "ACCRUAL": "", "START_DATE": d.isoformat(), "END_DATE": end.isoformat(), "RESET_DATE": "",
                                    "ACCRUAL_START_DATE": "", "ACCRUAL_END_DATE": "", "RATE": ""}]})
            self.flow_id += 1
            d = _days(end, 1)
        return {"LEG_ID": leg_id, "LEG_TYPE": leg_type, "LEG_ECONOMIC_TYPE": "", "CURRENCY": ccy, "LEASE_CURRENCY": ccy if lease else "",
                "FLOAT_SPREAD": "", "PRICE_UNIT": unit, "NOTIONAL": round(sum(f["NOTIONAL"] for f in out), 2), "DISCOUNT_INDEX": "", "SPOT_CONVERSION_FACTOR": "",
                "PHYSICAL_LOCATION_NAME": "", "INDEX_LOCATION_NAME": "", "RESET_FIXING_SHIFT": "", "RESET_HOLIDAY_SCHEDULE": None, "RESET_CONVENTION": "",
                "EMISSIONS_VINTAGE_MIN": "", "EMISSIONS_VINTAGE_MAX": "", "EMISSIONS_VINTAGE_DELIVERY_TYPE": "", "SETTLEMENT_TYPE": "Cash" if unit == "Currency" else "Physical",
                "NY_CURVE_FLAG": "", "NUM_RRDC_REC": "", "NUM_PROFILE_REC": "", "DAILY_VOLUME": str(int(qty)) if qty >= 1 else "0",
                "INDEX": {"NAME": index, "GROUP": "", "SUB_GROUP": ""}, "CASH_FLOWS": out}

    def mixed(self, n: int) -> dict:
        product = self.product_of(n)
        doc = self.deal(n)
        doc["PRODUCT_TYPE"] = product
        if product == "ComSwap":
            return doc
        rng, rich = self.rng, self.rich()
        doc.pop("EVENTS", None)                                                      # only swaps carry events
        start = dt.date.fromisoformat(doc["START_DATE"])
        ccy = doc["CURRENCY"]
        if product == "ComFut":                                                      # an exchange future: one leg, one settlement
            idx = rng.choice(INDEXES)
            lots = rng.choice([1, 5, 10, 25, 50])
            doc.update({"PRODUCT_SUB_TYPE": "EXCH-FUTURE", "QUANTITY": float(lots * 1000), "CURRENCY": idx[4],
                        "MATURITY_DATE": _month_end(start).isoformat()})
            px = round(idx[5] * rng.uniform(0.9, 1.1), 4)
            doc["PRICE"] = px if rich else None
            doc["LEGS"] = [self.simple_leg(1, "Float", idx[4], idx[3], float(lots * 1000), _month_end(start), 1, rich, idx[0], price=px, rate=f"{px:.4f}")]
        elif product == "Cash":                                                      # a cash payment: one leg in currency, one flow
            amount = round(rng.uniform(5_000, 2_000_000), 2)
            doc.update({"PRODUCT_SUB_TYPE": rng.choice(["CASH-PAYMENT", "CASH-FEE"]), "QUANTITY": amount, "MATURITY_DATE": doc["START_DATE"]})
            doc["LEGS"] = [self.simple_leg(1, "Fixed", ccy, "Currency", amount, start, 1, rich, lease=False)]
        elif product == "CallNot":                                                   # a call-notice deposit: interest, then principal
            amount = float(rng.choice([1, 2, 5, 10]) * 1_000_000)
            doc.update({"PRODUCT_SUB_TYPE": "CALL-DEPOSIT", "QUANTITY": amount, "CURRENCY": rng.choice(["USD", "CAD", "EUR"])})
            doc["LEGS"] = [self.simple_leg(1, "Fixed", doc["CURRENCY"], "Currency", amount, start, rng.choice([1, 2]), rich, lease=rng.random() < 0.6,
                                           rate=f"{rng.uniform(2.5, 5.5):.4f}")]
        else:                                                                        # a physical commodity deal: delivery leg and payment leg
            idx = rng.choice(INDEXES)
            months = rng.choice([1, 1, 10, 12])
            qty = float(rng.choice([1, 5, 20]) * 1000)
            doc.update({"PRODUCT_SUB_TYPE": rng.choice(["PHYS-COMMODITY", "WARRANT-INVENTORY"]), "QUANTITY": qty * months, "CURRENCY": idx[4]})
            px = round(idx[5] * rng.uniform(0.9, 1.1), 4)
            pay_months = max(1, months - rng.choice([0, 2]))
            doc["PRICE"] = px if rich else None
            doc["LEGS"] = [self.simple_leg(1, "Physical", idx[4], idx[3], qty, start, months, rich, idx[0], price=px, rate=f"{px:.4f}"),
                           self.simple_leg(2, "Fixed", idx[4], idx[3], qty * months / pay_months, start, pay_months, rich, price=px, rate=f"{px:.4f}")]
            doc["MATURITY_DATE"] = doc["LEGS"][0]["CASH_FLOWS"][-1]["END_DATE"]
        return doc

    def deals(self, count: int, mix: bool = False):
        for n in range(count):
            yield self.mixed(n) if mix else self.deal(n)


def write(out, count: int, seed: int, profile: str, jsonl: bool, indent: int | None, mix: bool = False) -> int:
    g = Generator(seed, profile)
    stream = sys.stdout if str(out) == "-" else open(out, "w", encoding="utf-8")
    try:
        if jsonl:
            for d in g.deals(count, mix):
                stream.write(json.dumps(d, separators=(",", ":")) + "\n")
        else:
            stream.write("[")
            for i, d in enumerate(g.deals(count, mix)):
                text = json.dumps(d, indent=indent, separators=(",", ": ") if indent else (",", ":"))
                stream.write(("," if i else "") + ("\n" if indent else "") + text)
            stream.write("\n]\n" if indent else "]\n")
    finally:
        if stream is not sys.stdout:
            stream.close()
    return count


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--deals", type=int, default=40, help="number of deals (default 40; at most 10,000)")
    p.add_argument("--seed", type=int, default=7, help="random seed (default 7): the same seed gives the same file")
    p.add_argument("--profile", choices=("mixed", "rich", "sparse"), default="mixed", help="how much is filled in (default mixed)")
    p.add_argument("--jsonl", action="store_true", help="one deal per line (JSON Lines) instead of one JSON array")
    p.add_argument("--indent", type=int, default=2, help="JSON array: indentation (default 2, as such exports are; 0 for one line per file)")
    p.add_argument("--mix", action="store_true", help="a whole book in one file: ComSwap, ComFut, Cash, Commodity and a rare CallNot (PRODUCT_TYPE tells them apart)")
    p.add_argument("--out", default="-", help="the file to write (default: standard output)")
    a = p.parse_args(argv)
    if not 1 <= a.deals <= 10_000:
        p.error("--deals is 1 to 10,000 (a laptop's limit for a scale check)")
    n = write(a.out if a.out == "-" else pathlib.Path(a.out), a.deals, a.seed, a.profile, a.jsonl, a.indent or None, a.mix)
    if a.out != "-":
        print(f"wrote {n} synthetic deals to {a.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
