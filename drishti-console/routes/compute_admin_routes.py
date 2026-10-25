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

"""Admin → Compute (/admin/compute): the server's compute workers (docs/admin/COMPUTE.md, docs/architecture/PYTHON_COMPUTE.md section 15).
The page shows the switch, each pool (its workers on this node and on the other Drishti*net nodes, queues by class, latency, crashes and
recycles), the workers busy now, recent requests, the locked environments, and the approval requests for what connectors and pools
require. It carries the administrator's decisions to the server as same-origin JSON: approve, reject or revoke (always with a reason; the
server refuses the person who asked: four eyes), build a pool's approved environment, test a pool with the sample engine, cancel a
running request. The server decides who may do what."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(prefix="/admin/compute", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _items(items: list) -> dict:
    """An approval request's items counted by kind, with the names (python 3, java 1: numpy==2.1.3, ...)."""
    kinds: dict = {}
    for i in items or []:
        kinds[i.get("kind", "?")] = kinds.get(i.get("kind", "?"), 0) + 1
    return kinds


@router.get("")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        data = await request.app.state.backend.admin("GET", "/compute", me)
        status = await request.app.state.backend.admin("GET", "/status", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    approvals = data.get("approvals") or []
    for a in approvals:
        a["kinds"] = _items(a.get("items"))
        a["mine"] = a.get("requestedBy") == data.get("me")
    pending = [a for a in approvals if a.get("state") == "pending"]
    busy = [dict(w, pool=p.get("name")) for p in data.get("pools") or [] for w in p.get("workers") or [] if w.get("state") == "busy"]
    return render(request, "admin/compute.html", c=data, pools=data.get("pools") or [], approvals=approvals, pending=pending, busy=busy,
                  recent=data.get("recent") or [], envs=data.get("environments") or [], status=status)


@router.post("/api/requests/{rid}/{verb}")
async def decide(request: Request, rid: str, verb: str):
    if verb not in ("approve", "reject", "revoke"):
        return JSONResponse({"code": "DRS-5001", "detail": "approve, reject or revoke"}, status_code=404)
    body = await json_body(request)
    reason = body.get("reason") if isinstance(body.get("reason"), str) else ""
    try:
        return await request.app.state.backend.admin("POST", f"/compute/requests/{quote(rid)}/{verb}", ident(request), {"reason": reason})
    except BackendError as e:
        return _problem(e)


@router.post("/api/pools/{pool}/{action}")
async def pool_action(request: Request, pool: str, action: str):
    if action not in ("build", "test"):
        return JSONResponse({"code": "DRS-5001", "detail": "build or test"}, status_code=404)
    try:
        return await request.app.state.backend.admin("POST", f"/compute/pools/{quote(pool)}/{action}", ident(request), {},
                                                     timeout=900.0 if action == "build" else 90.0)
    except BackendError as e:
        return _problem(e)


@router.post("/api/running/{rid}/cancel")
async def cancel(request: Request, rid: str):
    try:
        return await request.app.state.backend.admin("POST", f"/compute/running/{quote(rid)}/cancel", ident(request), {})
    except BackendError as e:
        return _problem(e)
