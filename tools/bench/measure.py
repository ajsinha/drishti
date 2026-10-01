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

"""Measures one running Drishti server over HTTP for the scale benchmark (tools/bench/scale.sh), standard library only.

    python3 tools/bench/measure.py --base http://localhost:18993 --store delta --trades 10000 --days 3 \
        --out results.jsonl --pid 12345 [--meta load_s=41.2 --meta store_bytes=123456 ...]

It waits until the newest business day's trades are searchable from columns (every search exact: partial false and
scanned equal to the trades a day), warms up, then times each question --reps times one after another (median, p95,
min, max) and again with --clients concurrent clients for --seconds (requests per second). Then a full GC and the
live heap (jcmd GC.run, GC.heap_info) and the process's resident memory. Each measure is one JSON line in --out.
"""
from __future__ import annotations

import argparse
import datetime as dt
import http.client
import json
import os
import platform
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse

SEARCHES = {
    "search_mtm": "TRD where mtm < -50m order by mtm",
    "search_usd": "TRD where currency = 'USD' and notional > 500m order by notional desc limit 20",
    "search_book": "TRD book=BOOK-RATES-3",
    "picklist": "TRD END-1100",
}
TYPEAHEAD = "TRD CLY-400"
DESK = "/api/v1/entities/desk-pnl/DESK-RATES/raw"
IMPACT = "/api/v1/impact/netting-set/NS-SUMMIT-NY"


class Client:
    """One keep-alive HTTP connection; get() returns (status, parsed JSON or None, milliseconds)."""

    def __init__(self, base: str, timeout: float = 120.0):
        u = urllib.parse.urlparse(base)
        self.host, self.port, self.timeout = u.hostname, u.port or 80, timeout
        self.conn = None
        self.last_bytes = 0

    def get(self, path: str, parse: bool = True):
        for attempt in range(2):
            try:
                if self.conn is None:
                    self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
                t0 = time.perf_counter()
                self.conn.request("GET", path, headers={"Accept": "application/json", "Accept-Encoding": "identity"})
                resp = self.conn.getresponse()
                body = resp.read()
                self.last_bytes = len(body)
                ms = (time.perf_counter() - t0) * 1000.0
                data = None
                if parse and body:
                    try:
                        data = json.loads(body)
                    except ValueError:
                        data = None
                return resp.status, data, ms
            except (OSError, http.client.HTTPException):
                self.close()
                if attempt:
                    raise
        raise RuntimeError("unreachable")

    def close(self):
        if self.conn is not None:
            self.conn.close()
            self.conn = None


def search_path(q: str, as_of: str | None = None) -> str:
    p = "/api/v1/search?q=" + urllib.parse.quote(q)
    return p + ("&asOf=" + as_of if as_of else "")


def pct(values: list[float], p: float) -> float:
    """Nearest-rank percentile."""
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(round(p / 100.0 * len(s) + 0.5)) - 1))
    return s[k]


