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

"""Sign-in, sign-out and the account page (profile and password change)."""
from __future__ import annotations

import json
from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.auth import COOKIE
from core.backend import BackendError
from routes.common import ident, render

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
    try:
        profile = await request.app.state.backend.login(user, password, auth.service())
    except BackendError as e:
        msg = {"DRS-6005": "This account is locked after repeated failures. Try again later or ask an administrator."}.get(
            e.code, "Unknown user or wrong password." if e.status in (401, 404) else f"Sign-in unavailable: {e.detail}")
        return render(request, "auth/login.html", status_code=401 if e.status < 500 else 503, next=target, error=msg)
    r = RedirectResponse("/account?must=1" if profile.get("mustChangePassword") else target, status_code=303)
    r.set_cookie(COOKIE, auth.session_for(profile), httponly=True, samesite="lax", secure=auth.secure_cookie, max_age=auth.session_ttl)
    return r


@router.get("/logout")
async def logout():
    r = RedirectResponse("/", status_code=303)
    r.delete_cookie(COOKIE)
    return r


@router.get("/account")
async def account(request: Request, must: int = 0):
    me = ident(request)
    try:
        profile = await request.app.state.backend.me(me)
    except BackendError:
        profile = {"username": me.user, "displayName": me.display, "desk": me.desk, "roles": list(me.roles)}
    return render(request, "account.html", profile=profile, must=bool(must) or me.must_change)


@router.post("/account/password")
async def change_password(request: Request):
    body = json.loads(await request.body() or b"{}")
    me = ident(request)
    try:
        profile = await request.app.state.backend.change_password(body.get("current", ""), body.get("next", ""), me)
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    r = JSONResponse({"ok": True})
    if request.app.state.auth.enabled:
        r.set_cookie(COOKIE, request.app.state.auth.session_for(profile), httponly=True, samesite="lax",
                     secure=request.app.state.auth.secure_cookie, max_age=request.app.state.auth.session_ttl)
    return r
