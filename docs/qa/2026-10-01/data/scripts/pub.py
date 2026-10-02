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

"""pub.py <queue> <n> [--offset K] [--mtm-add X] [--delete ID,...] [--pad BYTES]
Publishes the first n trades of the 09-30 book (persistent) to a RabbitMQ queue on localhost:16672 (publisher confirms)."""
import json, sys, time
import pika

S = "/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data"
q, n = sys.argv[1], int(sys.argv[2])
opt = {sys.argv[i]: sys.argv[i + 1] for i in range(3, len(sys.argv) - 1, 2)}
off, add, pad = int(opt.get("--offset", 0)), float(opt.get("--mtm-add", 0)), int(opt.get("--pad", 0))
conn = pika.BlockingConnection(pika.URLParameters("amqp://guest:guest@localhost:16672/%2f"))
ch = conn.channel()
ch.queue_declare(queue=q, durable=True)
ch.confirm_delivery()
props = pika.BasicProperties(delivery_mode=2, content_type="application/json")
t0 = time.time()
if "--delete" in opt:
    for i in opt["--delete"].split(","):
        ch.basic_publish("", q, b"", pika.BasicProperties(delivery_mode=2, headers={"id": i, "deleted": "true"}))
    print("deleted", opt["--delete"])
else:
    sent = 0
    with open(f"{S}/files/trading/2026-09-30/trade.jsonl") as f:
        for k, l in enumerate(f):
            if k < off:
                continue
            if sent >= n:
                break
            d = json.loads(json.loads(l)["doc"])
            d["mtm"] = d["mtm"] + add
            if pad:
                d["pad"] = __import__("os").urandom(pad // 2).hex()
            ch.basic_publish("", q, json.dumps(d).encode(), props)
            sent += 1
    print(f"published {sent} to {q} in {time.time() - t0:.1f}s")
conn.close()
