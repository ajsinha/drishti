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

"""The Pivot tab's same-origin JSON (USER_GUIDE.md, The Pivot tab). Every read goes through the server with the user's own
identity, business date and roles, as the view or search it belongs to does: a panel's rows for the browser's engine, the
server engine of a search's pivot (its cells, a cell's entities, a field's values), the user's saved arrangements, and an
author's promotion of a panel's pivot to the Sutra's next version (with the diff, as layout promotion shows it). Problems
come back as ``{"code", "detail"}`` with the server's status."""
from __future__ import annotations

import json
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.pivots import ARRANGEMENT
from routes.common import ident
from core import sutra_diff

router = APIRouter(prefix="/api/pivot", include_in_schema=False)
SEARCH_KEYS = ("q", "documents", "cell", "offset", "size", "field") + ARRANGEMENT


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _body(request: Request) -> dict:
    try:
        body = json.loads(await request.body() or b"{}")
    except ValueError:
        body = {}
    return body if isinstance(body, dict) else {}


def _forget(request: Request) -> None:
    me = ident(request)
    request.app.state.pivots.forget(me.user if me else "")


def _panel(sutra: str, panel: str) -> str:
    return f"panel/{quote(sutra, safe='')}/{quote(panel, safe='')}"


def _search(kind: str) -> str:
    return f"search/{quote(kind, safe='')}"


# ---- the browser's engine: a panel's rows ------------------------------------------------------------------------
@router.get("/records/{kind}/{id_}/{panel}")
async def records(request: Request, kind: str, id_: str, panel: str):
    """Every row of a table or ladder whose Sutra says pivot: (beyond the table's limit, up to the server's cap)."""
    try:
        return await request.app.state.backend.panel_records(kind, id_, panel, ident(request))
    except BackendError as e:
        return _problem(e)


# ---- the server's engine: a search's pivot ------------------------------------------------------------------------
async def _search_pivot(request: Request, kind: str, part: str):
    body = {k: v for k, v in (await _body(request)).items() if k in SEARCH_KEYS}
    try:
        return await request.app.state.backend.search_pivot(kind, body, ident(request), part)
    except BackendError as e:
        return _problem(e)


@router.post("/search/{kind}")
async def search_cube(request: Request, kind: str):
    """The cells of a search's pivot over every match of the day: ``{"q", "rows", "columns", "values", "filters", "documents"}``."""
    return await _search_pivot(request, kind, "")


@router.post("/search/{kind}/drill")
async def search_drill(request: Request, kind: str):
    """The entities behind one cell, a page at a time: the same body plus ``cell``, ``offset`` and ``size``."""
    return await _search_pivot(request, kind, "drill")


@router.post("/search/{kind}/values")
async def search_values(request: Request, kind: str):
    """A field's values among the matches, with counts, for a filter: ``{"q", "field", "documents"}``."""
    return await _search_pivot(request, kind, "values")


# ---- saved arrangements --------------------------------------------------------------------------------------------
async def _saved(request: Request, method: str, scope: str):
    body = None
    if method == "PUT":
        raw = await _body(request)
        body = {k: raw[k] for k in ARRANGEMENT if k in raw}
    try:
        kept = await request.app.state.backend.saved_pivot(method, scope, ident(request), body)
    except BackendError as e:
        if method == "DELETE" and e.status == 404:          # nothing to forget is not an error
            _forget(request)
            return {"ok": True}
        return _problem(e)
    if method != "GET":
        _forget(request)
    return kept if kept is not None else {"ok": True}


@router.get("/saved/panel/{sutra}/{panel}")
async def get_panel(request: Request, sutra: str, panel: str):
    return await _saved(request, "GET", _panel(sutra, panel))


@router.put("/saved/panel/{sutra}/{panel}")
async def put_panel(request: Request, sutra: str, panel: str):
    """Keeps the user's arrangement of a panel's pivot: ``{"rows", "columns", "values", "filters", "heat", "chart"}``."""
    return await _saved(request, "PUT", _panel(sutra, panel))


@router.delete("/saved/panel/{sutra}/{panel}")
async def delete_panel(request: Request, sutra: str, panel: str):
    """Back to the Sutra's arrangement."""
    return await _saved(request, "DELETE", _panel(sutra, panel))


@router.get("/saved/search/{kind}")
async def get_search(request: Request, kind: str):
    return await _saved(request, "GET", _search(kind))


@router.put("/saved/search/{kind}")
async def put_search(request: Request, kind: str):
    return await _saved(request, "PUT", _search(kind))


@router.delete("/saved/search/{kind}")
async def delete_search(request: Request, kind: str):
    return await _saved(request, "DELETE", _search(kind))


# ---- promotion (authors) --------------------------------------------------------------------------------------------
@router.get("/saved/panel/{sutra}/{panel}/promotion")
async def promotion(request: Request, sutra: str, panel: str):
    """What promoting the saved pivot would propose: the changes in words and the diff against the latest version."""
    try:
        p = await request.app.state.backend.pivot_promotion(_panel(sutra, panel), ident(request))
    except BackendError as e:
        return _problem(e)
    d = sutra_diff.review(p.get("base") or "", p.get("text") or "")
    return {"sutra": p.get("sutra"), "panel": p.get("panel"), "fromVersion": p.get("fromVersion"), "version": p.get("version"),
            "review": p.get("review"), "changes": p.get("changes") or [], "moves": [m["text"] for m in d["moves"]], "edits": d["edits"],
            "diff": d["full"]}


@router.post("/saved/panel/{sutra}/{panel}/promotion")
async def promote(request: Request, sutra: str, panel: str):
    """Proposes it (``{"note": "…"}``): a proposal to review, or (review off) the new version, live."""
    body = await _body(request)
    try:
        r = await request.app.state.backend.pivot_promotion(_panel(sutra, panel), ident(request), str(body.get("note") or "")[:500])
    except BackendError as e:
        return _problem(e)
    _forget(request)
    if r.get("proposal"):
        return {**r, "href": f"/studio/reviews/{r['proposal'].get('id')}"}
    return r
