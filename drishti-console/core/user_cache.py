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
"""What the console keeps of one person's answers, and under which key (docs/admin/USER_MANAGEMENT.md section 6, "What the console keeps of each person"): a figure read as a
person may only be served to that same person, on the same server, with the same roles, the same as-of date and known-at, and the same
lens. :func:`user_key` (and :func:`cache_key` for a request) is the one key every per-person cache uses; :class:`LRU` is the one bounded
store (the oldest entry goes, never everyone's at once)."""
from __future__ import annotations

import hashlib
import time
from collections import OrderedDict
from typing import Any

from core import asof, servers


def _roles(me) -> tuple:
    return tuple(sorted(str(r) for r in (getattr(me, "roles", None) or ())))


def user_key(me, edition=None, *parts) -> tuple:
    """The key of something read as ``me``: the server of this request, the user, the sorted roles, the as-of headers (a picked date and
    its known-at), the edition or lens it was seen through, then ``parts``."""
    lens = (str(getattr(edition, "name", "") or ""), bool(getattr(edition, "lens", False))) if edition is not None else ("", False)
    return (servers.current(), str(getattr(me, "user", "") or ""), _roles(me), tuple(sorted(asof.headers().items())), lens) + tuple(parts)


def cache_key(request, *parts) -> tuple:
    """:func:`user_key` for a request: its signed-in person and the edition (or lens) the request is seen through."""
    me = getattr(request.state, "identity", None)
    return user_key(me, getattr(request.state, "edition", None), *parts)


def fingerprint(items) -> str:
    """A short, stable digest of a set of names (a person's packs): two people with the same packs share an entry, others never do."""
    h = hashlib.sha256("\x1f".join(sorted(str(i) for i in items or ())).encode()).hexdigest()
    return h[:16]


class LRU:
    """A bounded store with an optional time to live: a new entry past ``maxsize`` drops the least recently used one."""

    def __init__(self, maxsize: int = 256, ttl: float | None = None):
        self.maxsize = max(1, int(maxsize))
        self.ttl = ttl
        self._data: OrderedDict[Any, tuple[float, Any]] = OrderedDict()

    def get(self, key, default=None):
        hit = self._data.get(key)
        if hit is None:
            return default
        if self.ttl is not None and time.monotonic() - hit[0] >= self.ttl:
            self._data.pop(key, None)
            return default
        self._data.move_to_end(key)
        return hit[1]

    def put(self, key, value):
        self._data[key] = (time.monotonic(), value)
        self._data.move_to_end(key)
        while len(self._data) > self.maxsize:
            self._data.popitem(last=False)
        return value

    def pop(self, key, default=None):
        hit = self._data.pop(key, None)
        return default if hit is None else hit[1]

    def clear(self) -> None:
        self._data.clear()

    def __contains__(self, key) -> bool:
        return self.get(key, _MISSING) is not _MISSING

    def values(self) -> list:
        return [v for _, v in self._data.values()]

    def __len__(self) -> int:
        return len(self._data)


_MISSING = object()
