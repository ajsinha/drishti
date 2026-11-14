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

"""Streams a pack's sample trades to Kafka, then keeps them ticking, so Drishti's Live views update from a real
stream (the trading pack's `trading-stream` connector, switched on with DRISHTI_STREAM_TRADING=true).

    uv run --with kafka-python python tools/samplegen/stream.py [--bootstrap localhost:9092] [--topic drishti.trading.trades]
                                                                [--samples config/packs/banking/trading/samples] [--rate 5] [--seconds 0]

Each trade is published once, keyed by its TRADE_ID (the topic is the state: the latest message per trade wins), then `--rate` random
trades per second get a new PRICE (a trade carries no MTM: that is the market-risk pack's trade valuation) and a new SOURCE_UPDATE_TIME.
`--seconds 0` runs until interrupted.

Every message carries a numeric TRADE_VERSION and UPDATED_BY (the demo trade blotter's version and who made it, modules/trading
blotters/trade.blotter.yaml): a tick is a new version by STREAM, so the blotter merges by version and refuses an edit made on an older one,
naming who moved it. The samples' own version (text, "1") is read as a number; a trade without one starts at 1.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import random
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "packgen" / "common"))
import packsamples  # noqa: E402  (samples/trade.jsonl)


def main():
    from kafka import KafkaProducer

    ap = argparse.ArgumentParser()
    ap.add_argument("--bootstrap", default="localhost:9092")
    ap.add_argument("--topic", default="drishti.trading.trades")
    ap.add_argument("--samples", default="config/packs/banking/trading/samples")
    ap.add_argument("--rate", type=float, default=5.0)
    ap.add_argument("--seconds", type=float, default=0.0)
    a = ap.parse_args()
    producer = KafkaProducer(bootstrap_servers=a.bootstrap, key_serializer=str.encode,
                             value_serializer=lambda d: json.dumps(d, ensure_ascii=False).encode(), linger_ms=20)
    trades = {}
    for d in packsamples.read(pathlib.Path(a.samples)).get("trade", {}).values():
        d.pop("_meta", None)
        versioned(d, d.get("TRADER_ID") or "BOOKING")
        trades[str(d["TRADE_ID"])] = d
        producer.send(a.topic, key=str(d["TRADE_ID"]), value=d)
    producer.flush()
    print(f"published {len(trades)} trades to {a.topic}; ticking {a.rate}/s")
    rnd, start, ids = random.Random(7), time.time(), list(trades)
    try:
        while a.seconds <= 0 or time.time() - start < a.seconds:
            d = trades[rnd.choice(ids)]
            price = d.get("PRICE") if isinstance(d.get("PRICE"), (int, float)) else 100.0
            d["PRICE"] = round(price + rnd.gauss(0, max(0.0001, abs(price) * 0.002)), 6)
            d["SOURCE_UPDATE_TIME"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            d["TRADE_VERSION"] = int(d["TRADE_VERSION"]) + 1
            d["UPDATED_BY"] = "STREAM"
            producer.send(a.topic, key=str(d["TRADE_ID"]), value=d)
            time.sleep(1 / a.rate)
    except KeyboardInterrupt:
        pass
    producer.flush()


def versioned(d: dict, by: str) -> dict:
    """The trade with a numeric TRADE_VERSION (the samples' text "3" is 3; none is 1) and UPDATED_BY (kept when it has one)."""
    try:
        d["TRADE_VERSION"] = max(1, int(float(str(d.get("TRADE_VERSION") or "1").strip())))
    except ValueError:
        d["TRADE_VERSION"] = 1
    d.setdefault("UPDATED_BY", by)
    return d


if __name__ == "__main__":
    main()
