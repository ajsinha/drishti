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

"""Turns the scale benchmark's JSON lines (tools/bench/scale.sh, measure.py) into Markdown tables, standard library only.

    python3 tools/bench/report.py tools/bench/results/2026-10-01.jsonl [more.jsonl ...] [--target 1000000] [--out report.md]

For each store: a table of every measure at every size measured (median and p95 of the repetitions, requests per
second with concurrent clients), the run-to-run variance where a size was run twice, and a linear fit per measure
(value = a + b x trades a day, least squares over every run, R-squared shown) with its value at --target trades a day.
That value is an EXTRAPOLATION from the measured points, labelled as such everywhere it appears. Then a comparison
across stores at the largest measured size.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from collections import defaultdict

TIMED = [  # (measure, label)
    ("typeahead", "type-ahead `TRD CLY-400`"),
    ("view_first", "open a trade (view), first time"),
    ("view_cached", "open a trade (view), again"),
    ("raw_today", "raw document, today"),
    ("raw_past", "raw document, a past business day"),
    ("search_mtm", "`TRD where mtm < -50m order by mtm`"),
    ("search_usd", "`TRD where currency = 'USD' and notional > 500m order by notional desc limit 20`"),
    ("search_book", "`TRD book=BOOK-RATES-3`"),
    ("picklist", "pick list `TRD END-1100`"),
    ("desk_pnl", "desk P&L `desk-pnl/DESK-RATES`"),
    ("impact", "impact `netting-set/NS-SUMMIT-NY`"),
    ("search_other_day_first", "search on another business day, first (cold)"),
    ("search_other_day_again", "search on another business day, again"),
]
SEARCHES = ("search_mtm", "search_usd", "search_book", "picklist", "search_other_day_first", "search_other_day_again")
SCALARS = [  # (measure, label, scale, unit)
    ("load_s", "load time (loader script, end to end)", 1.0, "s"),
    ("store_bytes", "store size", 1 / 1048576, "MB"),
    ("start_s", "server start to healthy", 1.0, "s"),
    ("columns_ready_s", "after start: newest day searchable from columns", 1.0, "s"),
    ("heap_live_mb", "server live heap after a full GC", 1.0, "MB"),
    ("server_rss_mb", "server resident memory (at the end)", 1.0, "MB"),
]
STORE_ORDER = ["delta", "postgres", "duckdb", "files", "mongodb", "redis", "aerospike", "iceberg"]
STORE_NAME = {"delta": "Delta Lake (native)", "postgres": "PostgreSQL", "duckdb": "DuckDB", "files": "JSON-lines files",
              "mongodb": "MongoDB", "redis": "Redis", "aerospike": "Aerospike", "iceberg": "Apache Iceberg"}


def load(paths: list[str]) -> list[dict]:
    rows = []
    for p in paths:
        with open(p, encoding="utf-8") as f:
            rows.extend(json.loads(line) for line in f if line.strip())
    return rows


def value_of(r: dict):
    """The number a record stands for: the median of timed repetitions, else its single value."""
    for k in ("median", "value"):
        v = r.get(k)
        if isinstance(v, (int, float)) and not (isinstance(v, float) and math.isnan(v)):
            return float(v)
    return None


def fit(points: list[tuple[float, float]]):
    """Least squares y = a + b x: (a, b, r2), or None with fewer than two distinct x."""
    xs = [p[0] for p in points]
    if len(set(xs)) < 2:
        return None
    mx, my = statistics.fmean(xs), statistics.fmean(p[1] for p in points)
    sxx = sum((x - mx) ** 2 for x in xs)
    b = sum((x - mx) * (y - my) for x, y in points) / sxx
    a = my - b * mx
    ss_tot = sum((y - my) ** 2 for _, y in points)
    ss_res = sum((y - (a + b * x)) ** 2 for x, y in points)
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else 1.0
    return a, b, r2


def extrapolate(points, target):
    """(value, rising, fit): the fit's value at target; or, when the points do not rise with size (slope not above
    zero), their mean, said to be flat. A rising fit with R-squared below 0.5 is still extrapolated, and flagged weak."""
    f = fit(points)
    if f is None:
        return None
    a, b, r2 = f
    if b <= 0:
        return statistics.fmean(p[1] for p in points), False, f
    return a + b * target, True, f


NOT_FITTED = {"columns_ready_s", "server_rss_mb"}   # noise around zero; resident memory follows -Xmx, not the book


def fmt(v, digits=1):
    if v is None:
        return "—"
    if abs(v) >= 1000:
        return f"{v:,.0f}"
    if abs(v) >= 100:
        return f"{v:.0f}"
    return f"{v:.{digits}f}"


class Report:
    def __init__(self, rows: list[dict], target: int):
        self.target = target
        self.by = defaultdict(list)                     # (store, measure) -> records
        self.failed = defaultdict(list)
        for r in rows:
            if r.get("measure") == "failed":
                self.failed[r["store"]].append(r)
            else:
                self.by[(r["store"], r["measure"])].append(r)
        present = {s for s, _ in self.by}
        self.stores = [s for s in STORE_ORDER if s in present] + sorted(present - set(STORE_ORDER))
        self.out: list[str] = []

    def w(self, s=""):
        self.out.append(s)

    def runs(self, store):
        """Every (trades a day, run label, days) measured for a store, in size order."""
        keys = {(r["trades_per_day"], r["run"], r.get("days", 3)) for r in self.by[(store, "load_s")]}
        return sorted(keys)

    def rec(self, store, measure, n, run):
        for r in self.by[(store, measure)]:
            if r["trades_per_day"] == n and r["run"] == run:
                return r
        return None

    def points(self, store, measure, scale=1.0):
        pts = []
        for r in self.by[(store, measure)]:
            v = value_of(r)
            if v is not None:
                pts.append((float(r["trades_per_day"]), v * scale))
        return pts

    # -- per store --------------------------------------------------------------------------------------------------
    def store_section(self, store):
        runs = self.runs(store)
        days = sorted({d for _, _, d in runs})
        self.w(f"### {STORE_NAME.get(store, store)}")
        self.w()
        eng = {r.get("engine") for r in self.by[(store, "load_s")] if r.get("engine")}
        self.w(f"Trades a day x business days: {', '.join(f'{n:,} x {d}' + (f' ({run})' if run != 'r1' else '') for n, run, d in runs)}"
               + (f"; engine {', '.join(sorted(eng))}" if eng else "") + ".")
        self.w()
        head = "| Measure | " + " | ".join(f"{n:,}" + (f" ({run})" if run != "r1" else "") for n, run, _ in runs) + " |"
        self.w(head)
        self.w("|---|" + "---|" * len(runs))
        for m, label in TIMED:
            cells = []
            for n, run, _ in runs:
                r = self.rec(store, m, n, run)
                if r is None:
                    cells.append("—")
                elif "median" in r:
                    c = f"{fmt(r['median'])} / {fmt(r['p95'])} ms"
                    if r.get("rps"):
                        c += f", {fmt(r['rps'], 0)}/s"
                    cells.append(c + self.exact_mark(r, n))
                elif "value" in r:
                    cells.append(f"{fmt(r['value'])} ms" + self.exact_mark(r, n))
                else:
                    cells.append(r.get("error", "error"))
            self.w(f"| {label} | " + " | ".join(cells) + " |")
        cells = []
        for n, run, _ in runs:
            r = self.rec(store, "view_mixed_rps", n, run)
            cells.append(f"{fmt(r['rps'], 0)}/s" if r and r.get("rps") else "—")
        self.w("| opens of many trades, 8 clients | " + " | ".join(cells) + " |")
        for m, label, scale, unit in SCALARS:
            cells = []
            for n, run, _ in runs:
                r = self.rec(store, m, n, run)
                v = value_of(r) if r else None
                c = f"{fmt(v * scale)} {unit}" if v is not None else "—"
                if m == "server_rss_mb" and r and r.get("peak"):
                    c += f" (peak {fmt(r['peak'], 0)})"
                cells.append(c)
            label2 = label + (" (Redis `used_memory`)" if m == "store_bytes" and store == "redis" else "")
            self.w(f"| {label2} | " + " | ".join(cells) + " |")
        self.w()
        self.w("Timed cells: median / p95 of the repetitions, then requests per second with 8 concurrent clients. "
               "✓ every search exact (`partial: false`, `scanned` = the trades a day); ✗ not.")
        self.w()
        self.variance(store, runs)
        self.fits(store, runs)

    def exact_mark(self, r, n):
        if r.get("scanned") is None:
            return ""
        return " ✓" if (r.get("partial") is False and r.get("scanned") == n) else f" ✗ (scanned {r.get('scanned')}, partial {r.get('partial')})"

    def variance(self, store, runs):
        twice = defaultdict(list)
        for n, run, _ in runs:
            twice[n].append(run)
        pairs = [(n, rs[0], rs[1]) for n, rs in twice.items() if len(rs) >= 2]
        if not pairs:
            return
        for n, a, b in pairs:
            diffs = []
            for m, label in TIMED:
                ra, rb = self.rec(store, m, n, a), self.rec(store, m, n, b)
                va, vb = (value_of(ra) if ra else None), (value_of(rb) if rb else None)
                if va and vb:
                    diffs.append((label, va, vb, abs(va - vb) / ((va + vb) / 2) * 100))
            if diffs:
                med = statistics.median(d[3] for d in diffs)
                worst = max(diffs, key=lambda d: d[3])
                self.w(f"Run-to-run variance at {n:,} ({a} against {b}): the medians differ by {med:.0f}% (median over the "
                       f"timed measures); the most is {worst[0]} ({fmt(worst[1])} against {fmt(worst[2])} ms, {worst[3]:.0f}%).")
                self.w()

    def fits(self, store, runs):
        self.w(f"Linear fit per measure over every run (value = a + b x trades a day), and its value at "
               f"{self.target:,} trades a day — **an extrapolation from the measured points, not a measurement**:")
        self.w()
        self.w(f"| Measure | a | b per 10,000 trades | R² | at {self.target:,} (extrapolated) |")
        self.w("|---|---|---|---|---|")
        for m, label, unit, scale in [(m, l, "ms", 1.0) for m, l in TIMED] + [(m, l, u, s) for m, l, s, u in SCALARS]:
            pts = self.points(store, m, scale)
            e = extrapolate(pts, self.target)
            if e is None or m in NOT_FITTED:
                continue
            y, trend, (a, b, r2) = e
            ext = (f"{fmt(y)} {unit}" + (" (weak fit)" if r2 < 0.5 else "")) if trend else f"flat: about {fmt(y)} {unit} (does not rise with size)"
            self.w(f"| {label} | {fmt(a, 2)} {unit} | {b * 10000:+.3g} {unit} | {r2:.2f} | {ext} |")
        self.w()

    # -- across stores ----------------------------------------------------------------------------------------------
    def comparison(self):
        largest = {s: max((n for n, _, _ in self.runs(s)), default=0) for s in self.stores}
        self.w("### Comparison across stores")
        self.w()
        self.w("At the largest size each store ran (median ms of the run labelled r1; requests per second with 8 clients in brackets):")
        self.w()
        self.w("| Measure | " + " | ".join(f"{STORE_NAME.get(s, s)} ({largest[s]:,})" for s in self.stores) + " |")
        self.w("|---|" + "---|" * len(self.stores))
        for m, label in TIMED:
            cells = []
            for s in self.stores:
                r = self.rec(s, m, largest[s], "r1")
                v = value_of(r) if r else None
                c = fmt(v) if v is not None else "—"
                if r and r.get("rps"):
                    c += f" ({fmt(r['rps'], 0)}/s)"
                cells.append(c)
            self.w(f"| {label} | " + " | ".join(cells) + " |")
        for m, label, scale, unit in SCALARS:
            cells = []
            for s in self.stores:
                r = self.rec(s, m, largest[s], "r1")
                v = value_of(r) if r else None
                cells.append(f"{fmt(v * scale)} {unit}" if v is not None else "—")
            self.w(f"| {label} | " + " | ".join(cells) + " |")
        self.w()
        self.w(f"Extrapolated to {self.target:,} trades a day from each store's linear fit (**extrapolations, not measurements**):")
        self.w()
        self.w("| Measure | " + " | ".join(STORE_NAME.get(s, s) for s in self.stores) + " |")
        self.w("|---|" + "---|" * len(self.stores))
        for m, label, scale, unit in [(m, l, 1.0, "ms") for m, l in TIMED] + [x for x in SCALARS if x[0] not in NOT_FITTED]:
            cells = []
            for s in self.stores:
                pts = self.points(s, m, scale)
                e = extrapolate(pts, self.target)
                if e is None:
                    cells.append("—")
                    continue
                y, trend, (_, _, r2) = e
                cells.append(f"{fmt(y)} {unit}" + ("" if trend and r2 >= 0.5 else "†" if trend else "*"))
            self.w(f"| {label} | " + " | ".join(cells) + " |")
        self.w()
        self.w("\\* flat: the measured points do not rise with size (the fitted slope is not above zero), so the figure is "
               "their mean, not a fit. † a rising fit with R² below 0.5: noisy points, a weak extrapolation.")
        self.w()

    def render(self) -> str:
        for s in self.stores:
            self.store_section(s)
        if len(self.stores) > 1:
            self.comparison()
        if self.failed:
            self.w("### Runs that failed")
            self.w()
            for s, rs in self.failed.items():
                for r in rs:
                    self.w(f"- {STORE_NAME.get(s, s)}, {r['trades_per_day']:,} trades a day ({r['run']}): exit {r.get('exit')}"
                           + (f" — {r['why']}" if r.get("why") else ""))
            self.w()
        return "\n".join(self.out)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--target", type=int, default=1_000_000, help="trades a day to extrapolate to (default 1,000,000)")
    ap.add_argument("--out", default="-")
    a = ap.parse_args(argv)
    text = Report(load(a.files), a.target).render()
    if a.out == "-":
        print(text)
    else:
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
