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

"""Admin → Engines (/admin/engines; docs/guides/ADMIN_GUIDE.md section 8, mock-up A): the compute engines of this server with
their switch, state, memory, threads, needs, used by and where each switch is set; a change is staged, reviewed in the side panel (the
dependency check, what disappears for people, live or restart) and applied with a reason; History below; the cluster as a second tab. The
page carries the administrator's batch to the server as same-origin JSON; the server decides (administrators only), writes the engine's
file, audits it, and refuses with words (DRS-1021, DRS-1023). After a change the console learns at once what runs, so words, menus and Help
follow without waiting for the next about poll."""
from __future__ import annotations

import asyncio
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(prefix="/admin", include_in_schema=False)

#: how long the page waits for each of the server's answers (asked together) before it is drawn; an answer not in by then is asked
#: again by the page's script, which fills it in when it comes (or says the server is not answering): one slow answer never holds the page
PAGE_BUDGET_S = 0.8


async def _within(coro, budget: float = PAGE_BUDGET_S):
    """The answer of ``coro``, or None when it takes longer than ``budget`` seconds (a refusal still raises)."""
    try:
        return await asyncio.wait_for(coro, budget)
    except asyncio.TimeoutError:
        return None


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def _relearn(request: Request) -> None:
    """The server's about answer read now: an engine turned off disappears from words, menus and Help at once."""
    try:
        about = await request.app.state.backend.about(ident(request))
    except Exception:  # noqa: BLE001 - the next about poll learns it anyway
        return
    request.app.state.editions.learn(about)
    packs = getattr(request.app.state, "packs", None)
    if packs is not None and hasattr(packs, "forget_all"):
        packs.forget_all()


@router.get("/engines")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    b = request.app.state.backend
    try:                                                    # asked together, each within the budget; the script asks again for what is missing
        data, hist, status = await asyncio.gather(_within(b.admin("GET", "/engines", me)), _within(b.admin("GET", "/engines/history", me, limit=50)),
                                                  _within(b.admin("GET", "/status", me)))
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    return render(request, "admin/engines.html", e=data, history=(hist or {}).get("changes") or [], status=status, screen="admin",
                  pending=data is None or hist is None)


@router.get("/api/engines")
async def listing(request: Request):
    try:
        return await request.app.state.backend.admin("GET", "/engines", ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/engines")
async def change(request: Request):
    """Body ``{reason, node, changes: [{engine, on}], withNeeds, withDependents, expected: {id: sha}}``: the batch the page reviewed."""
    body = await json_body(request)
    changes = [{"engine": str(c.get("engine", "")), "on": bool(c.get("on"))} for c in (body.get("changes") or []) if isinstance(c, dict)]
    expected = {str(k): str(v) for k, v in (body.get("expected") or {}).items()} if isinstance(body.get("expected"), dict) else None
    out = {"reason": str(body.get("reason") or "")[:500], "node": str(body.get("node") or ""), "changes": changes,
           "withNeeds": bool(body.get("withNeeds")), "withDependents": bool(body.get("withDependents"))}
    if expected:
        out["expected"] = expected
    try:
        res = await request.app.state.backend.admin("POST", "/engines", ident(request), out)
    except BackendError as e:
        return _problem(e)
    await _relearn(request)
    return res


@router.get("/api/engines/history")
async def history(request: Request, limit: int = 100):
    try:
        return await request.app.state.backend.admin("GET", "/engines/history", ident(request), limit=max(1, min(limit, 1000)))
    except BackendError as e:
        return _problem(e)


@router.get("/api/engines/{engine}/impact")
async def impact(request: Request, engine: str):
    try:
        return await request.app.state.backend.admin("GET", f"/engines/{quote(engine)}/impact", ident(request))
    except BackendError as e:
        return _problem(e)
