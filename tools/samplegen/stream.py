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
                                                                [--samples packs/trading/samples/trade] [--rate 5] [--seconds 0]

Each trade is published once (the topic is the state: the latest message per trade wins), then `--rate` random
trades per second get a new MTM and 1-day P&L. `--seconds 0` runs until interrupted.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import random
import time


def main():
    from kafka import KafkaProducer

    ap = argparse.ArgumentParser()
    ap.add_argument("--bootstrap", default="localhost:9092")
    ap.add_argument("--topic", default="drishti.trading.trades")
    ap.add_argument("--samples", default="packs/trading/samples/trade")
    ap.add_argument("--rate", type=float, default=5.0)
    ap.add_argument("--seconds", type=float, default=0.0)
    a = ap.parse_args()
    producer = KafkaProducer(bootstrap_servers=a.bootstrap, key_serializer=str.encode,
                             value_serializer=lambda d: json.dumps(d, ensure_ascii=False).encode(), linger_ms=20)
    trades = {}
    for f in sorted(pathlib.Path(a.samples).glob("*.json")):
        d = json.loads(f.read_text())
        d.pop("_meta", None)
        trades[d["tradeId"]] = d
        producer.send(a.topic, key=d["tradeId"], value=d)
    producer.flush()
    print(f"published {len(trades)} trades to {a.topic}; ticking {a.rate}/s")
    rnd, start, ids = random.Random(7), time.time(), list(trades)
    try:
        while a.seconds <= 0 or time.time() - start < a.seconds:
            d = trades[rnd.choice(ids)]
            step = max(500, abs(d["mtm"]) * 0.002 + d["notional"] * 1e-5)
            move = round(rnd.gauss(0, step))
            d["mtm"] += move
            d["pnl1d"] = d.get("pnl1d", 0) + move
            producer.send(a.topic, key=d["tradeId"], value=d)
            time.sleep(1 / a.rate)
    except KeyboardInterrupt:
        pass
    producer.flush()


if __name__ == "__main__":
    main()
