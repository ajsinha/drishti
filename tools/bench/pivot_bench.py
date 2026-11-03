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

"""The pivot engine's benchmark (docs/admin/BENCHMARK.md, section 16; docs/architecture/PIVOT_ENGINE.md, section 21): synthetic trades
generated as JSON Lines and loaded into Delta day by day, then the five views V1 to V5 against one server or three, cold and warm, without
and with a rollup, and the report with the measured points and the larger sizes EXTRAPOLATED from them (labelled so, never as measured).

Sizes (product owner, 2026-11-02: the machine's disk is limited): the lake grows 5 GB (warm-up), 25 GB, 75 GB (25 GB a node on three
nodes, at most); one node runs at 5 and 25 GB, three nodes at 5, 25 and 75 GB, on the same data. 100 GB, 1 TB and 3 TB are EXTRAPOLATED
from the measured 5, 25 and 75 GB points, labelled so, never presented as measured.

    # what would run, the disk it needs; nothing written
    python3 tools/bench/pivot_bench.py plan --nodes 3
    # the warm-up lake of 5 GB under a folder on the data disk; refuses under 150 GB free, never under /tmp
    drishti-console/.venv/bin/python tools/bench/pivot_bench.py generate --data-dir /data/drishti-bench/pivot --gb 5
    # the views against the servers (the bench lake wired as the kind bench_trade, see BENCHMARK.md 16.2), cold then warm, no rollup
    python3 tools/bench/pivot_bench.py run --servers http://10.0.0.11:18480,http://10.0.0.12:18480,http://10.0.0.13:18480 \\
        --token-file token.txt --gb 5 --nodes 3 --cache cold --rollups without --out results/pivot.jsonl
    # the tables for BENCHMARK.md, with the extrapolated sizes
    python3 tools/bench/pivot_bench.py report results/pivot.jsonl
    # everything at both sizes, then the generated data deleted (--keep keeps it)
    drishti-console/.venv/bin/python tools/bench/pivot_bench.py all --data-dir /data/drishti-bench/pivot --nodes 3 --servers ... --token-file token.txt

Rules (BENCHMARK.md section 2.1): scratch on a disk, never a memory-backed folder (/tmp here is a tmpfs shared with RAM), never the
repository's data/; one heavy job at a time; the machine otherwise idle. The runner deletes what it generated when it ends, unless --keep.
Standard library only, except the load into Delta (tools/ingest_jsonl.py, deltalake and pyarrow from the console's environment).
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import os
import random
import shutil
import subprocess
import sys
import time
import urllib.request
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIN_FREE_GB = 150
WARM_UP_GB = 5                 # the first, small size: everything runs once on it before the main size
MAX_GB_PER_NODE = 25           # the most one node reads when the nodes share the scan
EXTRAPOLATED_GB = (100, 1_000, 3_000)
SOURCE = "bench_trade"
DOMAIN = "bench"

# 40 columns, Endur-style names; dimensions first, then measures
DIMENSIONS = ["TRADE_ID", "BUSINESS_DATE", "TRADE_DATE", "VALUE_DATE", "MATURITY_DATE", "DESK", "BOOK", "LEGAL_ENTITY", "REGION",
              "PRODUCT_GROUP", "PRODUCT_TYPE", "INSTRUMENT", "CURRENCY", "COUNTERPARTY", "CPTY_RATING", "CPTY_COUNTRY", "TRADER",
              "STRATEGY", "TRADE_STATUS", "BUY_SELL"]
MEASURES = ["NOTIONAL", "NOTIONAL_USD", "PRICE", "QUANTITY", "MTM", "MTM_USD", "PNL_1D", "PNL_MTD", "PNL_YTD", "DV01", "CS01", "VEGA",
            "DELTA", "GAMMA", "FX_RATE", "TENOR_YEARS", "COUPON", "SPREAD_BP", "VERSION", "UPDATED_AT"]
COLUMNS = DIMENSIONS + MEASURES

DESKS = ["RATES-NY", "RATES-LDN", "FX-NY", "FX-LDN", "FX-TKY", "CREDIT-NY", "CREDIT-LDN", "EQUITY-NY", "EQUITY-HK", "COMMOD-HOU",
         "EM-SAO", "XVA-LDN"]
REGIONS = {"NY": "AMERICAS", "LDN": "EMEA", "TKY": "APAC", "HK": "APAC", "HOU": "AMERICAS", "SAO": "AMERICAS"}
LES = ["LE-US", "LE-UK", "LE-DE", "LE-JP", "LE-HK"]
PRODUCTS = {"RATES": ["IRS", "OIS", "FRA", "SWAPTION", "CAP"], "FX": ["FX_SPOT", "FX_FWD", "FX_SWAP", "FX_OPTION"],
            "CREDIT": ["CDS", "BOND", "CDX"], "EQUITY": ["EQ_SWAP", "EQ_OPTION", "STOCK"], "COMMOD": ["COMM_SWAP", "COMM_FUT"],
            "EM": ["NDF", "LOCAL_BOND"], "XVA": ["CVA_HEDGE"]}
CCYS = ["USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "HKD", "BRL", "MXN"]
FX = {"USD": 1.0, "EUR": 1.08, "GBP": 1.27, "JPY": 0.0067, "CHF": 1.12, "CAD": 0.73, "AUD": 0.65, "HKD": 0.128, "BRL": 0.19, "MXN": 0.055}
RATINGS = ["AAA", "AA", "A", "BBB", "BB", "B", "CCC"]
COUNTRIES = ["US", "GB", "DE", "FR", "JP", "HK", "BR", "MX", "CA", "AU", "CH", "SG"]
STATUSES = ["NEW", "LIVE", "LIVE", "LIVE", "AMENDED", "MATURED"]

# the views of section 21 (V5, a Python mergeable function, runs only where Python functions are on: P2)
VIEWS = {
    "V1": {"rows": ["DESK"], "columns": ["BUSINESS_DATE:month"], "values": [{"measure": "sum(NOTIONAL_USD)", "as": "Notional"}]},
    "V2": {"rows": ["DESK"], "columns": ["BUSINESS_DATE:month"], "values": [{"measure": "sum(NOTIONAL_USD)", "as": "Notional"},
                                                                         {"measure": "approx_count_distinct(COUNTERPARTY)", "as": "Cptys"}]},
    "V3": {"rows": ["DESK"], "columns": ["BUSINESS_DATE:month"], "values": [{"measure": "sum(NOTIONAL_USD)", "as": "Notional"},
                                                                         {"measure": "approx_percentile(PNL_1D, 0.99)", "as": "P99"}]},
    "V4": {"rows": ["REGION", "LEGAL_ENTITY", "DESK", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY"], "columns": [],
           "values": [{"measure": "sum(NOTIONAL_USD)", "as": "Notional"}, {"measure": "count()", "as": "Trades"}], "expandTo": {"rows": 6}},
    "V5": {"rows": ["DESK"], "columns": ["BUSINESS_DATE:month"], "values": [{"measure": "py.weighted_mean_return(PNL_1D, NOTIONAL_USD)",
                                                                         "as": "WMR"}], "optional": True},
}
ROLLUP = {"id": "bench-desk-month-ccy", "source": SOURCE, "grain": ["DESK", "BUSINESS_DATE:month", "CURRENCY"],
          "functions": ["sum(NOTIONAL_USD)", "count()", "approx_count_distinct(COUNTERPARTY)", "approx_percentile(PNL_1D, 0.99)"]}


# ---- safety: where data may go, and how much room there is ------------------------------------------------------------------------

def filesystem_type(path: Path) -> str:
    """The type of the filesystem holding path (from /proc/mounts: the longest mount point that contains it); '' when unknown."""
    try:
        best, kind = "", ""
        target = str(path.resolve())
        for line in Path("/proc/mounts").read_text().splitlines():
            parts = line.split()
            if len(parts) >= 3 and (target == parts[1] or target.startswith(parts[1].rstrip("/") + "/")) and len(parts[1]) > len(best):
                best, kind = parts[1], parts[2]
        return kind
    except OSError:
        return ""


def check_data_dir(d: Path, min_free_gb: float = MIN_FREE_GB) -> str | None:
    """Why the benchmark may not write under d, or None: never /tmp, a memory-backed filesystem or the repository; at least 150 GB free."""
    r = d.resolve() if d.exists() else d.absolute()
    if str(r) == "/tmp" or str(r).startswith("/tmp/") or str(r).startswith("/dev/shm"):
        return f"{r} is under /tmp or /dev/shm (a tmpfs shared with RAM): name a folder on the data disk"
    probe = r
    while not probe.exists() and probe != probe.parent:
        probe = probe.parent
    if filesystem_type(probe) in ("tmpfs", "ramfs"):
        return f"{r} is on a memory-backed filesystem: name a folder on the data disk"
    try:
        r.relative_to(ROOT)
        return f"{r} is inside the repository: name a folder outside it (never the repository's data/)"
    except ValueError:
        pass
    free = shutil.disk_usage(probe).free / 1e9
    floor = max(MIN_FREE_GB, min_free_gb)
    if free < floor:
        return f"{probe} has {free:.0f} GB free, under {floor:.0f} GB: the benchmark refuses to start"
    return None


def dir_bytes(d: Path) -> int:
    total = 0
    for p, _dirs, files in os.walk(d):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(p, f))
            except OSError:
                pass
    return total


# ---- the data ----------------------------------------------------------------------------------------------------------------------

def business_days(end: date, n: int) -> list[date]:
    """n weekdays ending at end, oldest first (5 years: 1,260)."""
    out, d = [], end
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d -= timedelta(days=1)
    return sorted(out)


def trade_row(rng: random.Random, i: int, day: date, cpties: int) -> dict:
    """One synthetic trade on one business date: the same seed and index give the same row (clustered by date, then desk)."""
    desk = DESKS[i % len(DESKS)]
    group = desk.split("-")[0]
    ccy = CCYS[rng.randrange(len(CCYS))] if group != "FX" else rng.choice(["USD", "EUR", "GBP", "JPY"])
    notional = round(10 ** rng.uniform(4, 8), 2)
    mtm = round(rng.gauss(0, notional * 0.02), 2)
    trade_date = day - timedelta(days=rng.randrange(0, 720))
    tenor = round(rng.choice([0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30]), 2)
    cp = rng.randrange(cpties)
    return {
        "TRADE_ID": f"BT-{i:010d}", "BUSINESS_DATE": day.isoformat(), "TRADE_DATE": trade_date.isoformat(),
        "VALUE_DATE": (trade_date + timedelta(days=2)).isoformat(),
        "MATURITY_DATE": (trade_date + timedelta(days=int(tenor * 365))).isoformat(), "DESK": desk, "BOOK": f"{desk}-B{i % 7}",
        "LEGAL_ENTITY": LES[i % len(LES)], "REGION": REGIONS[desk.split("-")[1]], "PRODUCT_GROUP": group,
        "PRODUCT_TYPE": PRODUCTS[group][i % len(PRODUCTS[group])], "INSTRUMENT": f"{group}-{ccy}-{tenor}Y", "CURRENCY": ccy,
        "COUNTERPARTY": f"CP-{cp:06d}", "CPTY_RATING": RATINGS[cp % len(RATINGS)], "CPTY_COUNTRY": COUNTRIES[cp % len(COUNTRIES)],
        "TRADER": f"T{(i * 7) % 240:03d}", "STRATEGY": f"S{i % 31:02d}", "TRADE_STATUS": STATUSES[i % len(STATUSES)],
        "BUY_SELL": "BUY" if i % 2 == 0 else "SELL",
        "NOTIONAL": notional, "NOTIONAL_USD": round(notional * FX[ccy], 2), "PRICE": round(rng.uniform(80, 120), 4),
        "QUANTITY": rng.randrange(1, 10_000), "MTM": mtm, "MTM_USD": round(mtm * FX[ccy], 2),
        "PNL_1D": round(rng.gauss(0, notional * 0.001), 2), "PNL_MTD": round(rng.gauss(0, notional * 0.004), 2),
        "PNL_YTD": round(rng.gauss(0, notional * 0.015), 2), "DV01": round(notional * tenor * 1e-4 * rng.uniform(0.8, 1.2), 2),
        "CS01": round(notional * tenor * 1e-4 * rng.uniform(0, 0.5), 2) if group in ("CREDIT", "EM") else 0.0,
        "VEGA": round(rng.gauss(0, notional * 1e-4), 2), "DELTA": round(rng.uniform(-1, 1), 4), "GAMMA": round(rng.uniform(0, 0.05), 5),
        "FX_RATE": FX[ccy], "TENOR_YEARS": tenor, "COUPON": round(rng.uniform(0, 0.08), 5), "SPREAD_BP": round(rng.uniform(0, 600), 1),
        "VERSION": 1 + i % 4, "UPDATED_AT": f"{day.isoformat()}T{(i % 24):02d}:{(i * 7) % 60:02d}:00Z",
    }


def write_day(path: Path, day: date, start: int, rows: int, seed: int, cpties: int) -> int:
    """One business date's trades as gzipped JSON Lines; the rows of a day are its own seed, so days can be made in any order."""
    rng = random.Random(seed * 1_000_003 + day.toordinal())
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=1) as f:
        for i in range(start, start + rows):
            f.write(json.dumps(trade_row(rng, i, day, cpties), separators=(",", ":")))
            f.write("\n")
    return rows


