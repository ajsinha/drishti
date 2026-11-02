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

"""The MR cube in the console (docs/guides/MR_CUBE_GUIDE.md): ``MCUBE <view> <GO>`` opens the cube page, landing A (the pivot first, the
decided layout) with tabs B (tail and attribution) and C (sensitivities and what-if) of the same view; ``MCUBE @owner/name`` opens a saved
view. The page is a shell that web/static/js/cube-core.js and mcube.js fill; every figure is the server's (``/api/v1/cube/...``, asked as
the person through the same-origin proxy ``/api/mcube/...``), the console only draws."""
from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "MCUBE"
#: the landing's view when none is typed (MR_CUBE_LENS.md section 12.1, home.default-view): the bank by desk and risk class, VaR
DEFAULT_TEXT = "MCUBE BANK BY MGMT.DESK,RISK_CLASS MEASURE VAR,ES SORT desc"
TABS = ("pivot", "tail", "sensitivities")


def mcube_command(words: list[str]) -> str | None:
    """Where ``MCUBE ...`` goes, or None when it is not the command: ``MCUBE @owner/name`` a saved view, anything else the view's text."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    if len(words) == 2 and words[1].startswith("@") and "/" in words[1]:
        return "/mcube?" + urlencode({"view": words[1][1:]})
    if len(words) == 1:
        return "/mcube"
    return "/mcube?" + urlencode({"text": " ".join(words)})


def redirect_for(typed: str):
    where = mcube_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/mcube")
async def page(request: Request, text: str = "", view: str = "", tab: str = "pivot"):
    """MCUBE: the cube page (landing A, tabs B and C)."""
    text = text.strip() or ("" if view else DEFAULT_TEXT)
    tab = tab if tab in TABS else "pivot"
    return render(request, "terminal/mcube.html", screen="mcube", view_text=text, view_id=view.strip(), tab=tab,
                  command_text=(f"{WORD} @{view.strip()}" if view else text))


async def _call(request: Request, method: str, path: str, body=None, **params):
    try:
        return JSONResponse(await request.app.state.backend.cube(method, path, ident(request), body=body, **params),
                            headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


async def _body(request: Request) -> dict:
    try:
        b = await request.json()
    except ValueError:
        b = {}
    return b if isinstance(b, dict) else {}


@router.post("/api/mcube/query")
async def query(request: Request):
    return await _call(request, "POST", "/mr/query", await _body(request))


@router.post("/api/mcube/cell")
async def cell(request: Request):
    return await _call(request, "POST", "/mr/cell", await _body(request))


@router.get("/api/mcube/describe")
async def describe(request: Request, scope: str = "", date: str = ""):
    return await _call(request, "GET", "/mr/describe", scope=scope, date=date)


@router.get("/api/mcube/provenance/{pid}")
async def provenance(request: Request, pid: str, cell: str = ""):
    return await _call(request, "GET", f"/provenance/{pid}", cell=cell)


@router.get("/api/mcube/versions")
async def versions(request: Request):
    return await _call(request, "GET", "/mr/versions")


@router.get("/api/mcube/explain")
async def explain(request: Request, scope: str = "", start: str = "", end: str = ""):
    q = request.query_params
    return await _call(request, "GET", "/mr/explain", scope=scope, **{"from": q.get("from") or start, "to": q.get("to") or end})


@router.get("/api/mcube/sensitivities")
async def sensitivities(request: Request):
    q = request.query_params
    return await _call(request, "GET", "/mr/sensitivities", **{k: q.get(k) for k in ("scope", "grid", "by", "class", "measure", "ccy", "asOf")
                                                              if q.get(k)})


@router.post("/api/mcube/what-if")
async def what_if(request: Request):
    return await _call(request, "POST", "/mr/what-if", await _body(request))


@router.get("/api/mcube/views")
async def views(request: Request):
    return await _call(request, "GET", "/views")


@router.post("/api/mcube/views")
async def save_view(request: Request):
    return await _call(request, "POST", "/views", await _body(request))


@router.get("/api/mcube/views/{owner}/{name}")
async def get_view(request: Request, owner: str, name: str):
    return await _call(request, "GET", f"/views/{owner}/{name}")


@router.delete("/api/mcube/views/{name}")
async def delete_view(request: Request, name: str):
    try:
        await request.app.state.backend.cube("DELETE", f"/views/{name}", ident(request))
        return Response(status_code=204)
    except BackendError as e:
        return _problem(e)


@router.post("/api/mcube/export")
async def export(request: Request, format: str = "csv"):
    """The view exported by the server (csv, xlsx with formulas where additive, Arrow), passed through as a download."""
    try:
        content, headers = await request.app.state.backend.cube("POST", "/mr/export", ident(request), body=await _body(request), raw=True,
                                                                 format=format)
    except BackendError as e:
        return _problem(e)
    keep = {k: v for k, v in headers.items() if k.lower() in ("content-type", "content-disposition")}
    keep["Cache-Control"] = "no-store"
    return Response(content=content, headers=keep)
