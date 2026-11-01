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
"""Synthetic VaR inputs at benchmark size (docs/admin/BENCHMARK.md section 4): the market-risk
pack's contract rows of N positions (one OTC trade each, so N positions) over D business dates, as JSON Lines, for the 2.9.7 benchmark.

    python3 tools/bench/vargen.py --out <scratch>/vargen --positions 200000 --portfolios 1500 --dates 2026-09-29,2026-09-30

Per date, under <out>/<kind>/: ``pnl-vector`` (509 USD P&Ls a trade from a 40-factor model, split into --parts gzip files),
``scenario-set`` (HS-509), ``risk-hierarchy`` (MGMT: bank, --divisions, --desks, --books; LEGAL: bank, --les, desks, books),
``portfolio`` (--portfolios: a HIERARCHY portfolio per book and desk, RULE portfolios, and BASKET portfolios, --big-baskets of them with
--basket-size positions) and ``var-limit`` (the bank, divisions and desks). The trade ids are the same on every date (the second date's
vectors move a little), so two open dates hold the same positions. Deterministic for a --seed. Never writes into the repository.
Load with ``drishti.py lake build --pack config/packs/market-risk --connector risk-store --kind <kind> ...`` (tools/bench/var_bench.py).
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np

N_SCEN = 509
FACTORS = 40
PRODUCTS = [("Rates", "IRS"), ("Rates", "XCCY_SWAP"), ("Rates", "SWAPTION"), ("FX", "FX_FORWARD"), ("FX", "FX_OPTION"),
            ("Credit", "CDS"), ("Commodities", "CMD_SWAP"), ("Equities", "EQ_SWAP")]
CCYS = ["USD", "EUR", "GBP", "JPY", "CAD", "CHF"]


def scenario_dates(day: date) -> list[str]:
    out, d = [], day - timedelta(days=1)
    while len(out) < N_SCEN:
        if d.weekday() < 5:
            out.append(d.isoformat())
        d -= timedelta(days=1)
    return sorted(out)


class Bank:
    """The shape: les legal entities, divisions, desks (each in one entity and one division), books (books ÷ desks a desk), positions
    spread evenly over the books."""

    def __init__(self, a):
        self.a = a
        self.les = [f"LE-{i:02d}" for i in range(a.les)]
        self.divs = [f"DIV-{i:02d}" for i in range(a.divisions)]
        self.desks = [f"DESK-{i:03d}" for i in range(a.desks)]
        self.desk_le = {d: self.les[i % a.les] for i, d in enumerate(self.desks)}
        self.desk_div = {d: self.divs[i % a.divisions] for i, d in enumerate(self.desks)}
        per = max(1, a.books // a.desks)
        self.books = [f"BOOK-{d[5:]}-{j}" for d in self.desks for j in range(per)]
        self.book_desk = {b: self.desks[i // per] for i, b in enumerate(self.books)}
        self.trade_book = [self.books[i % len(self.books)] for i in range(a.positions)]

    def trade_id(self, i: int) -> str:
        return f"BT-{10_000_000 + i}"

    def position_id(self, i: int) -> str:
        return f"POS-{self.trade_book[i]}~{self.trade_id(i)}"


def write_lines(path: Path, lines) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=3) as f:
        for line in lines:
            f.write(line)
            f.write("\n")
            n += 1
    return n


def vectors(a, bank: Bank, day: date, d_index: int):
    """The P&L rows of one date, as JSON lines, in --parts files of equal size."""
    rng = np.random.default_rng(a.seed)
    fac = rng.standard_t(4, size=(N_SCEN, FACTORS)) * 0.01                    # fat-tailed factor returns, the same every date
    load_idx = rng.integers(0, FACTORS, size=(a.positions, 3))
    load_w = rng.normal(0, 1, size=(a.positions, 3))
    notional = rng.lognormal(15, 1.2, size=a.positions) * rng.choice([-1, 1], size=a.positions)
    products = rng.integers(0, len(PRODUCTS), size=a.positions)
    ccys = rng.integers(0, len(CCYS), size=a.positions)
    cpty = rng.integers(0, a.counterparties, size=a.positions)
    letters = rng.integers(0, 12, size=a.positions)
    day_rng = np.random.default_rng(a.seed + 1000 + d_index)                 # the date's own small moves
    bd = day.isoformat()
    per_part = -(-a.positions // a.parts)
    chunk = 5000
    for part in range(a.parts):
        lo, hi = part * per_part, min(a.positions, (part + 1) * per_part)

        def lines(lo=lo, hi=hi):
            for c0 in range(lo, hi, chunk):
                c1 = min(hi, c0 + chunk)
                w = load_w[c0:c1] * (1 + 0.02 * d_index)
                pnl = np.zeros((c1 - c0, N_SCEN))
                for k in range(3):
                    pnl += w[:, k:k + 1] * fac[:, load_idx[c0:c1, k]].T
                pnl += day_rng.normal(0, 0.002, size=pnl.shape)
                pnl *= notional[c0:c1, None] * 0.05
                pnl = np.rint(pnl).astype(np.int64)
                for j, i in enumerate(range(c0, c1)):
                    book = bank.trade_book[i]
                    desk = bank.book_desk[book]
                    grp, typ = PRODUCTS[products[i]]
                    tid = bank.trade_id(i)
                    head = {"VALUE_TYPE": "PNL", "GRAIN": "TRADE", "TRADE_ID": tid, "CURRENCY": "USD", "SOURCE_SYSTEM": "BENCH",
                            "VECTOR_ID": f"PNL-{tid}-HS-509", "SECURITY_ID": None, "INSTRUMENT": f"{typ} {CCYS[ccys[i]]}",
                            "BUSINESS_DATE": bd, "SCENARIO_SET_ID": "HS-509", "TRADE_VERSIONS": "v1", "TRADE_STATUS": "VALIDATED",
                            "MATURITY_DATE": "2031-06-30", "LEGAL_ENTITY": bank.desk_le[desk], "DIVISION": bank.desk_div[desk],
                            "DESK": desk, "TT_BOOK": book, "LIMIT_LETTER": f"LL-{letters[i]:04d}", "PORTFOLIO": book, "STRATEGY": "",
                            "PRODUCT_GROUP": grp, "PRODUCT_TYPE": typ, "COUNTERPARTY": f"CP-{cpty[i]:05d}",
                            "DIRECTION": "LONG" if notional[i] > 0 else "SHORT", "QUANTITY": float(round(notional[i])), "TAGS": "",
                            "TRADE_CURRENCY": CCYS[ccys[i]], "REPORTING_CCY": "USD", "PV": int(notional[i] * 0.01)}
                    tail = {"STATUS": "ok", "PRICER": "bench-factor-model", "MODEL_VERSION": "bench-1", "COMPUTED_AT": f"{bd}T06:00:00Z",
                            "BATCH_ID": f"B-{bd}-01", "RESTATEMENT": 0, "RUN_KIND": "FULL"}
                    yield (json.dumps(head)[:-1] + ', "PNL": [' + ",".join(map(str, pnl[j].tolist())) + "], "
                           + json.dumps(tail)[1:])
        yield part, lines()


def hierarchy(bank: Bank, bd: str) -> list[dict]:
    common = {"BUSINESS_DATE": bd, "VALID_FROM": "2026-01-02", "VALID_TO": None}
    rows = []
    desk_books: dict[str, list[str]] = {}
    for b in bank.books:
        desk_books.setdefault(bank.book_desk[b], []).append(b)
    for h in ("MGMT", "LEGAL"):
        groups = bank.divs if h == "MGMT" else bank.les
        of = bank.desk_div if h == "MGMT" else bank.desk_le
        level = "division" if h == "MGMT" else "legal-entity"
        rows.append({"NODE_ID": f"{h}~BANK", "HIERARCHY": h, "LEVEL": "bank", "PARENT_ID": None, "NAME": "Bench Bank", "REF": None,
                     "CHILDREN": [f"{h}~{g}" for g in groups], "BOOKS": list(bank.books), **common})
        for g in groups:
            desks = [d for d in bank.desks if of[d] == g]
            rows.append({"NODE_ID": f"{h}~{g}", "HIERARCHY": h, "LEVEL": level, "PARENT_ID": f"{h}~BANK", "NAME": g,
                         "REF": {"KIND": level, "ID": g} if h == "LEGAL" else None, "CHILDREN": [f"{h}~{d}" for d in desks],
                         "BOOKS": [b for d in desks for b in desk_books[d]], **common})
            for d in desks:
                rows.append({"NODE_ID": f"{h}~{d}", "HIERARCHY": h, "LEVEL": "desk", "PARENT_ID": f"{h}~{g}", "NAME": d,
                             "REF": {"KIND": "desk", "ID": d}, "CHILDREN": [f"{h}~{b}" for b in desk_books[d]], "BOOKS": desk_books[d], **common})
                for b in desk_books[d]:
                    rows.append({"NODE_ID": f"{h}~{b}", "HIERARCHY": h, "LEVEL": "book", "PARENT_ID": f"{h}~{d}", "NAME": b,
                                 "REF": {"KIND": "book", "ID": b}, "CHILDREN": [], "BOOKS": [b], **common})
    return rows


def portfolios(a, bank: Bank, bd: str) -> list[dict]:
    common = {"BUSINESS_DATE": bd, "VALID_FROM": "2026-01-02", "VALID_TO": None}
    rng = np.random.default_rng(a.seed + 7)
    out = []

    def add(pid, name, typ, definition, rolls):
        if len(out) < a.portfolios:
            out.append({"PORTFOLIO_ID": pid, "NAME": name, "OWNER": "bench", "TYPE": typ, "DEFINITION": definition, "ROLLS_UP_TO": rolls, **common})

    for i in range(a.big_baskets):                                            # the 50,000-position baskets of the VaR mix first
        members = rng.choice(a.positions, size=min(a.basket_size, a.positions), replace=False)
        add(f"PF-BASKET-BIG-{i:02d}", f"Large basket {i}", "BASKET", {"MEMBERS": [{"POSITION_ID": bank.position_id(int(m))} for m in members]},
            {"KIND": "bank", "ID": "BANK"})
    for i in range(a.small_baskets):
        members = rng.choice(a.positions, size=int(rng.integers(50, 2000)), replace=False)
        add(f"PF-BASKET-{i:03d}", f"Basket {i}", "BASKET", {"MEMBERS": [{"POSITION_ID": bank.position_id(int(m))} for m in members]},
            {"KIND": "bank", "ID": "BANK"})
    for i in range(a.rules):
        ccy, grp = CCYS[i % len(CCYS)], PRODUCTS[i % len(PRODUCTS)][0]
        add(f"PF-RULE-{i:03d}", f"{grp} in {ccy} ({i})", "RULE", {"RULE": f"TRADE_CURRENCY == '{ccy}' and PRODUCT_GROUP == '{grp}'"},
            {"KIND": "bank", "ID": "BANK"})
    for d in bank.desks:
        add(f"PF-{d}", f"Desk {d}", "HIERARCHY", {"NODE": f"MGMT~{d}"}, {"KIND": "desk", "ID": d})
    for b in bank.books:
        add(f"PF-{b}", f"Book {b}", "HIERARCHY", {"NODE": f"MGMT~{b}"}, {"KIND": "book", "ID": b})
    return out


def limits(bank: Bank, bd: str) -> list[dict]:
    common = {"CURRENCY": "USD", "BUSINESS_DATE": bd, "VALID_FROM": "2026-01-02", "VALID_TO": None}
    nodes = ["MGMT~BANK"] + [f"MGMT~{d}" for d in bank.divs] + [f"MGMT~{d}" for d in bank.desks]
    return [{"LIMIT_ID": f"VL-{n}", "HIERARCHY": "MGMT", "NODE_ID": n, "LIMIT_VAR": 5_000_000_000 if n.endswith("BANK") else 500_000_000, **common}
            for n in nodes]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", required=True, help="the folder the files go to (never the repository's data/)")
    ap.add_argument("--positions", type=int, default=200_000)
    ap.add_argument("--portfolios", type=int, default=1500)
    ap.add_argument("--dates", default="2026-09-29,2026-09-30")
    ap.add_argument("--les", type=int, default=4)
    ap.add_argument("--divisions", type=int, default=5)
    ap.add_argument("--desks", type=int, default=100)
    ap.add_argument("--books", type=int, default=1000)
    ap.add_argument("--counterparties", type=int, default=10_000)
    ap.add_argument("--big-baskets", type=int, default=10, help="BASKET portfolios of --basket-size positions")
    ap.add_argument("--basket-size", type=int, default=50_000)
    ap.add_argument("--small-baskets", type=int, default=90)
    ap.add_argument("--rules", type=int, default=300)
    ap.add_argument("--parts", type=int, default=20, help="pnl-vector files a date")
    ap.add_argument("--seed", type=int, default=20261031)
    ap.add_argument("--kinds", default="pnl-vector,scenario-set,risk-hierarchy,portfolio,var-limit")
    a = ap.parse_args(argv)
    out = Path(a.out).resolve()
    if (Path(__file__).resolve().parents[2] / "data") in [out, *out.parents]:
        ap.error("--out must not be inside the repository's data/")
    kinds = set(a.kinds.split(","))
    bank = Bank(a)
    for d_index, d in enumerate(a.dates.split(",")):
        day = date.fromisoformat(d)
        stem = f"cob-{d}"
        if "scenario-set" in kinds:
            ds = scenario_dates(day)
            write_lines(out / "scenario-set" / f"{stem}.jsonl.gz", [json.dumps({
                "SCENARIO_SET_ID": "HS-509", "BUSINESS_DATE": d, "SCENARIO_DATES": ds, "SCENARIO_COUNT": N_SCEN, "WINDOW_START": ds[0],
                "WINDOW_END": ds[-1], "HORIZON_DAYS": 1, "REVALUATION": "full", "WEIGHTS": None, "SOURCE_BATCH": f"B-{d}-01",
                "DESCRIPTION": "Historical simulation, 509 business days, equal weights (benchmark)"})])
        if "risk-hierarchy" in kinds:
            n = write_lines(out / "risk-hierarchy" / f"{stem}.jsonl.gz", (json.dumps(r) for r in hierarchy(bank, d)))
            print(f"{d}: risk-hierarchy {n:,} nodes", flush=True)
        if "portfolio" in kinds:
            n = write_lines(out / "portfolio" / f"{stem}.jsonl.gz", (json.dumps(r) for r in portfolios(a, bank, d)))
            print(f"{d}: portfolio {n:,}", flush=True)
        if "var-limit" in kinds:
            write_lines(out / "var-limit" / f"{stem}.jsonl.gz", (json.dumps(r) for r in limits(bank, d)))
        if "pnl-vector" in kinds:
            total = 0
            for part, lines in vectors(a, bank, day, d_index):
                total += write_lines(out / "pnl-vector" / f"{stem}.part-{part + 1:03d}.jsonl.gz", lines)
            print(f"{d}: pnl-vector {total:,} rows in {a.parts} files", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
