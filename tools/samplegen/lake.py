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

"""Writes a pack's sample documents into Delta Lake tables for the server's Delta connector: one table per kind,
partitioned by business date, with a history of business days. Past dates vary the documents' market-sensitive
numbers with a deterministic random walk, so moving the date in the console changes what you see.

    uv run --with deltalake --with pyarrow --with pyyaml python tools/samplegen/lake.py \\
        --samples config/packs/finance/samples --root data/delta --domain finance --days 10 [--as-of 2026-09-30]

Layout (what the connector reads):  <root>/<domain>/<kind>/_delta_log/…
                                    <root>/<domain>/<kind>/business_date=YYYY-MM-DD/part-….parquet
Rows: id STRING, doc STRING (the JSON document), business_date DATE (the partition), plus the columns of the kind's
layout when a pack declares one for the domain (samplegen/layout.py: promoted paths, sorted by id, large files).
The newest date is written twice: the original, then a restatement of one document, so time travel
("as known at") has something to show. Build-time tooling only: the console never reads the lake.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib
import random
import sys
from datetime import date, timedelta

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
from samplegen.dates import Calendar  # noqa: E402
from samplegen.layout import Layout, layouts_for_domain, write_days, write_partition  # noqa: E402

VARY = {"mtm", "netMtm", "dv01", "pv", "npv", "eePeak", "pfe95Peak", "pfePeak", "spot", "lastPrice", "price", "level", "mid",
        "utilisation", "used", "collateralPosted", "cva", "fixing", "rate", "zeroRate"}


def walk(doc, key: str, steps: int, rnd: random.Random):
    """A copy of doc with the varying numbers moved back `steps` business days."""
    out = copy.deepcopy(doc)

    def visit(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if isinstance(v, (int, float)) and not isinstance(v, bool) and k in VARY and v != 0:
                    drift = sum(rnd.gauss(0, 0.012) for _ in range(steps))
                    nv = v * (1 + drift) if abs(v) > 1 else v + drift * 0.01 * max(abs(v), 0.01)
                    node[k] = round(nv) if isinstance(v, int) else round(nv, 6)
                else:
                    visit(v)
        elif isinstance(node, list):
            for x in node:
                visit(x)

    visit(out)
    return out


def business_days(end: date, n: int, cal: Calendar) -> list[date]:
    out, d = [], end
    while len(out) < n:
        if cal.is_business_day(d):
            out.append(d)
        d -= timedelta(days=1)
    return sorted(out)


def history_rows(kinds: dict[str, dict[str, dict]], end: date, days: int, cal: Calendar):
    """(kind, id, business_date, json) for `days` business days ending at `end`: past dates walk the market-sensitive
    numbers back deterministically. The same history goes to every store (Delta Lake, PostgreSQL, Aerospike)."""
    dates = business_days(end, days, cal)
    for kind, docs in sorted(kinds.items()):
        for i, d in enumerate(dates):
            steps = len(dates) - 1 - i
            for id_, doc in docs.items():
                rnd = random.Random(int(hashlib.sha256(f"{kind}/{id_}".encode()).hexdigest()[:12], 16))
                body = walk(doc, id_, steps, rnd) if steps else copy.deepcopy(doc)
                body.pop("_meta", None)
                body["businessDate"] = d.isoformat()
                yield kind, id_, d, json.dumps(body, ensure_ascii=False)


def restatement(docs: dict[str, dict], end: date) -> tuple[str, str]:
    """The correction the lake records for the newest date, after the fact: (id, document JSON). The lake writes it
    as a second commit (so "known at" time travel has something to show); stores without versions load it directly,
    so every store ends in the same state."""
    first = next(iter(docs))
    fixed = copy.deepcopy(docs[first])
    fixed.pop("_meta", None)
    fixed["businessDate"] = end.isoformat()
    fixed["restated"] = {"reason": "End-of-day correction", "version": 2}
    return first, json.dumps(fixed, ensure_ascii=False)


def final_rows(kinds: dict[str, dict[str, dict]], end: date, days: int, cal: Calendar):
    """history_rows with the newest date's restatement applied: the latest knowledge, for stores without versions."""
    fixes = {kind: restatement(docs, end) for kind, docs in kinds.items() if docs}
    for kind, id_, d, body in history_rows(kinds, end, days, cal):
        fix = fixes.get(kind)
        yield kind, id_, d, fix[1] if fix and d == end and id_ == fix[0] else body


def write_tables(out: pathlib.Path, kinds: dict[str, dict[str, dict]], end: date, days: int, cal: Calendar,
                 layouts: dict[str, Layout] | None = None) -> int:
    """Writes one Delta table per kind under `out` (see history_rows), plus a restatement of the newest date's first
    document, so time travel has something to show. A kind with a layout in `layouts` (layout.layouts_for_domain)
    is written in it: promoted columns, sorted by id, files of file-rows. Returns the number of rows written."""
    total = 0
    for kind, docs in sorted(kinds.items()):
        if not docs:
            continue
        lay = (layouts or {}).get(kind)
        by_day: dict[date, list] = {}
        for _, id_, d, body in history_rows({kind: docs}, end, days, cal):
            by_day.setdefault(d, []).append((id_, body, json.loads(body) if lay else None))
        path = out / kind
        write_days(str(path), by_day, lay, "overwrite")
        first, fixed = restatement(docs, end)
        keep = [r for r in by_day[end] if r[0] != first] + [(first, fixed, json.loads(fixed) if lay else None)]
        write_partition(str(path), end, keep, lay, "overwrite-partition")
        total += sum(len(r) for r in by_day.values())
        print(f"{path}: {len(docs)} entities x {days} business days" + (f", {len(lay.columns)} promoted columns" if lay else ""))
    return total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", required=True)
    ap.add_argument("--root", default=__import__("os").environ.get("DRISHTI_DATA_DIR", "data") + "/delta")
    ap.add_argument("--domain", required=True)
    ap.add_argument("--days", type=int, default=10)
    ap.add_argument("--as-of", default=None)
    ap.add_argument("--calendar", default="USNY")
    a = ap.parse_args()
    cal = Calendar.of(*a.calendar.split("+"))
    end = date.fromisoformat(a.as_of) if a.as_of else date.today()
    while not cal.is_business_day(end):
        end -= timedelta(days=1)
    samples = pathlib.Path(a.samples)
    kinds = {d.name: {f.stem: json.loads(f.read_text()) for f in sorted(d.glob("*.json"))} for d in sorted(samples.iterdir()) if d.is_dir()}
    total = write_tables(pathlib.Path(a.root) / a.domain, kinds, end, a.days, cal, layouts_for_domain(a.domain))
    print(f"{total} rows ({a.calendar})")


if __name__ == "__main__":
    main()
