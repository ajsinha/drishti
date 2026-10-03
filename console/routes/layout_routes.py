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

"""Layout mode's same-origin JSON (USER_GUIDE.md, Layout mode): keep the user's arrangement of a view's panels, go back
to the Sutra's, and promote an arrangement to the next version of the Sutra (an author's proposal, reviewed as a Studio
save is). The server checks the user's roles and the Sutra's panels; the console forgets what it knew of the user's
layouts after each change, so the next view is drawn with the new one."""
from __future__ import annotations


from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.csrf import json_body
from core.backend import BackendError
from routes.common import ident
from core import sutra_diff

router = APIRouter(prefix="/api/layout", include_in_schema=False)
KEYS = ("id", "area", "span", "height", "hidden")


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _forget(request: Request) -> None:
    me = ident(request)
    request.app.state.layouts.forget(me.user if me else "")


async def _body(request: Request) -> dict:
    try:
        body = await json_body(request)
    except ValueError:
        body = {}
    return body if isinstance(body, dict) else {}


@router.put("/{sutra}/{kind}")
async def save(request: Request, sutra: str, kind: str):
    """Keeps the arrangement: ``{"panels": [{"id", "area", "span", "height", "hidden"}, …]}`` in display order."""
    body = await _body(request)
    panels = [{k: p[k] for k in KEYS if k in p} for p in body.get("panels") or [] if isinstance(p, dict)]
    try:
        kept = await request.app.state.backend.save_layout(sutra, kind, {"panels": panels}, ident(request))
    except BackendError as e:
        return _problem(e)
    _forget(request)
    return kept


@router.delete("/{sutra}/{kind}")
async def reset(request: Request, sutra: str, kind: str):
    """Back to the Sutra's layout (nothing to forget is not an error)."""
    try:
        await request.app.state.backend.reset_layout(sutra, kind, ident(request))
    except BackendError as e:
        if e.status != 404:
            return _problem(e)
    _forget(request)
    return {"ok": True}


@router.get("/{sutra}/{kind}/promotion")
async def promotion(request: Request, sutra: str, kind: str, dropHidden: bool = False):  # noqa: N803 - the query's name
    """What promoting the saved layout would propose: the changes in words, the panels it moves, and the diff against the
    latest version (``edits`` without the moves, ``diff`` the full line diff)."""
    try:
        p = await request.app.state.backend.layout_promotion(sutra, kind, dropHidden, ident(request))
    except BackendError as e:
        return _problem(e)
    d = sutra_diff.review(p.get("base") or "", p.get("text") or "")
    return {"sutra": p.get("sutra"), "fromVersion": p.get("fromVersion"), "version": p.get("version"), "review": p.get("review"),
            "changes": p.get("changes") or [], "moves": [m["text"] for m in d["moves"]], "edits": d["edits"], "diff": d["full"]}


@router.post("/{sutra}/{kind}/promotion")
async def promote(request: Request, sutra: str, kind: str):
    """Proposes it (``{"note": "…", "dropHidden": false}``): a proposal to review, or (review off) the new version, live."""
    body = await _body(request)
    try:
        r = await request.app.state.backend.promote_layout(sutra, kind, str(body.get("note") or "")[:500],
                                                            bool(body.get("dropHidden")), ident(request))
    except BackendError as e:
        return _problem(e)
    if r.get("proposal"):
        return {**r, "href": f"/build/reviews/{r['proposal'].get('id')}"}
    return r
