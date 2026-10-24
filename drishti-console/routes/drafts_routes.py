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

"""Drafts of a form (SUTRA_NEXT.md 10), the console's side: ``/api/drafts`` passes each call to the server's ``/api/v1/drafts`` as the
signed-in person (static/js/forms.js calls it). The server keeps the drafts per person, checks them again with the form's rules and
refuses a masked or a locked field; a refusal comes back with its ``issues`` (field, message, severity) so the page can say, under the
field, what is wrong."""
from __future__ import annotations

import asyncio
import re
from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident

router = APIRouter(prefix="/api/drafts", include_in_schema=False)
DRAFT = re.compile(r"^d-[0-9a-f]{12}$")
LIMIT = 512 * 1024                                       # a draft of 500 edited cells fits well inside


def _problem(e: BackendError) -> JSONResponse:
    body = {"code": e.code, "detail": e.detail}
    for k in ("issues", "warnings"):
        if isinstance(e.extra.get(k), list):
            body[k] = e.extra[k]
    return JSONResponse(body, status_code=e.status if 400 <= e.status < 600 else 502)


def _bad(detail: str, status: int = 400) -> JSONResponse:
    return JSONResponse({"code": "DRS-5001", "detail": detail}, status_code=status)


async def _call(request: Request, method: str, path: str, body=None):
    kw = {"json": body} if body is not None else {}
    try:
        got = await request.app.state.backend._send(method, "/drafts" + path, ident(request), **kw)
    except BackendError as e:
        return _problem(e)
    if got is None:
        return Response(status_code=204)
    return JSONResponse(got, status_code=201 if method == "POST" else 200)


lookup_router = APIRouter(prefix="/api/forms", include_in_schema=False)
LOOKUP_KEYS = ("kind", "pack", "q", "limit", "label", "value")
LOOKUP_WAIT = 3.0                                        # the server answers within 2 s; a slower answer is "Searching..." no more


@lookup_router.get("/lookup")
async def lookup(request: Request):
    """An entity field's live search (record-forms.js): ``/api/v1/forms/lookup`` as the signed-in person, ``{kind, q, results: [{id,
    label}]}``. The server reads as the caller (masks, entitlements), caps the limit and keeps a short budget."""
    q = {k: str(v)[:100] for k, v in request.query_params.items() if k in LOOKUP_KEYS and v}
    if not q.get("kind"):
        return _bad("a lookup names a kind")
    try:
        got = await asyncio.wait_for(request.app.state.backend._send("GET", "/forms/lookup?" + urlencode(q), ident(request)), LOOKUP_WAIT)
    except BackendError as e:
        return _problem(e)
    except asyncio.TimeoutError:
        return JSONResponse({"code": "DRS-5001", "detail": "the search took too long: type a little more"}, status_code=504)
    return JSONResponse(got)


@router.get("")
async def list_drafts(request: Request):
    q = {k: v for k, v in request.query_params.items() if k in ("kind", "id", "sutra") and v}
    return await _call(request, "GET", ("?" + urlencode(q)) if q else "")


@router.get("/{draft}")
async def get_draft(request: Request, draft: str):
    if not DRAFT.match(draft):
        return _bad("a draft id is d- and 12 hex digits")
    return await _call(request, "GET", "/" + draft)


@router.post("")
async def create_draft(request: Request):
    try:
        body = await json_body(request, limit=LIMIT)
    except BodyError as e:
        return _bad(e.detail, e.status)
    return await _call(request, "POST", "", body)


@router.put("/{draft}")
async def update_draft(request: Request, draft: str):
    if not DRAFT.match(draft):
        return _bad("a draft id is d- and 12 hex digits")
    try:
        body = await json_body(request, limit=LIMIT)
    except BodyError as e:
        return _bad(e.detail, e.status)
    return await _call(request, "PUT", "/" + draft, body)


@router.delete("/{draft}")
async def delete_draft(request: Request, draft: str):
    if not DRAFT.match(draft):
        return _bad("a draft id is d- and 12 hex digits")
    return await _call(request, "DELETE", "/" + draft)
