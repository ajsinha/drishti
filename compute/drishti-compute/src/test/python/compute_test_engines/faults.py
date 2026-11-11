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

"""A test engine that misbehaves on request (tests only, never shipped): crashes, loops, leaks state between requests, eats memory, opens
sockets, starts processes and writes outside its scratch folder, so the supervisor's limits and the sandbox can be seen to work."""
from __future__ import annotations

import os
import socket
import subprocess
import time

from drishti_compute import ComputeError, Engine, Result, register_cache

# module-level state a request sets; the next request must not see it (fork: a fresh child; warm: cleared as a registered cache)
LEAK = register_cache({"value": None})


class FaultEngine(Engine):
    kinds = ("who", "crash", "loop", "sleep", "leak", "memory", "socket", "subprocess", "write-outside", "boom", "refuse", "progress",
             "rows")
    version = "test"

    def compute(self, req, inputs, progress):
        k = req.kind
        if k == "who":
            return Result(rows=[{"user": req.user, "pid": os.getpid(), "leak": LEAK.get("value"), "scratch": req.scratch,
                                 "cwd": os.getcwd(), "chunk": req.chunk}])
        if k == "crash":
            os._exit(9)
        if k == "loop":
            while True:
                pass
        if k == "sleep":
            end = time.monotonic() + float(req.param("seconds", 1))
            while time.monotonic() < end:
                time.sleep(0.01)
            return Result(rows=[{"slept": req.param("seconds", 1), "user": req.user, "pid": os.getpid()}])
        if k == "leak":
            LEAK["value"] = req.param("value")
            return Result(rows=[{"set": LEAK["value"]}])
        if k == "memory":
            hog = bytearray(int(req.param("bytes", 2 << 30)))
            time.sleep(1.0)
            return Result(rows=[{"held": len(hog)}])
        if k == "socket":
            socket.create_connection(("127.0.0.1", 9), timeout=1)
            return Result(rows=[{"connected": True}])
        if k == "subprocess":
            subprocess.run(["echo", "hi"], check=False)
            return Result(rows=[{"ran": True}])
        if k == "write-outside":
            with open(req.param("path"), "w", encoding="utf-8") as f:
                f.write("escaped")
            return Result(rows=[{"wrote": True}])
        if k == "boom":
            raise ValueError("secret internals that must not reach the caller")
        if k == "refuse":
            raise ComputeError("the scope is empty")
        if k == "progress":
            for i in range(1, 4):
                progress.fraction(i, 3, estimate=i * 10)
            return Result(rows=[{"done": True}])
        if k == "rows":
            n = int(req.param("n", 3))
            return Result(rows=[{"i": i} for i in range(n)])
        raise ComputeError(f"unknown kind {k}")
