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

"""The blocks every booked trade carries besides its economics: parties, execution, lifecycle and audit,
confirmation, clearing, regulatory reporting, settlement instructions and valuation. Deterministic per trade."""
from __future__ import annotations

import hashlib
import random
from datetime import date, datetime, timedelta

from . import ids

BANK = {"name": "Drishti Bank plc", "shortName": "DRISHTI", "lei": ids.lei("Drishti Bank plc"), "bic": ids.bic("DRSH", "GB", "2L")}
ENTITIES = {"New York": ("Drishti Bank Securities Inc.", "US", "CFTC / SEC"), "London": ("Drishti Bank plc", "GB", "PRA / FCA"),
            "Frankfurt": ("Drishti Bank Europe AG", "DE", "ECB / BaFin"), "Chicago": ("Drishti Bank Securities Inc.", "US", "CFTC"),
            "Singapore": ("Drishti Bank plc, Singapore Branch", "SG", "MAS"), "Tokyo": ("Drishti Securities Japan K.K.", "JP", "JFSA")}
VENUES = {"Rates": ["Tradeweb SEF", "Bloomberg SEF", "Voice"], "FX": ["360T", "FXall", "EBS", "Voice"],
          "Commodity": ["CME Globex", "ICE", "Voice"], "Credit": ["MarketAxess", "Tradeweb", "Voice"], "Equity": ["NYSE", "Voice"]}
CCP = {"USD": "LCH SwapClear", "EUR": "LCH SwapClear", "GBP": "LCH SwapClear", "JPY": "JSCC", "CHF": "LCH SwapClear"}


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def entity(location: str) -> dict:
    name, country, reg = ENTITIES.get(location, ENTITIES["London"])
    return {"name": name, "lei": ids.lei(name), "country": country, "regulators": reg}


def party(cp: dict) -> dict:
    """Counterparty reference as carried on a trade."""
    return {"id": cp["id"], "name": cp["name"], "lei": ids.lei(cp["name"])}


def execution(trade_id: str, trade_date: date, asset: str, trader: str, sales: str | None = None) -> dict:
    r = rng(trade_id + "exec")
    ts = datetime(trade_date.year, trade_date.month, trade_date.day, r.randint(8, 16), r.randint(0, 59), r.randint(0, 59), r.randint(0, 999) * 1000)
    venue = r.choice(VENUES.get(asset, ["Voice"]))
    return {"tradeDate": trade_date.isoformat(), "executionTimestamp": ts.isoformat(timespec="milliseconds") + "Z",
            "venue": venue, "venueMic": {"Voice": "XOFF", "Tradeweb SEF": "TWSF", "Bloomberg SEF": "BSEF", "360T": "360T",
                                         "FXall": "FXAL", "EBS": "EBSM", "CME Globex": "XCME", "ICE": "IFEU", "MarketAxess": "MAEL",
                                         "Tradeweb": "TWEM", "NYSE": "XNYS"}.get(venue, "XOFF"),
            "executionType": "Voice" if venue == "Voice" else "Electronic", "trader": trader, "salesperson": sales,
            "orderId": "ORD-" + hashlib.sha1(trade_id.encode()).hexdigest()[:10].upper()}


