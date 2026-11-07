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

"""The one Approvals queue (docs/architecture/SHARED_SCREENS.md section 2; USER_GUIDE.md "Approvals"): every four-eyes item waiting for
the signed-in person in one inbox, due-soon items pinned on top, the chosen item's diff, effect and rules, and approve, reject, withdraw or
ask a question. A shell: static/js/drishti_approvals.js fills it from ``/approvals/api/...``, which passes each call to the server's
``/api/v1/approvals`` as the signed-in person. The rules are the server's (who may decide, four eyes); this only draws them.

The per-kind review pages it replaced redirect here: Build → Reviews (Sutra proposals) and Review → Changes (corrections)."""
from __future__ import annotations

import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import sutra_diff
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)
KIND = re.compile(r"^[a-z][a-z0-9-]{0,31}$")
ITEM = re.compile(r"^[A-Za-z0-9._:@~+-]{1,200}$")
ACTIONS = ("approve", "reject", "withdraw", "questions")
BOXES = ("inbox", "sent", "done", "all")
GROUPS = ("", "data", "screens", "risk", "access")
LIMIT = 64 * 1024


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= e.status < 600 else 502)


def _bad(detail: str) -> JSONResponse:
    return JSONResponse({"code": "DRS-5001", "detail": detail}, status_code=400)


async def _answer(call):
    try:
        got = await call
    except BackendError as e:
        return _problem(e)
    return JSONResponse(got if got is not None else {})


def _page(request: Request, box: str = "inbox", group: str = "", kind: str = "", item_kind: str = "", item_id: str = ""):
    return render(request, "approvals.html", box=box if box in BOXES else "inbox", group=group if group in GROUPS else "",
                  kind=kind if KIND.match(kind or "x") and kind else "", item_kind=item_kind, item_id=item_id, screen="approvals",
                  command_text="")


@router.get("/approvals")
async def approvals(request: Request, box: str = "inbox", group: str = "", kind: str = ""):
    """The queue: inbox (waiting for me), sent (mine), done, or all; a filter (data, screens, risk, access) and one kind."""
    return _page(request, box, group, kind)


# ---- the JSON the page reads and writes ---------------------------------------------------------------------------------------------

@router.get("/approvals/api/list")
async def api_list(request: Request, box: str = "inbox", group: str = "", kind: str = ""):
    if box not in BOXES or group not in GROUPS or kind and not KIND.match(kind):
        return _bad("box is inbox, sent, done or all; group is data, screens, risk or access")
    return await _answer(request.app.state.backend.approvals_list(ident(request), box, group, kind))


@router.get("/approvals/api/count")
async def api_count(request: Request):
    """``{inbox, dueSoon}``: the number the top bar shows."""
    return await _answer(request.app.state.backend.approvals_count(ident(request)))


@router.get("/approvals/api/item/{kind}/{id_}")
async def api_item(request: Request, kind: str, id_: str):
    if not KIND.match(kind) or not ITEM.match(id_):
        return _bad("an item is named by its kind and id")
    return await _answer(request.app.state.backend.approvals_item(kind, id_, ident(request)))


@router.post("/approvals/api/item/{kind}/{id_}/{action}")
async def api_act(request: Request, kind: str, id_: str, action: str):
    """Approve, reject (a reason is required), withdraw, or ask a question (``questions``)."""
    if not KIND.match(kind) or not ITEM.match(id_) or action not in ACTIONS:
        return _bad("approve, reject, withdraw or ask about an item by its kind and id")
    try:
        body = await json_body(request, limit=LIMIT)
    except BodyError as e:
        return _bad(str(e))
    return await _answer(request.app.state.backend.approvals_act(kind, id_, action, body or {}, ident(request)))


@router.get("/approvals/api/view/sutra/{id_}")
async def sutra_view(request: Request, id_: str):
    """A Sutra proposal's diff with its moved panels and its evidence, as an HTML fragment for the item's "what changes"."""
    if not ITEM.match(id_):
        return _bad("a proposal is named by its id")
    try:
        opened = await request.app.state.backend.approvals_item("sutra", id_, ident(request))
    except BackendError as e:
        return _problem(e)
    facts = (opened.get("detail") or {}).get("facts") or {}
    before, after = facts.get("before") or "", facts.get("after") or ""
    return render(request, "approvals_sutra.html", diff=sutra_diff.review(before, after), before=before, new_version=facts.get("newVersion", True),
                  stale=facts.get("stale", False), p={"evidence": facts.get("evidence")})


# ---- the pages it replaced ------------------------------------------------------------------------------------------------------------

@router.get("/build/reviews")
async def old_reviews():
    return RedirectResponse("/approvals?kind=sutra", status_code=301)


@router.get("/build/reviews/{id_}")
async def old_review(id_: str):
    return RedirectResponse(f"/approvals/sutra/{quote(id_)}" if ITEM.match(id_) else "/approvals?kind=sutra", status_code=301)


@router.get("/review/changes")
async def old_changes():
    return RedirectResponse("/approvals?kind=change", status_code=301)


# ---- one item's page: last, so that /approvals/api/... is never taken for a kind and an id ------------------------------------------

@router.get("/approvals/{kind}/{id_}")
async def approval(request: Request, kind: str, id_: str):
    """The queue with one item open (the address a notice, a feature page or an old review page links to)."""
    if not KIND.match(kind) or not ITEM.match(id_):
        return RedirectResponse("/approvals", status_code=303)
    return _page(request, "all", "", "", kind, id_)
