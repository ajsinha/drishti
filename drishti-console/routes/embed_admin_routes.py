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

"""Admin → Embedding: the host applications that may show Drishti views in their own pages (docs/architecture/ELEMENTS.md, step 10).
The server keeps the registry, the counters and the audit trail and decides who may do what; this page lists the applications with
what each did since the server started, and carries the administrator's requests (register, rotate the secret, disable, enable,
delete) as same-origin JSON. A secret is in the answer to the call that made it and nowhere else: it is not cached and not logged."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(prefix="/admin/embedding", include_in_schema=False)

# what a registration may carry; anything else in the body is dropped, not passed on
_DRAFT = ("id", "name", "contact", "origins", "kinds", "scopes", "jwks", "secret", "tokenSeconds", "callsPerMinute", "userCallsPerMinute")


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _once(payload, status: int = 200) -> JSONResponse:
    """An answer that may hold a secret: never stored by a browser or a proxy."""
    return JSONResponse(payload, status_code=status, headers={"Cache-Control": "no-store"})


async def _server(request: Request, method: str, path: str, body: dict | None = None):
    return await request.app.state.backend.admin(method, "/embed" + path, ident(request), body)


@router.get("")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        apps = await _server(request, "GET", "/apps")
        usage = await _server(request, "GET", "/usage")
        status = await request.app.state.backend.admin("GET", "/status", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    use = usage.get("apps", {})
    empty = {"tokensIssued": 0, "calls": 0, "refusals": 0, "refusalsByCode": {}, "streamsOpen": 0, "lastUsedAt": None}
    rows = [{**a, "usage": {**empty, **use.get(a["id"], {})}} for a in apps]
    return render(request, "admin/embedding.html", apps=rows, embed_enabled=bool(usage.get("enabled")), since=usage.get("since"),
                  emb=usage.get("settings", {}), strangers=use.get("(unknown)"), status=status)


@router.post("/api/apps")
async def create(request: Request):
    body = await json_body(request)
    try:
        return _once(await _server(request, "POST", "/apps", {k: v for k, v in body.items() if k in _DRAFT}), 201)
    except BackendError as e:
        return _problem(e)


@router.post("/api/apps/{app_id}/rotate")
async def rotate(request: Request, app_id: str):
    body = await json_body(request)
    grace = body.get("graceSeconds")
    try:
        return _once(await _server(request, "POST", f"/apps/{quote(app_id)}/rotate-secret", {"graceSeconds": grace} if isinstance(grace, int) else {}))
    except BackendError as e:
        return _problem(e)


@router.post("/api/apps/{app_id}/{state}")
async def switch(request: Request, app_id: str, state: str):
    if state not in ("enable", "disable"):
        return JSONResponse({"code": "DRS-5001", "detail": "enable or disable"}, status_code=404)
    try:
        return await _server(request, "POST", f"/apps/{quote(app_id)}/{state}")
    except BackendError as e:
        return _problem(e)


@router.delete("/api/apps/{app_id}")
async def remove(request: Request, app_id: str):
    try:
        await _server(request, "DELETE", f"/apps/{quote(app_id)}")
    except BackendError as e:
        return _problem(e)
    return JSONResponse({"deleted": app_id})