def load_day(jsonl_gz: Path, lake: Path, python: str) -> None:
    """The day's file into the Delta lake by the project's loader (JSON Lines to Delta, every pack's data path)."""
    plain = jsonl_gz.with_suffix("")                               # ingest_jsonl reads .jsonl
    with gzip.open(jsonl_gz, "rb") as src, open(plain, "wb") as dst:
        shutil.copyfileobj(src, dst, 1 << 20)
    cmd = [python, str(ROOT / "tools" / "ingest_jsonl.py"), "--from", str(plain), "--domain", DOMAIN, "--kind", SOURCE, "--key", "TRADE_ID",
           "--date", "BUSINESS_DATE", "--columns", ",".join(c for c in COLUMNS if c not in ("TRADE_ID",)), "--lake", str(lake),
           "--mode", "overwrite-dates", "--batch-rows", "500000"]
    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
    finally:
        plain.unlink(missing_ok=True)


def generate(data_dir: Path, gb: float, seed: int = 20261102, years: int = 5, cpties: int = 250_000, rows_per_day: int = 0,
             python: str | None = None, min_free_gb: float = MIN_FREE_GB, log=print) -> dict:
    """Trades until the lake holds about gb gigabytes (measured on disk after each day): days from 5 years back, each day's rows the same
    count, one day's file on disk at once. Returns what was made."""
    why = check_data_dir(data_dir, min_free_gb)
    if why:
        raise SystemExit("pivot_bench: " + why)
    lake, tmp = data_dir / "delta", data_dir / "tmp"
    lake.mkdir(parents=True, exist_ok=True)
    tmp.mkdir(parents=True, exist_ok=True)
    python = python or str(ROOT / "drishti-console" / ".venv" / "bin" / "python")
    days = business_days(date(2026, 10, 30), years * 252)
    target = gb * 1e9
    state_file = data_dir / "generated.json"                          # a second, larger size appends days; never rewrites one
    state = json.loads(state_file.read_text()) if state_file.exists() else {"done": [], "rows": 0, "next": 0, "per_day": 0}
    per_day = rows_per_day or state["per_day"] or max(1_000, int(target / 130 / len(days)))   # ~130 bytes a row, corrected below
    made, start, t0 = state["rows"], state["next"], time.time()
    done = set(state["done"])
    for day in days:
        if day.isoformat() in done:
            continue
        size = dir_bytes(lake)
        if size >= target:
            break
        if shutil.disk_usage(data_dir).free / 1e9 < 20:
            raise SystemExit("pivot_bench: stopped: under 20 GB free while generating; the lake so far is kept for clean")
        f = tmp / f"{SOURCE}-{day.isoformat()}.jsonl.gz"
        made += write_day(f, day, start, per_day, seed, cpties)
        start += per_day
        load_day(f, lake, python)
        f.unlink(missing_ok=True)
        done.add(day.isoformat())
        if len(done) == 5 and not rows_per_day:                       # measured bytes a row: aim the remaining days at the target
            per_row = max(20.0, dir_bytes(lake) / max(1, made))
            per_day = max(1_000, int((target - dir_bytes(lake)) / per_row / max(1, len(days) - 5)))
        state = {"done": sorted(done), "rows": made, "next": start, "per_day": per_day}
        state_file.write_text(json.dumps(state))
        if len(done) % 50 == 0:
            log(f"pivot_bench: {day} {made:,} rows, {dir_bytes(lake) / 1e9:.1f} GB of {gb:.0f} GB, {time.time() - t0:.0f} s")
    shutil.rmtree(tmp, ignore_errors=True)
    return {"rows": made, "days": len(done), "bytes": dir_bytes(lake), "lake": str(lake), "seconds": round(time.time() - t0, 1)}


