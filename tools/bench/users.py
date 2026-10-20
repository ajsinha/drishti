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
and failures (an HTTP error, a timeout, a page saying DRS-1004, a page that sent the user to sign in again).

With --history-share (0.15 in tools/bench/net_soak.py, 0 by default here) that share of the requests are histories
(HISTORY_QUERIES.md): ``/api/v1/history/{kind}/{id}`` (the console's ``/v/{kind}/{id}/history``) of a trade of any system (four in five),
a counterparty, or one of --equities, with days=30, days=60, range=3M, diff=true or, now and then, knownAt=now; the rest keep the mix
above. A history that says DRS-1013 (no data) fails. ``failedBuckets`` counts the failures per kind and per --fail-bucket seconds
(epoch), so the soak can tell whether they fell inside its kill windows.

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
from urllib.parse import quote, urlencode
from datetime import datetime, timezone

IDS = [lambda i: str(10_000_000 + i), lambda i: f"CLY-{30_000_000 + i}", lambda i: f"MX-{50_000_000 + i}"]
BAD = ("DRS-1004", "timed out", "is unavailable")
HISTORY_BAD = BAD + ("DRS-1013",)                  # a history of an entity the lake holds never says "no data"
#: the history ranges, in the share each is asked (the last, "known at" now, now and then)
HISTORY_ASKS = ((0.30, {"days": "30"}), (0.20, {"days": "60"}), (0.20, {"range": "3M"}), (0.20, {"days": "30", "diff": "true"}),
                (0.10, {"days": "30", "knownAt": "now"}))


def history_pick(r: random.Random, a) -> tuple:
    """A history to ask: (kind, id, params). Trades four in five (any system: MUREX ones may be held only by a peer), else a
    counterparty, or an equity of --equities when there are some."""
    x = r.random()
    equities = getattr(a, "equities", []) or []
    if x < 0.80:
        kind, ident = "trade", r.choice(IDS)(r.randrange(a.ids))
    elif x < 0.90 or not equities:
        kind, ident = "counterparty", f"CP-{r.randrange(a.counterparties):05d}"
    else:
        kind, ident = "equity", r.choice(equities)
    y, params = r.random(), HISTORY_ASKS[-1][1]
    for share, p in HISTORY_ASKS:
        if y < share:
            params = p
            break
        y -= share
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return kind, ident, {k: now if v == "now" else v for k, v in params.items()}


def pick(r: random.Random, a):
    share = getattr(a, "history_share", 0.0) or 0.0
    if share and r.random() < share:
        return "history", history_pick(r, a), None
    x, date = r.random(), r.choice(a.dates)
    if x < 0.80:
        return "trade", ("trade", r.choice(IDS)(r.randrange(a.ids))), date
    if x < 0.90:
        return "ctrd", ("counterparty-trades", f"CP-{r.randrange(a.counterparties):05d}"), None
    if x < 0.95:
        return "netting-set", ("netting-set", f"NS-{r.randrange(a.counterparties):05d}-{r.choice(['NY', 'LDN', 'FRA'])}"), date
    return "search", None, None


async def user(n: int, client, stats: dict, stop: float, a) -> None:
    r = random.Random(n * 7919 + int(time.time()))
    while time.time() < stop:
        label, ent, date = pick(r, a)
        cp = f"CP-{r.randrange(a.counterparties):05d}"
        url, headers = request_of(label, ent, date, cp, a)
        t = time.time()
        try:
            resp = await client.get(url, headers=headers)
            bad = HISTORY_BAD if label == "history" else BAD
            ok = resp.status_code == 200 and not any(b in resp.text for b in bad) and "/login" not in resp.url.path   # signed out is a failure
        except Exception:
            ok = False
        stats.setdefault(label, []).append((time.time() - t, ok, t))


def request_of(label: str, ent, date, cp: str, a) -> tuple[str, dict]:
    """The address and headers of one request: the console's page, or the server's API."""
    if label == "history":
        kind, ident, params = ent
        query = urlencode(params)
        if a.console:
            return f"/v/{quote(kind)}/{quote(ident, safe='')}/history?{query}", {}
        return f"/api/v1/history/{quote(kind)}/~?" + urlencode({"id": ident, **params}), {}
    if a.console:
        url = (f"/v/{ent[0]}/{ent[1]}" + (f"?asOf={date}" if date else "")) if ent else \
            f"/form/trading?kind=trade&op.COUNTERPARTY=eq&v.COUNTERPARTY={cp}&run=1"
        return url, {}
    url = f"/api/v1/views/{ent[0]}/{ent[1]}" if ent else f"/api/v1/search?q=TRD where COUNTERPARTY = '{cp}' limit 100"
    return url, ({"X-Drishti-As-Of": date} if date else {})


def summary(stats: dict, elapsed: float, a) -> dict:
    """The JSON line: requests a second; per kind n, failed, p50/p95/p99; failedBuckets: per kind, failures per --fail-bucket seconds."""
    out = {"target": "console" if a.console else "server", "users": a.users, "seconds": round(elapsed), "requests": sum(len(v) for v in stats.values())}
    out["perSecond"] = round(out["requests"] / elapsed, 1) if elapsed else 0.0
    bucket = getattr(a, "fail_bucket", 10) or 10
    out["bucket"], out["failedBuckets"] = bucket, {}
    for label, v in sorted(stats.items()):
        ts = sorted(x[0] for x in v)
        out[label] = {"n": len(ts), "failed": sum(1 for x in v if not x[1]), "p50": round(statistics.median(ts), 3),
                      "p95": round(ts[max(0, int(len(ts) * .95) - 1)], 3), "p99": round(ts[max(0, int(len(ts) * .99) - 1)], 3)}
        for x in v:
            if not x[1]:
                b = str(int(x[2] // bucket * bucket))
                out["failedBuckets"].setdefault(label, {})[b] = out["failedBuckets"].get(label, {}).get(b, 0) + 1
    return out


async def run(a) -> dict:
    import httpx
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
    return summary(stats, elapsed, a)


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
    ap.add_argument("--history-share", type=float, default=0.0, help="the share of requests that ask a history (0.15 in the Drishti*net soak)")
    ap.add_argument("--equities", default="", help="equity ids whose history may be asked (comma separated; none: trades and counterparties)")
    ap.add_argument("--fail-bucket", type=float, default=10, help="seconds per bucket of failedBuckets")
    a = ap.parse_args(argv)
    a.dates = [d.strip() for d in a.dates.split(",") if d.strip()]
    a.equities = [e.strip() for e in a.equities.split(",") if e.strip()]
    if not 0 <= a.history_share <= 1:
        ap.error("--history-share is a share: 0 to 1")
    if a.server and not a.token_file:
        ap.error("--server needs --token-file")
    print(json.dumps(asyncio.run(run(a))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
