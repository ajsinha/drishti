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

"""A synthetic structured note, a deep document of another shape than a commodity swap (camelCase keys, other names, four levels), to show
that the pack generator is generic: note -> couponPeriods[] -> observations[] -> fixings[] (one fixing per underlying per observation day),
beside a flat callSchedule[] and an underlyings[] basket. Every value is invented.

    python3 tools/packgen/note_synth.py --notes 30 --out /tmp/notes.jsonl

The output is JSON Lines, one note per line (docs/guides/DATA_FORMATS.md).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import random
import sys

ISSUERS = ["Issuer North", "Issuer South", "Issuer East", "Issuer West"]
UNDERLYINGS = [("UDL-ALPHA", "Index Alpha", "EUR"), ("UDL-BRAVO", "Index Bravo", "USD"), ("UDL-CEDAR", "Stock Cedar", "USD"), ("UDL-DELTA", "Stock Delta", "GBP")]


def note(n: int, rng: random.Random, base: dt.date = dt.date(2026, 10, 16)) -> dict:
    issue = base - dt.timedelta(days=rng.randint(30, 900))
    periods = rng.choice([4, 8, 12])
    basket = rng.sample(UNDERLYINGS, rng.choice([1, 2, 3]))
    coupon = round(rng.uniform(0.03, 0.11), 4)
    cps = []
    for p in range(periods):
        start = issue + dt.timedelta(days=91 * p)
        end = start + dt.timedelta(days=91)
        obs = []
        for o in range(rng.choice([1, 3])):
            day = end - dt.timedelta(days=7 * o)
            obs.append({"observationDate": day.isoformat(), "barrierHit": rng.random() < 0.1,
                        "fixings": [{"underlyingId": u[0], "level": round(rng.uniform(80, 120), 2), "source": "Closing"} for u in basket]})
        cps.append({"periodNumber": p + 1, "startDate": start.isoformat(), "endDate": end.isoformat(), "paymentDate": (end + dt.timedelta(days=5)).isoformat(),
                    "couponRate": coupon, "couponAmount": round(1_000_000 * coupon / 4, 2) if end < base else None, "status": "Paid" if end < base else "Projected",
                    "observations": obs})
    return {"noteId": f"NOTE-{n + 1:05d}", "isin": f"XS{900000000 + n:010d}", "issuer": rng.choice(ISSUERS), "productType": rng.choice(["Autocall", "Phoenix", "Reverse convertible"]),
            "status": rng.choice(["Live", "Live", "Called", "Matured"]), "currency": rng.choice(["EUR", "USD"]), "notional": float(rng.choice([1, 2, 5]) * 1_000_000),
            "issueDate": issue.isoformat(), "maturityDate": (issue + dt.timedelta(days=91 * periods)).isoformat(), "couponBarrier": 0.7, "knockInBarrier": 0.6,
            "underlyings": [{"underlyingId": u[0], "name": u[1], "currency": u[2], "initialLevel": 100.0, "weight": round(1 / len(basket), 4)} for u in basket],
            "callSchedule": [{"callDate": cp["endDate"], "callLevel": 1.0, "callPrice": 1.0} for cp in cps[1::2]],
            "couponPeriods": cps}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--notes", type=int, default=30)
    p.add_argument("--seed", type=int, default=3)
    p.add_argument("--out", default="-")
    a = p.parse_args(argv)
    rng = random.Random(a.seed)
    text = "".join(json.dumps(note(i, rng), ensure_ascii=False, separators=(",", ":")) + "\n" for i in range(a.notes))
    if a.out == "-":
        sys.stdout.write(text)
    else:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
