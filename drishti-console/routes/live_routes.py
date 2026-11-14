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

"""The live result sets' console side (docs/guides/ELEMENTS.md, the grid's live mode; docs/guides/LIVE_RESULTS_GUIDE.md): one page for any
declaration (``/live/<id>``: a blotter), and ``/api/live/...`` passing the server's ``/api/v1/live/...`` on as the signed-in person: the
declarations, a page of rows, one record whole, a stream's window and a blotter's edit. The stream itself is the console's live channel
(``live:<id>``, core/channel.py), so a browser holds one connection however many sets are open. The rules (rights, masks, versions, four
eyes) are the server's; nothing here decides them."""
from __future__ import annotations

import re
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)
NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
STREAM = re.compile(r"^[A-Za-z0-9-]{8,64}$")
ROW_QUERY = ("offset", "limit", "sort", "descending", "filter", "query")
LIMIT = 256 * 1024
LIVE_MODULE = "trading"                 # the module whose blotters the live sets are (modules/trading/blotters)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502,
                        headers={"Cache-Control": "no-store"})


def _bad(detail: str, status: int = 400) -> JSONResponse:
    return JSONResponse({"code": "DRS-5001", "detail": detail}, status_code=status, headers={"Cache-Control": "no-store"})


async def _call(request: Request, method: str, path: str, body=None):
    kw = {"json": body} if body is not None else {}
    try:
        got = await request.app.state.backend._send(method, path, ident(request), **kw)
    except BackendError as e:
        return _problem(e)
    return JSONResponse(got if got is not None else {}, headers={"Cache-Control": "no-store"})


def _missing(request: Request):
    return render(request, "module_removed.html", route={"status": 404}, path=request.url.path, me=ident(request), status_code=404)


@router.get("/live/{live_id}")
async def page(request: Request, live_id: str):
    """The page of one declaration: its grid (the live mode of drishti-grid), its record panel, its tray. 404 when the person may not open it."""
    if not NAME.match(live_id):
        return _missing(request)
    try:
        listed = await request.app.state.backend._send("GET", "/live", ident(request))
    except BackendError:
        listed = {"sets": []}
    spec = next((s for s in (listed or {}).get("sets") or [] if s.get("id") == live_id), None)
    if spec is None:
        from core import modules
        if not modules.module_on(LIVE_MODULE):          # the module that declares the sets is not part of this distribution: say so, plainly
            return render(request, "module_removed.html", route={"status": 200, "state": "absent", "module": LIVE_MODULE, "title": "Trading",
                                                                   "what": f"The live set '{live_id}'"}, path=request.url.path, me=ident(request))
        return _missing(request)
    return render(request, "live/live.html", live_id=live_id, spec=spec, screen="live")


@router.get("/api/live")
async def sets(request: Request):
    """The declarations the person may open (``sets``), each with ``editable`` and ``readOnly`` (why not) as the server says for them."""
    return await _call(request, "GET", "/live")


@router.get("/api/live/{live_id}/rows")
async def rows(request: Request, live_id: str):
    if not NAME.match(live_id):
        return _bad("a live set's id is lower case, digits and dashes")
    q = {k: v for k, v in request.query_params.items() if k in ROW_QUERY and v != ""}
    return await _call(request, "GET", f"/live/{live_id}/rows" + (("?" + urlencode(q)) if q else ""))


@router.get("/api/live/{live_id}/rows/{entity:path}")
async def row(request: Request, live_id: str, entity: str):
    """One record whole as the person may see it, its version and sequence (the panel's Raw JSON)."""
    if not NAME.match(live_id):
        return _bad("a live set's id is lower case, digits and dashes")
    q = {k: v for k, v in request.query_params.items() if k == "query" and v}
    return await _call(request, "GET", f"/live/{live_id}/rows/{quote(entity, safe='')}" + (("?" + urlencode(q)) if q else ""))


@router.post("/api/live/{live_id}/stream/{stream}/window")
async def window(request: Request, live_id: str, stream: str):
    """The page a stream shows (``{ids: [...]}``, or ``{all: true}``): what the server sends changes of."""
    if not NAME.match(live_id) or not STREAM.match(stream):
        return _bad("a live set's id and a stream's id are plain names")
    try:
        body = await json_body(request, limit=LIMIT)
    except BodyError as e:
        return _bad(e.detail, e.status)
    return await _call(request, "POST", f"/live/{live_id}/stream/{stream}/window", body)


@router.post("/api/live/{live_id}/edit")
async def edit(request: Request, live_id: str):
    """A blotter's edit ``{entity, items: [{field, from, to}], basedOn, reason}``; 409 when the record moved since it was seen, 403 when read-only."""
    if not NAME.match(live_id):
        return _bad("a live set's id is lower case, digits and dashes")
    try:
        body = await json_body(request, limit=LIMIT)
    except BodyError as e:
        return _bad(e.detail, e.status)
    return await _call(request, "POST", f"/live/{live_id}/edit", body)
