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

"""The command switches' same-origin JSON (docs/admin/USER_MANAGEMENT.md), used by the Functions page (``/func``, routes/functions_routes.py) when an
administrator signs in: the listing, a batch of changes sent with one reason (kept in the audit log), the history, and what turning a command
off stops. The server decides who may change anything (administrators only), writes its database and spreads the change to every server of
the cluster. ``/admin/commands``, the page this used to be, redirects to ``/func``."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident

router = APIRouter(prefix="/admin", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/commands")
async def page(request: Request):
    """Admin → Commands is the Functions page now: an administrator sees the switches there (FUNC)."""
    return RedirectResponse("/func" + (f"?{request.url.query}" if request.url.query else ""), status_code=303)


@router.get("/api/commands")
async def listing(request: Request):
    try:
        return await request.app.state.backend.admin("GET", "/commands", ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/commands")
async def change(request: Request):
    """Body ``{reason, changes: [{scope, name, on}]}``: the batch the page reviewed, applied by the server with the reason."""
    body = await json_body(request)
    changes = [{"scope": str(c.get("scope", "")), "name": str(c.get("name", "")), "on": bool(c.get("on"))}
               for c in (body.get("changes") or []) if isinstance(c, dict)]
    try:
        out = await request.app.state.backend.admin("POST", "/commands", ident(request),
                                                     {"reason": str(body.get("reason") or "")[:500], "changes": changes})
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget_all()                # every user's packs, menus and type-ahead are read again
    return out


@router.get("/api/commands/history")
async def history(request: Request, scope: str = "", name: str = "", limit: int = 100):
    params = {"limit": max(1, min(limit, 500))}
    if scope and name:
        params.update(scope=scope, name=name)
    try:
        return await request.app.state.backend.admin("GET", "/commands/history", ident(request), **params)
    except BackendError as e:
        return _problem(e)


@router.get("/api/commands/{code}/impact")
async def impact(request: Request, code: str):
    try:
        return await request.app.state.backend.admin("GET", f"/commands/{quote(code)}/impact", ident(request))
    except BackendError as e:
        return _problem(e)
