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

"""CALYPSO and MUREX trades in the common schema (schema.py), from the banking generator's synthetic product range.

tools/packgen/banking/data_trades.py builds every product of the taxonomy (rates, FX, credit, equity, commodities, inflation, fixed
income, securities financing, money markets, structured) as a rich internal model: legs with calculation periods and fixings, schedules,
lifecycle. A booking system's feed does not carry a valuation or risk: it carries the booking, the legs, the flows and the events. This
module writes that, in the same 47 upper-snake attributes an Endur extract has:

* the deal header: product type (the taxonomy code, which picks the Sutra), sub type (its name), group (the asset class), sub group
  (the family), status, version, counterparty with its UEN and country of risk, legal entity and transit, portfolio (the book), trader;
* ``LEGS[]``: each leg of the model with its index, notional and calculation periods as ``CASH_FLOWS[]``, a floating period's fixings as
  its ``RESETS[]``; a product without legs gets one leg built from its schedule (coupons, fixings, observations, amortisation, exercise
  dates) or, without one, a single flow from start to maturity;
* ``EVENTS[]``: one cash settlement per flow that has an amount (signed: negative is paid) and the lifecycle (booked, confirmed,
  amended ...) as deal events.

Numbers stay numbers here (Endur writes many as text; a reader must cope with both, and the union of systems in one table does).
"""
from __future__ import annotations

import schema as S
import refdata as R

STATUS = {"Live": "VALIDATED", "Matured": "MATURED"}
LEG_TYPE = {"fixed": S.FIXED, "float": S.FLOAT, "floating": S.FLOAT}
PRICE_TERMS = ("cleanPrice", "tradePrice", "forwardPrice", "fixedPrice", "fixedRate", "coupon", "couponRate", "rate", "repoRate", "spread",
               "premium")
QUANTITY_TERMS = ("quantity", "faceAmount", "lots", "vegaNotional")
DVP_ASSETS = {"Fixed income", "Securities financing", "Equity"}


def system_of(doc: dict) -> str:
    return "CALYPSO" if str(doc.get("tradeId", "")).startswith("CLY-") else "MUREX"


def _num(v):
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def _leg_type(leg: dict, family: str) -> str:
    t = str(leg.get("rateType") or leg.get("type") or "").lower()
    if t in LEG_TYPE:
        return LEG_TYPE[t]
    return {"option": "OPTION", "exotic": "OPTION"}.get(family, S.FLOAT if leg.get("index") else S.FIXED)


