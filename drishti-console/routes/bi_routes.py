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

"""Rupaka phase 1 (docs/architecture/RUPAKA.md section 5): the BI pages (home, catalogue, workspaces, an item) and the same-origin JSON the page script uses.

The pages are a shell: they carry no data. The script (``static/js/bi.js``) asks ``/bi/api/...``, which passes each call on to the server's ``/api/v1/bi/...``
with the signed-in user's identity; the server decides what that person may see and do and answers with its problem code when not. The console decides
nothing about access except what to show (the BI menu needs the ``bi`` power)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

# the server paths the page script may reach: nothing else is passed on
_ALLOWED = ("me", "home", "catalogue", "settings", "workspaces", "items", "datasets", "model", "engine")
_NO_BODY = {"GET", "DELETE"}


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _page(request: Request, template: str, page: str, **context):
    """A BI page, or the refusal that explains what is missing."""
    me = getattr(request.state, "bi_me", None) or {}
    if not me.get("enabled"):
        return render(request, "bi/denied.html", status_code=403, screen="bi", bi_page=page)
    return render(request, template, screen="bi", bi_page=page, **context)


@router.get("/bi")
async def home(request: Request):
    return _page(request, "bi/home.html", "home")


@router.get("/bi/catalogue")
async def catalogue(request: Request):
    return _page(request, "bi/catalogue.html", "catalogue")


@router.get("/bi/workspaces")
async def workspaces(request: Request):
    return _page(request, "bi/workspaces.html", "workspaces")


@router.get("/bi/w/{workspace}")
async def workspace(request: Request, workspace: str):
    return _page(request, "bi/workspace.html", "workspace", workspace=workspace)


@router.get("/bi/i/{item:path}")
async def item(request: Request, item: str):
    return _page(request, "bi/item.html", "item", item=item)


@router.get("/bi/datasets")
async def datasets(request: Request):
    return _page(request, "bi/datasets.html", "datasets")


@router.get("/bi/d/{item:path}")
async def dataset(request: Request, item: str):
    """One dataset: its model (tables, relationships, measures, row rules) and a query preview, drawn by ``bi-dataset.js``."""
    return _page(request, "bi/dataset.html", "datasets", item=item)


@router.get("/bi/designer")
async def designer(request: Request):
    return _page(request, "bi/soon.html", "designer", title="Designer", phase="phase 3",
                 what="The report designer (fields, visuals, filters, preview with data) arrives with the reports engine. "
                      "Today a report item holds its YAML body, versioned and published, but is not rendered.")


@router.get("/bi/explore")
async def explore(request: Request):
    return _page(request, "bi/soon.html", "explore", title="Explore", phase="phase 9",
                 what="Free exploration of a dataset, and the analyst workbench, are not built yet.")


@router.get("/admin/bi")
async def settings_page(request: Request):
    me = getattr(request.state, "bi_me", None) or {}
    if "bi-admin" not in (me.get("powers") or []):
        return render(request, "admin/forbidden.html", status_code=403)
    return render(request, "bi/settings.html", screen="admin")


@router.api_route("/bi/api/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy(request: Request, path: str):
    """One call of the page script, passed on to the server under the caller's identity (the server enforces everything)."""
    head = path.split("/", 1)[0]
    if head not in _ALLOWED or ".." in path:
        return JSONResponse({"code": "DRS-9001", "detail": "no such BI path"}, status_code=404)
    body = None if request.method in _NO_BODY else await json_body(request)
    params = {k: v for k, v in request.query_params.items()}
    try:
        answer = await request.app.state.backend.bi(request.method, "/" + path, ident(request), body, **params)
    except BackendError as e:
        return _problem(e)
    if request.method != "GET":
        request.app.state.bi_me.forget(ident(request).user)         # a change may alter Recent or the powers shown in the menu
    return Response(status_code=204) if answer is None else JSONResponse(answer, headers={"Cache-Control": "no-store"})


@router.put("/bi/api-roles/{name}")
async def role_powers(request: Request, name: str):
    """Admin, BI settings: set the BI powers of a role the administrator defines (the server's role-definition API; administrators only)."""
    body = await json_body(request)
    powers = body.get("bi")
    if not isinstance(powers, list):
        return JSONResponse({"code": "DRS-9003", "detail": "bi is a list of powers"}, status_code=422)
    backend, me = request.app.state.backend, ident(request)
    try:
        role = await backend.admin("GET", f"/role-definitions/{name}", me)
        keep = {k: role.get(k) for k in ("description", "kinds", "raw", "author", "approve", "admin", "calc", "layout", "collaborate", "compliance")}
        saved = await backend.admin("PUT", f"/role-definitions/{name}", me, {**keep, "bi": [str(p) for p in powers]})
    except BackendError as e:
        return _problem(e)
    request.app.state.bi_me.forget(me.user)
    return JSONResponse({"name": saved["name"], "bi": sorted(saved.get("bi") or [])})
