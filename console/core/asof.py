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

"""The business date the user is looking at. The console only carries it: it keeps the choice in a cookie and
sends it to the server as the X-Drishti-As-Of header on every backend call. The server resolves it (rolling
weekends and holidays back) and its connectors read that date (Delta Lake partitions, dated folders, SQL
parameters). "live" means the current business date, streaming; any picked date is a static snapshot."""
from __future__ import annotations

import contextvars
import re
import time

COOKIE = "drishti_asof"
HEADER = "X-Drishti-As-Of"
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_current: contextvars.ContextVar[str] = contextvars.ContextVar("drishti_asof", default="live")


def clean(value: str | None) -> str:
    """'live' or a yyyy-mm-dd date; anything else is live."""
    v = (value or "").strip()
    return v if _DATE.match(v) else "live"


def current() -> str:
    return _current.get()


def set_current(value: str | None) -> str:
    v = clean(value)
    _current.set(v)
    return v


def headers() -> dict[str, str]:
    """The header for backend calls: nothing when live (the server's default)."""
    v = _current.get()
    return {} if v == "live" else {HEADER: v}


class BusinessDates:
    """The server's business-date answer per selected date, cached briefly (it changes once a day)."""

    def __init__(self, ttl: float = 60.0):
        self.ttl = ttl
        self._cache: dict[str, tuple[float, dict]] = {}

    async def info(self, backend, ident, selected: str) -> dict:
        hit = self._cache.get(selected)
        if hit and time.monotonic() - hit[0] < self.ttl:
            return hit[1]
        try:
            data = await backend.business_date(ident)
        except Exception:  # noqa: BLE001 - the picker degrades to a plain date box; pages still render
            data = {"current": None, "selected": None if selected == "live" else selected, "live": selected == "live",
                    "holidays": [], "earliest": None, "calendar": "", "error": True}
        self._cache[selected] = (time.monotonic(), data)
        return data