class Converter:
    """One trade at a time; flow, reset and event numbers run on per trade from the trade's own number (unique within the system)."""

    def __init__(self, doc: dict, as_of: str):
        self.d = doc
        self.as_of = as_of
        native = int(str(doc.get("sourceTradeId") or "".join(c for c in doc["tradeId"] if c.isdigit())))
        self.native = native
        self.flow_id = native * 1000
        self.reset_id = 0
        self.event_id = native * 1000

    # ---- legs --------------------------------------------------------------------------------------------------------
    def resets(self, cf: dict) -> list:
        out = []
        for fx in cf.get("fixings") or []:
            self.reset_id += 1
            fixed = str(fx.get("status", "")).lower() == "fixed"
            out.append(S.blank_reset() | {"RESET_ID": f"R-{self.reset_id}", "ACCRUAL": "", "START_DATE": fx.get("fixingDate", ""),
                                          "END_DATE": cf.get("periodEnd", ""), "RESET_DATE": fx.get("fixingDate", ""),
                                          "ACCRUAL_START_DATE": cf.get("periodStart", ""), "ACCRUAL_END_DATE": cf.get("periodEnd", ""),
                                          "RATE": fx.get("rate") if fixed else ""})
        return out

    def flow(self, *, start: str, end: str, pay: str, notional, rate, spread=None, kind: str = "", fixing: str = "", strike=None,
             resets: list | None = None) -> dict:
        self.flow_id += 1
        resets = resets or []
        return S.blank_flow() | {
            "FLOW_ID": self.flow_id, "FLOW_TYPE": kind, "NOTIONAL": float(notional or 0.0), "RATE": rate if rate is not None else "",
            "FLOAT_SPREAD": spread if spread not in (None, 0, 0.0) else "", "START_DATE": start, "END_DATE": end, "PAYMENT_DATE": pay,
            "RATE_DETERMINATION_DATE": fixing or (resets[0]["RESET_DATE"] if resets else ""),
            "FIRST_RESET_DATE": resets[0]["RESET_DATE"] if resets else "", "LAST_RESET_DATE": resets[-1]["RESET_DATE"] if resets else "",
            "STRIKE_PRICE": float(strike) if _num(strike) is not None else 0.0, "RESETS": resets}

    def leg(self, n: int, rich: dict, flows: list) -> dict:
        d = self.d
        typ = _leg_type(rich, d.get("family", ""))
        index = rich.get("index") or ""
        return S.blank_leg() | {
            "LEG_ID": n, "LEG_TYPE": typ, "LEG_ECONOMIC_TYPE": rich.get("payReceive") or "", "CURRENCY": rich.get("currency") or d["currency"],
            "LEASE_CURRENCY": "", "FLOAT_SPREAD": rich.get("spread") if typ == S.FLOAT and rich.get("spread") else "",
            "PRICE_UNIT": rich.get("priceUnit") or rich.get("quantityUnit") or "", "NOTIONAL": float(rich.get("notional") or d.get("notional") or 0.0),
            "DISCOUNT_INDEX": d.get("discountCurve") or "", "RESET_FIXING_SHIFT": "-2" if typ == S.FLOAT else "",
            "RESET_HOLIDAY_SCHEDULE": rich.get("calendar"), "RESET_CONVENTION": rich.get("resetFrequency") or rich.get("frequency") or "",
            "SETTLEMENT_TYPE": S.PHYSICAL if d.get("assetClass") == "Commodity" and "physical" in str(d.get("productName", "")).lower() else S.CASH_SETTLED,
            "NUM_PROFILE_REC": str(len(flows)),
            "INDEX": {"NAME": index, "GROUP": d.get("assetClass", "") if index else "", "SUB_GROUP": rich.get("resetFrequency") or "" if index else ""},
            "CASH_FLOWS": flows}

    def rich_flows(self, rich: dict) -> list:
        out = []
        for cf in rich.get("cashflows") or []:
            out.append(self.flow(start=cf.get("periodStart", ""), end=cf.get("periodEnd", ""), pay=cf.get("payDate", ""),
                                 notional=cf.get("notional", rich.get("notional")), rate=cf.get("rate"), spread=cf.get("spread"),
                                 kind=cf.get("type", ""), fixing=cf.get("fixingDate", ""), strike=cf.get("strike"), resets=self.resets(cf)) | {})
            out[-1]["_amount"] = cf.get("amount")
        return out

    def schedule_flows(self, rows: list, notional) -> list:
        """A product's schedule as one leg's flows: whatever date, rate and amount each row has (coupons, fixings, observations,
        amortisation, exercise dates)."""
        out, prev = [], self.d.get("effectiveDate", "")
        for row in rows:
            when = row.get("payDate") or row.get("date") or row.get("fixingDate") or ""
            if not when:
                continue
            rate = next((row[k] for k in ("rate", "level", "strike") if _num(row.get(k)) is not None), None)
            amount = next((row[k] for k in ("amount", "payoff", "principal") if _num(row.get(k)) is not None), None)
            if _num(row.get("interest")) is not None and amount is not None:
                amount += row["interest"]
            resets = []
            if row.get("fixingDate") or row.get("level") is not None:
                self.reset_id += 1
                resets = [S.blank_reset() | {"RESET_ID": f"R-{self.reset_id}", "START_DATE": when, "END_DATE": when, "RESET_DATE": when,
                                             "RATE": rate if str(row.get("status", "")).lower() in ("fixed", "settled", "paid", "") or row.get("outcome") else ""}]
            out.append(self.flow(start=prev, end=when, pay=when, notional=row.get("opening", notional), rate=rate,
                                 kind=str(row.get("type") or row.get("outcome") or row.get("period") or ""), strike=row.get("strike"), resets=resets))
            out[-1]["_amount"] = amount
            prev = when
        return out

    def legs(self) -> list:
        d = self.d
        rich_legs = d.get("legs") or []
        schedule = d.get("schedule") or []
        out = []
        for i, rl in enumerate(rich_legs, start=1):
            flows = self.rich_flows(rl) if rl.get("cashflows") else self.schedule_flows([r for r in schedule if r.get("leg") == rl.get("leg")],
                                                                                           rl.get("notional"))
            if not flows:                                   # a leg the model gives no periods: one flow over the trade's life
                flows = [self.flow(start=d.get("effectiveDate", ""), end=d.get("maturityDate", ""), pay=d.get("maturityDate", ""),
                                   notional=rl.get("notional", d.get("notional")), rate=rl.get("rate"), spread=rl.get("spread"),
                                   kind=str(rl.get("rateType") or rl.get("type") or ""))]
                flows[-1]["_amount"] = None
            out.append(self.leg(i, rl, flows))
        if not out:
            flows = self.schedule_flows(schedule, d.get("notional"))
            if not flows:
                flows = [self.flow(start=d.get("effectiveDate", ""), end=d.get("maturityDate", ""), pay=d.get("maturityDate", ""),
                                   notional=d.get("notional"), rate=None, kind="Principal")]
                flows[-1]["_amount"] = None
            pay = d.get("direction", "").split(" ")[0] in ("Pay", "Buy", "Long", "Repo")
            out.append(self.leg(1, {"payReceive": S.PAY if pay else S.RECEIVE, "currency": d["currency"], "notional": d.get("notional"),
                                    "index": (d.get("terms") or {}).get("index") or "", "rateType": "Fixed" if d.get("family") in ("bond", "money") else ""},
                                flows))
        return out

    # ---- events ------------------------------------------------------------------------------------------------------
    def event(self, leg_id, n: int, etype: str, ptype: str, when: str, amount, ccy: str) -> dict:
        self.event_id += 1
        return S.blank_event() | {"LEG_ID": leg_id, "EVENT_ID": self.event_id, "EVENT_NUMBER": n, "EVENT_TYPE": etype, "PAYMENT_TYPE": ptype,
                                  "EVENT_DATE": when, "AMOUNT": round(float(amount), 2) if _num(amount) is not None else "", "CURRENCY": ccy,
                                  "SEQUENCE_NUMBER": n}

    def events(self, legs: list) -> list:
        out = []
        n = 0
        for t in (self.d.get("lifecycle") or {}).get("timeline") or []:
            n += 1
            # a deal-level amount is in the currency of the leg whose notional it is (a cross-currency swap's yen leg), else the deal's
            amt = _num(t.get("amount"))
            ccy = next((lg["CURRENCY"] for lg in legs if amt is not None and _num(lg.get("NOTIONAL")) is not None and abs(_num(lg["NOTIONAL"])) == abs(amt)),
                       self.d["currency"])
            out.append(self.event("", n, str(t.get("event", "")).upper().replace(" ", "_"), "", t.get("date", ""), t.get("amount"), ccy))
        for leg in legs:
            sign = -1.0 if leg["LEG_ECONOMIC_TYPE"] == S.PAY else 1.0
            signed_already = any((_num(x.get("_amount")) or 0) < 0 for x in leg["CASH_FLOWS"])    # the model signs a paid amount itself
            for f in leg["CASH_FLOWS"]:
                amount = f.pop("_amount", None)
                if _num(amount) is None or not amount:
                    continue
                n += 1
                signed = amount if signed_already else sign * abs(amount)
                out.append(self.event(leg["LEG_ID"], n, "CASH_SETTLEMENT", f["FLOW_TYPE"] or "Interest", f["PAYMENT_DATE"], signed, leg["CURRENCY"]))
        for leg in legs:
            for f in leg["CASH_FLOWS"]:
                f.pop("_amount", None)
        return out

    # ---- the deal ----------------------------------------------------------------------------------------------------
    def trade(self) -> dict:
        d = self.d
        terms = d.get("terms") or {}
        cp = d["counterparty"]["id"]
        ref = R.CPTY.get(cp, {})
        le = d.get("legalEntity") or R.legal_entity_of(d["desk"])
        trader = next((n for n in R.traders_of(d["desk"]) if R.trader_id(n) == d.get("trader")), "")
        first, last = R.trader_names(trader) if trader else ("", "")
        version = int((d.get("lifecycle") or {}).get("version") or 1)
        direction = str(d.get("direction", ""))
        buy = direction.split(" ")[0] in ("Pay", "Buy", "Long", "Repo")
        price = next((terms[k] for k in PRICE_TERMS if _num(terms.get(k)) is not None), None)
        quantity = next((terms[k] for k in QUANTITY_TERMS if _num(terms.get(k)) is not None), d.get("notional"))
        legs = self.legs()
        status = STATUS.get(d.get("status"), "VALIDATED")
        if status == "VALIDATED" and version > 1:
            status = "AMENDED"
        out = {
            "SOURCE_SYSTEM": system_of(d), "SOURCE_UPDATE_TIME": f"{self.as_of}T18:30:00Z", "TRADE_DATE": d["tradeDate"], "INPUT_DATE": d["tradeDate"],
            "TRADE_ID": d["tradeId"], "TRADE_STATUS": status, "TRADE_ACTION": "AMEND" if version > 1 else "NEW", "TRADE_VERSION": str(version),
            "TRANSACTION_ID": f"{self.native}{version:03d}", "PACKAGE_ID": "", "QUANTITY": float(quantity or 0.0),
            "PRICE": float(price) if price is not None else None, "BUY_SELL_CODE": S.BUY if buy else S.SELL,
            "PRODUCT_TYPE": d["productType"], "PRODUCT_SUB_TYPE": d.get("productName", ""), "PRODUCT_GROUP": d.get("assetClass", ""),
            "PRODUCT_SUB_GROUP": d.get("family", ""), "OPTION_TYPE": terms.get("optionType") or terms.get("payerReceiver") or terms.get("capFloor"),
            "STRIKE_PRICE": float(terms["strike"]) if _num(terms.get("strike")) is not None else None,
            "COUNTERPARTY": cp, "COUNTERPARTY_UEN": ref.get("uen", ""), "COUNTERPARTY_COUNTRY_OF_RISK": ref.get("country"),
            "ORIGINAL_COUNTERPARTY": "", "ORIGINAL_COUNTERPARTY_UEN": "", "INTERNAL_EXTERNAL_CODE": "External", "TRANSIT": R.TRANSIT.get(le, ""),
            "LEGAL_ENTITY": le, "PORTFOLIO": d["book"], "TRADER_ID": d.get("trader", ""), "TRADER_FIRST_NAME": first, "TRADER_LAST_NAME": last,
            "START_DATE": d.get("effectiveDate", ""), "MATURITY_DATE": terms.get("expiryDate") or d.get("maturityDate", ""), "REGISTRY_DELIVERY_DATE": "",
            "CURRENCY": d["currency"], "DELIVERY_VERSUS_PAYMENT": "YES" if d.get("assetClass") in DVP_ASSETS else "NO", "STRATEGY": None,
            "INTERNAL_TRADING_STRATEGY": "", "NY_CURVE_FLAG": "", "VAULT_LOCATION_ALLOCATION": "",
            "PRECIOUS_METAL_FLAG": "Y" if any(m in str(d.get("commodity", "")) for m in ("GOLD", "SILVER")) else "", "TT_GROUP": d["desk"],
            "TT_BOOK": d["book"], "LEASE_PERPETUITY_FLAG": "", "SETTLEMENT_DATE": d.get("effectiveDate", ""), "LEGS": legs, "EVENTS": []}
        out["EVENTS"] = self.events(legs)
        return out


def convert(doc: dict, as_of: str) -> dict:
    """A rich internal trade (data_trades.py) as its booking system's feed writes it: the common schema, nothing else."""
    return S.text_ids(Converter(doc, as_of).trade())
