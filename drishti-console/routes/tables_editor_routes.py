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

"""The editors of tables, virtual tables, semantic models and Python-driven tables (docs/architecture/EDITORS.md sections 1 to 4;
docs/guides/TABLES_GUIDE.md section 11): Build › Tables & models. One page, three panes, drawn by static/js/drishti_editors.js and its
editor scripts; every check, preview, total, "adds up?" and save is the server's (``/api/v1/tables/editor/...``), read as the signed-in
person. The console adds nothing but the page and a same-origin pass-through of the editor API (an allow-list of its paths); nothing is
kept here per person (the view each person chose is the server's preference)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import under_construction
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

NO_STORE = {"Cache-Control": "no-store"}
#: The editor API's paths the page may call, with their methods (anything else is 404 here).
ROUTES = {
    "objects": {"GET"}, "object": {"GET"}, "sources": {"GET"}, "impact": {"GET"}, "history": {"GET"},
    "check": {"POST"}, "statement": {"POST"}, "preview": {"POST"}, "access": {"POST"}, "suggest": {"POST"}, "save": {"POST"},
    "propose": {"POST"}, "prefs": {"GET", "PUT"},
    "python/templates": {"GET"}, "python/notebook": {"POST"}, "python/contract": {"POST"},
}
#: The parameters a GET may pass on.
PARAMS = ("kind", "name", "item")
#: The under-construction registry's item for the Python table's sample run (config/under-construction.yaml).
PYTHON_RUNS = "python-table-runs"
#: The most bytes a definition sent to the editor API may have (a definition is configuration: the layer's own bound is 512 KB).
LIMIT = 600 * 1024


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status, headers=NO_STORE)


@router.get("/build/tables")
async def editors_page(request: Request):
    """The shell: objects with search | the editor with the live YAML below it | the live preview with checks. ``?kind=&name=`` or
    ``?kind=&item=`` opens an object; ``?new=table|model|python`` opens the + New steps."""
    me = getattr(request.state, "bi_me", None) or {}
    if not me.get("enabled"):
        return render(request, "bi/denied.html", status_code=403, screen="bi", bi_page="tables")
    q = request.query_params
    marked = under_construction.of(request).marked(PYTHON_RUNS)
    return render(request, "build/tables.html", screen="tables-editor", kind=q.get("kind", ""), name=q.get("name", ""), item=q.get("item", ""),
                  new=q.get("new", ""), python_runs_marked=marked)


@router.api_route("/build/tables/api/{path:path}", methods=["GET", "POST", "PUT"])
async def editor_api(request: Request, path: str):
    """The editor API as the signed-in person: the server decides what they may see and change, every time."""
    if request.method not in ROUTES.get(path, ()):
        return JSONResponse({"code": "DRS-9003", "detail": f"no editor call {request.method} {path}"}, status_code=404, headers=NO_STORE)
    kw: dict = {"timeout": 60.0}
    if request.method == "GET":
        kw["params"] = {k: v for k, v in request.query_params.items() if k in PARAMS and v}
    else:
        try:
            kw["json"] = await json_body(request, limit=LIMIT)
        except BodyError as e:
            return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status, headers=NO_STORE)
    try:
        got = await request.app.state.backend._send(request.method, "/tables/editor/" + path, ident(request), **kw)
    except BackendError as e:
        return _problem(e)
    return JSONResponse(got, headers=NO_STORE)


@router.get("/build/tables/workspaces")
async def workspaces(request: Request):
    """The workspaces a draft may be saved in (BI's: the person's own first)."""
    try:
        got = await request.app.state.backend.bi("GET", "/workspaces", ident(request))
    except BackendError as e:
        return _problem(e)
    return JSONResponse(got, headers=NO_STORE)
