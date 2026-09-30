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

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core import asof

router = APIRouter(include_in_schema=False)


@router.get("/asof")
async def choose(request: Request, d: str = "live", next: str = "/t"):
    value = asof.clean(d)
    target = next if next.startswith("/") and not next.startswith("//") else "/t"
    r = RedirectResponse(target, status_code=303)
    if value == "live":
        r.delete_cookie(asof.COOKIE, path="/")
    else:
        r.set_cookie(asof.COOKIE, value, path="/", samesite="lax", httponly=False, max_age=12 * 3600)
    return r