def lifecycle(trade_id: str, trade_date: date, as_of: date, events: list[tuple[str, str]] | None = None) -> dict:
    """Version history: New, optionally amendments or partial terminations, with who and when."""
    r = rng(trade_id + "life")
    base = [("New", "Trade captured")] + (events or [])
    hist, t = [], datetime(trade_date.year, trade_date.month, trade_date.day, 9, 0)
    for v, (ev, why) in enumerate(base, 1):
        t += timedelta(minutes=r.randint(1, 240)) if v == 1 else timedelta(days=r.randint(1, max(1, (as_of - trade_date).days // len(base) or 1)))
        hist.append({"version": v, "event": ev, "reason": why, "at": t.isoformat(timespec="seconds") + "Z",
                     "by": r.choice(["mo.ops", "fo.capture", "stp.gateway", "mo.amend"])})
    return {"status": "Live", "version": len(hist), "events": hist, "sourceSystem": r.choice(["Murex", "Calypso", "Summit", "Endur"]),
            "createdAt": hist[0]["at"], "lastModifiedAt": hist[-1]["at"], "lastModifiedBy": hist[-1]["by"]}


def confirmation(trade_id: str, trade_date: date, method: str = "MarkitWire") -> dict:
    r = rng(trade_id + "conf")
    matched = datetime(trade_date.year, trade_date.month, trade_date.day, r.randint(10, 17), r.randint(0, 59))
    return {"status": "Confirmed", "method": method, "platformId": method[:2].upper() + str(r.randint(10**8, 10**9 - 1)),
            "matched": matched.strftime("%Y-%m-%d %H:%M"), "affirmedBy": "Counterparty ops", "breaks": []}


def clearing(trade_id: str, ccy: str, cleared: bool, account: str | None = None) -> dict:
    if not cleared:
        return {"status": "Bilateral", "mandatory": False, "reason": "Counterparty below clearing threshold"}
    r = rng(trade_id + "clr")
    return {"status": "Cleared", "mandatory": True, "ccp": CCP.get(ccp_key(ccy), "LCH SwapClear"), "clearingBroker": "Summit Clearing LLC",
            "ccpTradeId": "LCH" + str(r.randint(10**9, 10**10 - 1)), "account": account or "House", "novatedAt": None}


def ccp_key(ccy: str) -> str:
    return ccy


def regulatory(trade_id: str, reporting_lei: str, asset: str, product: str, jurisdiction: str, cleared: bool) -> dict:
    regimes = {"US": ["CFTC Part 43/45"], "GB": ["UK EMIR", "UK MiFIR"], "DE": ["EU EMIR", "EU MiFIR"], "SG": ["MAS"], "JP": ["JFSA"]}
    return {"uti": ids.uti(reporting_lei, trade_id), "upi": ids.upi(asset, product), "reportingRegimes": regimes.get(jurisdiction, ["EU EMIR"]),
            "reportingParty": "Us", "reportingStatus": "Accepted", "clearingObligation": cleared, "tradingObligation": cleared,
            "mifidClassification": "Professional client", "volckerExempt": False, "saCcrAssetClass": {"Rates": "Interest rate", "FX": "Foreign exchange",
            "Credit": "Credit", "Equity": "Equity", "Commodity": "Commodity"}.get(asset, "Interest rate")}


def settlement(ccy: str, counterparty: str, method: str | None = None) -> dict:
    agents = {"USD": ("JPMorgan Chase Bank, N.A.", ids.bic("CHAS", "US", "33")), "EUR": ("Deutsche Bank AG", ids.bic("DEUT", "DE", "FF")),
              "GBP": ("Barclays Bank plc", ids.bic("BARC", "GB", "22")), "JPY": ("MUFG Bank, Ltd.", ids.bic("BOTK", "JP", "JT"))}
    agent, bic = agents.get(ccy, agents["USD"])
    return {"method": method or ("CLS" if ccy in ("EUR", "GBP", "JPY", "CHF") else "Gross"), "ourAgent": agent, "ourAgentBic": bic,
            "theirAgentBic": ids.bic(counterparty[:4], "US" if ccy == "USD" else "GB", "33"), "ssiId": "SSI-" + ccy + "-" +
            hashlib.sha1(counterparty.encode()).hexdigest()[:6].upper(), "netting": "Payment netting by currency and value date"}


def valuation(as_of: date, mtm: float, ccy: str, model: str, curves: list[str], legs_pv: list[float] | None = None,
              accrued: float = 0.0) -> dict:
    return {"asOf": as_of.isoformat(), "currency": ccy, "npv": mtm, "cleanPv": round(mtm - accrued, 2), "accrued": accrued,
            "legPvs": legs_pv or [], "model": model, "curves": curves, "status": "Official EOD", "runId": "EOD-" + as_of.strftime("%Y%m%d") + "-01"}


def pnl_history(trade_id: str, as_of: date, mtm: float, days: int = 20) -> list[dict]:
    """Daily P&L that sums (with the inception value) to today's MTM."""
    r = rng(trade_id + "pnl")
    moves = [r.gauss(0, max(abs(mtm), 1e5) * 0.03) for _ in range(days)]
    out, d = [], as_of
    for m in reversed(moves):
        while d.weekday() >= 5:
            d -= timedelta(days=1)
        out.append({"date": d.isoformat(), "pnl": round(m)})
        d -= timedelta(days=1)
    return list(reversed(out))
