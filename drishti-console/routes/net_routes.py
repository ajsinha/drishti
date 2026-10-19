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

"""Admin → Net (/admin/net): the Drishti*net cluster this console's server belongs to (docs/admin/NET.md). Tabs: Members (every node,
its state, version, tags, latency, packs; add a seed, remove a gone node, drain, leave, rejoin), Capability map, Routing (explain a
request, forwarded traffic), Access (nodes per role, this node's tags and peer-only packs), Files (config/net/ with check, version,
history and diff) and Security (the key's state, refused calls). Administrators only. The server keeps the files, checks them and
audits every change; this page carries the administrator's requests as same-origin JSON, a file's version as ``If-Match``. The page
shows which server the console is on and the console's own server list (failover), never a key."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(prefix="/admin/net", include_in_schema=False)

_ACTIONS = ("drain", "undrain", "leave", "rejoin")


def _problem(e: BackendError) -> JSONResponse:
    body = {"code": e.code, "detail": e.detail}
    if getattr(e, "extra", None) and e.extra.get("problems"):
        body["problems"] = e.extra["problems"]
    return JSONResponse(body, status_code=e.status)


def _admin(request: Request):
    me = ident(request)
    return me if me is not None and me.is_admin else None


def _forbidden() -> JSONResponse:
    return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)


async def _net(request: Request, method: str, path: str, body: dict | None = None, headers: dict | None = None, **params):
    return await request.app.state.backend.net(method, path, ident(request), body, headers, **params)


def _match(request: Request) -> dict:
    tag = request.headers.get("if-match")
    return {"If-Match": tag} if tag is not None else {}


def _console_servers(request: Request) -> dict:
    """The console's own view: the server it is on and the servers it would fall over to (core/cluster.py), when that is on."""
    pool = getattr(request.app.state, "cluster", None)
    return pool.snapshot() if pool is not None else {"enabled": False}


@router.get("")
async def page(request: Request):
    me = _admin(request)
    if me is None:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        members = await _net(request, "GET", "/members")
        files = await _net(request, "GET", "/files")
        status = await request.app.state.backend.admin("GET", "/status", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    return render(request, "admin/net.html", members=members, files=files, status=status, console=_console_servers(request),
                  tab=request.query_params.get("tab", "members"))


@router.get("/api/members")
async def api_members(request: Request):
    if _admin(request) is None:
        return _forbidden()
    try:
        out = await _net(request, "GET", "/members")
    except BackendError as e:
        return _problem(e)
    out["console"] = _console_servers(request)
    return out


@router.post("/api/seeds")
async def api_seed(request: Request):
    if _admin(request) is None:
        return _forbidden()
    body = await json_body(request)
    pick = {k: str(body[k]) for k in ("url", "name") if body.get(k)}
    try:
        return await _net(request, "POST", "/seeds", pick)
    except BackendError as e:
        return _problem(e)


@router.delete("/api/members/{node_id}")
async def api_remove(request: Request, node_id: str):
    if _admin(request) is None:
        return _forbidden()
    try:
        return await _net(request, "DELETE", f"/members/{quote(node_id, safe='')}")
    except BackendError as e:
        return _problem(e)


@router.post("/api/action/{action}")
async def api_action(request: Request, action: str):
    if _admin(request) is None:
        return _forbidden()
    if action not in _ACTIONS:
        return JSONResponse({"code": "DRS-5056", "detail": f"no action {action}: one of {', '.join(_ACTIONS)}"}, status_code=400)
    try:
        return await _net(request, "POST", f"/{action}")
    except BackendError as e:
        return _problem(e)


@router.get("/api/files")
async def api_files(request: Request):
    if _admin(request) is None:
        return _forbidden()
    try:
        return await _net(request, "GET", "/files")
    except BackendError as e:
        return _problem(e)


@router.get("/api/file")
async def api_file(request: Request, id: str):  # noqa: A002 - the server's own parameter name
    if _admin(request) is None:
        return _forbidden()
    try:
        return await _net(request, "GET", "/file", id=id)
    except BackendError as e:
        return _problem(e)


@router.get("/api/file/revision")
async def api_revision(request: Request, id: str, rev: str):  # noqa: A002
    if _admin(request) is None:
        return _forbidden()
    try:
        return await _net(request, "GET", "/file/revision", id=id, rev=rev)
    except BackendError as e:
        return _problem(e)


@router.post("/api/file/check")
async def api_check(request: Request, id: str):  # noqa: A002
    if _admin(request) is None:
        return _forbidden()
    body = await json_body(request)
    try:
        return await _net(request, "POST", "/file/check", {"text": str(body.get("text", ""))}, id=id)
    except BackendError as e:
        return _problem(e)


@router.put("/api/file")
async def api_save(request: Request, id: str):  # noqa: A002
    if _admin(request) is None:
        return _forbidden()
    body = await json_body(request)
    try:
        return await _net(request, "PUT", "/file", {"text": str(body.get("text", ""))}, _match(request), id=id)
    except BackendError as e:
        return _problem(e)


@router.delete("/api/file")
async def api_delete(request: Request, id: str):  # noqa: A002
    if _admin(request) is None:
        return _forbidden()
    try:
        return await _net(request, "DELETE", "/file", None, _match(request), id=id)
    except BackendError as e:
        return _problem(e)
