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
from core.perspective_csp import (CHART_STYLE_HASHES, PERSPECTIVE_BASE, STYLE_HASHES, board_uses_chart, board_uses_perspective, policy as perspective_policy, uses_chart,
                                  uses_perspective)
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

# the server paths the page script may reach: nothing else is passed on
_ALLOWED = ("me", "home", "catalogue", "settings", "workspaces", "items", "datasets", "model", "engine", "reports", "bundle", "boards", "python", "ai", "imports", "mq", "models", "pack-datasets")
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


@router.get("/bi/environments")
async def environments(request: Request):
    """Environments: export a workspace as a bundle, import one (dry run first). Drawn by ``bi-env.js`` (phase 4)."""
    return _page(request, "bi/environments.html", "environments")


@router.get("/bi/datasets")
async def datasets(request: Request):
    return _page(request, "bi/datasets.html", "datasets")


@router.get("/bi/d/{item:path}")
async def dataset(request: Request, item: str):
    """One dataset: its model (tables, relationships, measures, row rules) and a query preview, drawn by ``bi-dataset.js``."""
    return _page(request, "bi/dataset.html", "datasets", item=item)


async def _holds_perspective(request: Request, path: str, test, chart=None) -> tuple[bool, bool]:
    """Whether the report or board at ``path`` holds a Perspective visual, and whether one of them is a chart. Only then is its page given the narrow WebAssembly policy
    (and the chart plugin's two styles, for a chart); a failure to ask is the strict policy (the page then shows the plain table in place of Perspective)."""
    try:
        model = await request.app.state.backend.bi("GET", path, ident(request))
        return bool(test(model)), bool(chart and chart(model))
    except Exception:  # noqa: BLE001 - the page loads either way; the strict policy is the safe answer
        return False, False


def _with_perspective(response, on: bool, chart: bool = False):
    if on:
        from core.app import CSP

        response.headers["Content-Security-Policy"] = perspective_policy(CSP, STYLE_HASHES + CHART_STYLE_HASHES if chart else STYLE_HASHES)
    return response


@router.get("/bi/r/{item}")
@router.get("/bi/r/{item}/{page}")
async def report(request: Request, item: str, page: str = ""):
    """A report, drawn by ``bi-report.js``: its pages are tabs, its visuals ask the server for their data one query at a time (phase 3). One that holds a
    Perspective visual loads the vendored Perspective and has the narrow WebAssembly policy (phase 5); any other report has the strict one."""
    me = getattr(request.state, "bi_me", None) or {}
    psp, chart = (await _holds_perspective(request, f"/reports/{item}", uses_perspective, uses_chart)) if me.get("enabled") else (False, False)
    return _with_perspective(_page(request, "bi/report.html", "report", item=item, report_page=page, perspective=PERSPECTIVE_BASE if psp else ""), psp, chart)


@router.get("/bi/b/{item}")
async def board(request: Request, item: str):
    """A board, drawn by ``bi-board.js``: a wall of live tiles from several reports, page by page; ``?kiosk=1`` (or the button) fills the screen and turns the
    pages by itself (phase 5). The narrow WebAssembly policy applies only when a tile is a Perspective visual."""
    me = getattr(request.state, "bi_me", None) or {}
    psp, chart = (await _holds_perspective(request, f"/boards/{item}", board_uses_perspective, board_uses_chart)) if me.get("enabled") else (False, False)
    return _with_perspective(_page(request, "bi/board.html", "board", item=item, perspective=PERSPECTIVE_BASE if psp else ""), psp, chart)


@router.get("/bi/designer")
async def designer(request: Request):
    """The designer's start: your report drafts, and a new report on a dataset."""
    return _page(request, "bi/designer.html", "designer")


@router.get("/bi/design/board/{draft:path}")
async def design_board(request: Request, draft: str):
    """The board designer on one draft (a board item you may edit): the grid of tiles, pages, kiosk rotation, a threshold alert for each tile, and the YAML."""
    return _page(request, "bi/design_board.html", "designer", item=draft)


@router.get("/bi/design/{draft}")
async def design(request: Request, draft: str):
    """The report designer on one draft (a report item you may edit): the canvas, Fields, Visualizations, Filters, pages, YAML, preview and Check."""
    return _page(request, "bi/design.html", "designer", item=draft)


@router.get("/bi/m/{item:path}")
async def model_view(request: Request, item: str):
    """The Model view of a dataset item: tables as boxes, relationships as lines, the YAML beside them (two views of one draft)."""
    return _page(request, "bi/model.html", "datasets", item=item)


@router.get("/admin/bi")
async def settings_page(request: Request):
    me = getattr(request.state, "bi_me", None) or {}
    if "bi-admin" not in (me.get("powers") or []):
        return render(request, "admin/forbidden.html", status_code=403)
    files = None
    if ident(request).is_admin:                    # the shipped files and their safe uninstall are the system administrator's (Admin → Artifacts)
        try:
            listing = await request.app.state.backend.admin("GET", "/artifacts", ident(request))
            files = [a for a in listing.get("artifacts", []) if a.get("kind") == "bi"]
        except BackendError:
            files = None
    return render(request, "bi/settings.html", screen="admin", bi_files=files)


@router.api_route("/bi/api/{path:path}", methods=["GET", "POST", "PUT", "DELETE"])
async def proxy(request: Request, path: str):
    """One call of the page script, passed on to the server under the caller's identity (the server enforces everything)."""
    head = path.split("/", 1)[0]
    if head not in _ALLOWED or ".." in path:
        return JSONResponse({"code": "DRS-9001", "detail": "no such BI path"}, status_code=404)
    body = None if request.method in _NO_BODY else await json_body(request)
    if request.method == "POST" and head == "items" and path.rsplit("/", 1)[-1] in ("publish", "approve"):
        from routes.python_routes import publish_gate
        refused = await publish_gate(request, path, body)          # a Python item whose own tests fail is not published (a bi-admin may force it)
        if refused is not None:
            return refused
    params = {k: v for k, v in request.query_params.items()}
    if request.method == "POST" and head == "reports" and path.endswith("/query") and params.get("format") == "arrow":
        try:                                                  # a Perspective visual's rows: the server's Arrow bytes, passed through unchanged
            content, headers = await request.app.state.backend.bi_arrow("/" + path, ident(request), body, **params)
        except BackendError as e:
            return _problem(e)
        kept = {k: v for k, v in headers.items() if k.lower() in ("x-bi-rows", "x-bi-masked", "x-bi-live")}
        kept["Cache-Control"] = "no-store"
        return Response(content, media_type="application/vnd.apache.arrow.stream", headers=kept)
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
