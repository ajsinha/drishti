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
``scenario-set`` (HS-509) and ``var-limit`` (the bank, divisions and desks); and once, <out>/system-baskets.yaml (--portfolios: a hierarchy
system basket per book and desk, rule baskets, and baskets of positions, --big-baskets of them with --basket-size positions): the system
baskets are no data (VAR_GUIDE.md section 5.7), imported with ``drishti.py var baskets import <out>/system-baskets.yaml --apply``. Every vector row carries DIVISION, LEGAL_ENTITY, DESK,
TT_BOOK and LIMIT_LETTER: the market-risk pack's hierarchies (MGMT: bank, --divisions, --desks, --books; LEGAL: bank, --les, desks, books)
are built from them by the server. The trade ids are the same on every date (the second date's
vectors move a little), so two open dates hold the same positions. Deterministic for a --seed. Never writes into the repository.
Load with ``drishti.py lake build --pack config/packs/banking/market-risk --connector risk-store --kind <kind> ...`` (tools/bench/var_bench.py).
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lake"))
import feedfiles as FF  # noqa: E402  (our dated feed files and tars)

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


def system_baskets(a, bank: Bank) -> list[dict]:
    """The benchmark's system baskets, in the import file's form (drishti.py var baskets import, VAR_GUIDE.md section 5.7)."""
    rng = np.random.default_rng(a.seed + 7)
    out = []

    def add(pid, name, typ, rolls, **definition):
        if len(out) < a.portfolios:
            out.append({"id": pid, "name": name, "owner": "bench", "type": typ, **definition, "rolls-up-to": rolls, "valid-from": "2026-01-02"})

    for i in range(a.big_baskets):                                            # the 50,000-position baskets of the VaR mix first
        members = rng.choice(a.positions, size=min(a.basket_size, a.positions), replace=False)
        add(f"PF-BASKET-BIG-{i:02d}", f"Large basket {i}", "basket", {"kind": "bank", "id": "BANK"},
            members=[{"position": bank.position_id(int(m))} for m in members])
    for i in range(a.small_baskets):
        members = rng.choice(a.positions, size=int(rng.integers(50, 2000)), replace=False)
        add(f"PF-BASKET-{i:03d}", f"Basket {i}", "basket", {"kind": "bank", "id": "BANK"},
            members=[{"position": bank.position_id(int(m))} for m in members])
    for i in range(a.rules):
        ccy, grp = CCYS[i % len(CCYS)], PRODUCTS[i % len(PRODUCTS)][0]
        add(f"PF-RULE-{i:03d}", f"{grp} in {ccy} ({i})", "rule", {"kind": "bank", "id": "BANK"},
            rule=f"TRADE_CURRENCY == '{ccy}' and PRODUCT_GROUP == '{grp}'")
    for d in bank.desks:
        add(f"PF-{d}", f"Desk {d}", "hierarchy", {"kind": "desk", "id": d}, node=f"MGMT~{d}")
    for b in bank.books:
        add(f"PF-{b}", f"Book {b}", "hierarchy", {"kind": "book", "id": b}, node=f"MGMT~{b}")
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
    ap.add_argument("--big-baskets", type=int, default=10, help="system baskets of --basket-size positions")
    ap.add_argument("--basket-size", type=int, default=50_000)
    ap.add_argument("--small-baskets", type=int, default=90)
    ap.add_argument("--rules", type=int, default=300)
    ap.add_argument("--parts", type=int, default=20, help="pnl-vector files a date")
    ap.add_argument("--seed", type=int, default=20261031)
    ap.add_argument("--kinds", default="pnl-vector,scenario-set,var-limit,system-baskets",
                    help="what to write; system-baskets: <out>/system-baskets.yaml, to import (not data)")
    a = ap.parse_args(argv)
    out = Path(a.out).resolve()
    if (Path(__file__).resolve().parents[2] / "data") in [out, *out.parents]:
        ap.error("--out must not be inside the repository's data/")
    kinds = set(a.kinds.split(","))
    bank = Bank(a)
    if "system-baskets" in kinds:                    # not data: the import file (drishti.py var baskets import <file> --apply)
        out.mkdir(parents=True, exist_ok=True)
        sb = system_baskets(a, bank)
        (out / "system-baskets.yaml").write_text(json.dumps({"system-baskets": sb}), encoding="utf-8")   # JSON is YAML
        print(f"system-baskets.yaml: {len(sb):,}", flush=True)
    for d_index, d in enumerate(a.dates.split(",")):
        day = date.fromisoformat(d)
        stem = f"{{kind}}.{d}"                       # the load rules' name, <table>.<YYYY-MM-DD>.jsonl.gz (DATA_LAKE_GUIDE.md section 5.2)
        if "scenario-set" in kinds:
            ds = scenario_dates(day)
            write_lines(out / "scenario-set" / f"{stem.format(kind='scenario-set')}.jsonl.gz", [json.dumps({
                "SCENARIO_SET_ID": "HS-509", "BUSINESS_DATE": d, "SCENARIO_DATES": ds, "SCENARIO_COUNT": N_SCEN, "WINDOW_START": ds[0],
                "WINDOW_END": ds[-1], "HORIZON_DAYS": 1, "REVALUATION": "full", "WEIGHTS": None, "SOURCE_BATCH": f"B-{d}-01",
                "DESCRIPTION": "Historical simulation, 509 business days, equal weights (benchmark)"})])
        if "var-limit" in kinds:
            write_lines(out / "var-limit" / f"{stem.format(kind='var-limit')}.jsonl.gz", (json.dumps(r) for r in limits(bank, d)))
        if "pnl-vector" in kinds:
            total, parts = 0, []
            for part, lines in vectors(a, bank, day, d_index):         # the date's parts, then one tar of pnl-vector/<date>/ (the rules)
                parts.append(out / "pnl-vector" / f"part-{part + 1:03d}.jsonl.gz")
                total += write_lines(parts[-1], lines)
            FF.pack_tar(out / "pnl-vector" / FF.tar_name("pnl-vector", d), "pnl-vector", {d: parts})
            print(f"{d}: pnl-vector {total:,} rows in {a.parts} files ({FF.tar_name('pnl-vector', d)})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
