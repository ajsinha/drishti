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

"""SwapOne-style trades, synthetic: a made-up swaps booking system's own JSON Lines (its own field names, UPPER_SNAKE like Endur, its own enums), for
the Pack designer's source mode (tests, the tutorial docs/tutorials/23-add-a-source-system.md). Nothing in it is real: the counterparties,
books and traders are invented, the numbers are random with a fixed seed.

    python3 tools/packgen/swapone_synth.py --rows 2000 --date 2026-11-13 --out data/tmp/swapone/swapone_trades.2026-11-13.jsonl

TRUTH is the field map a person would write by hand: canonical trade field <- SwapOne field (the test scores the proposal against it)."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import random

TRUTH = {
    "TRADE_ID": "DEAL_REF", "TRADE_VERSION": "DEAL_VERSION", "TRANSACTION_ID": "TXN_NO", "TRADE_DATE": "TRADE_DT", "START_DATE": "EFF_DT",
    "MATURITY_DATE": "END_DT", "SETTLEMENT_DATE": "SETTLE_DT", "SOURCE_UPDATE_TIME": "LAST_MODIFIED", "TRADE_STATUS": "LIFECYCLE",
    "TRADE_ACTION": "ACTION", "BUY_SELL_CODE": "DIRECTION", "PRODUCT_TYPE": "PROD_TYPE", "PRODUCT_SUB_TYPE": "PROD_SUB_TYPE",
    "PRODUCT_GROUP": "ASSET_CLASS", "QUANTITY": "NOTIONAL_AMT", "PRICE": "FIXED_RATE", "CURRENCY": "CCY", "COUNTERPARTY": "CPTY_CODE",
    "COUNTERPARTY_UEN": "CPTY_UEN", "COUNTERPARTY_COUNTRY_OF_RISK": "CPTY_COUNTRY", "LEGAL_ENTITY": "LEGAL_ENTITY", "PORTFOLIO": "BOOK",
    "TRADER_ID": "TRADER_ID", "PACKAGE_ID": "PACKAGE_REF", "DELIVERY_VERSUS_PAYMENT": "DVP", "LEGS": "LEGS",
}
UNMAPPED = ["CLEARING_HOUSE", "CONFIRM_METHOD", "BROKER_REF", "SW_ONE_DESK_TAG", "TRADER_NAME"]
CPTY = ["CP-NORTHWIND", "CP-BLUEHARBOR", "CP-SILVERPEAK", "CP-REDWOODCAP", "CP-LAKESIDE", "CP-ORIONFUND", "CP-MAPLEGATE", "CP-TIDEWATER"]
COUNTRY = ["US", "GB", "CA", "DE", "FR", "JP", "AU"]
BOOKS = ["BOOK-SW1-RATES-1", "BOOK-SW1-RATES-2", "BOOK-SW1-XCCY-1", "BOOK-SW1-FX-1"]
TRADERS = [("TRDR-SW1-AMORI", "A. Mori"), ("TRDR-SW1-KLEE", "K. Lee"), ("TRDR-SW1-PNOVAK", "P. Novak"), ("TRDR-SW1-RDIAZ", "R. Diaz")]
PRODUCTS = [("IRS", "Fixed/Float", "Rates"), ("OIS", "Overnight index", "Rates"), ("BASIS", "Float/Float", "Rates"),
            ("XCCY", "Cross currency MtM", "Rates"), ("FXSWAP", "FX swap", "FX")]
CCY = ["USD", "EUR", "GBP", "CAD", "JPY", "CHF", "AUD"]


def trades(n: int, day: str, seed: int = 7):
    r = random.Random(seed)
    d0 = dt.date.fromisoformat(day)
    for i in range(n):
        pt, sub, grp = r.choice(PRODUCTS)
        trade = d0 - dt.timedelta(days=r.randint(0, 700))
        start = trade + dt.timedelta(days=2)
        end = start + dt.timedelta(days=365 * r.choice([1, 2, 3, 5, 7, 10]))
        ver = r.choice([1, 1, 1, 2, 3])
        tid, tname = r.choice(TRADERS)
        life = r.choices(["LIVE", "AMD", "CXL", "MAT"], [80, 12, 4, 4])[0]
        doc = {
            "DEAL_REF": f"SW1-{4000000 + i}", "DEAL_VERSION": ver, "TXN_NO": 9100000000 + i * 7 + ver, "TRADE_DT": trade.isoformat(),
            "EFF_DT": start.isoformat(), "END_DT": end.isoformat(), "SETTLE_DT": start.isoformat(),
            "LAST_MODIFIED": f"{(trade + dt.timedelta(days=ver - 1)).isoformat()}T{r.randint(7, 19):02d}:{r.randint(0, 59):02d}:00Z",
            "LIFECYCLE": life, "ACTION": "NEW" if ver == 1 else "AMEND", "DIRECTION": r.choice(["PAY", "RECEIVE"]), "PROD_TYPE": pt,
            "PROD_SUB_TYPE": sub, "ASSET_CLASS": grp, "NOTIONAL_AMT": float(r.choice([5, 10, 25, 50, 100, 250]) * 1_000_000),
            "FIXED_RATE": round(r.uniform(0.005, 0.065), 5), "CCY": r.choice(CCY), "CPTY_CODE": r.choice(CPTY),
            "CPTY_UEN": f"UEN0{r.randint(100000, 999999)}", "CPTY_COUNTRY": r.choice(COUNTRY), "LEGAL_ENTITY": r.choice(["LE-NY", "LE-LDN", "LE-TOR"]),
            "BOOK": r.choice(BOOKS), "TRADER_ID": tid, "TRADER_NAME": tname, "DVP": r.choice(["Y", "N"]),
            "CLEARING_HOUSE": r.choice(["LCH", "CME", "EUREX", "BILATERAL"]), "CONFIRM_METHOD": r.choice(["ELECTRONIC", "PAPER", "PLATFORM"]),
            "SW_ONE_DESK_TAG": r.choice(["SW1-RATES-NY", "SW1-RATES-LDN", "SW1-XCCY"]),
            "LEGS": [{"LEG_NO": 1, "PAY_REC": "PAY", "RATE_TYPE": "FIXED", "CCY": None},
                     {"LEG_NO": 2, "PAY_REC": "RECEIVE", "RATE_TYPE": "FLOAT", "INDEX": r.choice(["SOFR", "ESTR", "SONIA", "CORRA"])}],
        }
        doc["LEGS"][0]["CCY"] = doc["CCY"]
        if r.random() < 0.3:
            doc["BROKER_REF"] = f"BRK-{r.randint(10000, 99999)}"
        if r.random() < 0.15:
            doc["PACKAGE_REF"] = f"PKG-{r.randint(1, 400):04d}"
        yield doc


def write(path: pathlib.Path, n: int, day: str, seed: int = 7) -> pathlib.Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as out:
        for d in trades(n, day, seed):
            out.write(json.dumps(d, separators=(",", ":")) + "\n")
    return path


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--rows", type=int, default=2000)
    p.add_argument("--date", default=dt.date.today().isoformat())
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--out", type=pathlib.Path, required=True)
    a = p.parse_args(argv)
    print(write(a.out, a.rows, a.date, a.seed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
