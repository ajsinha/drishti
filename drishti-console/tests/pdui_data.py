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
"""A small multi-file JSON Lines set for the Pack designer's tests: synthetic trades (PRODUCT_TYPE x SOURCE_SYSTEM), three files
(one gzipped), six combinations of 60 to 1 rows, one of them rare. Written where the test says (under the worktree's data/), never committed."""
import gzip
import json
import pathlib
import random

COMBOS = [("IRS", "ENDUR", 60), ("IRS", "MUREX", 40), ("FXFWD", "ENDUR", 30), ("FXFWD", "SUMMIT", 20), ("SWAPTION", "MUREX", 12), ("CAPFLOOR", "SUMMIT", 1)]


def make(folder: pathlib.Path, date: str = "2026-07-08") -> list[pathlib.Path]:
    """The files written (trade_endur.<date>.jsonl.gz, trade_murex.<date>.jsonl, trade_summit.<date>.jsonl); ids are unique across them."""
    rnd = random.Random(3)
    folder.mkdir(parents=True, exist_ok=True)
    rows: dict[str, list[dict]] = {}
    n = 0
    for product, system, count in COMBOS:
        for _ in range(count):
            n += 1
            rows.setdefault(system, []).append({
                "TRADE_ID": f"T{n:05d}", "PRODUCT_TYPE": product, "SOURCE_SYSTEM": system, "BOOK": rnd.choice(["RATES-NY", "RATES-LDN", "XCCY-TOR"]),
                "NOTIONAL": rnd.randint(1, 50) * 1_000_000, "STRIKE": round(rnd.random() * 0.05, 4), "EXPIRY_DATE": "2031-03-14",
                "PV": rnd.randint(-500_000, 500_000), "BUSINESS_DATE": date})
    out = []
    for system, docs in sorted(rows.items()):
        f = folder / (f"trade_{system.lower()}.{date}.jsonl" + (".gz" if system == "ENDUR" else ""))
        with (gzip.open(f, "wt", encoding="utf-8") if f.suffix == ".gz" else open(f, "w", encoding="utf-8")) as w:
            w.write("".join(json.dumps(d) + "\n" for d in docs))
        out.append(f)
    return out
