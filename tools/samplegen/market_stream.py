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

"""Intraday market data for the market-data pack's live companion market-store-live (DRISHTI_MARKET_STREAM=true): one Kafka topic per
kind, drishti.market.<kind> (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 8.3).

    uv run --with kafka-python python tools/samplegen/market_stream.py [--bootstrap localhost:9092] [--rate 20] [--seconds 0]
                                                                       [--kinds fx-spot,equity,ir-curve] [--samples ...]

Every sample row of each kind is published whole first, with the time the contract orders by (QUOTE_TIME, FIXED_AT or BUILT_AT), so a
merged tick has a base. Then `--rate` ticks a second: a price kind (fx-spot, equity, equity-index, commodity, bond) gets only its moved
price fields and QUOTE_TIME (partial: merge); a curve or a surface is published whole again, its points moved together (partial: whole).
The screens see the latest per instrument once a conflation period (1 s for prices, 5 s for curves, 30 s for surfaces).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import random
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "packgen" / "common"))
import packsamples  # noqa: E402  (samples/<kind>.jsonl)

#: kind -> (its key, the field the contract orders by, the price fields a tick moves; None: the row is sent whole)
KINDS = {
    "fx-spot": ("PAIR", "QUOTE_TIME", ["MID", "BID", "ASK"]),
    "equity": ("TICKER", "QUOTE_TIME", ["PRICE"]),
    "equity-index": ("INDEX_ID", "QUOTE_TIME", ["LEVEL"]),
    "commodity": ("COMMODITY_ID", "QUOTE_TIME", ["FRONT"]),
    "bond": ("ISIN", "QUOTE_TIME", ["PRICE", "YIELD", "Z_SPREAD"]),
    "ir-curve": ("CURVE_ID", "BUILT_AT", None),
    "fx-forward-curve": ("CURVE_ID", "BUILT_AT", None),
    "credit-curve": ("CURVE_ID", "BUILT_AT", None),
    "fx-vol-surface": ("SURFACE_ID", "BUILT_AT", None),
}
DEFAULT = "fx-spot,equity,equity-index,ir-curve,fx-vol-surface"


def now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def moved(v: float, rnd: random.Random, scale: float = 0.002) -> float:
    return round(v + rnd.gauss(0, max(1e-6, abs(v) * scale)), 6)


def tick(kind: str, row: dict, rnd: random.Random) -> dict:
    """The message of one tick: a price kind's moved fields and its time (merged over the row the feed holds); a curve's or a
    surface's whole row, every number of its points moved by the same shift."""
    key, at, prices = KINDS[kind]
    if prices is not None:
        out = {key: row[key], at: now()}
        for f in prices:
            if isinstance(row.get(f), (int, float)) and not isinstance(row.get(f), bool):
                row[f] = moved(float(row[f]), rnd)
                out[f] = row[f]
        if "BID" in out and "ASK" in out and "MID" in out:
            half = abs(row["ASK"] - row["BID"]) / 2 or abs(row["MID"]) * 1e-5
            out["BID"], out["ASK"] = round(row["MID"] - half, 6), round(row["MID"] + half, 6)
        return out
    shift = rnd.gauss(0, 0.0005)
    for part in ("POINTS", "GRID", "TENORS", "NODES"):
        for p in row.get(part) or []:
            for f, v in list(p.items()):
                if isinstance(v, (int, float)) and not isinstance(v, bool) and f not in ("TENOR_YEARS",):
                    p[f] = round(v * (1 + shift), 6)
    row[at] = now()
    return dict(row)


def main():
    from kafka import KafkaProducer

    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--bootstrap", default="localhost:9092")
    ap.add_argument("--prefix", default="drishti.market", help="topics are <prefix>.<kind>, as market-store-live reads them")
    ap.add_argument("--kinds", default=DEFAULT, help="comma-separated, of: " + ", ".join(KINDS))
    ap.add_argument("--samples", default=str(pathlib.Path(__file__).resolve().parents[2] / "modules/market-data/packs/market-data/samples"))
    ap.add_argument("--rate", type=float, default=20.0)
    ap.add_argument("--seconds", type=float, default=0.0)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    kinds = [k.strip() for k in a.kinds.split(",") if k.strip()]
    unknown = [k for k in kinds if k not in KINDS]
    if unknown:
        sys.exit(f"not a market kind this publisher ticks: {', '.join(unknown)} (of: {', '.join(KINDS)})")
    producer = KafkaProducer(bootstrap_servers=a.bootstrap, key_serializer=str.encode,
                             value_serializer=lambda d: json.dumps(d, ensure_ascii=False).encode(), linger_ms=20)
    samples = packsamples.read(pathlib.Path(a.samples))
    rows = {}
    for k in kinds:
        key, at, _ = KINDS[k]
        for d in samples.get(k, {}).values():
            d.pop("_meta", None)
            d[at] = now()
            rows[(k, str(d[key]))] = d
            producer.send(f"{a.prefix}.{k}", key=str(d[key]), value=d)
    producer.flush()
    print(f"published {len(rows)} rows of {', '.join(kinds)} to {a.prefix}.<kind>; ticking {a.rate}/s")
    rnd, start, ids = random.Random(a.seed), time.time(), list(rows)
    try:
        while ids and (a.seconds <= 0 or time.time() - start < a.seconds):
            k, i = ids[rnd.randrange(len(ids))]
            producer.send(f"{a.prefix}.{k}", key=i, value=tick(k, rows[(k, i)], rnd))
            time.sleep(1 / a.rate)
    except KeyboardInterrupt:
        pass
    producer.flush()


if __name__ == "__main__":
    main()
