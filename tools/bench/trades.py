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

"""Trades at scale for the scale strategy (docs/architecture/SCALE_STRATEGY.md section 4): synthetic trades of the real shapes of
ENDUR, CALYPSO and MUREX (the committed test data, cloned with new ids), N a day per system for D business days, with counterparties
and netting sets, loaded into a Delta lake with drishti.py lake build, one system and day at a time (one day's file on disk at once).

    python tools/bench/trades.py --lake <data dir>/delta --days 7 --min 300000 --max 500000 --systems endur,calypso,murex \
        --counterparties 10000 --tmp <data dir>/tmp/bench

Ids are stable across days (trade i is the same trade every day, as in a full daily dump) but trades change: each day about
5 % are amended (version up, AMENDED/AMEND, price and first-leg notional moved, update time that day), amendments add up across
the days, and about 1 in 1,000 is cancelled for good from some day on. Counterparty CP-nnnnn and legal entity are a function of
the id; each netting set NS-nnnnn-<LE> lists its trades of the day. Everything written goes under --lake and --tmp, never the system temp folder.
"""
from __future__ import annotations

import argparse
import copy
import gzip
import json
import os
import random
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SYSTEMS = {"endur": (lambda i: str(10_000_000 + i), "trade-endur", 0), "calypso": (lambda i: f"CLY-{30_000_000 + i}", "trade-calypso", 3),
           "murex": (lambda i: f"MX-{50_000_000 + i}", "trade-murex", 5)}
LES = ["LE-NY", "LE-LDN", "LE-FRA"]


def business_days(end: str, n: int) -> list[str]:
    from datetime import date, timedelta
    d, out = date.fromisoformat(end), []
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d.isoformat())
        d -= timedelta(days=1)
    return sorted(out)


AMEND_PCT, CANCEL_PER_MILLE = 5, 1


def _h(i: int, k: int) -> int:
    """A cheap, repeatable hash of trade i on day k (0..99 after % 100)."""
    x = (i * 2654435761 ^ (k + 1) * 40503 * 97) & 0xFFFFFFFF
    x ^= x >> 15
    return (x * 2246822519) & 0xFFFFFFFF


def evolve(doc: dict, i: int, k: int, day: str) -> dict:
    """Trade i as it stands on day k: its amendments so far (each day about 5 % of trades), and cancelled from its day on."""
    amended = [j for j in range(k + 1) if _h(i, j) % 100 < AMEND_PCT]
    cancel_day = _h(i, 1000) % 1000
    cancelled = (_h(i, 2000) % 1000) < CANCEL_PER_MILLE and cancel_day % 30 <= k
    if not amended and not cancelled:
        return doc
    doc = dict(doc)
    if amended:
        n = len(amended)
        doc["TRADE_VERSION"] = str(int(doc.get("TRADE_VERSION") or 1) + n)
        doc["TRADE_STATUS"], doc["TRADE_ACTION"] = "AMENDED", "AMEND"
        doc["TRANSACTION_ID"] = f"{doc.get('TRANSACTION_ID') or i}-{n}"
        move = 1 + ((_h(i, amended[-1] + 500) % 41) - 20) / 1000             # within +-2 % per the last amendment
        if isinstance(doc.get("PRICE"), (int, float)):
            doc["PRICE"] = round(doc["PRICE"] * move, 6)
        if isinstance(doc.get("QUANTITY"), (int, float)) and n % 2 == 0:
            doc["QUANTITY"] = round(doc["QUANTITY"] * (1 + n / 100), 2)
        if doc.get("LEGS") and isinstance(doc["LEGS"][0].get("NOTIONAL"), (int, float)):
            doc["LEGS"] = copy.deepcopy(doc["LEGS"])
            doc["LEGS"][0]["NOTIONAL"] = round(doc["LEGS"][0]["NOTIONAL"] * (1 + n / 100), 2)
        doc["SOURCE_UPDATE_TIME"] = f"{days_of[amended[-1]]}T{9 + _h(i, k) % 9:02d}:{_h(i, k) % 60:02d}:00Z"
    if cancelled:
        doc["TRADE_STATUS"], doc["TRADE_ACTION"] = "CANCELLED", "CANCEL"
    return doc


days_of: list[str] = []