class Bench:
    def __init__(self, a):
        self.a = a
        self.client = Client(a.base)
        self.records: list[dict] = []
        self.today, self.past, self.other = a.today, a.past, a.other
        self.ids: list[str] = []
        self.started = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")

    # -- output ---------------------------------------------------------------------------------------------------
    def record(self, measure: str, unit: str, **fields):
        r = {"date": self.started[:10], "at": self.started, "run": self.a.run, "store": self.a.store, "engine": self.a.engine,
             "trades_per_day": self.a.trades, "days": self.a.days, "measure": measure, "unit": unit}
        r.update(fields)
        self.records.append(r)
        print(f"  {measure:<22} " + " ".join(f"{k}={v}" for k, v in fields.items() if k in
                                               ("value", "median", "p95", "rps", "scanned", "matched", "partial", "error")), flush=True)

    def flush(self):
        with open(self.a.out, "a", encoding="utf-8") as f:
            for r in self.records:
                f.write(json.dumps(r, sort_keys=False) + "\n")

    # -- readiness ------------------------------------------------------------------------------------------------
    def wait_columns(self) -> float:
        """Seconds until a search of the newest day is exact over every trade (the day's columns are loaded)."""
        t0, last = time.time(), None
        while time.time() - t0 < self.a.ready_timeout:
            try:
                st, d, _ = self.client.get(search_path(SEARCHES["search_mtm"]))
                if st == 200 and d:
                    last = (d.get("scanned"), d.get("partial"))
                    if not d.get("partial") and d.get("scanned", 0) >= self.a.trades:
                        return time.time() - t0
            except OSError:
                pass
            time.sleep(1.0)
        print(f"  columns not ready after {self.a.ready_timeout} s (last scanned/partial {last})", file=sys.stderr)
        return float("nan")

    def connector_state(self):
        """The trading store's line from Admin → Health (its plugin, engine and layout), as a note on the run."""
        try:
            st, d, _ = self.client.get("/api/v1/admin/health")
            text = json.dumps(d) if d is not None else ""
            i = text.find("trading-store")
            self.record("trading_store_health", "", status=st, value=text[i:i + 400] if i >= 0 else text[:400])
        except OSError as e:
            self.record("trading_store_health", "", error=str(e))

    def collect_ids(self):
        seen = []
        for q in (SEARCHES["search_book"], SEARCHES["picklist"], SEARCHES["search_mtm"]):
            st, d, _ = self.client.get(search_path(q))
            for row in (d or {}).get("rows", []) if st == 200 else []:
                ref = row.get("ref") or {}
                rid = ref.get("id") if isinstance(ref, dict) else None
                if rid and rid not in seen:
                    seen.append(rid)
        # spread across the id space: every k-th, so neighbouring ids do not share row groups
        step = max(1, len(seen) // 400)
        self.ids = seen[::step] or seen
        if not self.ids:
            raise SystemExit("no trade ids found in the search results: is the trading pack loaded?")

    # -- timing ---------------------------------------------------------------------------------------------------
    def timed(self, measure: str, paths, check=None, parse=True, throughput=True):
        """paths: a list of request paths, used in turn (one per repetition)."""
        lat, errors, info = [], 0, {}
        for i in range(self.a.reps):
            st, d, ms = self.client.get(paths[i % len(paths)], parse=parse or check is not None)
            if st != 200:
                errors += 1
                continue
            lat.append(ms)
            info["bytes"] = self.client.last_bytes
            if check and d is not None:
                info.update(check(d))
        fields = {}
        if lat:
            fields.update(median=round(statistics.median(lat), 2), p95=round(pct(lat, 95), 2), min=round(min(lat), 2),
                          max=round(max(lat), 2), reps=len(lat))
        fields.update(errors=errors, **info)
        if throughput and self.a.clients > 0 and lat:
            fields.update(self.throughput(paths))
        self.record(measure, "ms", **fields)

    def throughput(self, paths) -> dict:
        stop = time.time() + self.a.seconds
        counts, fails = [0] * self.a.clients, [0] * self.a.clients

        def worker(n):
            c, i = Client(self.a.base), n
            while time.time() < stop:
                try:
                    st, _, _ = c.get(paths[i % len(paths)], parse=False)
                    if st == 200:
                        counts[n] += 1
                    else:
                        fails[n] += 1
                except OSError:
                    fails[n] += 1
                i += self.a.clients
            c.close()

        t0 = time.time()
        threads = [threading.Thread(target=worker, args=(n,)) for n in range(self.a.clients)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        el = time.time() - t0
        return {"rps": round(sum(counts) / el, 1), "rps_clients": self.a.clients, "rps_seconds": round(el, 1), "rps_errors": sum(fails)}

    def single(self, measure: str, path: str, check=None):
        st, d, ms = self.client.get(path)
        fields = {"value": round(ms, 2), "status": st}
        if check and d is not None and st == 200:
            fields.update(check(d))
        if st != 200:
            fields["error"] = f"HTTP {st}"
        self.record(measure, "ms", **fields)

    def search_check(self, d) -> dict:
        return {"scanned": d.get("scanned"), "matched": d.get("matched"), "partial": d.get("partial"),
                "exact": (not d.get("partial")) and d.get("scanned") == self.a.trades, "server_ms": d.get("elapsedMs")}

    # -- the run --------------------------------------------------------------------------------------------------
    def run(self):
        for k, v in self.a.meta:
            self.record(k, unit_of(k), value=v)
        self.connector_state()
        cols = self.wait_columns()
        self.record("columns_ready_s", "s", value=round(cols, 1))
        self.collect_ids()
        n = len(self.ids)
        # warm-up: every question a few times (JIT, connection pools, the newest day's id map and columns)
        for _ in range(self.a.warmup):
            self.client.get("/api/v1/command/suggest?q=" + urllib.parse.quote(TYPEAHEAD))
            for q in SEARCHES.values():
                self.client.get(search_path(q))
            self.client.get(DESK)
            self.client.get(IMPACT)
            self.client.get(f"/api/v1/views/trade/{self.ids[0]}")
        # distinct trades for the uncached opens; the warm open repeats one
        third = max(1, n // 3)
        cold_views = [f"/api/v1/views/trade/{i}" for i in self.ids[1:third]] or [f"/api/v1/views/trade/{self.ids[0]}"]
        raw_today = [f"/api/v1/entities/trade/{i}/raw" for i in self.ids[third:2 * third]] or [f"/api/v1/entities/trade/{self.ids[0]}/raw"]
        raw_past = [f"/api/v1/entities/trade/{i}/raw?asOf={self.past}" for i in self.ids[2 * third:]] or \
                   [f"/api/v1/entities/trade/{self.ids[0]}/raw?asOf={self.past}"]
        self.timed("typeahead", ["/api/v1/command/suggest?q=" + urllib.parse.quote(TYPEAHEAD)])
        self.timed("view_first", cold_views, throughput=False)
        self.timed("view_cached", [f"/api/v1/views/trade/{self.ids[0]}"])
        self.timed("raw_today", raw_today, throughput=False)
        self.timed("raw_past", raw_past, throughput=False)
        for m, q in SEARCHES.items():
            self.timed(m, [search_path(q)], check=self.search_check)
        self.timed("desk_pnl", [DESK])
        self.timed("impact", [IMPACT])
        # opens over the whole id list with 8 clients (trades come back into cache, so this is a mix)
        self.record("view_mixed_rps", "req/s", **self.throughput([f"/api/v1/views/trade/{i}" for i in self.ids]))
        self.single("search_other_day_first", search_path(SEARCHES["search_mtm"], self.other), self.search_check)
        self.timed("search_other_day_again", [search_path(SEARCHES["search_mtm"], self.other)], check=self.search_check, throughput=False)
        self.memory()
        self.record("trade_ids_sampled", "count", value=n)

    def memory(self):
        if not self.a.pid:
            return
        jcmd = os.path.join(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-25-openjdk-amd64"), "bin", "jcmd")
        try:
            subprocess.run([jcmd, str(self.a.pid), "GC.run"], check=True, capture_output=True, timeout=120)
            out = subprocess.run([jcmd, str(self.a.pid), "GC.heap_info"], check=True, capture_output=True, text=True, timeout=60).stdout
            m = re.search(r"used (\d+)K", out)
            if m:
                self.record("heap_live_mb", "MB", value=round(int(m.group(1)) / 1024.0, 1))
        except (OSError, subprocess.SubprocessError) as e:
            self.record("heap_live_mb", "MB", error=str(e))
        try:
            with open(f"/proc/{self.a.pid}/status", encoding="utf-8") as f:
                st = dict(l.split(":", 1) for l in f if ":" in l)
            self.record("server_rss_mb", "MB", value=round(int(st["VmRSS"].split()[0]) / 1024.0, 1),
                        peak=round(int(st["VmHWM"].split()[0]) / 1024.0, 1))
        except (OSError, KeyError, ValueError):
            pass


def unit_of(name: str) -> str:
    if name.endswith("_s"):
        return "s"
    if name.endswith("_bytes"):
        return "bytes"
    if name.endswith("_mb") or name.endswith("_gb"):
        return name.rsplit("_", 1)[1].upper()
    return ""


def meta_pair(s: str):
    k, _, v = s.partition("=")
    try:
        return k, float(v) if "." in v or "e" in v.lower() else int(v)
    except ValueError:
        return k, v


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:18993")
    ap.add_argument("--store", required=True)
    ap.add_argument("--engine", default="")
    ap.add_argument("--trades", type=int, required=True, help="trades a day loaded (every exact search scans this many)")
    ap.add_argument("--days", type=int, default=3)
    ap.add_argument("--run", default="r1", help="a label telling repeated runs of the same size apart")
    ap.add_argument("--out", required=True, help="JSON lines file to append to")
    ap.add_argument("--pid", type=int, default=0, help="the server's process id, for jcmd and resident memory")
    ap.add_argument("--reps", type=int, default=25)
    ap.add_argument("--warmup", type=int, default=5)
    ap.add_argument("--clients", type=int, default=8)
    ap.add_argument("--seconds", type=float, default=5.0)
    ap.add_argument("--today", default="2026-09-30")
    ap.add_argument("--past", default="2026-09-29")
    ap.add_argument("--other", default="2026-09-28")
    ap.add_argument("--ready-timeout", type=int, default=900)
    ap.add_argument("--meta", type=meta_pair, action="append", default=[], help="name=value measured outside (load_s, store_bytes, start_s)")
    a = ap.parse_args(argv)
    a.meta.append(("host_cpus", os.cpu_count()))
    b = Bench(a)
    print(f"measuring {a.store} {a.trades:,} trades a day x {a.days} ({a.run}) on {platform.node()}", flush=True)
    try:
        b.run()
    finally:
        b.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
