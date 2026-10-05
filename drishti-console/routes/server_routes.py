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

"""One console, many servers (ADR-016): choosing the server this browser works with, and the list of servers."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core import servers
from routes.common import render

router = APIRouter()
YEAR = 365 * 24 * 3600


def _safe_next(target: str | None) -> str:
    return target if target and target.startswith("/") and not target.startswith("//") else "/"


def choose(response, request: Request, sid: str) -> None:
    """Remembers the server for this browser (only ids in the catalogue are ever accepted)."""
    response.set_cookie(servers.COOKIE, sid, httponly=True, samesite="lax", secure=request.app.state.auth.secure_cookie, max_age=YEAR)


@router.get("/connect/{sid}")
async def connect(request: Request, sid: str, next: str = "/"):
    """Work with this server from now on (also the way to an unlisted one). Sign-in follows if you have no session there."""
    if request.app.state.servers.get(sid) is None:
        return render(request, "servers.html", status_code=404, error=f"There is no server '{sid}' in this console's list.",
                      rows=await _rows(request))
    r = RedirectResponse(_safe_next(next), status_code=303)
    choose(r, request, sid)
    return r


@router.get("/servers")
async def server_list(request: Request):
    return render(request, "servers.html", rows=await _rows(request), error=None)


async def _rows(request: Request) -> list[dict]:
    catalogue = request.app.state.servers
    states = await catalogue.states()
    auth = request.app.state.auth
    rows = []
    for s in catalogue.listed():
        token = servers.CURRENT.set(s.id)                  # read this server's session cookie with its own Auth
        try:
            signed_in = auth.identity(request.cookies.get(auth.cookie)) is not None if auth.enabled else True
        finally:
            servers.CURRENT.reset(token)
        rows.append({"server": s, "state": states.get(s.id, {}), "current": s.id == servers.current(), "signed_in": signed_in})
    return rows
