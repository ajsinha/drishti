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

"""Same-origin JSON endpoints for the browser (the CSP allows connect-src 'self' only)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import user_of

router = APIRouter(prefix="/api", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/suggest")
async def suggest(request: Request, q: str = "", limit: int = 10):
    try:
        return await request.app.state.backend.suggest(q, user_of(request), limit)
    except BackendError as e:
        return _problem(e)


@router.get("/raw/{kind}/{id_}")
async def raw(request: Request, kind: str, id_: str):
    try:
        return await request.app.state.backend.raw(kind, id_)
    except BackendError as e:
        return _problem(e)


@router.get("/view/{kind}/{id_}")
async def view(request: Request, kind: str, id_: str):
    try:
        return await request.app.state.backend.view(kind, id_, user_of(request))
    except BackendError as e:
        return _problem(e)
