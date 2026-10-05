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

"""Administration of collaboration: Admin > Collaboration (COLLABORATION.md, Administrators and compliance). One page for moderation
(search threads, hide and unhide comments, lock), legal holds, the compliance export, chain verification, the retention dry run and
the bridges. The server decides who may do what (``admin`` or ``compliance``, call by call); the page shows only the sections the
caller's roles open and these routes carry the request, put each time in the console's zone and turn the server's problems into JSON."""
from __future__ import annotations

import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.background import BackgroundTask

from core import threads as threads_core
from core.backend import BackendError
from core.csrf import json_body
from routes.collab_routes import collab
from routes.common import ident, local_zone, localise, problem, render

router = APIRouter(prefix="/admin/collab", include_in_schema=False)

_NAME = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
_SEARCH = ("user", "kind", "id", "from", "to", "limit", "after")


def _problem(e: BackendError) -> JSONResponse:
    return problem(e)


def _bad(what: str) -> JSONResponse:
    return JSONResponse({"code": "DRS-5001", "detail": f"not a valid {what}"}, status_code=400)


async def _call(request: Request, method: str, path: str, body: dict | None = None, status: int = 200, **params):
    """One admin call, its times localised; the server's refusal (a role it does not hold) comes back as the same JSON problem."""
    try:
        out = await request.app.state.backend.admin(method, "/collab" + path, ident(request), body, **{k: v for k, v in params.items() if v not in (None, "")})
    except BackendError as e:
        return _problem(e)
    return JSONResponse(localise(out, *local_zone(request)), status_code=status)


@router.get("")
async def page(request: Request):
    """The page: sections by what the caller holds (administrators moderate and run retention and bridges; compliance holds, exports, verifies)."""
    me = ident(request)
    try:
        caps = await collab(request).config(me)
    except BackendError as e:
        if e.status in (404, 503):
            caps = {"enabled": False}
        else:
            return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    admin, compliance = bool(me.is_admin), bool(caps.get("compliance"))
    if not admin and not compliance:
        return render(request, "admin/forbidden.html", status_code=403)
    return render(request, "admin/collab.html", tab="collab", admin=admin, compliance=compliance, enabled=bool(caps.get("enabled")),
                  status={}, screen="admin-collab")


# ---- search and moderation (admin or compliance; hide, unhide and lock are the existing /api/comment and /api/thread calls) ------------

@router.get("/api/threads")
async def threads(request: Request):
    q = request.query_params
    return await _call(request, "GET", "/threads", **{k: q.get(k) for k in _SEARCH})


# ---- legal holds (compliance) -----------------------------------------------------------------------------------------------------

@router.get("/api/holds")
async def holds(request: Request, active: bool = False):
    return await _call(request, "GET", "/holds", active="true" if active else "")


@router.post("/api/holds")
async def hold_place(request: Request):
    body = await json_body(request)
    send = {k: str(body[k]).strip() for k in ("scope", "kind", "id", "user", "thread", "from", "to", "reason") if body.get(k) not in (None, "")}
    return await _call(request, "POST", "/holds", send, status=201)


@router.delete("/api/holds/{hold_id}")
async def hold_release(request: Request, hold_id: int):
    return await _call(request, "DELETE", f"/holds/{int(hold_id)}")


# ---- compliance export (compliance): start, poll, download once -------------------------------------------------------------------

@router.post("/api/exports")
async def export_start(request: Request):
    body = await json_body(request)
    send = {k: str(body[k]).strip() for k in ("from", "to", "kind", "id", "user") if body.get(k) not in (None, "")}
    for k in ("includeShares", "includeThreads"):
        if k in body:
            send[k] = bool(body[k])
    return await _call(request, "POST", "/exports", send, status=202)


@router.get("/api/exports/{export_id}")
async def export_status(request: Request, export_id: str):
    if not _NAME.match(export_id):
        return _bad("export id")
    return await _call(request, "GET", f"/exports/{quote(export_id, safe='')}")


@router.get("/api/exports/{export_id}/download")
async def export_download(request: Request, export_id: str):
    """The zip, once: the server hands it to the person who asked and then forgets it (a second call is the server's problem JSON)."""
    if not _NAME.match(export_id):
        return _bad("export id")
    try:
        upstream = await request.app.state.backend.open_download(f"/admin/collab/exports/{quote(export_id, safe='')}/download", ident(request))
    except BackendError as e:
        return _problem(e)
    return StreamingResponse(upstream.aiter_bytes(), media_type="application/zip", background=BackgroundTask(upstream.aclose),
                             headers={"Content-Disposition": f'attachment; filename="drishti-collab-export-{export_id}.zip"', "Cache-Control": "no-store"})


# ---- chain verification (compliance), retention dry run and bridges (admin) -------------------------------------------------------

@router.get("/api/verify")
async def verify(request: Request):
    q = request.query_params
    thread = q.get("thread") or ""
    if thread and not threads_core.clean_id(thread):
        return _bad("thread id")
    return await _call(request, "GET", "/verify", thread=thread, kind=q.get("kind"), id=q.get("id"))


@router.post("/api/retention")
async def retention(request: Request):
    """Counts what retention would remove; this page only ever asks for the dry run (the real run is the server's own schedule)."""
    return await _call(request, "POST", "/retention/run", dryRun="true")


@router.get("/api/bridges")
async def bridges(request: Request):
    return await _call(request, "GET", "/bridges")


@router.post("/api/bridges/{name}/test")
async def bridge_test(request: Request, name: str):
    if not _NAME.match(name):
        return _bad("bridge name")
    # the test waits for the bridge's own timeout (drishti.collab.bridges.timeout), so the console waits longer than it
    wait = float(request.app.state.settings.get("collab.bridge_test_timeout_seconds", 30))
    try:
        out = await request.app.state.backend.admin("POST", f"/collab/bridges/{quote(name, safe='')}/test", ident(request), timeout=wait)
    except BackendError as e:
        if e.code == "DRS-1004":                      # the console gave up first: say which wait ran out, not the sources' fetch timeout
            return JSONResponse({"code": "DRS-7013", "detail": f"bridge '{name}' did not answer within {wait:g} s; raise "
                                 "drishti.collab.bridges.timeout on the server (and collab.bridge_test_timeout_seconds in the console) or check the endpoint"},
                                status_code=504)
        return _problem(e)
    return JSONResponse(localise(out, *local_zone(request)))


@router.get("/api/outbox")
async def outbox(request: Request, state: str = "", limit: int = 100):
    """The mail outbox (pending with attempts and last error, dead letters): the server's ``GET /admin/collab/outbox`` (administrators)."""
    return await _call(request, "GET", "/outbox", state=state if _NAME.match(state or "x") else "", limit=max(1, min(limit, 500)))


@router.post("/api/outbox/{seq}/retry")
async def outbox_retry(request: Request, seq: int):
    return await _call(request, "POST", f"/outbox/{int(seq)}/retry")


@router.get("/api/hidden")
async def hidden(request: Request, after: str = "", limit: int = 50):
    """The hidden comments across threads (administrators and compliance), without opening each thread."""
    if after and not threads_core.clean_id(after):
        return _bad("thread id")
    return await _call(request, "GET", "/threads/hidden", after=after, limit=max(1, min(limit, 200)))


@router.post("/api/mail-test")
async def mail_test(request: Request):
    """A test mail to the signed-in administrator's own address (the server says why when email is off or fails)."""
    return await _call(request, "POST", "/mail-test")
