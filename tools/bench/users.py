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

"""Simultaneous users against a server (its API) or a console (its pages), on a lake made by tools/bench/trades.py: each user loops for
the given seconds over a mix of 80 % single trades (a random system and date), 10 % counterparty-trades pages, 5 % netting sets and
5 % searches of one counterparty's trades, and the result is one JSON line: requests a second and, per kind of request, p50/p95/p99
and failures (an HTTP error, a timeout, a page saying DRS-1004).

    python tools/bench/users.py --server http://127.0.0.1:18480 --token-file token.txt --users 50 --seconds 60
    python tools/bench/users.py --console http://127.0.0.1:17480 --user drishti-dev-admin --password ... --users 50 --seconds 60
"""
from __future__ import annotations

import argparse
import asyncio
import json
import random
import statistics
import sys
import time

import httpx

IDS = [lambda i: str(10_000_000 + i), lambda i: f"CLY-{30_000_000 + i}", lambda i: f"MX-{50_000_000 + i}"]
BAD = ("DRS-1004", "timed out", "is unavailable")


def pick(r: random.Random, a):
    x, date = r.random(), r.choice(a.dates)
    if x < 0.80:
        return "trade", ("trade", r.choice(IDS)(r.randrange(a.ids))), date
    if x < 0.90:
        return "ctrd", ("counterparty-trades", f"CP-{r.randrange(a.counterparties):05d}"), None
    if x < 0.95:
        return "netting-set", ("netting-set", f"NS-{r.randrange(a.counterparties):05d}-{r.choice(['NY', 'LDN', 'FRA'])}"), date
    return "search", None, None


async def user(n: int, client: httpx.AsyncClient, stats: dict, stop: float, a) -> None:
    r = random.Random(n * 7919 + int(time.time()))
    while time.time() < stop:
        label, ent, date = pick(r, a)
        cp = f"CP-{r.randrange(a.counterparties):05d}"
        if a.console:
            url = (f"/v/{ent[0]}/{ent[1]}" + (f"?asOf={date}" if date else "")) if ent else \
                f"/form/trading?kind=trade&op.COUNTERPARTY=eq&v.COUNTERPARTY={cp}&run=1"
            headers = {}
        else:
            url = f"/api/v1/views/{ent[0]}/{ent[1]}" if ent else f"/api/v1/search?q=TRD where COUNTERPARTY = '{cp}' limit 100"
            headers = {"X-Drishti-As-Of": date} if date else {}
        t = time.time()
        try:
            resp = await client.get(url, headers=headers)
            ok = resp.status_code == 200 and not any(b in resp.text for b in BAD)
        except Exception:
            ok = False
        stats.setdefault(label, []).append((time.time() - t, ok))


async def run(a) -> dict:
    base = a.console or a.server
    headers = {}
    if a.server:
        headers["Authorization"] = "Bearer " + open(a.token_file, encoding="utf-8").read().strip()
    limits = httpx.Limits(max_connections=a.users + 10, max_keepalive_connections=a.users + 10)
    async with httpx.AsyncClient(base_url=base, timeout=60, limits=limits, headers=headers, follow_redirects=True) as client:
        if a.console:
            await client.post("/login", data={"user": a.user, "password": a.password})
        stats: dict = {}
        t0 = time.time()
        await asyncio.gather(*(user(i, client, stats, t0 + a.seconds, a) for i in range(a.users)))
        elapsed = time.time() - t0
    out = {"target": "console" if a.console else "server", "users": a.users, "seconds": round(elapsed), "requests": sum(len(v) for v in stats.values())}
    out["perSecond"] = round(out["requests"] / elapsed, 1)
    for label, v in sorted(stats.items()):
        ts = sorted(x for x, _ in v)
        out[label] = {"n": len(ts), "failed": sum(1 for _, ok in v if not ok), "p50": round(statistics.median(ts), 3),
                      "p95": round(ts[max(0, int(len(ts) * .95) - 1)], 3), "p99": round(ts[max(0, int(len(ts) * .99) - 1)], 3)}
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    target = ap.add_mutually_exclusive_group(required=True)
    target.add_argument("--server", help="the server's base address (its API)")
    target.add_argument("--console", help="the console's base address (its pages)")
    ap.add_argument("--token-file", help="an API token, for --server")
    ap.add_argument("--user", default="drishti-dev-admin")
    ap.add_argument("--password", default="")
    ap.add_argument("--users", type=int, default=10)
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--ids", type=int, default=300_000, help="trade ids per system that exist every day (trades.py --min)")
    ap.add_argument("--counterparties", type=int, default=10_000)
    ap.add_argument("--dates", default="2026-10-07,2026-10-08,2026-10-09,2026-10-13,2026-10-14,2026-10-15,2026-10-16")
    a = ap.parse_args(argv)
    a.dates = [d.strip() for d in a.dates.split(",") if d.strip()]
    if a.server and not a.token_file:
        ap.error("--server needs --token-file")
    print(json.dumps(asyncio.run(run(a))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
