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

"""A mixed, concurrent read load against one Drishti server, to compare Java 21 and Java 25 and to provoke
virtual-thread pinning (run the server with -Djdk.tracePinnedThreads=full on Java 21).

  tools/bench/pinload.py --base http://localhost:18993 --trades 10000 --seconds 180 --concurrency 200 --out run.json

Every thread loops over: trade views, raw documents, searches, the desk P&L, impact (the F8 reverse lookup) and, with
--mq-ids N, the entities a message source (RabbitMQ) holds. Prints one JSON object with the count, errors, p50 and
p95 of each request type and the overall throughput. Reuses the client and the questions of measure.py.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from measure import DESK, IMPACT, SEARCHES, Client, pct, search_path  # noqa: E402


def collect_ids(base: str, trades: int, timeout: float) -> list[str]:
    """Trade ids from the searches, once the newest day's columns are loaded (the search sees every trade)."""
    c = Client(base)
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            st, d, _ = c.get(search_path(SEARCHES["search_mtm"]))
            if st == 200 and d and not d.get("partial") and d.get("scanned", 0) >= trades:
                break
        except OSError:
            pass
        time.sleep(1.0)
    ids: list[str] = []
    for q in (SEARCHES["search_book"], SEARCHES["picklist"], SEARCHES["search_mtm"], SEARCHES["search_usd"]):
        st, d, _ = c.get(search_path(q))
        for row in (d or {}).get("rows", []) if st == 200 else []:
            ref = row.get("ref") or {}
            rid = ref.get("id") if isinstance(ref, dict) else None
            if rid and rid not in ids:
                ids.append(rid)
    c.close()
    if not ids:
        raise SystemExit("no trade ids found: is the trading pack loaded?")
    return ids


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--trades", type=int, default=10000)
    ap.add_argument("--seconds", type=float, default=180)
    ap.add_argument("--warmup", type=float, default=20)
    ap.add_argument("--concurrency", type=int, default=200)
    ap.add_argument("--ready-timeout", type=float, default=240)
    ap.add_argument("--mq-ids", type=int, default=0, help="pin-order ids MC-LOAD-1..N held by the message source")
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default="")
    a = ap.parse_args()

    ids = collect_ids(a.base, a.trades, a.ready_timeout)
    types: dict[str, list] = {
        "view": [f"/api/v1/views/trade/{i}" for i in ids],
        "raw": [f"/api/v1/entities/trade/{i}/raw" for i in ids],
        "search": [search_path(q) for q in SEARCHES.values()],
        "desk_pnl": [DESK],
        "impact": [IMPACT],
    }
    if a.mq_ids:
        types["message_entity"] = [f"/api/v1/entities/pin-order/MC-LOAD-{n}/raw" for n in range(1, a.mq_ids + 1)]
    weights = {"view": 4, "raw": 3, "search": 2, "desk_pnl": 1, "impact": 1, "message_entity": 2}
    names = [n for n in types for _ in range(weights[n])]
    lat: dict[str, list[float]] = {n: [] for n in types}
    errs: dict[str, int] = {n: 0 for n in types}
    lock = threading.Lock()
    start = time.time()
    measure_from = start + a.warmup
    stop_at = measure_from + a.seconds

    def worker(seed: int):
        rnd = random.Random(seed)
        c = Client(a.base, timeout=60)
        mine: dict[str, list[float]] = {n: [] for n in types}
        bad: dict[str, int] = {n: 0 for n in types}
        while time.time() < stop_at:
            n = rnd.choice(names)
            try:
                st, _, ms = c.get(rnd.choice(types[n]), parse=False)
            except OSError:
                st, ms = 0, 0.0
            if time.time() >= measure_from:
                if st == 200:
                    mine[n].append(ms)
                else:
                    bad[n] += 1
        c.close()
        with lock:
            for n in types:
                lat[n].extend(mine[n])
                errs[n] += bad[n]

    threads = [threading.Thread(target=worker, args=(s,), daemon=True) for s in range(a.concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    every = [v for n in types for v in lat[n]]
    res = {
        "label": a.label, "concurrency": a.concurrency, "seconds": a.seconds, "trade_ids": len(ids),
        "requests": len(every), "errors": sum(errs.values()),
        "rps": round(len(every) / a.seconds, 1),
        "p50_ms": round(pct(every, 50), 1) if every else None, "p95_ms": round(pct(every, 95), 1) if every else None,
        "by_type": {n: {"n": len(lat[n]), "errors": errs[n],
                        "p50_ms": round(pct(lat[n], 50), 1) if lat[n] else None,
                        "p95_ms": round(pct(lat[n], 95), 1) if lat[n] else None} for n in types},
    }
    with open(a.out, "w") as f:
        json.dump(res, f, indent=1)
    print(json.dumps({k: res[k] for k in ("label", "requests", "errors", "rps", "p50_ms", "p95_ms")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
