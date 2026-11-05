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

"""Admin → Data out (/admin/data-out; docs/guides/FLIGHT_GUIDE.md section 9; mock-ups A and B of docs/architecture/mockups/data-out/): the
Arrow Flight service of this server in two views of one screen over one API, the page (A, the default) and the map of clients and nodes
(B), switched by a toggle. Both draw from ``GET /api/v1/admin/data-out``; Stop is ``POST …/streams/{id}/stop``. Administrators only; the
paths are the engine ``flight``'s, so with the engine off everywhere the screen is gone (core/engines.py)."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(prefix="/admin", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/data-out")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        data = await request.app.state.backend.admin("GET", "/data-out", me)
        status = await request.app.state.backend.admin("GET", "/status", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    view = "map" if request.query_params.get("view") == "map" else "page"
    return render(request, "admin/data_out.html", d=data, status=status, view=view, screen="admin")


@router.get("/api/data-out")
async def describe(request: Request):
    try:
        return await request.app.state.backend.admin("GET", "/data-out", ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/data-out/streams/{stream}/stop")
async def stop(request: Request, stream: str):
    try:
        return await request.app.state.backend.admin("POST", f"/data-out/streams/{quote(stream, safe='')}/stop", ident(request))
    except BackendError as e:
        return _problem(e)
