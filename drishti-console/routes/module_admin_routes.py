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
"""Admin -> Modules (mock-up A, table + detail) and Admin -> Modules -> Deploy (mock-up C, placement | plan) over the server's one deployment
API (docs/architecture/KERNEL_AND_MODULES.md section 7.6; the CLI is `drishti.py module ...`), and the page a removed module's paths show
(mock-up Module removed A, section 12.10): 410 with the kernel's notice, or 404 when it is switched off."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import BodyError, limited_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

_ACTIONS = ("install", "upgrade", "remove", "disable", "enable")


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _admin(request: Request):
    me = ident(request)
    return me if me is not None and me.is_admin else None


async def _overview(request: Request, me) -> tuple[dict, str | None]:
    try:
        return await request.app.state.backend.admin("GET", "/modules/deployments", me), None
    except BackendError as e:
        return {}, e.detail


@router.get("/admin/modules")
async def modules_page(request: Request, module: str = ""):
    """The modules of the cluster (mock-up A): one row each (version, state, the nodes it is placed on, the store's versions), the selected
    one's detail beside it (its names, checks, actions), the plans waiting for a second administrator pinned on top, the deployments below."""
    me = _admin(request)
    if me is None:
        return render(request, "admin/forbidden.html", status_code=403)
    data, error = await _overview(request, me)
    mods = data.get("modules") or []
    chosen = next((m for m in mods if m.get("id") == module), mods[0] if mods else None)
    return render(request, "admin/modules.html", data=data, error=error, chosen=chosen, me=me)


@router.get("/admin/modules/deploy")
async def deploy_page(request: Request, module: str = "", version: str = "", action: str = "install", plan: str = ""):
    """Deploy (mock-up C): the package and where it is placed on the left, the plan per node on the right; propose, then a second
    administrator applies with progress per node."""
    me = _admin(request)
    if me is None:
        return render(request, "admin/forbidden.html", status_code=403)
    data, error = await _overview(request, me)
    b = request.app.state.backend
    deployment = None
    if plan:
        try:
            deployment = await b.admin("GET", f"/modules/deployments/{quote(plan, safe='')}", me)
            module, version, action = deployment.get("module") or module, deployment.get("version") or version, deployment.get("action") or action
        except BackendError as e:
            error = e.detail
    mods = data.get("modules") or []
    chosen = next((m for m in mods if m.get("id") == module), None)
    stored = next((v for v in (chosen or {}).get("versions") or [] if not version or v.get("version") == version), None)
    return render(request, "admin/module_deploy.html", data=data, error=error, chosen=chosen, stored=stored, deployment=deployment,
                  action=action if action in _ACTIONS else "install", me=me)


@router.post("/admin/modules/api/upload")
async def upload(request: Request):
    """A .dmod's bytes to the module store: the server checks it once (the fifteen checks) and keeps it by id and version."""
    me = _admin(request)
    if me is None:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    try:
        data = await limited_body(request, request.app.state.pack_upload_limit, "the .dmod")
        if not data:
            raise BodyError(400, "choose a .dmod file")
        return await request.app.state.backend.admin_upload("/modules/store", me, data, {}, timeout=600.0)
    except BodyError as e:
        return JSONResponse({"code": "DRS-5001", "detail": str(e)}, status_code=e.status)
    except BackendError as e:
        return _problem(e)


@router.post("/admin/modules/api/{action}")
async def act(request: Request, action: str):
    """``plan`` {action, module, version, reason} · ``apply`` / ``reject`` {id, comment} · ``withdraw`` {id}: the server decides (four eyes,
    a stale plan refused with DRS-1031, audited)."""
    me = _admin(request)
    if me is None:
        return JSONResponse({"code": "DRS-5002", "detail": "administrators only"}, status_code=403)
    try:
        body = await request.json()
    except ValueError:
        return JSONResponse({"code": "DRS-5001", "detail": "a JSON body"}, status_code=400)
    body = body if isinstance(body, dict) else {}
    pid = quote(str(body.get("id") or ""), safe="")
    b = request.app.state.backend
    try:
        if action == "plan":
            return await b.admin("POST", "/modules/deployments/plans", me, {
                "action": str(body.get("action") or ""), "module": str(body.get("module") or ""), "version": str(body.get("version") or ""),
                "reason": str(body.get("reason") or "")[:500]})
        if action in ("apply", "reject"):
            return await b.admin("POST", f"/modules/deployments/{pid}/{action}", me, {"comment": str(body.get("comment") or "")[:500]},
                                 timeout=600.0)
        if action == "withdraw":
            return await b.admin("POST", f"/modules/deployments/{pid}/withdraw", me, {})
    except BackendError as e:
        return _problem(e)
    return JSONResponse({"code": "DRS-5001", "detail": "unknown action"}, status_code=400)


@router.get("/m/{module}/{rest:path}")
async def module_page(request: Request, module: str, rest: str):
    """A module's console path: while the module's pages are not served here, the route table's answer as the kernel's notice page (410 when
    the module was removed, with who and when for administrators; 404 when it is off or unknown). Saved work that used it stays."""
    me = ident(request)
    path = f"/m/{module}/{rest}"
    try:
        r = await request.app.state.backend._send("GET", "/modules/route", me, params={"path": path})
    except BackendError:
        r = {"status": 404}
    status = int(r.get("status") or 404)
    if status == 200:
        status = 404                                                 # the console part is not served by this console (yet): not here
    resp = render(request, "module_removed.html", route=r, path=path, me=me, status_code=status if status in (404, 410) else 404)
    resp.headers["Cache-Control"] = "no-store"
    return resp


async def needs(request: Request, items: list[dict]) -> dict:
    """What each saved item needs that is off or removed ({id: [{module, title, state, words}]}): one call for a list; empty when the
    server cannot say (an older server), so a list never breaks."""
    if not items:
        return {}
    try:
        r = await request.app.state.backend._send("POST", "/modules/needs", ident(request), json={"items": items[:500]})
        return r.get("needs") or {}
    except BackendError:
        return {}
