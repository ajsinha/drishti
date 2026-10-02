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
from core.servers import scoped

COOKIE = "drishti_asof"
HEADER = "X-Drishti-As-Of"
KNOWN_COOKIE = "drishti_knownat"
KNOWN_HEADER = "X-Drishti-Known-At"
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_INSTANT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?Z$")
_current: contextvars.ContextVar[str] = contextvars.ContextVar("drishti_asof", default="live")
_known: contextvars.ContextVar[str | None] = contextvars.ContextVar("drishti_knownat", default=None)


def clean(value: str | None) -> str:
    """'live' or a real yyyy-mm-dd date that is not yet in the future anywhere; anything else is live. The server has the
    last word (its history window, its business zone): see :func:`is_refusal` and the /asof route."""
    v = (value or "").strip()
    if not _DATE.match(v):
        return "live"
    from datetime import date, datetime, timedelta, timezone

    try:
        day = date.fromisoformat(v)
    except ValueError:                                      # 2026-02-30, 9999-99-99
        return "live"
    return v if day <= datetime.now(timezone.utc).date() + timedelta(days=1) else "live"


def is_refusal(error) -> bool:
    """True when the server refused the business date itself (DRS-4003), rather than failing for another reason."""
    return getattr(error, "code", None) == "DRS-4003"


def current() -> str:
    return _current.get()


def set_current(value: str | None) -> str:
    v = clean(value)
    _current.set(v)
    return v


def clean_known(value: str | None) -> str | None:
    """A UTC instant (2026-09-29T14:30:00Z) or None."""
    v = (value or "").strip()
    return v if _INSTANT.match(v) else None


def known_at() -> str | None:
    return _known.get()


def set_known(value: str | None) -> str | None:
    v = clean_known(value)
    _known.set(v)
    return v


def to_instant(local: str | None, zone: str) -> str | None:
    """A datetime-local value (2026-09-29T10:30) read in the business zone, as a UTC instant; None when blank or bad."""
    from datetime import datetime, timezone
    from zoneinfo import ZoneInfo

    try:
        t = datetime.fromisoformat((local or "").strip()).replace(tzinfo=ZoneInfo(zone or "America/New_York"))
    except (ValueError, KeyError):
        return None
    return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def to_local(instant: str | None, zone: str) -> str:
    """The datetime-local value for a UTC instant in the business zone ('' when none)."""
    from datetime import datetime
    from zoneinfo import ZoneInfo

    if not instant:
        return ""
    try:
        t = datetime.fromisoformat(instant.replace("Z", "+00:00")).astimezone(ZoneInfo(zone or "America/New_York"))
    except (ValueError, KeyError):
        return ""
    return t.strftime("%Y-%m-%dT%H:%M")


def headers() -> dict[str, str]:
    """Headers for backend calls: none when live (the server's default); a picked date, and a "known at" if set."""
    v = _current.get()
    out = {} if v == "live" else {HEADER: v}
    k = _known.get()
    if k and v != "live":
        out[KNOWN_HEADER] = k
    return out


class BusinessDates:
    """The server's business-date answer per selected date, cached briefly (it changes once a day)."""

    def __init__(self, ttl: float = 60.0):
        self.ttl = ttl
        self._cache: dict[str, tuple[float, dict]] = {}

    async def info(self, backend, ident, selected: str) -> dict:
        hit = self._cache.get(scoped(selected))
        if hit and time.monotonic() - hit[0] < self.ttl:
            return hit[1]
        try:
            data = await backend.business_date(ident)
        except Exception as e:  # noqa: BLE001 - the picker degrades to a plain date box; pages still render
            data = {"current": None, "selected": None if selected == "live" else selected, "live": selected == "live",
                    "holidays": [], "earliest": None, "calendar": "", "error": True,
                    "refused": selected != "live" and is_refusal(e)}   # the date itself: the page falls back to live
        self._cache[scoped(selected)] = (time.monotonic(), data)
        return data
