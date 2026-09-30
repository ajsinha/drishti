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

    uv run --with deltalake --with pyarrow python tools/samplegen/lake.py \\
        --samples packs/finance/samples --root data/delta --domain finance --days 10 [--as-of 2026-09-30]

Layout (what the connector reads):  <root>/<domain>/<kind>/_delta_log/…
                                    <root>/<domain>/<kind>/business_date=YYYY-MM-DD/part-….parquet
Rows: id STRING, doc STRING (the JSON document), business_date DATE (the partition).
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


def main():
    import pyarrow as pa
    from deltalake import write_deltalake

    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", required=True)
    ap.add_argument("--root", default="data/delta")
    ap.add_argument("--domain", required=True)
    ap.add_argument("--days", type=int, default=10)
    ap.add_argument("--as-of", default=None)
    ap.add_argument("--calendar", default="USNY")
    a = ap.parse_args()
    cal = Calendar.of(*a.calendar.split("+"))
    end = date.fromisoformat(a.as_of) if a.as_of else date.today()
    while not cal.is_business_day(end):
        end -= timedelta(days=1)
    dates = business_days(end, a.days, cal)
    samples = pathlib.Path(a.samples)
    out = pathlib.Path(a.root) / a.domain
    total = 0
    for kind_dir in sorted(p for p in samples.iterdir() if p.is_dir()):
        docs = {f.stem: json.loads(f.read_text()) for f in sorted(kind_dir.glob("*.json"))}
        if not docs:
            continue
        ids, bodies, days = [], [], []
        for i, d in enumerate(dates):
            steps = len(dates) - 1 - i
            for id_, doc in docs.items():
                rnd = random.Random(int(hashlib.sha256(f"{kind_dir.name}/{id_}".encode()).hexdigest()[:12], 16))
                body = walk(doc, id_, steps, rnd) if steps else copy.deepcopy(doc)
                body["businessDate"] = d.isoformat()
                ids.append(id_)
                bodies.append(json.dumps(body, ensure_ascii=False))
                days.append(d)
        table = pa.table({"id": pa.array(ids, pa.string()), "doc": pa.array(bodies, pa.string()),
                          "business_date": pa.array(days, pa.date32())})
        path = out / kind_dir.name
        write_deltalake(str(path), table, mode="overwrite", partition_by=["business_date"])
        # a restatement of the newest date's first document: time travel shows the value before the correction
        first = next(iter(docs))
        fixed = copy.deepcopy(docs[first])
        fixed["businessDate"] = end.isoformat()
        fixed["restated"] = {"reason": "End-of-day correction", "version": 2}
        keep = [(i, b) for i, b, d in zip(ids, bodies, days) if d == end and i != first] + [(first, json.dumps(fixed, ensure_ascii=False))]
        write_deltalake(str(path), pa.table({"id": pa.array([k for k, _ in keep], pa.string()), "doc": pa.array([b for _, b in keep], pa.string()),
                                             "business_date": pa.array([end] * len(keep), pa.date32())}),
                        mode="overwrite", partition_by=["business_date"], predicate=f"business_date = '{end.isoformat()}'")
        total += len(ids)
        print(f"{path}: {len(docs)} entities x {len(dates)} business days")
    print(f"{total} rows, {dates[0]} .. {dates[-1]} ({cal.name})")


if __name__ == "__main__":
    main()
