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

"""Retry with exponential backoff: the console's twin of the server's ``RetryPolicy`` / ``RetryTracker`` / ``RetryBoard``
(drishti-common). Settings are ``retry.*`` with a per-area override ``retry.<area>.*`` (areas: ``ai``, ``mcp``)::

    enabled          true   retrying at all
    initial          5s     wait before the first retry
    factor           2.0    each further wait is this many times the one before
    max              5m     no wait is longer than this
    jitter           0.2    each wait varies by up to this fraction either way
    permanent_after  3      a failure waiting cannot fix (bad settings, refused credentials) seen this many times in a row is permanent:
                            it is retried only every ``max``

Pure Python, no I/O: the owner asks :meth:`RetryBoard.due` on its own timer."""
from __future__ import annotations

import logging
import random
import re
import threading
import time
from dataclasses import dataclass

LOG = logging.getLogger("drishti.retry")

_UNITS = {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0, "d": 86400.0}
_PERMANENT = ("401", "403", "unauthori", "forbidden", "authentication failed", "access denied", "invalid credentials", "bad credentials",
              "invalid api key", "incorrect api key", "is not set", "no provider type", "no mcp transport")


def parse_duration(value) -> float:
    """Seconds from ``500ms``, ``5s``, ``2m``, ``1h`` or a bare number (seconds)."""
    if isinstance(value, (int, float)):
        return float(value)
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)?\s*", str(value).lower())
    if not m:
        raise ValueError(f"not a duration (use 500ms, 5s, 2m, 1h): {value}")
    return float(m.group(1)) * _UNITS[m.group(2) or "s"]


def looks_permanent(message) -> bool:
    """Whether an error is one waiting will not fix: bad settings or refused credentials (a heuristic over the message)."""
    m = str(message or "").lower()
    return any(p in m for p in _PERMANENT)


@dataclass(frozen=True)
class RetryPolicy:
    enabled: bool = True
    initial: float = 5.0
    factor: float = 2.0
    max: float = 300.0
    jitter: float = 0.2
    permanent_after: int = 3

    def __post_init__(self):
        if self.initial <= 0:
            raise ValueError("retry.initial must be a positive duration")
        if self.max < self.initial:
            raise ValueError("retry.max must not be shorter than retry.initial")
        if self.factor < 1:
            raise ValueError("retry.factor must be at least 1")
        if not 0 <= self.jitter <= 1:
            raise ValueError("retry.jitter must be between 0 and 1")
        if self.permanent_after < 1:
            raise ValueError("retry.permanent_after must be at least 1")

    @staticmethod
    def of(settings, area: str | None = None) -> "RetryPolicy":
        """Reads ``retry.*`` then ``retry.<area>.*`` (the area wins) from a ``Settings``-like object with ``get(dotted, default)``."""
        d = RetryPolicy()

        def val(key, default):
            for k in ((f"retry.{area}.{key}",) if area else ()) + (f"retry.{key}",):
                v = settings.get(k, None)
                if v is not None and str(v).strip() != "":
                    return v
            return default

        on = val("enabled", d.enabled)
        return RetryPolicy(str(on).lower() in ("true", "1", "yes", "on") if not isinstance(on, bool) else on,
                           parse_duration(val("initial", d.initial)), float(val("factor", d.factor)), parse_duration(val("max", d.max)),
                           float(val("jitter", d.jitter)), int(val("permanent_after", d.permanent_after)))

    def base_delay(self, attempt: int) -> float:
        """Seconds before retry number ``attempt`` (1 = the first), without jitter: ``initial * factor^(attempt-1)``, never more than ``max``."""
        try:
            return min(self.max, self.initial * self.factor ** max(0, attempt - 1))
        except OverflowError:
            return self.max

    def delay(self, attempt: int, permanent: bool = False, rnd: float = 0.5) -> float:
        base = self.max if permanent else self.base_delay(attempt)
        return max(0.001, min(self.max, base * (1 + (rnd * 2 - 1) * self.jitter)))


@dataclass
class Status:
    attempt: int
    next_at: float | None
    last_error: str
    permanent: bool
    since: float | None

    def next_in(self, now: float | None = None) -> int:
        """Whole seconds until the next retry (rounded up), never negative; -1 when none is planned."""
        if self.next_at is None:
            return -1
        left = self.next_at - (time.time() if now is None else now)
        return 0 if left <= 0 else int(left) + (1 if left % 1 else 0)

    def as_dict(self, now: float | None = None) -> dict:
        return {"attempt": self.attempt, "nextRetryInSeconds": self.next_in(now), "lastError": self.last_error, "permanent": self.permanent,
                "failingSince": self.since}


