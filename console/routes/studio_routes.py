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

"""Sutra Studio: edit a Sutra, preview it against any entity, start from inference, save (authors)."""
from __future__ import annotations

import json

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(prefix="/studio", include_in_schema=False)

NEW_SUTRA = """sutra: my-layout
version: 1
match: { kind: trade, where: "$.productType == 'IRS'" }
title: { pill: "Trade", id: $.tradeId }
strip:
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
panels:
  - { id: refs, kind: links, title: Linked entities, area: right }
"""


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail, "problems": getattr(e, "problems", [])}, status_code=e.status)


@router.get("")
async def studio(request: Request, sutra: str = "irs-vanilla@3", kind: str = "trade", id: str = "IRS-48213"):
    backend, me = request.app.state.backend, ident(request)
    sutras = await backend.sutras(me)
    source, picked = NEW_SUTRA, ""
    if "@" in sutra:
        name, _, version = sutra.partition("@")
        try:
            source, picked = await backend.sutra_source(name, int(version), me), sutra
        except (BackendError, ValueError):
            pass
    settings = await backend.studio_settings(me)
    return render(request, "studio/studio.html", sutras=sutras, source=source, picked=picked, ref_kind=kind, ref_id=id,
                  can_save=bool(settings.get("save")))


@router.get("/source/{name}/{version}")
async def source(request: Request, name: str, version: int):
    try:
        return PlainTextResponse(await request.app.state.backend.sutra_source(name, version, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/preview")
async def preview(request: Request):
    body = json.loads(await request.body() or b"{}")
    try:
        vm = await request.app.state.backend.preview(body.get("yaml", ""), body.get("kind", ""), body.get("id", ""), ident(request))
    except BackendError as e:
        return _problem(e)
    html = request.app.state.templates.get_template("studio/_preview.html").render(vm=vm)
    return HTMLResponse(html)


@router.get("/inferred/{kind}/{id_}")
async def inferred(request: Request, kind: str, id_: str, name: str = ""):
    try:
        return PlainTextResponse(await request.app.state.backend.inferred(kind, id_, name, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/save")
async def save(request: Request):
    body = json.loads(await request.body() or b"{}")
    try:
        return await request.app.state.backend.save_sutra(body.get("yaml", ""), ident(request))
    except BackendError as e:
        return _problem(e)
