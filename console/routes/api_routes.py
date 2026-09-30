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
import json

from fastapi.responses import JSONResponse, StreamingResponse

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


CHART_KINDS = {"line", "area"}


def _panel_html(request: Request, panel: dict) -> str:
    """Renders one panel with the same macro as first paint, so live and static views look identical."""
    module = request.app.state.templates.env.get_template("_macros/panels.html").module
    return str(module.panel(panel))


@router.get("/stream/{kind}/{id_}")
async def stream(request: Request, kind: str, id_: str):
    """Relays the server's live stream. Panel patches arrive as ready HTML (charts as data), strip patches as cells."""
    backend = request.app.state.backend

    async def events():
        try:
            async for event, data in backend.stream(kind, id_):
                if await request.is_disconnected():
                    break
                if event == "frame":
                    frame = json.loads(data)
                    for patch in frame.get("patches", []):
                        panel = patch.get("panel")
                        if panel and panel.get("kind") not in CHART_KINDS:
                            patch["html"] = _panel_html(request, panel)
                            patch["panel"] = {"id": panel["id"], "kind": panel["kind"]}
                    data = json.dumps(frame, ensure_ascii=False)
                elif event == "view":
                    view = json.loads(data)
                    data = json.dumps({"generation": view.get("provenance", {}).get("generation")})
                yield f"event: {event}\ndata: {data}\n\n"
        except BackendError as e:
            yield f"event: gone\ndata: {json.dumps({'code': e.code, 'detail': e.detail})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
