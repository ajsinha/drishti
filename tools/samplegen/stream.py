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
                                                                [--samples config/packs/trading/samples] [--rate 5] [--seconds 0]

Each trade is published once, keyed by its TRADE_ID (the topic is the state: the latest message per trade wins), then `--rate` random
trades per second get a new PRICE (a trade carries no MTM: that is the market-risk pack's trade valuation) and a new SOURCE_UPDATE_TIME.
`--seconds 0` runs until interrupted.
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
    ap.add_argument("--samples", default="config/packs/trading/samples")
    ap.add_argument("--rate", type=float, default=5.0)
    ap.add_argument("--seconds", type=float, default=0.0)
    a = ap.parse_args()
    producer = KafkaProducer(bootstrap_servers=a.bootstrap, key_serializer=str.encode,
                             value_serializer=lambda d: json.dumps(d, ensure_ascii=False).encode(), linger_ms=20)
    trades = {}
    for d in packsamples.read(pathlib.Path(a.samples)).get("trade", {}).values():
        d.pop("_meta", None)
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
            producer.send(a.topic, key=str(d["TRADE_ID"]), value=d)
            time.sleep(1 / a.rate)
    except KeyboardInterrupt:
        pass
    producer.flush()


if __name__ == "__main__":
    main()
