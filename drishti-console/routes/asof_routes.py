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

"""Choosing the business date: GET /asof?d=2026-09-29 (or d=live) remembers the choice and returns to the page.
Works without JavaScript (the top-bar form submits here); the date is only stored and forwarded, never read."""
from __future__ import annotations

from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core import asof
from core.nextpath import safe_next

router = APIRouter(include_in_schema=False)


async def _refused(request: Request, day: str) -> bool:
    """True when the server would refuse ``day``: after today in its business zone, or before its history window. Asked
    under live (the request's own date may be the bad one); when the server cannot say, the console's own check stands."""
    saved = asof.current()
    asof.set_current("live")
    try:
        info = await request.app.state.business_dates.info(request.app.state.backend, request.state.identity, "live")
    finally:
        asof.set_current(saved)
    try:
        today = datetime.now(ZoneInfo(info.get("zone") or "America/New_York")).date()
    except (KeyError, ValueError):
        today = datetime.now(timezone.utc).date()
    earliest = info.get("earliest")
    return date.fromisoformat(day) > today or bool(earliest and day < earliest)


@router.get("/asof")
async def choose(request: Request, d: str = "live", next: str = "/t", k: str | None = None, ki: str | None = None):
    """Sets the business date (d) and, with a picked date, what was known at a time (k, local time in the business
    zone; blank clears it). Live clears both. A date that is not a real date, is in the future or is older than the
    server keeps is not stored (it would break every page until it expired): the page comes back saying so."""
    target = safe_next(next)
    asked = (d or "").strip()
    live = asked.lower() in ("", "live")
    value = "live" if live else asof.clean(asked)
    if not live and (value == "live" or await _refused(request, value)):
        return RedirectResponse(target + ("&" if "?" in target else "?") + "asofRefused=1", status_code=303)
    r = RedirectResponse(target, status_code=303)
    if value == "live":
        r.delete_cookie(asof.COOKIE, path="/")
        r.delete_cookie(asof.KNOWN_COOKIE, path="/")
        return r
    r.set_cookie(asof.COOKIE, value, path="/", samesite="lax", httponly=False, max_age=12 * 3600)
    if ki is not None:                               # an exact instant, as a shared link carries it
        instant = asof.clean_known(ki)
        if instant:
            r.set_cookie(asof.KNOWN_COOKIE, instant, path="/", samesite="lax", httponly=False, max_age=12 * 3600)
        else:
            r.delete_cookie(asof.KNOWN_COOKIE, path="/")
    elif k is not None:
        # the zone does not depend on the date: ask under the request's own date, which is also the cache key
        info = await request.app.state.business_dates.info(request.app.state.backend, request.state.identity, asof.current())
        instant = asof.to_instant(k, info.get("zone") or "America/New_York")
        if instant:
            r.set_cookie(asof.KNOWN_COOKIE, instant, path="/", samesite="lax", httponly=False, max_age=12 * 3600)
        else:
            r.delete_cookie(asof.KNOWN_COOKIE, path="/")
    return r
