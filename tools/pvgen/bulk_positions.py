#!/usr/bin/env python3
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
"""A rehearsal batch shaped like production's: position-grain scenario PVs, UPPER_SNAKE, several business dates.

    python tools/pvgen/bulk_positions.py --out data/rehearsal --dates 2026-11-12,2026-11-13 --positions 20000 --scenarios 250 \
        [--parts 4] [--gzip] [--seed 7]

Per date it writes ``scenario-pv.<date>.part-NNNN.jsonl[.gz]`` (--parts files of about equal rows), ``fx-scenario.<date>.jsonl`` and
``scenario-set.<date>.jsonl``: the three inputs a position-grain VaR date needs (docs/guides/DATA_LAKE_GUIDE.md, the scenario PV
runbook). The positions are the same on every date (a few percent retire and arrive), each PV path is one series of daily
moves, so a later date's vector is the earlier one's shifted by a day (the scenario window rolls): "Why it moved" then has something
to explain. Synthetic only; never commit the output.
"""
import argparse
import datetime as dt
import gzip
import json
import os
import sys

import numpy as np

LES = ["LE-NY", "LE-LDN", "LE-TKY"]
DIVS = ["DIV-MARKETS", "DIV-TREASURY"]
PRODUCTS = [("Rates", "IRS"), ("Rates", "BOND_FUTURE"), ("Commodity", "ComSwap"), ("Equity", "EQ_OPTION"),
            ("FX", "FX_FORWARD"), ("Credit", "CDS")]
CCYS = {"USD": 1.0, "EUR": 1.08, "CAD": 0.73, "GBP": 1.27, "JPY": 0.0067}
SYSTEMS = ["ENDUR", "CALYPSO", "MUREX"]


def business_days_before(day, n):
    out = []
    d = day
    while len(out) < n:
        d -= dt.timedelta(days=1)
        if d.weekday() < 5:
            out.append(d)
    return out[::-1]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="the folder the files go to")
    ap.add_argument("--dates", default="2026-11-12,2026-11-13", help="business dates, oldest first, consecutive business days")
    ap.add_argument("--positions", type=int, default=20000)
    ap.add_argument("--scenarios", type=int, default=250, help="values per vector (the scenario set's length)")
    ap.add_argument("--parts", type=int, default=1, help="files per date for the scenario PVs")
    ap.add_argument("--gzip", action="store_true")
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    rng = np.random.default_rng(a.seed)
    dates = [dt.date.fromisoformat(d) for d in a.dates.split(",")]
    n, s = a.positions, a.scenarios
    k = len(dates)
    # one long path per position: len s + k - 1 daily PV moves; date i reads the window [i, i + s)
    scale = np.exp(rng.normal(10.5, 1.2, n))
    moves = rng.standard_t(4, size=(n, s + k - 1)) * (scale[:, None] * 0.004)
    base = rng.normal(0, 1, n) * scale * 3
    book = rng.integers(0, 400, n)
    ext = ".jsonl.gz" if a.gzip else ".jsonl"
    opener = (lambda p: gzip.open(p, "wt", encoding="utf-8", compresslevel=6)) if a.gzip else (lambda p: open(p, "w", encoding="utf-8"))
    for i, day in enumerate(dates):
        iso = day.isoformat()
        set_dates = [d.isoformat() for d in business_days_before(day, s)]
        batch = f"B-{iso}-01"
        with opener(os.path.join(a.out, f"scenario-set.{iso}{ext}")) as f:
            f.write(json.dumps({"SCENARIO_SET_ID": f"HS-{s}", "BUSINESS_DATE": iso, "SCENARIO_DATES": set_dates,
                                "SCENARIO_COUNT": s, "WINDOW_START": set_dates[0], "WINDOW_END": set_dates[-1],
                                "HORIZON_DAYS": 1, "REVALUATION": "full", "SOURCE_BATCH": batch,
                                "DESCRIPTION": f"Historical simulation, {s} business days, equal weights"}) + "\n")
        with opener(os.path.join(a.out, f"fx-scenario.{iso}{ext}")) as f:
            for c, r in CCYS.items():
                if c == "USD":
                    continue
                rates = np.round(r * np.exp(np.cumsum(rng.normal(0, 0.003, s))), 8).tolist()
                f.write(json.dumps({"FX_SCENARIO_ID": f"FXS-{c}-HS-{s}", "BUSINESS_DATE": iso, "SCENARIO_SET_ID": f"HS-{s}",
                                    "CURRENCY": c, "QUOTE": f"USD per 1 {c}", "RATE": r, "RATES": rates,
                                    "SOURCE": "rehearsal", "BATCH_ID": batch}) + "\n")
        per = -(-n // a.parts)
        for part in range(a.parts):
            lo, hi = part * per, min(n, (part + 1) * per)
            name = f"scenario-pv.{iso}.part-{part + 1:04d}{ext}" if a.parts > 1 else f"scenario-pv.{iso}{ext}"
            with opener(os.path.join(a.out, name)) as f:
                for p in range(lo, hi):
                    if i > 0 and p % 50 == 0:      # a few percent arrive and retire between the dates
                        continue
                    le = LES[book[p] % 3]
                    desk = f"DESK-{book[p] % 20:02d}"
                    bk = f"BOOK-{book[p]:03d}"
                    grp, typ = PRODUCTS[p % len(PRODUCTS)]
                    ccy = list(CCYS)[p % 5] if p % 7 == 0 else "USD"
                    pid = f"POS-{bk}~SEC-{typ}-{p:06d}"
                    bpv = float(np.round(base[p] + moves[p, s + i - 1] * 5, 2))
                    pv = np.round(bpv + moves[p, i:i + s], 2).tolist()
                    row = {"VALUE_TYPE": "PV", "GRAIN": "POSITION", "POSITION_ID": pid, "CURRENCY": ccy,
                           "SOURCE_SYSTEM": SYSTEMS[p % 3], "SCENARIO_PV_ID": f"SPV-{pid}-HS-{s}", "SECURITY_ID": f"SEC-{typ}-{p:06d}",
                           "INSTRUMENT": f"{typ} {ccy} 2027-{(p % 12) + 1:02d}", "BUSINESS_DATE": iso, "SCENARIO_SET_ID": f"HS-{s}",
                           "TRADE_STATUS": "VALIDATED", "MATURITY_DATE": f"2027-{(p % 12) + 1:02d}-28", "LEGAL_ENTITY": le,
                           "DIVISION": DIVS[book[p] % 2], "DESK": desk, "TT_BOOK": bk, "LIMIT_LETTER": f"LL-{book[p] % 30:04d}",
                           "PORTFOLIO": bk, "STRATEGY": "", "PRODUCT_GROUP": grp, "PRODUCT_TYPE": typ,
                           "COUNTERPARTY": f"CP-{p % 300:04d}", "DIRECTION": "LONG" if p % 2 else "SHORT",
                           "QUANTITY": float(1000 * (1 + p % 90)), "TAGS": "", "BASE_PV": bpv, "VALUATION_DATE": iso,
                           "BASE_VALUATION_DATE": iso, "SCENARIO_PV": pv, "STATUS": "ok", "PRICER": "rehearsal",
                           "MODEL_VERSION": "r-1", "COMPUTED_AT": f"{iso}T06:00:00Z", "BATCH_ID": batch, "RESTATEMENT": 0,
                           "RUN_KIND": "FULL", "TRADE_IDS": [f"T{p:07d}"]}
                    f.write(json.dumps(row, separators=(",", ":")) + "\n")
        print(f"{iso}: {n} positions x {s} values in {a.parts} file(s) under {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
