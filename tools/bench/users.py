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

With --var-share (0 by default) that share of the requests are VaR questions (RESOURCES_AND_SCALE.md section 10.1, the 2.9.7 benchmark)
on a lake made by tools/bench/vargen.py: 60 % a node (the bank, a legal entity, a division, a desk or a book), 20 % the bank minus a
desk, 10 % a stored basket of 50,000 positions (``PORT:PF-BASKET-BIG-nn``), 10 % a desk's contributions by position or a node's P&L
path; each kind its own line (var-node, var-bank-minus-desk, var-basket, var-contrib, var-path). ``--server`` may name several servers
(comma separated): user n asks server n mod k (a cluster's members side by side).

With --history-share (0.15 in tools/bench/net_soak.py, 0 by default here) that share of the requests are histories
(DATA_ARCHITECTURE.md): ``/api/v1/history/{kind}/{id}`` (the console's ``/v/{kind}/{id}/history``) of a trade of any system (four in five),
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


def var_pick(r: random.Random, a) -> tuple:
    """A VaR question of the 2.9.7 mix: (label, path and query)."""
    x, date = r.random(), r.choice(a.var_dates)
    desk = f"DESK-{r.randrange(a.var_desks):03d}"

    def node() -> str:
        y = r.random()
        if y < 0.05:
            return "BANK"
        if y < 0.15:
            return f"LE:LE-{r.randrange(a.var_les):02d}" if r.random() < 0.5 else f"DIV:DIV-{r.randrange(a.var_divisions):02d}"
        if y < 0.50:
            return f"DESK:DESK-{r.randrange(a.var_desks):03d}"
        return f"BOOK:BOOK-{r.randrange(a.var_desks):03d}-{r.randrange(a.var_books_per_desk)}"
    if x < 0.60:
        return "var-node", "/api/v1/risk/var/scope?" + urlencode({"scope": node(), "asOf": date})
    if x < 0.80:
        return "var-bank-minus-desk", "/api/v1/risk/var/scope?" + urlencode({"scope": f"BANK - DESK:{desk}", "asOf": date})
    if x < 0.90:
        return "var-basket", "/api/v1/risk/var/scope?" + urlencode({"scope": f"PORT:PF-BASKET-BIG-{r.randrange(a.var_big_baskets):02d}", "asOf": date})
    if r.random() < 0.5:
        return "var-contrib", "/api/v1/risk/var/scope/contributions?" + urlencode({"scope": f"DESK:{desk}", "by": "position", "asOf": date})
    return "var-path", "/api/v1/risk/var/scope/path?" + urlencode({"scope": node(), "page": 0, "size": 60, "asOf": date})


def pick(r: random.Random, a):
    var_share = getattr(a, "var_share", 0.0) or 0.0
    if var_share and r.random() < var_share:
        label, url = var_pick(r, a)
        return label, url, None
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
    if isinstance(client, list):                       # several servers: user n asks server n mod k
        client = client[n % len(client)]
    while time.time() < stop:
        label, ent, date = pick(r, a)
        cp = f"CP-{r.randrange(a.counterparties):05d}"
        if label.startswith("var-"):
            url, headers = ent, {}
        else:
            url, headers = request_of(label, ent, date, cp, a)
        t = time.time()
        busy = False
        try:
            resp = await client.get(url, headers=headers)
            bad = HISTORY_BAD if label == "history" else BAD
            ok = resp.status_code == 200 and not any(b in resp.text for b in bad) and "/login" not in resp.url.path   # signed out is a failure
            # busy protection (OPERATIONS.md 12.5): a polite 503 DRS-9501..9505 with Retry-After is counted apart, not as a failure
            busy = resp.status_code == 503 and "DRS-950" in resp.text[:400]
        except Exception:
            ok = False
        stats.setdefault(label, []).append((time.time() - t, ok, t, busy) if busy else (time.time() - t, ok, t))


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
    out["busy"] = sum(1 for v in stats.values() for x in v if len(x) > 3 and x[3])   # asked to wait (503 DRS-95xx), not failed
    bucket = getattr(a, "fail_bucket", 10) or 10
    out["bucket"], out["failedBuckets"] = bucket, {}
    for label, v in sorted(stats.items()):
        ts = sorted(x[0] for x in v)
        out[label] = {"n": len(ts), "failed": sum(1 for x in v if not x[1] and not (len(x) > 3 and x[3])),
                      "busy": sum(1 for x in v if len(x) > 3 and x[3]), "p50": round(statistics.median(ts), 3),
                      "p95": round(ts[max(0, int(len(ts) * .95) - 1)], 3), "p99": round(ts[max(0, int(len(ts) * .99) - 1)], 3)}
        for x in v:
            if not x[1] and not (len(x) > 3 and x[3]):
                b = str(int(x[2] // bucket * bucket))
                out["failedBuckets"].setdefault(label, {})[b] = out["failedBuckets"].get(label, {}).get(b, 0) + 1
    return out


async def run(a) -> dict:
    import httpx
    bases = [a.console] if a.console else [b.strip() for b in a.server.split(",") if b.strip()]
    headers = {}
    if a.server:
        headers["Authorization"] = "Bearer " + open(a.token_file, encoding="utf-8").read().strip()
    limits = httpx.Limits(max_connections=a.users + 10, max_keepalive_connections=a.users + 10)
    clients = [httpx.AsyncClient(base_url=b, timeout=60, limits=limits, headers=headers, follow_redirects=True) for b in bases]
    try:
        if a.console:
            await clients[0].post("/login", data={"user": a.user, "password": a.password})
        stats: dict = {}
        t0 = time.time()
        await asyncio.gather(*(user(i, clients if len(clients) > 1 else clients[0], stats, t0 + a.seconds, a) for i in range(a.users)))
        elapsed = time.time() - t0
    finally:
        for c in clients:
            await c.aclose()
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
    ap.add_argument("--var-share", type=float, default=0.0, help="the share of requests that are VaR questions (vargen.py's lake)")
    ap.add_argument("--var-dates", default="2026-09-29,2026-09-30", help="the business dates the VaR questions ask")
    ap.add_argument("--var-les", type=int, default=4)
    ap.add_argument("--var-divisions", type=int, default=5)
    ap.add_argument("--var-desks", type=int, default=100)
    ap.add_argument("--var-books-per-desk", type=int, default=10)
    ap.add_argument("--var-big-baskets", type=int, default=10)
    ap.add_argument("--fail-bucket", type=float, default=10, help="seconds per bucket of failedBuckets")
    a = ap.parse_args(argv)
    a.dates = [d.strip() for d in a.dates.split(",") if d.strip()]
    a.equities = [e.strip() for e in a.equities.split(",") if e.strip()]
    a.var_dates = [d.strip() for d in a.var_dates.split(",") if d.strip()]
    if not 0 <= a.var_share <= 1:
        ap.error("--var-share is a share: 0 to 1")
    if not 0 <= a.history_share <= 1:
        ap.error("--history-share is a share: 0 to 1")
    if a.server and not a.token_file:
        ap.error("--server needs --token-file")
    print(json.dumps(asyncio.run(run(a))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
