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

"""Workspaces: several live views on one screen, with panes that follow each other's selection."""
from __future__ import annotations

import json
from pathlib import Path

import yaml
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, packs, render

router = APIRouter(prefix="/w", include_in_schema=False)
TEMPLATES = Path(__file__).resolve().parent.parent / "config" / "workspaces.yaml"


async def _templates(request: Request) -> dict:
    core = yaml.safe_load(TEMPLATES.read_text(encoding="utf-8")).get("templates", {}) or {}
    for p in await packs(request):
        for name, t in p["workspaces"].items():
            core.setdefault(name, {**t, "pack": p["title"]})
    return core


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("")
async def index(request: Request):
    try:
        mine = await request.app.state.backend.workspaces(ident(request))
    except BackendError:
        mine = []
    return render(request, "workspace/index.html", mine=mine, templates=await _templates(request), screen="workspace")


@router.get("/{name}")
async def workspace(request: Request, name: str, template: str = ""):
    ws, saved = None, False
    if template:
        ws = (await _templates(request)).get(template)
    else:
        try:
            ws, saved = await request.app.state.backend.workspace(name, ident(request)), True
        except BackendError:
            ws = (await _templates(request)).get(name)
    if ws is None:
        return render(request, "workspace/index.html", status_code=404, mine=[], templates=await _templates(request), missing=name,
                      screen="workspace")
    return render(request, "workspace/workspace.html", name=name, ws=ws, saved=saved, screen="workspace")


@router.post("/api/{name}")
async def save(request: Request, name: str):
    body = json.loads(await request.body() or b"{}")
    try:
        return await request.app.state.backend.save_workspace(name, body, ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/delete")
async def delete(request: Request, name: str):
    try:
        await request.app.state.backend.delete_workspace(name, ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)
