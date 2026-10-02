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

"""Monitors (live watchlists) and alerts (rules evaluated by the server, with a notification stream)."""
from __future__ import annotations

import json
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse

from core.csrf import json_body
from core.backend import BackendError
from routes.common import ident, packs, render

router = APIRouter(include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _starters(request: Request) -> dict:
    out = {}
    for p in await packs(request):
        for name, ents in p["monitors"].items():
            out.setdefault(name, {"entities": ents, "pack": p["title"]})
    return out


@router.get("/m")
async def monitors(request: Request):
    try:
        mine = await request.app.state.backend.mine("GET", "/monitors", ident(request))
    except BackendError:
        mine = []
    return render(request, "monitor/index.html", mine=mine, starters=await _starters(request), screen="monitor")


@router.get("/m/{name}")
async def monitor(request: Request, name: str, template: str = ""):
    backend, me = request.app.state.backend, ident(request)
    saved = False
    if template:
        starter = (await _starters(request)).get(template)
        if not starter:
            return render(request, "monitor/index.html", status_code=404, mine=[], starters=await _starters(request), missing=template)
        try:
            await backend.mine("PUT", f"/monitors/{quote(name)}", me, {"entities": starter["entities"]})
        except BackendError as e:
            return render(request, "monitor/index.html", status_code=e.status, mine=[], starters=await _starters(request), missing=e.detail)
    try:
        rows = await backend.mine("GET", f"/monitors/{quote(name)}", me)
        saved = True
    except BackendError as e:
        return render(request, "monitor/index.html", status_code=404, mine=[], starters=await _starters(request), missing=name)
    return render(request, "monitor/monitor.html", name=name, rows=rows, saved=saved, screen="monitor")


@router.post("/m/api/{name}")
async def save_monitor(request: Request, name: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.mine("PUT", f"/monitors/{quote(name)}", ident(request), body)
    except BackendError as e:
        return _problem(e)


@router.post("/m/api/{name}/delete")
async def delete_monitor(request: Request, name: str):
    try:
        await request.app.state.backend.mine("DELETE", f"/monitors/{quote(name)}", ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)


def _relay(request: Request, path: str) -> StreamingResponse:
    backend = request.app.state.backend

    async def events():
        try:
            async for event, data in backend.sse(path, ident(request)):
                if await request.is_disconnected():
                    break
                yield f"event: {event}\ndata: {data}\n\n"
        except BackendError as e:
            yield f"event: gone\ndata: {json.dumps({'code': e.code, 'detail': e.detail})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.get("/api/monitor-stream/{name}")
async def monitor_stream(request: Request, name: str):
    return _relay(request, f"/me/monitors/{quote(name)}/stream")


@router.get("/api/alerts/stream")
async def alerts_stream(request: Request):
    return _relay(request, "/me/alerts/stream")


@router.get("/alerts")
async def alerts(request: Request, kind: str = "", id: str = ""):
    backend, me = request.app.state.backend, ident(request)
    try:
        events = await backend.mine("GET", "/alerts", me, limit=100)
        rules = await backend.mine("GET", "/alerts/rules", me)
    except BackendError:
        events, rules = [], []
    suggestions = [s for p in await packs(request) for s in p["alerts"] if not kind or s.get("kind") == kind]
    return render(request, "alerts.html", events=events, rules=rules, suggestions=suggestions, kind=kind, id=id, screen="alerts")


@router.post("/alerts/api/{name}")
async def save_rule(request: Request, name: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.mine("PUT", f"/alerts/rules/{quote(name)}", ident(request), body)
    except BackendError as e:
        return _problem(e)


@router.post("/alerts/api/{name}/delete")
async def delete_rule(request: Request, name: str):
    try:
        await request.app.state.backend.mine("DELETE", f"/alerts/rules/{quote(name)}", ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)
