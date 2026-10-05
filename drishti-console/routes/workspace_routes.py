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

from pathlib import Path

import yaml
from fastapi import APIRouter, Request
from urllib.parse import quote

from fastapi.responses import JSONResponse, RedirectResponse

from core.csrf import json_body
from core.backend import BackendError
from routes.common import ident, packs, render

router = APIRouter(prefix="/w", include_in_schema=False)
TEMPLATES = Path(__file__).resolve().parent.parent / "config" / "workspaces.yaml"


def _config() -> dict:
    return yaml.safe_load(TEMPLATES.read_text(encoding="utf-8")) or {}


async def _templates(request: Request) -> dict:
    core = _config().get("templates", {}) or {}
    for p in await packs(request):
        for name, t in p["workspaces"].items():
            core.setdefault(name, {**t, "pack": p["title"]})
    return core


def _blank() -> dict:
    """A new workspace (UX-06): the configured blank layout and empty panes, whatever packs are switched on."""
    b = _config().get("blank") or {}
    panes = [{"ref": None, "follows": None, "title": str((p or {}).get("title") or "")} for p in (b.get("panes") or [{}, {}])][:4]
    return {"layout": b.get("layout") if b.get("layout") in ("2col", "3col", "2x2", "1+2") else "2col", "panes": panes or [{"ref": None, "follows": None, "title": ""}]}


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("")
async def index(request: Request):
    try:
        mine = await request.app.state.backend.workspaces(ident(request))
    except BackendError:
        mine = []
    try:
        shared = await request.app.state.backend.shared_workspaces(ident(request))
    except BackendError:
        shared = []
    return render(request, "workspace/index.html", mine=mine, shared=shared, templates=await _templates(request), screen="workspace")


@router.get("/shared/{owner}/{name}")
async def shared(request: Request, owner: str, name: str):
    """A workspace someone shared: read-only, as its owner keeps it; Save as copies it into yours."""
    try:
        ws = await request.app.state.backend.shared_workspace(owner, name, ident(request))
    except BackendError:
        return render(request, "workspace/index.html", status_code=404, mine=[], shared=[], templates=await _templates(request),
                      missing=f"{name} (shared by {owner})", screen="workspace")
    return render(request, "workspace/workspace.html", name=name, ws=ws, saved=False, owner=owner, readonly=True, screen="workspace")


@router.get("/new")
async def new(request: Request, name: str | None = None):
    """New workspace (the form on /w): opens a blank one under the name typed; saving it makes it yours."""
    if name is None:                        # a workspace that is itself called "new"
        return await workspace(request, "new")
    name = " ".join(name.split())[:64]
    return RedirectResponse(f"/w/{quote(name)}?new=1" if name else "/w", status_code=303)


@router.get("/{name}")
async def workspace(request: Request, name: str, template: str = "", new: int = 0):
    ws, saved = None, False
    if new:
        try:                                # the name is taken: open that one rather than a blank over it
            ws, saved = await request.app.state.backend.workspace(name, ident(request)), True
        except BackendError:
            ws = _blank()
    elif template:
        ws = (await _templates(request)).get(template)
    else:
        try:
            ws, saved = await request.app.state.backend.workspace(name, ident(request)), True
        except BackendError:
            ws = (await _templates(request)).get(name)
    if ws is None:
        return render(request, "workspace/index.html", status_code=404, mine=[], templates=await _templates(request), missing=name,
                      create=name, screen="workspace")
    share = None
    if saved:
        try:
            share = await request.app.state.backend.workspace_share(name, ident(request))
        except BackendError:
            share = None
    return render(request, "workspace/workspace.html", name=name, ws=ws, saved=saved, share=share, screen="workspace")


@router.post("/api/{name}")
async def save(request: Request, name: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.save_workspace(name, body, ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/share")
async def share(request: Request, name: str):
    body = await json_body(request)
    try:
        if body.get("stop"):
            await request.app.state.backend.unshare_workspace(name, ident(request))
            return {"shared": False}
        return await request.app.state.backend.share_workspace(name, body, ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/{name}/delete")
async def delete(request: Request, name: str):
    try:
        await request.app.state.backend.delete_workspace(name, ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)
