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

"""Admin → Commands (/admin/commands, docs/admin/COMMANDS.md): every command of the packs, grouped by pack, with what it does, and who may use
it: today the "Everyone" column (on or off for everyone), with greyed role columns for grants per role later. Changes are batched on the page
(Undo, "Review N changes") and sent with one reason, kept in the audit log; the side panel says what turning a command off stops. The page
carries the administrator's changes to the server as same-origin JSON; the server decides, writes its database and spreads the change to
every server of the cluster."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(prefix="/admin", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/commands")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    backend = request.app.state.backend
    try:
        data = await backend.admin("GET", "/commands", me)
        status = await backend.admin("GET", "/status", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    return render(request, "admin/commands.html", c=data, status=status)


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
