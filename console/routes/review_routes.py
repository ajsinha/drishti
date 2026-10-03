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

"""Sutra governance in the Build workbench (Govern → Reviews): the proposals waiting for an approver, one proposal with its diff,
and the decision (approve, reject, withdraw). These were Studio's pages (``/studio/reviews``, which redirect here); the rules are
the server's (author right to propose, approve right to decide, four eyes), this only draws them."""
from __future__ import annotations

from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core import sutra_diff
from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(prefix="/build", include_in_schema=False)


async def pending_count(request: Request) -> tuple[bool, int]:
    """(governance on, proposals waiting) for the signed-in user; (False, 0) when the server has no review step or says no."""
    backend, me = request.app.state.backend, ident(request)
    try:
        if not (await backend.studio_settings(me)).get("review"):
            return False, 0
        return True, len((await backend.proposals(me, status="pending")).get("proposals", []))
    except BackendError:
        return False, 0


@router.get("/review-count")
async def review_count(request: Request):
    """``{review, pending}``: the number the Govern → Reviews menu entry shows."""
    on, n = await pending_count(request)
    return {"review": on, "pending": n}


@router.get("/reviews")
async def reviews(request: Request, status: str = "pending"):
    """Sutra governance (W19): proposals waiting for review, or all of them."""
    try:
        data = await request.app.state.backend.proposals(ident(request), status="" if status == "all" else status)
    except BackendError as e:
        return render(request, "build/reviews.html", status_code=e.status, proposals=[], status=status, error=e, screen="build")
    return render(request, "build/reviews.html", proposals=data.get("proposals", []), status=status, error=None, screen="build")


@router.get("/reviews/{id_}")
async def review(request: Request, id_: str):
    try:
        p = await request.app.state.backend.proposal(id_, ident(request))
    except BackendError as e:
        return render(request, "build/reviews.html", status_code=e.status, proposals=[], status="pending", error=e, screen="build")
    return render(request, "build/review.html", p=p, diff=sutra_diff.review(await _compared(request, p), p.get("text") or ""), error=None, screen="build")


@router.post("/reviews/{id_}/{action}")
async def decide(request: Request, id_: str, action: str):
    if action not in ("approve", "reject", "withdraw"):
        return RedirectResponse(f"/build/reviews/{id_}", status_code=303)
    form = parse_qs((await request.body()).decode("utf-8", "replace"))
    try:
        await request.app.state.backend.decide(id_, action, ident(request), comment=form.get("comment", [""])[0][:500])
    except BackendError as e:
        p = await request.app.state.backend.proposal(id_, ident(request))
        return render(request, "build/review.html", status_code=e.status, p=p, diff=sutra_diff.review(await _compared(request, p), p.get("text") or ""),
                      error=e, screen="build")
    return RedirectResponse(f"/build/reviews/{id_}", status_code=303)


async def _compared(request: Request, p: dict) -> str:
    """What the review page shows a proposal against. Once approved, the live text IS the proposal, so a diff against it
    is empty (UX-15): an approved change to a live version is shown against the text it was proposed on, and an approved
    new version against the latest version before it."""
    if p.get("status") != "approved":
        return _against(p)
    if p.get("baseText"):
        return p["baseText"]
    backend, who = request.app.state.backend, ident(request)
    try:
        known = next((x for x in await backend.sutras(who) if x.get("name") == p.get("name")), None)
        earlier = max([v for v in (known or {}).get("versions", []) if v < int(p.get("version", 0))], default=None)
        return await backend.sutra_source(p["name"], earlier, who) if earlier is not None else ""
    except BackendError:
        return ""


def _against(p: dict) -> str:
    """What a proposal is compared with: the live text of its version, or for a new version the latest earlier one."""
    return p.get("liveText") or p.get("previousText") or ""
