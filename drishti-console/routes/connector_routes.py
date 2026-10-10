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

"""Admin → Connectors: the site's connectors, one YAML file each, created, tested, changed, switched off and deleted from the console.

The server keeps the files, validates them, applies a change to the running connector and audits it. This page lists the connectors and
carries the administrator's requests as same-origin JSON: a connector's version travels as ``If-Match`` (the server's ETag) so that two
people cannot overwrite each other, and ``confirm=true`` is sent only after the administrator has seen which packs a change affects."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(prefix="/admin/connectors", include_in_schema=False)

# what a draft may carry; anything else in the body is dropped, not passed on
_DRAFT = ("text", "plugin", "enabled", "kinds", "description", "settings")


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _draft(body: dict) -> dict:
    return {k: v for k, v in body.items() if k in _DRAFT}


def _match(request: Request) -> dict:
    tag = request.headers.get("if-match")
    return {"If-Match": tag} if tag else {}


def _confirm(request: Request) -> dict:
    return {"confirm": "true"} if request.query_params.get("confirm") == "true" else {}


async def _server(request: Request, method: str, path: str, body: dict | None = None, **kw):
    return await request.app.state.backend.admin(method, "/connectors" + path, ident(request), body, **kw)


@router.get("")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        listing = await _server(request, "GET", "")
        plugins = await _server(request, "GET", "/plugins")
        status = await request.app.state.backend.admin("GET", "/status", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    return render(request, "admin/connectors.html", connectors=listing.get("connectors", []), listing=listing, plugins=plugins.get("plugins", []), status=status)


@router.get("/api/list")
async def api_list(request: Request):
    try:
        return await _server(request, "GET", "")
    except BackendError as e:
        return _problem(e)


@router.get("/api/plugins")
async def api_plugins(request: Request):
    try:
        return await _server(request, "GET", "/plugins")
    except BackendError as e:
        return _problem(e)


@router.get("/api/{name}")
async def api_get(request: Request, name: str):
    try:
        return await _server(request, "GET", f"/{quote(name)}")
    except BackendError as e:
        return _problem(e)


@router.get("/api/{name}/suggestion")
async def api_suggestion(request: Request, name: str):
    try:
        return await _server(request, "GET", f"/{quote(name)}/suggestion")
    except BackendError as e:
        return _problem(e)


@router.put("/api/{name}")
async def api_put(request: Request, name: str):
    body = await json_body(request)
    try:
        return await _server(request, "PUT", f"/{quote(name)}", _draft(body), headers=_match(request), timeout=60.0, **_confirm(request))
    except BackendError as e:
        return _problem(e)


@router.delete("/api/{name}")
async def api_delete(request: Request, name: str):
    try:
        return await _server(request, "DELETE", f"/{quote(name)}", headers=_match(request), timeout=60.0, **_confirm(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/test")
async def api_test(request: Request, name: str):
    """Tries a draft (or the saved file when no body is sent) on the real source: a throwaway instance, nothing running changes."""
    body = await json_body(request)
    try:
        return await _server(request, "POST", f"/{quote(name)}/test", _draft(body) if body else None, timeout=90.0)
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/validate")
async def api_validate(request: Request, name: str):
    body = await json_body(request)
    try:
        return await _server(request, "POST", f"/{quote(name)}/validate", _draft(body))
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/render")
async def api_render(request: Request, name: str):
    """The YAML text of the form's fields: the form and the YAML tab edit one draft."""
    body = await json_body(request)
    try:
        return await _server(request, "POST", f"/{quote(name)}/render", _draft(body))
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/parse")
async def api_parse(request: Request, name: str):
    body = await json_body(request)
    try:
        return await _server(request, "POST", f"/{quote(name)}/parse", {"text": str(body.get("text", ""))})
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/enabled")
async def api_enabled(request: Request, name: str):
    body = await json_body(request)
    try:
        return await _server(request, "POST", f"/{quote(name)}/enabled", {"enabled": bool(body.get("enabled"))}, headers=_match(request), timeout=60.0, **_confirm(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/reset")
async def api_reset(request: Request, name: str):
    try:
        return await _server(request, "POST", f"/{quote(name)}/reset", headers=_match(request), timeout=60.0)
    except BackendError as e:
        return _problem(e)


@router.get("/api/{name}/history/{rev}")
async def api_revision(request: Request, name: str, rev: str):
    try:
        return await _server(request, "GET", f"/{quote(name)}/history/{quote(rev)}")
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/restore/{rev}")
async def api_restore(request: Request, name: str, rev: str):
    try:
        return await _server(request, "POST", f"/{quote(name)}/restore/{quote(rev)}", headers=_match(request), timeout=60.0, **_confirm(request))
    except BackendError as e:
        return _problem(e)
