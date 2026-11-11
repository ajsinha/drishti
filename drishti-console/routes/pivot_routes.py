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

"""The pivot screen (docs/guides/BI_GUIDE.md; mock-up A of HOW_IT_FITS.md section A26): ``PIVOT <GO>``,
``PIVOT <table> <GO>`` (a table of Export data, or ``var``), ``PIVOT @owner/name <GO>`` (a saved view), Views → Pivot and "Pivot" on a
table; ``/pivot?preset=<name>`` is a preset (config/pivot-presets.yaml: the MR cube, ``MCUBE``, and the CCR cube), the same screen with a source,
a lens and a landing, never a grid of its own. The page is a shell that web/static/js/drishti_pivot-grid.js and drishti_pivot.js fill: the field list and its four zones on the right, the grid with
nested rows opened with + and −, the scan-estimate bar, the provenance popover, a chart of a selection and the Export menu. Every figure is
the server's (``/api/v1/pivot``, asked as the person through the same-origin proxy ``/api/pivot/...``); the console only draws."""
from __future__ import annotations

import json
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response, StreamingResponse

from core import pivot_presets, under_construction
from core.backend import BackendError
from core.csrf import json_body
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
async def page(request: Request, source: str = "", view: str = "", date: str = "", preset: str = "", text: str = "", mode: str = ""):
    """PIVOT: the pivot screen; with ``preset`` (and its word's ``text``), one of its presets: the MR cube, the CCR cube. ``mode`` opens it
    as views (the View picker in front of the compact line, the default), compact or panes (BI_GUIDE.md section P2); a saved view and an
    address of before open the same, the old field list now behind Customize ›."""
    cube = pivot_presets.get(preset) if preset else None
    if cube is None:
        typed = f"{WORD} @{view}" if view else f"{WORD} {source} {date}".strip()
        return render(request, "terminal/pivot.html", screen="pivot", source=source.strip(), view_id=view.strip(), date=date.strip(),
                      command_text=typed, mode=pivot_presets.mode_of(mode) if mode else "", views_json="[]", open_view="")
    from core import engines
    if cube.engine and not engines.engine_available(cube.engine):          # its engine runs on no server of the cluster: said in the server's words
        return RedirectResponse("/t?error=" + quote(engines.refusal(engines.entry(cube.engine) or {"id": cube.engine})), status_code=303)
    from core import plugins
    parse = plugins.preset_parser(cube.lens)                               # a preset's word lays a view over its landing (its module's grammar)
    laid = parse(text.split()) if parse is not None and text.strip() else {}
    if date and "date" not in laid:
        laid["date"] = date
    typed = f"{cube.word} @{view}" if view else f"{cube.word} {text}".strip()
    building = bool(cube.under_construction) and under_construction.of(request).marked(cube.under_construction)
    return render(request, "terminal/pivot.html", screen=cube.help, source=cube.source, view_id=view.strip(), date=laid.get("date", ""),
                  command_text=typed, lens=cube.lens, preset=json.dumps(laid) if laid else "", cube=cube, cube_building=building,
                  mode=pivot_presets.mode_of(mode) if mode else cube.mode, views_json=json.dumps(list(cube.views)), open_view=cube.view)