# ---- the runs ----------------------------------------------------------------------------------------------------------------------

def http(server: str, path: str, body: dict | None, token: str, accept: str = "application/json", timeout: float = 3_600):
    req = urllib.request.Request(server.rstrip("/") + path, data=None if body is None else json.dumps(body).encode(),
                                 method="GET" if body is None else "POST")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", accept)
    return urllib.request.urlopen(req, timeout=timeout)


def run_view(server: str, token: str, name: str, view: dict, exact: bool | None) -> dict:
    """One view: the estimate, then the answer as progressive events (time to the first ≈ answer and to the exact one)."""
    body = {"source": SOURCE, **{k: v for k, v in view.items() if k != "optional"}}
    if exact is not None:
        body["exact"] = exact
    t0 = time.time()
    with http(server, "/api/v1/pivot/estimate", body, token) as r:
        est = json.load(r)
    t1 = time.time()
    first = exact_at = None
    answer = {}
    with http(server, "/api/v1/pivot/query?progressive=true", body, token, accept="text/event-stream") as r:
        event = None
        for raw in r:
            line = raw.decode("utf-8").rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                now = time.time()
                if first is None:
                    first = now
                if event == "exact":
                    exact_at = now
                    answer = json.loads(line[5:])
                elif event == "error":
                    raise RuntimeError(f"{name}: {line[5:]}")
    computed = answer.get("computed", {})
    return {"view": name, "exact_asked": exact, "estimate_s": est.get("seconds"), "estimate_rows": est.get("rows"),
            "estimate_bytes": est.get("bytes"), "first_s": round((first or t1) - t1, 3), "exact_s": round((exact_at or time.time()) - t1, 3),
            "estimate_ms": round((t1 - t0) * 1000, 1), "cells": answer.get("cellCount"), "rows_read": computed.get("rowsRead"),
            "provider_ms": computed.get("providerMs"), "from_cache": computed.get("fromCache"), "sets": computed.get("sets"),
            "rollup": next((n for n in answer.get("notes", []) if "rollup" in n), None),
            "nodes": next((n for n in answer.get("notes", []) if "nodes" in n), None)}


