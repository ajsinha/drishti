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

"""Sign-in and sign-out. Only active when auth.enabled is true."""
from __future__ import annotations

from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core.auth import COOKIE
from routes.common import render

router = APIRouter(include_in_schema=False)


def _safe_next(target: str | None) -> str:
    return target if target and target.startswith("/") and not target.startswith("//") else "/t"


@router.get("/login")
async def login_form(request: Request, next: str = "/t", error: str | None = None):
    return render(request, "auth/login.html", next=_safe_next(next), error=error)


@router.post("/login")
async def login(request: Request):
    form = parse_qs((await request.body()).decode(), keep_blank_values=True)
    user = (form.get("user") or [""])[0].strip()
    password = (form.get("password") or [""])[0]
    target = _safe_next((form.get("next") or ["/t"])[0])
    auth = request.app.state.auth
    cookie = auth.login(user, password)
    if not cookie:
        return render(request, "auth/login.html", status_code=401, next=target, error="Unknown user or wrong password.")
    r = RedirectResponse(target, status_code=303)
    r.set_cookie(COOKIE, cookie, httponly=True, samesite="lax", secure=auth.secure_cookie, max_age=auth.session_ttl)
    return r


@router.get("/logout")
async def logout():
    r = RedirectResponse("/", status_code=303)
    r.delete_cookie(COOKIE)
    return r
