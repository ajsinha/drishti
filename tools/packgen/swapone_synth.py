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

"""SwapOne-style trades, synthetic: a made-up swaps booking system's own JSON Lines (its own field names, camelCase, its own enums), for
the Pack designer's source mode (tests, the tutorial docs/guides/BUILD_GUIDE.md). Nothing in it is real: the counterparties,
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
    "TRADE_ID": "dealRef", "TRADE_VERSION": "dealVersion", "TRANSACTION_ID": "txnNo", "TRADE_DATE": "tradeDt", "START_DATE": "effDt",
    "MATURITY_DATE": "endDt", "SETTLEMENT_DATE": "settleDt", "SOURCE_UPDATE_TIME": "lastModified", "TRADE_STATUS": "lifecycle",
    "TRADE_ACTION": "action", "BUY_SELL_CODE": "direction", "PRODUCT_TYPE": "prodType", "PRODUCT_SUB_TYPE": "prodSubType",
    "PRODUCT_GROUP": "assetClass", "QUANTITY": "notionalAmt", "PRICE": "fixedRate", "CURRENCY": "ccy", "COUNTERPARTY": "cptyCode",
    "COUNTERPARTY_UEN": "cptyUen", "COUNTERPARTY_COUNTRY_OF_RISK": "cptyCountry", "LEGAL_ENTITY": "legalEntity", "PORTFOLIO": "book",
    "TRADER_ID": "traderId", "PACKAGE_ID": "packageRef", "DELIVERY_VERSUS_PAYMENT": "dvp", "LEGS": "legs",
}
UNMAPPED = ["clearingHouse", "confirmMethod", "brokerRef", "swOneDeskTag", "traderName"]
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
            "dealRef": f"SW1-{4000000 + i}", "dealVersion": ver, "txnNo": 9100000000 + i * 7 + ver, "tradeDt": trade.isoformat(),
            "effDt": start.isoformat(), "endDt": end.isoformat(), "settleDt": start.isoformat(),
            "lastModified": f"{(trade + dt.timedelta(days=ver - 1)).isoformat()}T{r.randint(7, 19):02d}:{r.randint(0, 59):02d}:00Z",
            "lifecycle": life, "action": "NEW" if ver == 1 else "AMEND", "direction": r.choice(["PAY", "RECEIVE"]), "prodType": pt,
            "prodSubType": sub, "assetClass": grp, "notionalAmt": float(r.choice([5, 10, 25, 50, 100, 250]) * 1_000_000),
            "fixedRate": round(r.uniform(0.005, 0.065), 5), "ccy": r.choice(CCY), "cptyCode": r.choice(CPTY),
            "cptyUen": f"UEN0{r.randint(100000, 999999)}", "cptyCountry": r.choice(COUNTRY), "legalEntity": r.choice(["LE-NY", "LE-LDN", "LE-TOR"]),
            "book": r.choice(BOOKS), "traderId": tid, "traderName": tname, "dvp": r.choice(["Y", "N"]),
            "clearingHouse": r.choice(["LCH", "CME", "EUREX", "BILATERAL"]), "confirmMethod": r.choice(["ELECTRONIC", "PAPER", "PLATFORM"]),
            "swOneDeskTag": r.choice(["SW1-RATES-NY", "SW1-RATES-LDN", "SW1-XCCY"]),
            "legs": [{"legNo": 1, "payRec": "PAY", "rateType": "FIXED", "ccy": None},
                     {"legNo": 2, "payRec": "RECEIVE", "rateType": "FLOAT", "index": r.choice(["SOFR", "ESTR", "SONIA", "CORRA"])}],
        }
        doc["legs"][0]["ccy"] = doc["ccy"]
        if r.random() < 0.3:
            doc["brokerRef"] = f"BRK-{r.randint(10000, 99999)}"
        if r.random() < 0.15:
            doc["packageRef"] = f"PKG-{r.randint(1, 400):04d}"
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
