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

"""Intraday updates of a stand-in pricing system, published to the PV topic: each message's value JSONL text, one or more records.

    pip install kafka-python   (pure Python; or: uv run --with kafka-python python tools/pvgen/publish_kafka.py ...)
    python tools/pvgen/publish_kafka.py [--bootstrap localhost:9092] [--topic drishti.risk.pv-updates] [--date 2026-10-27]
                                        [--rate 5] [--seconds 60] [--per-message 10] [--seed 7] [--mix amend=70,new=15,cancel=15]
                                        [--pv-systems ENDUR] [--pnl-systems ...] [--all-vectors] [--save FILE.jsonl] [--dry-run]

The same updates as tools/pvgen/intraday.py (amended, new and cancelled trades after the morning run, the same for a seed), sent at
``--rate`` messages a second for ``--seconds`` (0: until interrupted), as tools/samplegen/stream.py does. A message's value is JSONL
text, UTF-8: ``--per-message`` records (default 10), one JSON object a line, each exactly one row of the contract (a scenario-pv row,
or a pnl-vector row for a system sent as P&L vectors), ``RUN_KIND: INTRADAY``, its ``COMPUTED_AT`` after the morning run's
(``--start``, default five minutes after it, then a minute a batch of 50: the same for a seed; give a later ``--start`` to send newer
prices again). A cancel is a cancel line, as in the files: ``TRADE_STATUS: CANCELLED``, the vector null. The message's key names the
message (``P-<date>-<n>``), never a trade: Drishti reads each line's ``TRADE_ID``. Drishti's market-risk connector ``pv-updates`` (off
by default) collects the messages over a window and appends the window to the lake as one intraday batch. ``--save`` also writes every
record sent, as JSONL, for tools/pvgen/check.py. Guide: docs/guides/VAR_GUIDE.md section 15.12; docs/connectors/KAFKA_CONNECTOR.md."""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402


def records(ups: C.Updates, count: int):
    """The next ``count`` records (rows of the contract), in the order made."""
    sent = 0
    while sent < count:
        _, rows = ups.batch(min(50, count - sent))
        if not rows:
            return
        for _kind, row in rows:
            yield row
            sent += 1
            if sent >= count:
                return


def messages(ups: C.Updates, count: int, per_message: int, day: str):
    """The next ``count`` messages: (key, JSONL value, the records in it)."""
    batch: list[dict] = []
    n = 0
    for row in records(ups, count * per_message):
        batch.append(row)
        if len(batch) == per_message:
            n += 1
            yield f"P-{day}-{n:06d}", "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in batch), batch
            batch = []
    if batch:
        n += 1
        yield f"P-{day}-{n:06d}", "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in batch), batch


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--bootstrap", default="localhost:9092")
    p.add_argument("--topic", default="drishti.risk.pv-updates")
    p.add_argument("--testdata", default=str(C.ROOT / "data" / "testdata"))
    p.add_argument("--date", help="the business date (default: the test data's, 2026-09-30)")
    p.add_argument("--rate", type=float, default=5.0, help="messages a second (default 5)")
    p.add_argument("--seconds", type=float, default=60.0, help="how long (default 60; 0: until interrupted)")
    p.add_argument("--per-message", type=int, default=10, help="records (JSONL lines) a message (default 10)")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--mix", default="amend=70,new=15,cancel=15")
    p.add_argument("--pv-systems", default="ENDUR", metavar="SYS,SYS")
    p.add_argument("--pnl-systems", default="", metavar="SYS,SYS")
    p.add_argument("--all-vectors", action="store_true")
    p.add_argument("--morning-pv-systems", default="ENDUR", metavar="SYS,SYS")
    p.add_argument("--start", metavar="ISO", help="the first record's COMPUTED_AT (default five minutes after the morning run)")
    p.add_argument("--save", help="also write every record sent to this JSONL file (for tools/pvgen/check.py)")
    p.add_argument("--dry-run", action="store_true", help="make the messages and print them; publish nothing (no broker needed)")
    a = p.parse_args(argv)
    if a.per_message < 1:
        p.error("--per-message must be 1 or more")
    morning = C.Morning(Path(a.testdata), C.systems(a.morning_pv_systems), a.date)
    ups = C.Updates(morning, a.seed, C.mix_of(a.mix), C.M.pv_systems(a.pv_systems, a.pnl_systems, a.all_vectors), a.start)
    count = int(a.rate * a.seconds) if a.seconds > 0 else 10 ** 12
    saved: list[dict] = []
    sent = 0
    if a.dry_run:
        for key, value, rows in messages(ups, min(count, 10 ** 6), a.per_message, morning.day):
            first = value.split("\n", 1)[0]
            print(f"{a.topic} key={key} {len(rows)} lines: {first[:120]} ...")
            saved += rows
            sent += 1
        print(f"dry run: {sent} messages of up to {a.per_message} records ({len(saved)} records) made, none published")
    else:
        try:
            from kafka import KafkaProducer
        except ImportError:
            print("kafka-python is not installed: python -m pip install kafka-python (pure Python), or run with --dry-run", file=sys.stderr)
            return 2
        producer = KafkaProducer(bootstrap_servers=a.bootstrap, key_serializer=str.encode, value_serializer=str.encode, linger_ms=20)
        start = time.time()
        try:
            for key, value, rows in messages(ups, count, a.per_message, morning.day):
                producer.send(a.topic, key=key, value=value)
                saved += rows
                sent += 1
                if a.seconds > 0 and time.time() - start >= a.seconds:
                    break
                time.sleep(1 / a.rate)
        except KeyboardInterrupt:
            pass
        producer.flush()
        print(f"published {sent} messages ({len(saved)} records) to {a.topic} at {a.bootstrap}")
    if a.save:
        C.write_jsonl(Path(a.save), saved, a.save.endswith(".gz"))
        print(f"saved them to {a.save}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
