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

"""Console-side limits for embedded calls (docs/architecture/ELEMENTS.md, section 6.8; build step 7).

* ``embed.rate_per_minute``: calls per host application per minute (a sliding window); over it ``429 DRS-8004`` with ``Retry-After``.
  The server has its own rates (``drishti.embed.limits``); this one protects the console's own CPU before the server is asked.
* ``embed.max_streams``: upstream streams (live subscriptions) all embed channels together may hold open, so embedding cannot take
  the connection pool (``backend.pool_size``) from the console's own users. Beyond it a channel open is ``429 DRS-8004`` and a
  subscription added to an open channel is answered with ``gone`` ``DRS-5003`` (the channel's own limit message).
"""
from __future__ import annotations

import threading
import time
from collections import deque

WINDOW = 60.0


class EmbedLimits:
    """Per-application call rates and the global stream budget; safe to call from several threads."""

    def __init__(self, cfg: dict, clock=time.monotonic):
        self.rate = int(cfg.get("rate_per_minute", 600))
        self.max_streams = int(cfg.get("max_streams", 64))
        self._clock = clock
        self._calls: dict[str, deque] = {}
        self._lock = threading.Lock()

    def take(self, app: str) -> None:
        """Counts one call of ``app``; raises ``EmbedError`` 429 DRS-8004 over the rate. 0 means unlimited."""
        if self.rate <= 0:
            return
        now = self._clock()
        with self._lock:
            q = self._calls.setdefault(app, deque())
            while q and now - q[0] > WINDOW:
                q.popleft()
            if len(q) >= self.rate:
                wait = max(1, int(WINDOW - (now - q[0])) + 1)
                from core.embed import EmbedError

                raise EmbedError(429, "DRS-8004", f"host application {app} is over {self.rate} embed calls a minute (embed.rate_per_minute)", str(wait))
            q.append(now)

    def room(self, guards: dict) -> int:
        """Upstream streams still available, given the open embed channels."""
        used = sum(len(g.session.tasks) for g in list(guards.values()))
        return max(0, self.max_streams - used) if self.max_streams > 0 else 10 ** 9
