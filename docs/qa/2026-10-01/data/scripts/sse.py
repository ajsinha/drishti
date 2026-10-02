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

"""sse.py <port> <kind> <id> <seconds>: opens a view's SSE stream and prints every event (truncated) for <seconds>."""
import sys, time, urllib.request, threading

port, kind, id_, secs = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
url = f"http://localhost:{port}/api/v1/views/{kind}/{id_}/stream"
end = time.time() + secs


def run():
    try:
        with urllib.request.urlopen(url, timeout=secs + 5) as r:
            buf = ""
            while time.time() < end:
                line = r.readline().decode()
                if not line:
                    break
                line = line.rstrip("\n")
                if line.startswith("event:") or (line.startswith("data:") and ("delet" in line or "restor" in line or len(line) < 400)):
                    print(f"{time.strftime('%T')} {line[:300]}", flush=True)
                elif line.startswith("data:"):
                    print(f"{time.strftime('%T')} data: <{len(line)} chars> {line[5:120]}", flush=True)
    except Exception as e:  # noqa
        print("stream ended:", repr(e)[:200], flush=True)


t = threading.Thread(target=run, daemon=True)
t.start()
t.join(secs + 6)
