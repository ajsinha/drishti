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

"""The live tables' console side (docs/guides/LIVE_RESULTS_GUIDE.md; Drishti Elements' grid, live mode): one page for any table
(``/live/<name>``: a blotter), and ``/api/rdp/...`` passing the server's Data Protocol (``/api/v1/rdp/...``) on as the signed-in person:
the tables, a table's schema (masks, ``write`` for a reader who may edit), a page (``query``), a write, a stream's window. The changes
stream through the console's live channel (``live:<table>``, core/channel.py), so a browser holds one connection however many tables are
open. The rules (rights, masks, versions, four eyes) are the server's; nothing here decides them."""
from __future__ import annotations

import re
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import live_results
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

NAME = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,127}$")
SUB = re.compile(r"^[A-Za-z0-9-]{8,64}$")
LIMIT = 256 * 1024
LIVE_MODULE = "trading"                 # the module whose blotters are the shipped live tables (modules/trading/blotters)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"error": {"code": e.code, "message": e.detail}}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502,
                        headers={"Cache-Control": "no-store"})


def _bad(detail: str, status: int = 400) -> JSONResponse:
    return JSONResponse({"error": {"code": "rdp.query.invalid", "message": detail}}, status_code=status, headers={"Cache-Control": "no-store"})


async def _call(request: Request, method: str, path: str, body=None):
    kw = {"json": body} if body is not None else {}
    try:
        got = await request.app.state.backend._send(method, "/rdp" + path, ident(request), **kw)
    except BackendError as e:
        return _problem(e)
    return JSONResponse(got if got is not None else {}, headers={"Cache-Control": "no-store"})


async def _body(request: Request):
    try:
        return await json_body(request, limit=LIMIT), None
    except BodyError as e:
        return None, _bad(e.detail, e.status)


def _missing(request: Request):
    return render(request, "module_unavailable.html", route={"status": 404}, path=request.url.path, me=ident(request), status_code=404)


@router.get("/live/{name}")
async def page(request: Request, name: str):
    """The page of one live table: its grid (the live mode of drishti-grid), its record panel, its tray. 404 when the person may not read it."""
    if not NAME.match(name):
        return _missing(request)
    try:
        schema = await request.app.state.backend._send("GET", f"/rdp/tables/{quote(name, safe='')}", ident(request))
    except BackendError as e:
        if e.status != 404:
            return _missing(request)
        from core import modules
        if not modules.module_on(LIVE_MODULE):          # the module that declares the tables is not part of this distribution: say so, plainly
            return render(request, "module_unavailable.html", route={"status": 200, "state": "absent", "module": LIVE_MODULE, "title": "Trading",
                                                                   "what": f"The live table '{name}'"}, path=request.url.path, me=ident(request))
        return _missing(request)
    return render(request, "live/live.html", live_id=name, spec=live_results.table_spec(name, schema or {}), screen="live")


@router.get("/api/rdp/tables")
async def tables(request: Request):
    """The tables the person may read."""
    return await _call(request, "GET", "/tables")


@router.get("/api/rdp/tables/{name}")
async def schema(request: Request, name: str):
    """A table's schema for the person: masked columns, ``write`` when they may edit, ``live`` (up or paused), its profile."""
    if not NAME.match(name):
        return _bad("a table's name is lower case, digits, dots and dashes")
    return await _call(request, "GET", f"/tables/{quote(name, safe='')}")


@router.post("/api/rdp/tables/{name}/query")
async def query(request: Request, name: str):
    """A page: ``{filter?, sort?, limit?, offset?, columns?}`` (PROTOCOL 6)."""
    if not NAME.match(name):
        return _bad("a table's name is lower case, digits, dots and dashes")
    body, bad = await _body(request)
    return bad or await _call(request, "POST", f"/tables/{quote(name, safe='')}/query", body or {})


@router.post("/api/rdp/tables/{name}/write")
async def write(request: Request, name: str):
    """A change ``{id, op, key, basedOn, set, from?, reason?}``: 409 when the row moved since it was read, 403 when read-only, held for four eyes."""
    if not NAME.match(name):
        return _bad("a table's name is lower case, digits, dots and dashes")
    body, bad = await _body(request)
    return bad or await _call(request, "POST", f"/tables/{quote(name, safe='')}/write", body or {})


@router.post("/api/rdp/changes/{sub}/window")
async def window(request: Request, sub: str):
    """The rows a windowed stream sends changes of: ``{keys, top?}``; only the stream's own reader may move it."""
    if not SUB.match(sub):
        return _bad("a stream's id is a plain name")
    body, bad = await _body(request)
    return bad or await _call(request, "POST", f"/changes/{sub}/window", body or {})


@router.get("/api/rdp/searches")
async def searches(request: Request):
    """A search opened as a query on a table (a FORM's result): ``{table, query, live, page, search}``."""
    q = request.query_params.get("q", "")
    if not q.strip():
        return _bad("a search names what it reads: q=TRD where ...")
    return await _call(request, "GET", "/searches?" + urlencode({"q": q}))