def define_rollup(server: str, token: str, wait_s: float = 3_600) -> None:
    with http(server, "/api/v1/pivot/rollups", ROLLUP, token):
        pass
    deadline = time.time() + wait_s
    while time.time() < deadline:
        with http(server, f"/api/v1/pivot/rollups/{ROLLUP['id']}/refresh", {}, token, timeout=wait_s) as r:
            words = json.load(r).get("words", "")
        if "current" in words or "rows of partial states" in words:
            return
        time.sleep(10)


def runs(servers: list[str], token: str, gb: float, nodes: int, cache: str, rollups: str, label: str, out: Path,
         cold_cmd: str | None = None, views: list[str] | None = None, log=print) -> list[dict]:
    """The views at one configuration, each result one JSON line in out. cold: --cold-cmd runs before each view (restart the servers,
    drop the page cache: an operator's command); warm: each view once untimed first."""
    server = servers[0]
    if rollups == "with":
        define_rollup(server, token)
    results = []
    for name in views or list(VIEWS):
        view = VIEWS[name]
        for exact in ((False, True) if name in ("V2", "V3") else (None,)):
            if cache == "cold" and cold_cmd:
                subprocess.run(cold_cmd, shell=True, check=True)
            elif cache == "warm":
                try:
                    run_view(server, token, name, view, exact)
                except Exception:  # noqa: BLE001 - the timed run says what failed
                    pass
            try:
                r = run_view(server, token, name, view, exact)
                r["error"] = None
            except Exception as e:  # noqa: BLE001 - an optional view (V5) without Python functions, or a refusal: recorded
                r = {"view": name, "exact_asked": exact, "error": str(e)[:300]}
            r.update({"label": label, "gb": gb, "nodes": nodes, "cache": cache, "rollups": rollups,
                      "at": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
            results.append(r)
            out.parent.mkdir(parents=True, exist_ok=True)
            with out.open("a", encoding="utf-8") as f:
                f.write(json.dumps(r) + "\n")
            log(f"pivot_bench: {label} {name} exact={exact}: first {r.get('first_s')} s, exact {r.get('exact_s')} s"
                + (f", error {r['error']}" if r.get("error") else ""))
    return results


# ---- the report: measured points, extrapolated sizes --------------------------------------------------------------------------------

def fit(points: list[tuple[float, float]]) -> tuple[float, float] | None:
    """Least squares of seconds = a + b·GB over the measured points; None with fewer than two sizes."""
    xs = sorted({x for x, _ in points})
    if len(xs) < 2:
        return None
    n = len(points)
    mx = sum(x for x, _ in points) / n
    my = sum(y for _, y in points) / n
    sxx = sum((x - mx) ** 2 for x, _ in points)
    b = sum((x - mx) * (y - my) for x, y in points) / sxx
    return my - b * mx, b


def report(lines: list[dict]) -> str:
    """Markdown for BENCHMARK.md: one table of the measured runs, one of the sizes extrapolated from them (labelled EXTRAPOLATED)."""
    ok = [r for r in lines if not r.get("error")]
    out = ["### Measured", "", "| GB | Nodes | Cache | Rollup | View | Exact asked | First (≈) s | Exact s | Estimate s | Rows read | Cells |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in sorted(ok, key=lambda r: (r["gb"], r["nodes"], r["cache"], r["rollups"], r["view"], str(r.get("exact_asked")))):
        out.append(f"| {r['gb']:g} | {r['nodes']} | {r['cache']} | {r['rollups']} | {r['view']} | {'' if r.get('exact_asked') is None else r['exact_asked']} "
                   f"| {r.get('first_s')} | {r.get('exact_s')} | {fmt(r.get('estimate_s'))} | {fmt(r.get('rows_read'))} | {r.get('cells')} |")
    failed = [r for r in lines if r.get("error")]
    if failed:
        out += ["", "Not answered: " + "; ".join(f"{r['view']} at {r['gb']:g} GB on {r['nodes']} node(s): {r['error']}" for r in failed)]
    out += ["", "### Extrapolated (EXTRAPOLATED: a straight line through the measured points above, not measured)", "",
            "| Nodes | Cache | Rollup | View | Exact asked | Measured at (GB) | " + " | ".join(f"{g:,} GB (EXTRAPOLATED) s" for g in EXTRAPOLATED_GB) + " |",
            "|---|---|---|---|---|---|" + "---|" * len(EXTRAPOLATED_GB)]
    groups: dict[tuple, list[tuple[float, float]]] = {}
    for r in ok:
        groups.setdefault((r["nodes"], r["cache"], r["rollups"], r["view"], r.get("exact_asked")), []).append((float(r["gb"]), float(r["exact_s"])))
    for key in sorted(groups, key=str):
        f = fit(groups[key])
        if not f:
            continue
        a, b = f
        sizes = ", ".join(f"{g:g}" for g in sorted({x for x, _ in groups[key]}))
        est = " | ".join(f"≈{max(0.0, a + b * g):,.0f}" for g in EXTRAPOLATED_GB)
        nodes, cache, rollups, view, exact = key
        out.append(f"| {nodes} | {cache} | {rollups} | {view} | {'' if exact is None else exact} | {sizes} | {est} |")
    return "\n".join(out) + "\n"


def fmt(x) -> str:
    if x is None:
        return ""
    if isinstance(x, float):
        return f"{x:,.1f}"
    if isinstance(x, int):
        return f"{x:,}"
    return str(x)


def stages(nodes: int) -> list[int]:
    """The lake's sizes, smallest first: the warm-up, 25 GB, and 25 GB a node for the cluster (5, 25 and 75 GB on three nodes). The lake
    grows from one to the next (days appended); at each size the views run on one node and on the cluster, on the same data."""
    return sorted({WARM_UP_GB, MAX_GB_PER_NODE, MAX_GB_PER_NODE * max(1, nodes)})


def node_counts(total: float, nodes: int) -> list[int]:
    """The node counts a size runs on: one node only while it reads at most 25 GB; the cluster always."""
    return sorted({n for n in (1, nodes) if total / n <= MAX_GB_PER_NODE + 1e-9})


def plan(nodes: int) -> str:
    """The run matrix and the disk it needs, in words."""
    lines = []
    for total in stages(nodes):
        for n in node_counts(total, nodes):
            for cache in ("cold", "warm"):
                for roll in ("without", "with"):
                    lines.append(f"{total:>4g} GB lake ({total / n:.1f} GB a node) | {n} node(s) | {cache:4} | rollup {roll:7} | views {', '.join(VIEWS)}")
    need = max(stages(nodes))
    lines.append(f"disk: the lake grows to about {need} GB (the largest size), on a disk with at least {MIN_FREE_GB} GB free; deleted at the end "
                 "unless --keep")
    lines.append("extrapolated, never measured: " + ", ".join(f"{g:,} GB" for g in EXTRAPOLATED_GB))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan", help="the run matrix and the disk it needs; nothing written")
    p.add_argument("--nodes", type=int, default=3)
    g = sub.add_parser("generate", help="trades into a Delta lake under --data-dir")
    g.add_argument("--data-dir", type=Path, required=True)
    g.add_argument("--gb", type=float, default=WARM_UP_GB, help="the lake's size in GB (it grows to it: days appended)")
    g.add_argument("--seed", type=int, default=20261102)
    g.add_argument("--rows-per-day", type=int, default=0, help="fixed rows a day (default: aimed at the size)")
    g.add_argument("--python", help="the Python with deltalake and pyarrow (default: the console's .venv)")
    r = sub.add_parser("run", help="the views against running servers, one configuration")
    r.add_argument("--servers", required=True, help="comma-separated server URLs (the first answers; the others are its cluster)")
    r.add_argument("--token-file", required=True)
    r.add_argument("--gb", type=float, required=True, help="the lake's size, as generated")
    r.add_argument("--nodes", type=int, default=1)
    r.add_argument("--cache", choices=("cold", "warm"), default="cold")
    r.add_argument("--rollups", choices=("without", "with"), default="without")
    r.add_argument("--cold-cmd", help="a command run before each cold view (restart the servers, drop the page cache)")
    r.add_argument("--views", help="comma-separated (default: V1..V5)")
    r.add_argument("--label", default="r1")
    r.add_argument("--out", type=Path, default=Path("tools/bench/results/pivot.jsonl"))
    rep = sub.add_parser("report", help="the tables for BENCHMARK.md from results")
    rep.add_argument("results", type=Path)
    c = sub.add_parser("clean", help="delete what generate made")
    c.add_argument("--data-dir", type=Path, required=True)
    a = sub.add_parser("all", help="both sizes, every configuration, the report; the data deleted at the end unless --keep")
    a.add_argument("--data-dir", type=Path, required=True)
    a.add_argument("--nodes", type=int, default=3)
    a.add_argument("--servers", required=True, help="the cluster's servers (the 3-node runs)")
    a.add_argument("--single", help="a server on the same lake with drishti.pivot.cluster.enabled: false (the 1-node runs); none: skipped")
    a.add_argument("--token-file", required=True)
    a.add_argument("--cold-cmd")
    a.add_argument("--out", type=Path, default=Path("tools/bench/results/pivot.jsonl"))
    a.add_argument("--keep", action="store_true", help="keep the generated lake")
    a.add_argument("--python")
    args = ap.parse_args(argv)

    if args.cmd == "plan":
        print(plan(args.nodes))
        return 0
    if args.cmd == "generate":
        print(json.dumps(generate(args.data_dir, args.gb, args.seed, rows_per_day=args.rows_per_day, python=args.python)))
        return 0
    if args.cmd == "run":
        token = Path(args.token_file).read_text(encoding="utf-8").strip()
        res = runs(args.servers.split(","), token, args.gb, args.nodes, args.cache, args.rollups, args.label, args.out, args.cold_cmd,
                   args.views.split(",") if args.views else None)
        return 0 if all(not x.get("error") or VIEWS[x["view"]].get("optional") for x in res) else 1
    if args.cmd == "report":
        lines = [json.loads(x) for x in args.results.read_text(encoding="utf-8").splitlines() if x.strip()]
        sys.stdout.write(report(lines))
        return 0
    if args.cmd == "clean":
        why = check_data_dir(args.data_dir, 0)
        if why and "inside the repository" in why:
            raise SystemExit("pivot_bench: " + why)
        for sub_dir in ("delta", "tmp"):
            shutil.rmtree(args.data_dir / sub_dir, ignore_errors=True)
        (args.data_dir / "generated.json").unlink(missing_ok=True)
        return 0
    if args.cmd == "all":
        token = Path(args.token_file).read_text(encoding="utf-8").strip()
        servers = args.servers.split(",")
        try:
            for total in stages(args.nodes):
                generate(args.data_dir, total, python=args.python)         # grows the same lake: days are appended until the size
                have = total
                for n in node_counts(total, args.nodes):
                    targets = [args.single] if n == 1 and args.nodes > 1 else servers[:n]
                    if not targets or not targets[0]:
                        print(f"pivot_bench: 1-node runs skipped: no --single server", file=sys.stderr)
                        continue
                    for cache in ("cold", "warm"):
                        for roll in ("without", "with"):
                            runs(targets, token, have, n, cache, roll, f"{have:g}GB-{n}n", args.out, args.cold_cmd)
            sys.stdout.write(report([json.loads(x) for x in args.out.read_text(encoding="utf-8").splitlines() if x.strip()]))
        finally:
            if not args.keep:
                for sub_dir in ("delta", "tmp"):
                    shutil.rmtree(args.data_dir / sub_dir, ignore_errors=True)
                (args.data_dir / "generated.json").unlink(missing_ok=True)
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
