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

"""The embed API for ``<drishti-report>`` (docs/architecture/ELEMENTS.md section 18; Rupaka phase 7).

A published BI report in another web application: its definition (``GET /embed/v1/reports/{id}``), one visual's rows under the
page's slicers and selections (``POST .../query``), and the visual types (``GET /embed/v1/reports/catalogue``). Live updates use the
embed channel with the key ``bilive:<id>``. Every call is the embed caller's own: the server verifies the embed token on each one,
applies the user's row rules and always masks. Which hosts may show which reports is the host application's ``kinds``
(``report:<id>`` or ``report:*``, Admin -> Embedding); the console adds only that a report must be published.
"""
from __future__ import annotations

import json

from fastapi import APIRouter, Request
from fastapi.responses import Response

from core.backend import BackendError
from core.csrf import BodyError, json_body
from core.embed import EmbedError
from routes.embed_routes import identity, refuse, reply

router = APIRouter(prefix="/embed/v1", include_in_schema=False)
_ASK_LIMIT = 65_536
KEPT = ("id", "title", "description", "sample", "shipped", "valid", "problems", "report", "dataset")


async def _call(request: Request, method: str, path: str, body: dict | None = None):
    """The server's answer for the embed caller (a dict), or the Response that refuses."""
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    try:
        return await request.app.state.backend.bi(method, path, me, body)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)


async def _json(request: Request, answer) -> Response:
    return answer if isinstance(answer, Response) else await reply(request, json.dumps(answer, ensure_ascii=False), "application/json")


@router.get("/reports/catalogue")
async def catalogue(request: Request):
    return await _json(request, await _call(request, "GET", "/reports/catalogue"))


@router.post("/reports/{id_:path}/query")
async def query(request: Request, id_: str):
    """One visual's rows. The body is the report viewer's own: ``{page, visual, state}`` (slicers, selections, drill, carry, dates)."""
    try:
        body = await json_body(request, limit=_ASK_LIMIT)
    except (BodyError, ValueError):
        return await refuse(request, 400, "DRS-5001", "a JSON body {page, visual, state}")
    return await _json(request, await _call(request, "POST", f"/reports/{id_}/query", body))


@router.get("/reports/{id_:path}")
async def definition(request: Request, id_: str):
    """The report as the viewer draws it: model, dataset summary and problems; never the YAML, and only a published report."""
    got = await _call(request, "GET", f"/reports/{id_}")
    if isinstance(got, Response):
        return got
    if got.get("status") != "published":
        return await refuse(request, 403, "DRS-8005", "only a published report can be embedded")
    keep = {k: got.get(k) for k in KEPT}
    keep["problems"] = [p for p in (keep["problems"] or []) if p.get("level") == "error"]
    return await _json(request, keep)
