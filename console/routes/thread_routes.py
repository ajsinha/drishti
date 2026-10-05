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

"""Comment threads, the console's calls (COLLABORATION.md, build step 6): what the Discussion tab of the side drawer asks. The server
decides every right (who may read, write, edit, hide); these routes carry the request, add the *Open as it was* link to each comment and
turn the server's problems into the console's JSON problem form."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import asof, threads as threads_core
from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, local_zone, localise

router = APIRouter(include_in_schema=False)


def threads(request: Request):
    """The thread client: a test may put its own on ``app.state.threads``."""
    return getattr(request.app.state, "threads", None) or threads_core.Threads(request.app.state.backend)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)


def _page_pin(body: dict) -> dict:
    """The page's own date travels in the body (a pinned link sets no cookie), so the server pins what the writer sees."""
    as_of, known = body.pop("asOf", None), body.pop("knownAt", None)
    asof.set_current(as_of)
    asof.set_known(known if asof.current() != "live" else None)
    return body


def _bad_id() -> JSONResponse:
    return JSONResponse({"code": "DRS-5001", "detail": "not a valid id"}, status_code=400)


@router.get("/api/threads/{kind}/{id_:path}")
async def thread_list(request: Request, kind: str, id_: str, anchor: str = "", panel: str = "", path: str = "", state: str = "", limit: int = 0,
                      before: str = ""):
    try:
        return localise(await threads(request).list(kind, id_, ident(request), anchor=anchor, panel=panel, path=path, state=state,
                                                    limit=limit or None, before=before), *local_zone(request))
    except BackendError as e:
        return _problem(e)


@router.get("/api/thread-counts/{kind}/{id_:path}")
async def thread_counts(request: Request, kind: str, id_: str):
    try:
        return await threads(request).counts(kind, id_, ident(request))
    except BackendError as e:                          # an older server, or none: no badges, and the page works as it did
        return {"entity": 0, "panels": {}, "fields": {}} if e.status in (404, 503) else _problem(e)


@router.post("/api/threads/{kind}/{id_:path}")
async def thread_start(request: Request, kind: str, id_: str):
    body = _page_pin(await json_body(request))
    try:
        out = await threads(request).start(kind, id_, body, ident(request))
        threads_core.annotate([{"kind": kind, "entityId": id_, "panel": body.get("panel"), "items": [out.get("comment") or {}]}])
        return JSONResponse(localise(out, *local_zone(request)), status_code=201)
    except BackendError as e:
        return _problem(e)


@router.post("/api/thread/{tid}/comments")
async def thread_reply(request: Request, tid: str):
    if not threads_core.clean_id(tid):
        return _bad_id()
    body = _page_pin(await json_body(request))
    try:
        return JSONResponse(localise(await threads(request).reply(tid, body, ident(request)), *local_zone(request)), status_code=201)
    except BackendError as e:
        return _problem(e)


@router.post("/api/thread/{tid}/state")
async def thread_state(request: Request, tid: str):
    body = await json_body(request)
    if not threads_core.clean_id(tid):
        return _bad_id()
    try:
        return await threads(request).state(tid, str(body.get("state") or ""), ident(request))
    except BackendError as e:
        return _problem(e)


@router.put("/api/thread/{tid}/follow")
async def thread_follow(request: Request, tid: str):
    body = await json_body(request)
    if not threads_core.clean_id(tid):
        return _bad_id()
    try:
        return await threads(request).follow(tid, bool(body.get("muted")), ident(request))
    except BackendError as e:
        return _problem(e)


@router.delete("/api/thread/{tid}/follow")
async def thread_unfollow(request: Request, tid: str):
    if not threads_core.clean_id(tid):
        return _bad_id()
    try:
        await threads(request).unfollow(tid, ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)


@router.patch("/api/comment/{cid}")
async def comment_edit(request: Request, cid: str):
    body = await json_body(request)
    if not threads_core.clean_id(cid):
        return _bad_id()
    try:
        return await threads(request).edit(cid, {"body": body.get("body"), "revision": body.get("revision")}, ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/comment/{cid}/retract")
async def comment_retract(request: Request, cid: str):
    if not threads_core.clean_id(cid):
        return _bad_id()
    try:
        return await threads(request).retract(cid, ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/api/comment/{cid}/revisions")
async def comment_revisions(request: Request, cid: str):
    if not threads_core.clean_id(cid):
        return _bad_id()
    try:
        return localise(await threads(request).revisions(cid, ident(request)), *local_zone(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/comment/{cid}/hide")
async def comment_hide(request: Request, cid: str):
    body = await json_body(request)
    if not threads_core.clean_id(cid):
        return _bad_id()
    try:
        return await threads(request).hide(cid, str(body.get("reason") or ""), ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/api/comment/{cid}/unhide")
async def comment_unhide(request: Request, cid: str):
    if not threads_core.clean_id(cid):
        return _bad_id()
    try:
        return await threads(request).unhide(cid, ident(request))
    except BackendError as e:
        return _problem(e)
