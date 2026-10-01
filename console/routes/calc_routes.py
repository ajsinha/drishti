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

"""Calc's same-origin JSON (PYTHON_CALC.md). The Python runs in the browser; when it reads data, the page asks here (or
the ordinary /api/raw, /api/view and /api/series) with the user's session, and the console asks the server with the
user's own identity, so roles and redaction apply exactly as on the screen. Every route here needs Calc: the server
says whether the user's roles allow it."""
from __future__ import annotations

import json
import re

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, packs

router = APIRouter(prefix="/api/calc", include_in_schema=False)
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,79}$")


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _refused(request: Request) -> JSONResponse | None:
    """None when the user may use Calc; otherwise the 403 to answer with."""
    calc = request.app.state.calc
    if not calc.enabled:
        return JSONResponse({"code": "DRS-5002", "detail": "Calc is switched off on this console (calc.enabled)"}, status_code=403)
    s = await calc.settings(request.app.state.backend, ident(request))
    if not s.get("allowed"):
        return JSONResponse({"code": "DRS-5002", "detail": "you may not use Calc: ask an administrator for a role with calc"},
                            status_code=403)
    return None


@router.get("/snippets")
async def snippets(request: Request, kind: str = ""):
    """The snippets for a kind: those the user's packs ship ("pack") and the user's own ("mine")."""
    if (refused := await _refused(request)) is not None:
        return refused
    try:
        mine = await request.app.state.backend.calc_snippets(ident(request))
    except BackendError as e:
        return _problem(e)
    return {"pack": request.app.state.calc.snippets(await packs(request), kind), "mine": mine}


@router.put("/snippets/{name}")
async def save_snippet(request: Request, name: str):
    if (refused := await _refused(request)) is not None:
        return refused
    if not NAME.match(name.strip()):
        return JSONResponse({"code": "DRS-5001", "detail": "a snippet's name is 1-80 letters, digits, spaces and . _ ( ) -"},
                            status_code=400)
    body = json.loads(await request.body() or b"{}")
    keep = {k: str(body.get(k) or "") for k in ("code", "description", "kind")}
    try:
        return await request.app.state.backend.save_calc_snippet(name.strip(), keep, ident(request))
    except BackendError as e:
        return _problem(e)


@router.delete("/snippets/{name}")
async def delete_snippet(request: Request, name: str):
    if (refused := await _refused(request)) is not None:
        return refused
    try:
        await request.app.state.backend.delete_calc_snippet(name.strip(), ident(request))
    except BackendError as e:
        return _problem(e)
    return {"ok": True}


@router.get("/search")
async def search(request: Request, q: str = ""):
    """drishti.search(): a structured search as JSON (the screen's /s, without the page)."""
    if (refused := await _refused(request)) is not None:
        return refused
    if not q.strip():
        return JSONResponse({"code": "DRS-5001", "detail": "say what to search: TRD where mtm > 1m"}, status_code=400)
    try:
        return await request.app.state.backend.search(q, ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/columns/{kind}")
async def columns(request: Request, kind: str, paths: str = "", limit: int = 0):
    """drishti.columns(): whole columns of a kind on the business date (without paths: the fields kept as columns)."""
    if (refused := await _refused(request)) is not None:
        return refused
    try:
        return await request.app.state.backend.columns(kind, paths, limit if limit > 0 else None, ident(request))
    except BackendError as e:
        return _problem(e)
