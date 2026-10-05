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

"""Share with a note, the console's pages (COLLABORATION.md, build step 3): the share dialog's calls (``/api/collab``,
``/api/directory``, ``/api/share``), the link a share opens (``/share/{id}``, with the clean no-access page) and the inbox
(``/inbox``, ``/api/inbox``). The server decides who may do what; these routes carry the request and draw what comes back."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core import asof, collab as collab_core
from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, local_zone, localise, problem, render

router = APIRouter(include_in_schema=False)

# what the inbox page filters by: the tab, and the server's row type it means
TABS = (("all", "All", ""), ("share", "Shares", "share"), ("mention", "Mentions", "mention"), ("reply", "Replies", "reply"))


def collab(request: Request):
    """The collaboration client: a test may put its own on ``app.state.collab``."""
    return getattr(request.app.state, "collab", None) or collab_core.Collab(request.app.state.backend)


def _problem(e: BackendError) -> JSONResponse:
    return problem(e)


@router.get("/api/collab")
async def config(request: Request, kind: str = ""):
    """Whether collaboration is on and what the caller may do (the share dialog reads this once; ``kind`` adds whether a picture may be offered)."""
    try:
        return await (collab(request).config(ident(request), kind[:80]) if kind else collab(request).config(ident(request)))
    except BackendError as e:
        if e.status in (404, 503):                       # an older server, or none: the share button stays a copy-link button
            return {"enabled": False, "collaborate": False}
        return _problem(e)


@router.get("/api/directory")
async def directory(request: Request, q: str = "", kind: str = "", limit: int = 10):
    try:
        return await collab(request).directory(q, ident(request), max(1, min(limit, 25)), kind)
    except BackendError as e:
        return _problem(e)


@router.post("/api/share")
async def share(request: Request):
    """Sends a share. The page's own date travels in the body (a pinned link set no cookie), so the server pins what the sender sees."""
    body = await json_body(request)
    as_of, known = body.pop("asOf", None), body.pop("knownAt", None)
    asof.set_current(as_of)
    asof.set_known(known if asof.current() != "live" else None)
    try:
        return JSONResponse(await collab(request).send(body, ident(request)), status_code=201)
    except BackendError as e:
        return _problem(e)


def _png(data: bytes) -> Response:
    return Response(data, media_type="image/png", headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})


@router.post("/api/share/preview-picture")
async def share_preview_picture(request: Request):
    """The picture a share would carry for the chosen people (the dialog's preview): the PNG, or the server's refusal as JSON."""
    body = await json_body(request)
    as_of, known = body.pop("asOf", None), body.pop("knownAt", None)
    asof.set_current(as_of)
    asof.set_known(known if asof.current() != "live" else None)
    try:
        return _png(await collab(request).preview_picture(body, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.get("/api/share/{share_id}/picture")
async def share_picture(request: Request, share_id: str):
    """The watermarked picture of a share, for its sender, recipients and compliance (the server checks)."""
    sid = collab_core.clean_share(share_id)
    if sid is None:
        return JSONResponse({"code": "DRS-7001", "detail": "no such share"}, status_code=404)
    try:
        return _png(await collab(request).picture(sid, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/api/share/{share_id}/replies")
async def share_reply(request: Request, share_id: str):
    """A reply to a share, from its sender or a person it reached; the server tells the other party."""
    sid = collab_core.clean_share(share_id)
    if sid is None:
        return JSONResponse({"code": "DRS-7001", "detail": "no such share"}, status_code=404)
    body = await json_body(request)
    try:
        return JSONResponse(localise(await collab(request).reply(sid, str(body.get("note") or ""), ident(request)), *local_zone(request)), status_code=201)
    except BackendError as e:
        return _problem(e)


def _denied(request: Request, s: dict | None, status: int, packs_on: list[str] | None = None):
    titles = {p["name"]: p.get("title") or p["name"] for p in getattr(request.state, "pack_switcher", [])}
    return render(request, "terminal/share_denied.html", status_code=status, share=s, screen="share-denied",
                  active_packs=packs_on or [], pack_title=titles.get((s or {}).get("pack"), (s or {}).get("pack")))


@router.get("/share/{share_id}")
async def open_share(request: Request, share_id: str):
    """What a share's link does: open the view as it was pinned, or say, cleanly, that this person cannot (no title, no
    figures, nothing about what the entity holds)."""
    sid = collab_core.clean_share(share_id)
    if sid is None:
        return _denied(request, None, 404)
    try:
        s = await collab(request).share(sid, ident(request))
    except BackendError as e:
        if e.code == "DRS-7001" or e.status == 404:      # a stranger learns nothing: not even that the share exists
            return _denied(request, None, 404)
        return render(request, "terminal/missing.html", status_code=e.page_status, kind="share", id=sid, error=e)
    if s.get("access"):
        return RedirectResponse(collab_core.link_for(s), status_code=303)
    on = [p["name"] for p in getattr(request.state, "pack_switcher", []) if p.get("active")]
    return _denied(request, s, 403, on)


@router.get("/inbox")
async def inbox(request: Request, tab: str = "all", unread: int = 0, before: int = 0):
    me = ident(request)
    chosen = next((t for t in TABS if t[0] == tab), TABS[0])
    error, rows = "", []
    try:
        rows = await collab(request).inbox(me, chosen[2], bool(unread), 50, before)
    except BackendError as e:
        error = e.detail
    for r in rows:
        r["href"] = _row_href(r)
    return render(request, "inbox.html", rows=rows, tabs=TABS, tab=chosen[0], unread=bool(unread), error=error, screen="inbox",
                  more=(rows[-1]["seq"] if len(rows) >= 50 else 0))


def _row_href(r: dict) -> str:
    """Where a row goes: a share through its link (which checks the right to open), anything else to the entity it is about."""
    if r.get("shareId"):
        return f"/share/{quote(r['shareId'], safe='')}"
    if r.get("access") and r.get("kind") and r.get("id"):
        return f"/v/{quote(r['kind'], safe='')}/{quote(r['id'], safe='')}" + (f"#p-{quote(r['panel'], safe='')}" if r.get("panel") else "")
    return ""


@router.get("/api/inbox/count")
async def inbox_count(request: Request):
    try:
        return {"unread": await collab(request).unread(ident(request))}
    except BackendError:
        return {"unread": 0}


@router.post("/api/inbox/read")
async def inbox_read(request: Request):
    body = await json_body(request)
    send = {k: body[k] for k in ("seqs", "upTo") if k in body}
    try:
        return await collab(request).mark_read(send, ident(request))
    except BackendError as e:
        return _problem(e)
