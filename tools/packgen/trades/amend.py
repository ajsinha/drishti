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

"""A book over several business days: each day some trades are amended the way a booking system amends them, so a later date differs
from an earlier one where a trader or middle office changed something (and Compare has real changes to show).

An amendment bumps TRADE_VERSION, sets TRADE_ACTION to AMEND, takes a new TRANSACTION_ID and SOURCE_UPDATE_TIME, and changes one thing:
a fixed rate or a spread on one flow, a leg's notional (its flows scaled with it), the status (NEW to VALIDATED, or a cancellation), or
an added event (a fee or a cash settlement on a leg). Deterministic: the same trade, day and seed always amend the same way."""
from __future__ import annotations

import copy
import hashlib
import random

import schema as S

KINDS = ("rate", "notional", "status", "event")


def _rng(trade_id, day: str, seed: int) -> random.Random:
    return random.Random(int(hashlib.sha256(f"{seed}/{trade_id}/{day}".encode()).hexdigest()[:12], 16))


def _num(v):
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return float(v)
    try:
        return float(str(v))
    except ValueError:
        return None


def amend(doc: dict, day: str, seed: int = 5, share: float = 0.06) -> dict:
    """The trade as booked at the end of ``day``: the same document (not copied) when nothing changed that day, else an amended copy."""
    rng = _rng(doc["TRADE_ID"], day, seed)
    if rng.random() >= share or doc["TRADE_STATUS"] in ("MATURED", "CANCELLED"):
        return doc
    d = copy.deepcopy(doc)
    kind = rng.choice(KINDS)
    legs = d.get("LEGS") or []
    flows = [(leg, f) for leg in legs for f in leg.get("CASH_FLOWS") or [] if f.get("PAYMENT_DATE", "") >= day]
    if kind == "rate" and flows:
        leg, f = rng.choice(flows)
        if _num(f.get("RATE")) is not None:
            r = _num(f["RATE"])
            new = round(r * rng.uniform(0.97, 1.03), 6)
            f["RATE"] = f"{new:.4f}" if isinstance(f["RATE"], str) else new
        else:
            f["FLOAT_SPREAD"] = f"{rng.choice([0.05, 0.1, -0.05, 0.25]):.4f}"
    elif kind == "notional" and legs:
        leg = rng.choice(legs)
        factor = rng.choice([0.8, 0.9, 1.1, 1.25])
        leg["NOTIONAL"] = round(float(leg.get("NOTIONAL") or 0.0) * factor, 2)
        for f in leg.get("CASH_FLOWS") or []:
            f["NOTIONAL"] = round(float(f.get("NOTIONAL") or 0.0) * factor, 2)
        if isinstance(d.get("QUANTITY"), (int, float)):
            d["QUANTITY"] = round(d["QUANTITY"] * factor, 2)
    elif kind == "status":
        d["TRADE_STATUS"] = "VALIDATED" if d["TRADE_STATUS"] == "NEW" else ("CANCELLED" if rng.random() < 0.25 else "AMENDED")
    else:
        leg = rng.choice(legs) if legs else None
        events = d.setdefault("EVENTS", [])
        n = len(events) + 1
        ids = [e["EVENT_ID"] for e in events if isinstance(e.get("EVENT_ID"), int)]
        amount = round(float((leg or {}).get("NOTIONAL") or 1e6) * rng.uniform(0.0002, 0.002), 2)
        fee = rng.random() < 0.5
        events.append(S.blank_event() | {"LEG_ID": leg["LEG_ID"] if leg else "", "EVENT_ID": (max(ids) + 1) if ids else int(f"9{n:04d}"),
                                         "EVENT_NUMBER": n, "EVENT_TYPE": "FEE" if fee else "CASH_SETTLEMENT",
                                         "PAYMENT_TYPE": "Amendment fee" if fee else "Adjustment", "EVENT_DATE": day,
                                         "AMOUNT": -amount if fee else amount, "CURRENCY": (leg or {}).get("CURRENCY") or d.get("CURRENCY", ""),
                                         "SEQUENCE_NUMBER": n})
    version = int(_num(d.get("TRADE_VERSION")) or 1) + 1
    d["TRADE_VERSION"] = str(version)
    d["TRADE_ACTION"] = "AMEND"
    d["TRANSACTION_ID"] = str(int(_num(d.get("TRANSACTION_ID")) or 0) + 1)
    d["SOURCE_UPDATE_TIME"] = f"{day}T17:{rng.randint(0, 59):02d}:00Z"
    return d


def history(doc: dict, days: list[str], seed: int = 5, share: float = 0.06) -> list[dict]:
    """The trade on each of ``days`` (oldest first): the first day as given, each later day amended from the one before."""
    out, cur = [], doc
    for i, day in enumerate(days):
        if i:
            cur = amend(cur, day, seed, share)
        out.append(cur)
    return out
