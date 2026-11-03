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

"""The pivot screen (docs/guides/PIVOT_GUIDE.md; mock-up A of docs/architecture/PIVOT_ENGINE.md section 25): ``PIVOT <GO>``,
``PIVOT <table> <GO>`` (a table of the Data view, or ``var``), ``PIVOT @owner/name <GO>`` (a saved view), Views → Pivot and "Pivot" on a
table. The page is a shell that web/static/js/pivot-grid.js and pivot.js fill: the field list and its four zones on the right, the grid with
nested rows opened with + and −, the scan-estimate bar, the provenance popover, a chart of a selection and the Export menu. Every figure is
the server's (``/api/v1/pivot``, asked as the person through the same-origin proxy ``/api/pivot/...``); the console only draws."""
from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "PIVOT"


def pivot_command(words: list[str]) -> str | None:
    """Where ``PIVOT ...`` goes, or None when it is not the command: ``PIVOT @owner/name`` a saved view, ``PIVOT <table> [date]`` a table."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    if len(words) == 1:
        return "/pivot"
    if words[1].startswith("@") and "/" in words[1]:
        return "/pivot?" + urlencode({"view": words[1][1:]})
    q = {"source": words[1]}
    if len(words) > 2:
        q["date"] = words[2]
    return "/pivot?" + urlencode(q)


def redirect_for(typed: str):
    where = pivot_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    from core.busy import problem as busy_problem

    return busy_problem(e, e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/pivot")
async def page(request: Request, source: str = "", view: str = "", date: str = ""):
    """PIVOT: the pivot screen."""
    text = f"{WORD} @{view}" if view else f"{WORD} {source} {date}".strip()
    return render(request, "terminal/pivot.html", screen="pivot", source=source.strip(), view_id=view.strip(), date=date.strip(),
                  command_text=text)


async def _call(request: Request, method: str, path: str, body=None, **params):
    try:
        return JSONResponse(await request.app.state.backend.pivot(method, path, ident(request), body=body, **params),
                            headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


async def _body(request: Request) -> dict:
    try:
        b = await request.json()
    except ValueError:
        b = {}
    return b if isinstance(b, dict) else {}


@router.get("/api/pivot/sources")
async def sources(request: Request):
    return await _call(request, "GET", "/sources")


@router.get("/api/pivot/catalogue")
async def catalogue(request: Request, source: str = "", date: str = ""):
    return await _call(request, "GET", "/catalogue", source=source, date=date)


@router.post("/api/pivot/export")
async def export(request: Request):
    """An export made by the server (xlsx with outline and formulas only where a measure adds up, CSV, Arrow, the definition), passed
    through as a download."""
    try:
        content, headers = await request.app.state.backend.pivot("POST", "/export", ident(request), body=await _body(request), raw=True)
    except BackendError as e:
        return _problem(e)
    keep = {k: v for k, v in headers.items() if k.lower() in ("content-type", "content-disposition")}
    keep["Cache-Control"] = "no-store"
    return Response(content=content, headers=keep)


@router.post("/api/pivot/{action}")
async def post(request: Request, action: str):
    if action not in ("query", "estimate", "expand", "compare", "rows", "inline", "views", "promote"):
        return JSONResponse({"detail": "no such pivot action"}, status_code=404)
    return await _call(request, "POST", "/" + action, await _body(request))


@router.get("/api/pivot/provenance/{pid}")
async def provenance(request: Request, pid: str, cell: str = ""):
    return await _call(request, "GET", f"/provenance/{pid}", cell=cell)


@router.get("/api/pivot/cells")
async def cells(request: Request, ref: str = ""):
    return await _call(request, "GET", "/cells", ref=ref)


@router.get("/api/pivot/views")
async def views(request: Request):
    return await _call(request, "GET", "/views")


@router.get("/api/pivot/views/{owner}/{name}")
async def get_view(request: Request, owner: str, name: str):
    return await _call(request, "GET", f"/views/{owner}/{name}")


@router.delete("/api/pivot/views/{name}")
async def delete_view(request: Request, name: str):
    try:
        await request.app.state.backend.pivot("DELETE", f"/views/{name}", ident(request))
        return Response(status_code=204)
    except BackendError as e:
        return _problem(e)