class RetryTracker:
    """The retry state of one thing that failed. Thread-safe; schedules nothing."""

    def __init__(self, policy: RetryPolicy, clock=time.time, rnd=random.random):
        self.policy, self._clock, self._rnd = policy, clock, rnd
        self._lock = threading.Lock()
        self._failures = self._same = 0
        self._last_hint = self._permanent = False
        self._next_at: float | None = None
        self._since: float | None = None
        self._error = ""

    def failed(self, error: str, permanent_hint: bool = False) -> Status:
        with self._lock:
            now = self._clock()
            if self._failures == 0:
                self._since = now
            self._failures += 1
            self._same = self._same + 1 if permanent_hint and self._last_hint else 1 if permanent_hint else 0
            self._last_hint = permanent_hint
            self._permanent = permanent_hint and self._same >= self.policy.permanent_after
            self._error = error or ""
            self._next_at = now + self.policy.delay(self._failures, self._permanent, self._rnd()) if self.policy.enabled else None
            return self._status()

    def due(self) -> bool:
        with self._lock:
            return self._failures > 0 and self._next_at is not None and self._clock() >= self._next_at

    def retry_now(self) -> None:
        with self._lock:
            if self._failures:
                self._next_at, self._permanent, self._same = self._clock(), False, 0

    def succeeded(self) -> int:
        with self._lock:
            n = self._failures
            self._failures = self._same = 0
            self._last_hint = self._permanent = False
            self._next_at = self._since = None
            self._error = ""
            return n

    def failing(self) -> bool:
        with self._lock:
            return self._failures > 0

    def status(self) -> Status:
        with self._lock:
            return self._status()

    def _status(self) -> Status:
        return Status(self._failures, self._next_at, self._error, self._permanent, self._since)


class RetryBoard:
    """The failing things of one area by name. Logs failure 1, 2, 4, 8, ... and every recovery; ``listener(event, key, detail)`` hears
    ``"failed"`` / ``"recovered"`` (for the audit)."""

    def __init__(self, area: str, policy: RetryPolicy, clock=time.time, listener=None):
        self.area, self.policy, self._clock, self.listener = area, policy, clock, listener
        self._trackers: dict[str, RetryTracker] = {}
        self._lock = threading.Lock()

    def failed(self, key: str, error: str, permanent_hint: bool = False) -> Status:
        with self._lock:
            t = self._trackers.setdefault(key, RetryTracker(self.policy, self._clock))
        s = t.failed(error, permanent_hint)
        if s.attempt & (s.attempt - 1) == 0:
            LOG.warning("%s '%s' failed (%s): %s; %s", self.area, key, "first time" if s.attempt == 1 else f"attempt {s.attempt}", error,
                        "retrying is off" if s.next_at is None else f"{'looks permanent, ' if s.permanent else ''}next try in {s.next_in()}s")
            if self.listener:
                self.listener("failed", key, f"attempt {s.attempt}{' (looks permanent)' if s.permanent else ''}: {error}")
        return s

    def succeeded(self, key: str) -> bool:
        with self._lock:
            t = self._trackers.pop(key, None)
        if t is None:
            return False
        last = t.status().last_error
        n = t.succeeded()
        if not n:
            return False
        LOG.info("%s '%s' recovered after %d failed attempt(s); it was: %s", self.area, key, n, last)
        if self.listener:
            self.listener("recovered", key, f"working after {n} failed attempt(s); it was: {last}")
        return True

    def forget(self, key: str) -> None:
        with self._lock:
            self._trackers.pop(key, None)

    def failing(self, key: str) -> bool:
        return key in self._trackers

    def retry_all_now(self) -> int:
        with self._lock:
            ts = list(self._trackers.values())
        for t in ts:
            t.retry_now()
        return len(ts)

    def due(self) -> list[str]:
        with self._lock:
            items = list(self._trackers.items())
        return [k for k, t in items if t.due()]

    def status(self, key: str) -> Status | None:
        t = self._trackers.get(key)
        return t.status() if t else None

    def statuses(self) -> dict[str, Status]:
        with self._lock:
            return {k: t.status() for k, t in sorted(self._trackers.items())}
