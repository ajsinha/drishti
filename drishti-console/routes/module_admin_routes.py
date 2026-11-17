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
"""Admin -> Modules over the server's module list (``GET /api/v1/admin/modules``: what each module is, whether it is on, the switch), the
module console parts a module brings, and the page a module's path shows when the module is off or unknown (a plain 404). A module is a file
beside the server and a restart: nothing here installs, upgrades or removes one (docs/architecture/KERNEL_AND_MODULES.md)."""
from __future__ import annotations

from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)


def _admin(request: Request):
    me = ident(request)
    return me if me is not None and me.is_admin else None


@router.get("/admin/modules")
async def modules_page(request: Request, module: str = ""):
    """The modules of this installation: one row each (version, state, origin), the selected one's detail beside it."""
    me = _admin(request)
    if me is None:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        data, error = await request.app.state.backend.admin("GET", "/modules", me), None
    except BackendError as e:
        data, error = {}, e.detail
    mods = data.get("modules") or []
    chosen = next((m for m in mods if m.get("id") == module), mods[0] if mods else None)
    saved = await saved_needing_modules(request) if mods and any(m.get("state") != "loaded" or not m.get("on") for m in mods) else []
    return render(request, "admin/modules.html", data=data, error=error, chosen=chosen, saved=saved, me=me)


async def saved_needing_modules(request: Request) -> list[dict]:
    """Saved work (the Build home's kinds) that needs a module that is off or not installed: ``[{ref, name, kind, needs: [{module, words}]}]``;
    empty when the server or a source cannot say, so the page never breaks on it."""
    try:
        from core import build_registry
        reg = build_registry.loaded()
        rows = await build_registry.gather(request, [t for t in reg.types() if t.group != "work"])
        items = [it for v in rows.values() for it in v or []]
        found = await needs(request, [{"id": it.ref, "text": " ".join([it.name, it.kind, it.href, *it.uses, repr(it.extra)])} for it in items])
    except Exception:  # noqa: BLE001 - an unavailable source leaves the section empty
        return []
    return [{"ref": it.ref, "name": it.name, "kind": it.kind, "needs": found[it.ref]} for it in items if found.get(it.ref)]


def _hosted(request: Request, module: str):
    """The module's hosted console part when it is on for this request (its part active, the person's answer not saying it is off)."""
    from core import modules as _modules

    p = request.app.state.module_parts.part(module)
    return p if p is not None and _modules.module_on(module) else None


def _file(root: Path, rest: str, module: str, version: str, request: Request):
    """A static file of a module's part: read-only, the version in its path busts the cache (a year, immutable); 404 when it went."""
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
    """What this console hosts now (the shell's open-tab check, section 12.4): the modules on and the ones off, with titles;
    nothing of any person."""
    s = request.app.state.module_parts.snapshot
    return JSONResponse({"hosted": {p.id: {"version": p.version, "title": p.title} for p in s.parts.values()},
                         "gone": {g.id: {"id": g.id, "title": g.title, "state": g.state} for g in s.gone.values()},
                         "refused": s.refused}, headers={"Cache-Control": "no-store"})


@router.get("/m/{module}/api/{rest:path}")
async def module_api(request: Request, module: str, rest: str):
    """A hosted module's own API for its pages (the ``api('<path>')`` of its templates): ``/m/<id>/api/<path>`` read from the server's
    ``/api/v1/<id>/<path>`` as the person (GET only: a page reads rows, never a layout). Gone with the module: 404."""
    p = _hosted(request, module)
    if p is None:
        return JSONResponse({"code": "DRS-1025", "detail": f"the module '{module}' is not on here"},
                            status_code=404, headers={"Cache-Control": "no-store"})
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
        return Response(status_code=404, headers={"Cache-Control": "no-store"})
    return _file(p.folder / "console" / "static", rest, module, version, request)


@router.get("/m/{module}/elements/{lib}/{version}/{rest:path}")
async def module_elements(request: Request, module: str, lib: str, version: str, rest: str):
    """A hosted module's domain Elements library (``elements/<lib>/``), at ``/m/<id>/elements/<lib>/<version>/<path>`` (section 9.7)."""
    p = _hosted(request, module)
    if p is None or p.version != version:
        return Response(status_code=404, headers={"Cache-Control": "no-store"})
    return _file(p.folder / "elements" / lib, rest, module, version, request)


@router.get("/m/{module}/{rest:path}")
async def module_page(request: Request, module: str, rest: str):
    """A module's page (sections 9.1 and 9.8): a hosted module's declared route, a composed page rendered from its own templates in its own
    environment or a Sutra route opened as the kernel's page of its word; else the route table's answer as the kernel's notice page
    (404: the module is off or unknown). Saved work that used it stays, marked."""
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
    resp = render(request, "module_unavailable.html", route=r, path=path, me=me, status_code=404)
    resp.headers["Cache-Control"] = "no-store"
    return resp


async def needs(request: Request, items: list[dict]) -> dict:
    """What each saved item needs that is off or not installed ({id: [{module, title, state, words}]}): one call for a list; empty when the
    server cannot say (an older server), so a list never breaks."""
    if not items:
        return {}
    try:
        r = await request.app.state.backend._send("POST", "/modules/needs", ident(request), json={"items": items[:500]})
        return r.get("needs") or {}
    except BackendError:
        return {}
