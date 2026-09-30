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
from urllib.parse import parse_qs, quote

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.auth import COOKIE
from core.backend import BackendError
from core.oidc import COOKIE as OIDC_COOKIE
from core.oidc import TTL as OIDC_TTL
from core.oidc import OidcError
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
    r = RedirectResponse("/account?must=1" if profile.get("mustChangePassword") else await _landing(request, profile, target), status_code=303)
    r.set_cookie(COOKIE, auth.session_for(profile), httponly=True, samesite="lax", secure=auth.secure_cookie, max_age=auth.session_ttl)
    return r


@router.get("/auth/oidc/login")
async def oidc_login(request: Request, next: str = "/t"):
    oidc = request.app.state.oidc
    if not oidc.enabled:
        return RedirectResponse("/login", status_code=303)
    try:
        url, sealed = await oidc.start(str(request.base_url), _safe_next(next))
    except (OidcError, httpx.HTTPError) as e:
        return render(request, "auth/login.html", status_code=503, next=_safe_next(next), error=f"Single sign-on is unavailable: {e}")
    r = RedirectResponse(url, status_code=303)
    r.set_cookie(OIDC_COOKIE, sealed, httponly=True, samesite="lax", secure=request.app.state.auth.secure_cookie, max_age=OIDC_TTL,
                 path="/auth/oidc")
    return r


@router.get("/auth/oidc/callback")
async def oidc_callback(request: Request, code: str = "", state: str = "", error: str = "", error_description: str = ""):
    oidc, auth = request.app.state.oidc, request.app.state.auth
    if error:
        return render(request, "auth/login.html", status_code=401, next="/t", error=f"The sign-in provider said: {error_description or error}")
    try:
        id_token, nonce, target = await oidc.finish(str(request.base_url), request.cookies.get(OIDC_COOKIE), code, state)
        profile = await request.app.state.backend.oidc_login(id_token, nonce, auth.service())
    except OidcError as e:
        return render(request, "auth/login.html", status_code=401, next="/t", error=str(e))
    except httpx.HTTPError:
        return render(request, "auth/login.html", status_code=503, next="/t", error="The sign-in provider is unavailable.")
    except BackendError as e:
        return render(request, "auth/login.html", status_code=401 if e.status < 500 else 503, next="/t",
                      error=e.detail if e.status < 500 else f"Sign-in unavailable: {e.detail}")
    r = RedirectResponse(await _landing(request, profile, _safe_next(target)), status_code=303)
    r.delete_cookie(OIDC_COOKIE, path="/auth/oidc")
    r.set_cookie(COOKIE, auth.session_for(profile), httponly=True, samesite="lax", secure=auth.secure_cookie, max_age=auth.session_ttl)
    return r


@router.get("/logout")
async def logout():
    r = RedirectResponse("/", status_code=303)
    r.delete_cookie(COOKIE)
    return r


@router.get("/account")
async def account(request: Request, must: int = 0, saved: int = 0, error: str = ""):
    me = ident(request)
    try:
        profile = await request.app.state.backend.me(me)
    except BackendError:
        profile = {"username": me.user, "displayName": me.display, "desk": me.desk, "roles": list(me.roles)}
    return render(request, "account.html", profile=profile, must=bool(must) or me.must_change, saved=bool(saved), error=error,
                  zones=ZONES)


ZONES = ["America/New_York", "America/Chicago", "America/Toronto", "America/Sao_Paulo", "Europe/London", "Europe/Frankfurt", "Europe/Paris",
         "Europe/Zurich", "Asia/Dubai", "Asia/Kolkata", "Asia/Singapore", "Asia/Hong_Kong", "Asia/Shanghai", "Asia/Tokyo", "Australia/Sydney", "UTC"]


@router.post("/account/settings")
async def save_settings(request: Request):
    """Personal settings from the account page (W21); the server validates every value."""
    me = ident(request)
    form = {k: v[0] for k, v in parse_qs((await request.body()).decode(), keep_blank_values=True).items()}
    changes = {"theme": form.get("theme") or None, "clockZone": form.get("clockZone") or None, "density": form.get("density") or "comfortable",
               "flash": form.get("flash") == "on", "landing": form.get("landing") or "/t"}
    if form.get("searchLimit", "").isdigit():
        changes["searchLimit"] = int(form["searchLimit"])
    try:
        await request.app.state.backend.patch_settings(changes, me)
    except BackendError as e:
        return RedirectResponse(f"/account?error={quote(e.detail)}#settings", status_code=303)
    request.app.state.user_settings.forget(me.user)
    return RedirectResponse("/account?saved=1#settings", status_code=303)


async def _landing(request: Request, profile: dict, target: str) -> str:
    """Where to go after signing in: the page asked for, or the user's chosen landing page when none was."""
    if target != "/t":
        return target
    auth = request.app.state.auth
    who = auth.identity(auth.session_for(profile))
    try:
        return _safe_next((await request.app.state.backend.settings(who)).get("landing") or "/t")
    except Exception:  # noqa: BLE001 - a missing landing page never blocks a sign-in
        return target


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
