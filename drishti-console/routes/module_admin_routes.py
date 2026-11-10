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

from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from core.csrf import BodyError, json_body, limited_body
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
    body = await json_body(request)                                  # 400/415 problems for a body that is not a JSON object
    pid = quote(str(body.get("id") or ""), safe="")
    b = request.app.state.backend
    try:
        if action == "plan":
            return await b.admin("POST", "/modules/deployments/plans", me, {
                "action": str(body.get("action") or ""), "module": str(body.get("module") or ""), "version": str(body.get("version") or ""),
                "reason": str(body.get("reason") or "")[:500]})
        if action in ("apply", "reject"):
            out = await b.admin("POST", f"/modules/deployments/{pid}/{action}", me, {"comment": str(body.get("comment") or "")[:500]},
                                timeout=600.0)
            if action == "apply":                                    # this console's surfaces follow at once (others within seconds)
                from core import modules as _modules
                await request.app.state.module_parts.refresh(b, _modules.state().loaded, force=True)
            return out
        if action == "withdraw":
            return await b.admin("POST", f"/modules/deployments/{pid}/withdraw", me, {})
    except BackendError as e:
        return _problem(e)
    return JSONResponse({"code": "DRS-5001", "detail": "unknown action"}, status_code=400)


def _hosted(request: Request, module: str):
    """The module's hosted console part when it is on for this request (its part active, the person's answer not saying it is off)."""
    from core import modules as _modules

    p = request.app.state.module_parts.part(module)
    return p if p is not None and _modules.module_on(module) else None


def _gone_status(request: Request, module: str) -> int:
    g = request.app.state.module_parts.gone(module)
    return 410 if g is not None and g.state == "removed" else 404


def _file(root: Path, rest: str, module: str, version: str, request: Request):
    """A static file of a module's part: read-only, the version in its path busts the cache (a year, immutable); 410/404 when it went."""
    f = (root / rest).resolve()
    if root.resolve() not in f.parents or not f.is_file():
        return Response(status_code=404, headers={"Cache-Control": "no-store"})
    from core import edition as _edition
    from core import modules as _modules

    need = _modules.lens_modules(_edition.current_lens())
    if need is not None and module not in need:                     # the person's lens does not use the module (today's asset rule)
        return Response(status_code=404, headers={"Cache-Control": "no-store"})
    return FileResponse(f, headers={"Cache-Control": "public, max-age=31536000, immutable"})


@router.get("/api/modules/hosted")
async def hosted(request: Request):
    """What this console hosts now (the shell's open-tab check, section 12.4): the modules on and the ones off or removed, with titles;
    nothing of any person."""
    s = request.app.state.module_parts.snapshot
    return JSONResponse({"hosted": {p.id: {"version": p.version, "title": p.title} for p in s.parts.values()},
                         "gone": {g.id: {"id": g.id, "title": g.title, "state": g.state} for g in s.gone.values()},
                         "refused": s.refused}, headers={"Cache-Control": "no-store"})


@router.get("/m/{module}/api/{rest:path}")
async def module_api(request: Request, module: str, rest: str):
    """A hosted module's own API for its pages (the ``api('<path>')`` of its templates): ``/m/<id>/api/<path>`` read from the server's
    ``/api/v1/<id>/<path>`` as the person (GET only: a page reads rows, never a layout). Gone with the module: 410 removed, else 404."""
    p = _hosted(request, module)
    if p is None:
        status = _gone_status(request, module)
        return JSONResponse({"code": "DRS-1028" if status == 410 else "DRS-1025", "detail": f"the module '{module}' is not on here"},
                            status_code=status, headers={"Cache-Control": "no-store"})
    try:
        answer = await request.app.state.backend._send("GET", f"/{quote(module, safe='')}/{rest}", ident(request),
                                                       params=dict(request.query_params))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)
    return JSONResponse(answer, headers={"Cache-Control": "no-store"})


@router.get("/m/{module}/static/{version}/{rest:path}")
async def module_static(request: Request, module: str, version: str, rest: str):
    """A hosted module's static file (``console/static/``), at ``/m/<id>/static/<version>/<path>`` (section 9.7)."""
    p = _hosted(request, module)
    if p is None or p.version != version:
        return Response(status_code=_gone_status(request, module) if p is None else 404, headers={"Cache-Control": "no-store"})
    return _file(p.folder / "console" / "static", rest, module, version, request)


@router.get("/m/{module}/elements/{lib}/{version}/{rest:path}")
async def module_elements(request: Request, module: str, lib: str, version: str, rest: str):
    """A hosted module's domain Elements library (``elements/<lib>/``), at ``/m/<id>/elements/<lib>/<version>/<path>`` (section 9.7)."""
    p = _hosted(request, module)
    if p is None or p.version != version:
        return Response(status_code=_gone_status(request, module) if p is None else 404, headers={"Cache-Control": "no-store"})
    return _file(p.folder / "elements" / lib, rest, module, version, request)


@router.get("/m/{module}/{rest:path}")
async def module_page(request: Request, module: str, rest: str):
    """A module's page (sections 9.1 and 9.8): a hosted module's declared route, a composed page rendered from its own templates in its own
    environment or a Sutra route opened as the kernel's page of its word; else the route table's answer as the kernel's notice page
    (mock-up Module removed A: 410 when the module was removed, with who and when for administrators; 404 when it is off or unknown).
    Saved work that used it stays, marked."""
    me = ident(request)
    path = f"/m/{module}/{rest}"
    p = _hosted(request, module)
    if p is not None:
        for route in p.routes:
            params = route.match(rest)
            if params is None:
                continue
            if route.sutra:                                          # tier 1: the kernel draws the Sutra (the word's own page)
                said = " ".join([route.function, *[v for v in params.values() if v]]).strip()
                return RedirectResponse("/go?q=" + quote(f"{said} <GO>"), status_code=302)
            if route.page:                                           # tier 2: the module's template, in its own environment
                from starlette.templating import Jinja2Templates

                env = request.app.state.module_parts.env(p, request.app.state.templates.env)
                return render(request, f"{p.id}:{route.page}", _templates=Jinja2Templates(env=env), route={"id": route.id, "title": route.title},
                              params=params, me=me)
    try:
        r = await request.app.state.backend._send("GET", "/modules/route", me, params={"path": path})
    except BackendError:
        r = {"status": 404}
    status = int(r.get("status") or 404)
    if status == 200:
        status = 404                                                 # no such page of the module here
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