async def _call(request: Request, method: str, path: str, body=None, **params):
    try:
        return JSONResponse(await request.app.state.backend.pivot(method, path, ident(request), body=body, **params),
                            headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


async def _body(request: Request) -> dict:
    b = await json_body(request)
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
    if action not in ("query", "estimate", "expand", "compare", "rows", "inline", "views", "promote", "whatif", "functions", "live"):   # Why did it move: the one Explain (/api/explain, kind pivot)
        return JSONResponse({"detail": "no such pivot action"}, status_code=404)
    return await _call(request, "POST", "/" + action, await _body(request))


@router.get("/api/pivot/provenance/{pid}")
async def provenance(request: Request, pid: str, cell: str = ""):
    return await _call(request, "GET", f"/provenance/{pid}", cell=cell)


@router.get("/api/pivot/cells")
async def cells(request: Request, ref: str = ""):
    return await _call(request, "GET", "/cells", ref=ref)


@router.get("/api/pivot/paths")
async def paths(request: Request, source: str = ""):
    """An administrator's suggestions: the document paths people typed on a source, most used first."""
    return await _call(request, "GET", "/paths", source=source)


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


# ---- custom functions (BI_GUIDE.md, custom functions): the playground's check, the list, a version, approval -----------------------------

@router.get("/api/pivot/functions")
async def functions(request: Request):
    return await _call(request, "GET", "/functions")


@router.post("/api/pivot/functions/test")
async def function_test(request: Request):
    return await _call(request, "POST", "/functions/test", await _body(request))


@router.get("/api/pivot/functions/{owner}/{name}")
async def function_get(request: Request, owner: str, name: str, version: str = ""):
    return await _call(request, "GET", f"/functions/{owner}/{name}", version=version)


@router.post("/api/pivot/functions/{owner}/{ref}/{op}")
async def function_op(request: Request, owner: str, ref: str, op: str):
    if op not in ("submit", "revoke"):                  # approving is done in the one Approvals queue (/approvals/function/<owner:name@v>)
        return JSONResponse({"detail": "no such function action"}, status_code=404)
    return await _call(request, "POST", f"/functions/{owner}/{ref}/{op}", await _body(request))


# ---- the screen's switches, live pivots and cell comments (BI_GUIDE.md: the screen's switches, live pivots, cell comments) -------------

_SSE = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


def _relay(request: Request, pairs) -> StreamingResponse:
    """Server-sent events relayed as they come; a refusal is one ``error`` event with the server's code and words."""
    async def events():
        try:
            async for event, data in pairs:
                if await request.is_disconnected():
                    break
                yield f"event: {event}\ndata: {data}\n\n"
        except BackendError as e:
            yield f"event: error\ndata: {json.dumps({'code': e.code, 'error': e.detail})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream", headers=_SSE)


def _view_path(owner: str, name: str) -> str:
    return f"/views/{quote(owner, safe='')}/{quote(name, safe='')}"


@router.post("/api/pivot/query/progressive")
async def progressive(request: Request):
    """Progressive on: the server's ``POST /api/v1/pivot/query?progressive=true`` relayed, ``approximate`` (≈ from a sample, with intervals)
    then ``exact``; the screen draws each as it comes and computes nothing."""
    body = await _body(request)
    return _relay(request, request.app.state.backend.sse_post("/pivot/query?progressive=true", ident(request), body))


@router.get("/api/pivot/live")
async def live_list(request: Request):
    return await _call(request, "GET", "/live")


@router.delete("/api/pivot/live/{lid}")
async def live_end(request: Request, lid: str):
    try:
        await request.app.state.backend.pivot("DELETE", f"/live/{quote(lid, safe='')}", ident(request))
        return Response(status_code=204)
    except BackendError as e:
        return _problem(e)


@router.get("/api/pivot/views/{owner}/{name}/comments")
async def comments(request: Request, owner: str, name: str, cell: str = "", open: str = ""):
    return await _call(request, "GET", _view_path(owner, name) + "/comments", cell=cell, open=open)


@router.post("/api/pivot/views/{owner}/{name}/comments")
async def comment_add(request: Request, owner: str, name: str):
    return await _call(request, "POST", _view_path(owner, name) + "/comments", await _body(request))


@router.post("/api/pivot/views/{owner}/{name}/comments/{cid}/{op}")
async def comment_op(request: Request, owner: str, name: str, cid: str, op: str):
    if op not in ("resolve", "reopen"):
        return JSONResponse({"detail": "no such comment action"}, status_code=404)
    return await _call(request, "POST", f"{_view_path(owner, name)}/comments/{quote(cid, safe='')}/{op}", {})


@router.delete("/api/pivot/views/{owner}/{name}/comments/{cid}")
async def comment_delete(request: Request, owner: str, name: str, cid: str):
    return await _call(request, "DELETE", f"{_view_path(owner, name)}/comments/{quote(cid, safe='')}")
