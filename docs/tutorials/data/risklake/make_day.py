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

"""A day of synthetic, Endur-style risk data for tutorial 16, Part A and docs/guides/DATA_LAKE_GUIDE.md: trades, positions' scenario PVs and the
VaR limits of the management hierarchy's nodes (the hierarchy itself is built by the server from the rows' DESK and TT_BOOK), as JSON Lines named by the load rules (<table>.<YYYY-MM-DD>.jsonl[.gz]). Every value is invented; the same
arguments always write the same files. For each file it prints the rows and the SHA-256 to put in its .ready file.

    python3 docs/tutorials/data/risklake/make_day.py --out drop --date 2026-11-06
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import random
from pathlib import Path

DESKS = {"DESK-RATES": ["IRS", "FRA", "SWAPTION"], "DESK-FX": ["FX_FWD", "FX_SWAP", "FX_OPTION"],
         "DESK-COMM": ["ComSwap", "ComFut", "ComOpt"], "DESK-EQD": ["EQ_OPTION", "EQ_SWAP", "WARRANT"],
         "DESK-CREDIT": ["CDS", "CDS_INDEX", "TRS"]}
COUNTERPARTIES = ["CP-HARBOURVIEW", "CP-REDWOOD", "CP-LONGFIELD", "CP-SILVERLAKE", "CP-MERIDIAN", "CP-OAKMONT"]
CURRENCIES = ["USD", "EUR", "GBP", "CAD", "JPY"]


def _gz(path: Path):
    # mtime 0: the same rows always make the same bytes, so the same SHA-256
    return gzip.GzipFile(filename="", mode="wb", fileobj=open(path, "wb"), mtime=0)


def trades(rnd: random.Random, day: str, n: int):
    for i in range(n):
        desk = rnd.choice(list(DESKS))
        product = rnd.choice(DESKS[desk])
        book = f"BOOK-{desk[5:]}-{rnd.randint(1, 4)}"
        qty = rnd.choice([-1, 1]) * rnd.randint(1, 500) * 1000
        yield {"TRADE_ID": str(7000001 + i), "TRADE_VERSION": rnd.randint(1, 5), "SOURCE_SYSTEM": "ENDUR",
               "TRADE_STATUS": rnd.choice(["VALIDATED", "VALIDATED", "AMENDED"]), "PRODUCT_TYPE": product,
               "PRODUCT_GROUP": desk[5:].title(), "DESK": desk, "BOOK": book, "PORTFOLIO": book,
               "COUNTERPARTY_ID": rnd.choice(COUNTERPARTIES), "LEGAL_ENTITY": rnd.choice(["LE-NY", "LE-LDN", "LE-TOR"]),
               "TRADER_ID": f"TR-{rnd.randint(100, 140)}", "BUY_SELL_CODE": "BUY" if qty > 0 else "SELL",
               "CURRENCY": rnd.choice(CURRENCIES), "QUANTITY": float(qty), "NOTIONAL": abs(float(qty)) * 100,
               "TRADE_DATE": "2026-10-%02d" % rnd.randint(1, 30), "MATURITY_DATE": "20%d-%02d-15" % (rnd.randint(27, 36), rnd.randint(1, 12)),
               "UPDATED_AT": f"{day}T05:%02d:00Z" % rnd.randint(0, 59)}


def positions(rnd: random.Random, day: str, n: int, scenarios: int):
    for i in range(n):
        desk = rnd.choice(list(DESKS))
        product = rnd.choice(DESKS[desk])
        book = f"BOOK-{desk[5:]}-{rnd.randint(1, 4)}"
        security = f"SEC-{product}-{rnd.choice(CURRENCIES)}-{i + 1:04d}"
        pos = f"POS-{book}~{security}"
        base = round(rnd.uniform(-2e6, 2e6), 2)
        yield {"VALUE_TYPE": "PV", "GRAIN": "POSITION", "POSITION_ID": pos, "CURRENCY": "USD", "SOURCE_SYSTEM": "ENDUR",
               "SCENARIO_PV_ID": f"SPV-{pos}-HS-{scenarios}", "BUSINESS_DATE": day, "SCENARIO_SET_ID": f"HS-{scenarios}",
               "LEGAL_ENTITY": "LE-NY", "DIVISION": "DIV-MARKETS", "DESK": desk, "TT_BOOK": book,
               "LIMIT_LETTER": f"LL-{rnd.randint(1, 12):04d}", "PORTFOLIO": book, "PRODUCT_GROUP": desk[5:].title(), "PRODUCT_TYPE": product,
               "SECURITY_ID": security, "DIRECTION": "LONG" if base > 0 else "SHORT", "QUANTITY": float(rnd.randint(1, 900) * 10),
               "BASE_PV": base, "VALUATION_DATE": day, "BASE_VALUATION_DATE": day,
               "SCENARIO_PV": [round(base * (1 + rnd.gauss(0, 0.02)), 2) for _ in range(scenarios)],
               "STATUS": "ok", "COMPUTED_AT": f"{day}T06:00:00Z", "BATCH_ID": f"B-{day}-01", "RUN_KIND": "FULL"}


def limits(day: str):
    """The VaR limit of each node of MGMT (the bank, every desk, every book): 26 var-limit rows."""
    def row(node: str, limit: float):
        return {"LIMIT_ID": f"VL-MGMT~{node}", "HIERARCHY": "MGMT", "NODE_ID": f"MGMT~{node}", "LIMIT_VAR": limit, "CURRENCY": "USD",
                "BUSINESS_DATE": day}
    yield row("BANK", 250_000_000.0)
    for desk in DESKS:
        yield row(desk, 60_000_000.0)
        for b in range(1, 5):
            yield row(f"BOOK-{desk[5:]}-{b}", 20_000_000.0)


def write(path: Path, rows) -> int:
    n = 0
    with (_gz(path) if path.suffix == ".gz" else open(path, "wb")) as f:
        for r in rows:
            f.write((json.dumps(r) + "\n").encode("utf-8"))
            n += 1
    return n


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="drop", help="the folder written (default drop)")
    ap.add_argument("--date", required=True, help="the business date, YYYY-MM-DD")
    ap.add_argument("--trades", type=int, default=2000)
    ap.add_argument("--positions", type=int, default=300)
    ap.add_argument("--scenarios", type=int, default=509)
    a = ap.parse_args(argv)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    rnd = random.Random(a.date)
    for name, rows in ((f"trade_endur.{a.date}.jsonl.gz", trades(rnd, a.date, a.trades)),
                       (f"scenario_pv.{a.date}.jsonl.gz", positions(rnd, a.date, a.positions, a.scenarios)),
                       (f"var_limit.{a.date}.jsonl", limits(a.date))):
        n = write(out / name, rows)
        sha = hashlib.sha256((out / name).read_bytes()).hexdigest()
        print(f"{out / name}  rows: {n}  sha256: {sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
