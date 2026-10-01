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

"""User administration: the users page, the audit page, and same-origin JSON actions. The server enforces
the admin role on every call; the console also hides these pages from non-admins."""
from __future__ import annotations

import json
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(prefix="/admin", include_in_schema=False)


def _forbidden(request: Request):
    return render(request, "admin/forbidden.html", status_code=403)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _status(request: Request) -> dict:
    try:
        return await request.app.state.backend.admin("GET", "/status", ident(request))
    except BackendError:
        return {}


@router.get("/users")
async def users(request: Request, q: str = ""):
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    backend = request.app.state.backend
    try:
        rows = await backend.admin("GET", "/users", me, q=q)
        roles = sorted(await backend.admin("GET", "/roles", me))
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/users.html", users=rows, roles=roles, q=q, status=await _status(request))


@router.get("/roles")
async def roles(request: Request):
    """Every role and what it allows; administrators add their own (built-in roles come from configuration and packs)."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    backend = request.app.state.backend
    try:
        rows = await backend.admin("GET", "/role-definitions", me)
        packs = await backend.packs(me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    kinds = sorted({k for p in packs for k in (p.get("kinds") or [])})
    return render(request, "admin/roles.html", roles=rows, kinds=kinds, packs=packs)


@router.post("/api/roles/{name}")
async def save_role(request: Request, name: str):
    body = json.loads(await request.body() or b"{}")
    try:
        return await request.app.state.backend.admin("PUT", f"/role-definitions/{quote(name)}", ident(request), body)
    except BackendError as e:
        return _problem(e)


@router.post("/api/roles/{name}/delete")
async def delete_role(request: Request, name: str):
    try:
        await request.app.state.backend.admin("DELETE", f"/role-definitions/{quote(name)}", ident(request))
    except BackendError as e:
        return _problem(e)
    return {"ok": True}


@router.get("/audit")
async def audit(request: Request, subject: str = "", limit: int = 200):
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        events = await request.app.state.backend.admin("GET", "/audit", me, limit=limit, subject=subject)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/audit.html", events=events, subject=subject)


@router.get("/health")
async def health(request: Request, partial: int = 0):
    """Connector and pack health (admins): the page, or only its body for the page's own refresh."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        data = await request.app.state.backend.admin("GET", "/health", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/_health_body.html" if partial else "admin/health.html", h=data)


@router.get("/caches")
async def caches(request: Request):
    """Every cache (the engine's and each connector's), with a purge for any of them or all."""
    me = ident(request)
    if not me.is_admin:
        return _forbidden(request)
    try:
        rows = await request.app.state.backend.admin("GET", "/caches", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.status, error=e)
    return render(request, "admin/caches.html", caches=rows)


@router.post("/api/caches/{name}/purge")
async def purge(request: Request, name: str):
    try:
        return await request.app.state.backend.admin("POST", f"/caches/{quote(name)}/purge", ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/users")
async def create(request: Request):
    body = json.loads(await request.body() or b"{}")
    try:
        return JSONResponse(await request.app.state.backend.admin("POST", "/users", ident(request), body), status_code=201)
    except BackendError as e:
        return _problem(e)


@router.post("/api/users/{username}/{action}")
async def act(request: Request, username: str, action: str):
    """``update`` (profile), ``enabled``, ``password`` (reset) or ``delete``."""
    body = json.loads(await request.body() or b"{}")
    me, backend, u = ident(request), request.app.state.backend, quote(username)
    try:
        if action == "update":
            return await backend.admin("PUT", f"/users/{u}", me, body)
        if action == "enabled":
            return await backend.admin("POST", f"/users/{u}/enabled", me, {"enabled": bool(body.get("enabled"))})
        if action == "password":
            return await backend.admin("POST", f"/users/{u}/password", me, {"password": body.get("password", "")})
        if action == "delete":
            await backend.admin("DELETE", f"/users/{u}", me)
            return {"ok": True}
    except BackendError as e:
        return _problem(e)
    return JSONResponse({"code": "DRS-5001", "detail": "unknown action"}, status_code=400)
