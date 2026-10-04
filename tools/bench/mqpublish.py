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

"""Publishes documents to a RabbitMQ queue through the management HTTP API (no client library needed): N entities
(ids PREFIX1..PREFIXN), then, with --rate, updates of random ones for --seconds, so a load test has a message source
that is busy while the server is read. Used by tools/bench/pinning.sh.

  tools/bench/mqpublish.py --api http://localhost:25673 --user pin --password pin --queue pin.orders --kind-id-field orderId \
      --count 2000 [--rate 20 --seconds 240]
"""
from __future__ import annotations

import argparse
import base64
import json
import random
import time
import urllib.request


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", required=True)
    ap.add_argument("--user", default="guest")
    ap.add_argument("--password", default="guest")
    ap.add_argument("--queue", required=True)
    ap.add_argument("--prefix", default="MC-LOAD-")
    ap.add_argument("--id-field", default="callId")
    ap.add_argument("--count", type=int, default=2000)
    ap.add_argument("--rate", type=float, default=0, help="updates a second after the initial publish")
    ap.add_argument("--seconds", type=float, default=0)
    a = ap.parse_args()
    auth = "Basic " + base64.b64encode(f"{a.user}:{a.password}".encode()).decode()
    url = a.api.rstrip("/") + "/api/exchanges/%2f/amq.default/publish"

    def publish(n: int, version: int):
        doc = {a.id_field: f"{a.prefix}{n}", "callDate": "2026-09-28", "marginType": "Variation", "status": "Issued",
               "amount": 1000.0 * n + version, "version": version, "currency": "USD", "nettingSet": "NS-SUMMIT-NY"}
        body = {"properties": {"delivery_mode": 2, "headers": {"id": f"{a.prefix}{n}"}}, "routing_key": a.queue,
                "payload": json.dumps(doc), "payload_encoding": "string"}
        req = urllib.request.Request(url, json.dumps(body).encode(), {"Authorization": auth, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            r.read()

    for n in range(1, a.count + 1):
        publish(n, 0)
    print(f"published {a.count} documents to {a.queue}", flush=True)
    if a.rate > 0:
        end, v = time.time() + a.seconds, 1
        while time.time() < end:
            publish(random.randint(1, a.count), v)
            v += 1
            time.sleep(1.0 / a.rate)
        print(f"published {v - 1} updates", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