def build(a, src: Path, day: str, pack: str, connector: str, kind: str | None = None, partition: bool = True, key: str | None = None) -> None:
    argv = [sys.executable, str(ROOT / "tools" / "drishti.py"), "lake", "build", "--from", str(src), "--as-of", day,
            "--pack", str(ROOT / "config" / "packs" / pack), "--connector", connector, "--mode", "overwrite-dates", "--lake", a.lake, "-q"]
    argv += ["--partition-by", "PRODUCT_TYPE"] if partition else []
    argv += ["--kind", kind] if kind else []
    argv += ["--key", key] if key else []                    # trades: the pack's ingest.trade.key
    t = time.time()
    r = subprocess.run(argv, capture_output=True, text=True, env={**os.environ, "TMPDIR": a.tmp})
    print(f"  {connector} {day}: exit {r.returncode} in {time.time() - t:.0f} s", flush=True)
    if r.returncode not in (0, 2):
        sys.exit((r.stdout + r.stderr)[-2000:])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--lake", required=True, help="the Delta lake root (the server's <data dir>/delta)")
    ap.add_argument("--tmp", required=True, help="a folder for one day's file at a time (removed after each load)")
    ap.add_argument("--days", type=int, default=7)
    ap.add_argument("--end", default="2026-10-16", help="the last business date")
    ap.add_argument("--min", type=int, default=300_000, help="fewest trades a day per system")
    ap.add_argument("--max", type=int, default=500_000, help="most trades a day per system")
    ap.add_argument("--systems", default="endur,calypso,murex")
    ap.add_argument("--counterparties", type=int, default=10_000, help="netting sets are three per counterparty")
    ap.add_argument("--seed", type=int, default=20261018)
    ap.add_argument("--dry-run", action="store_true", help="say the days and counts, write nothing")
    a = ap.parse_args(argv)
    rnd = random.Random(a.seed)
    systems = [s.strip() for s in a.systems.split(",") if s.strip()]
    days = business_days(a.end, a.days)
    counts = {d: {s: rnd.randint(a.min, a.max) for s in systems} for d in days}
    for d in days:
        print(d, ", ".join(f"{s.upper()} {counts[d][s]:,}" for s in systems), flush=True)
    if a.dry_run:
        return 0
    days_of[:] = days
    tmp = Path(a.tmp)
    tmp.mkdir(parents=True, exist_ok=True)
    base = {s: [json.loads(x) for x in gzip.open(ROOT / "data" / "testdata" / "trades" / s / "snapshot-2026-09-22.jsonl.gz", "rt", encoding="utf-8")]
            for s in systems}
    n_cp = a.counterparties
    for k, d in enumerate(days):
        members: dict[str, list[str]] = {}
        for s in systems:
            ident, connector, salt = SYSTEMS[s]
            path = tmp / f"{s}-{d}.jsonl"
            with open(path, "w", encoding="utf-8") as out:
                for i in range(counts[d][s]):
                    doc = evolve(base[s][i % len(base[s])], i, k, d)
                    doc = doc if doc is not base[s][i % len(base[s])] else dict(doc)
                    doc["TRADE_ID"] = ident(i)
                    c = (i * 7919 + salt) % n_cp
                    le = LES[(i // 7) % 3]
                    doc["COUNTERPARTY"], doc["LEGAL_ENTITY"] = f"CP-{c:05d}", le
                    members.setdefault(f"NS-{c:05d}-{le[3:]}", []).append(doc["TRADE_ID"])
                    out.write(json.dumps(doc, separators=(",", ":")) + "\n")
            build(a, path, d, "trading", connector)
            path.unlink()
        cps, nss = tmp / f"cp-{d}.jsonl", tmp / f"ns-{d}.jsonl"
        with open(cps, "w", encoding="utf-8") as out:
            for c in range(n_cp):
                out.write(json.dumps({"COUNTERPARTY_ID": f"CP-{c:05d}", "NAME": f"Counterparty {c:05d}", "RATING": "A", "COUNTRY_OF_RISK": "GB",
                                      "NETTING_SETS": [{"ID": f"NS-{c:05d}-{le[3:]}", "TRADES": len(members.get(f"NS-{c:05d}-{le[3:]}", []))} for le in LES]},
                                     separators=(",", ":")) + "\n")
        with open(nss, "w", encoding="utf-8") as out:
            for c in range(n_cp):
                for le in LES:
                    ids = members.get(f"NS-{c:05d}-{le[3:]}", [])
                    out.write(json.dumps({"NETTING_SET_ID": f"NS-{c:05d}-{le[3:]}", "COUNTERPARTY": f"CP-{c:05d}", "LEGAL_ENTITY": le,
                                          "TRADE_COUNT": len(ids), "TRADE_IDS": ids}, separators=(",", ":")) + "\n")
        build(a, cps, d, "core", "reference-store", "counterparty", partition=False, key="COUNTERPARTY_ID")
        build(a, nss, d, "counterparty-risk", "credit-store", "netting-set", partition=False, key="NETTING_SET_ID")
        cps.unlink()
        nss.unlink()
    return 0


if __name__ == "__main__":
    sys.exit(main())
