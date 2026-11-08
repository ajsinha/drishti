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

"""Write-back, the console's side (docs/guides/APPROVALS_GUIDE.md): ``/api/changes`` passes each call to the server's ``/api/v1/changes``
as the signed-in person (static/js/drishti_write-back.js submits a form's draft after the review and confirm sheet); a change waiting for an
approver is decided in the one Approvals queue (routes/approval_routes.py); Admin → Edits shows where the edit log lives, its lanes,
warnings, source commands not confirmed and each connector's ``writes:`` with its switch. The rules are the server's (the edit power,
the field's roles, masks, four eyes); this only draws them."""
from __future__ import annotations

import re
from urllib.parse import parse_qs, quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)
CHANGE = re.compile(r"^chg-[0-9A-Z]{26}$")
NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
LIMIT = 256 * 1024


def _problem(e: BackendError) -> JSONResponse:
    body = {"code": e.code, "detail": e.detail}
    for k in ("issues", "warnings"):
        if isinstance(e.extra.get(k), list):
            body[k] = e.extra[k]
    return JSONResponse(body, status_code=e.status if 400 <= e.status < 600 else 502)


def _bad(detail: str, status: int = 400) -> JSONResponse:
    return JSONResponse({"code": "DRS-5001", "detail": detail}, status_code=status)


async def _call(request: Request, method: str, path: str, body=None, ok: int = 200):
    kw = {"json": body} if body is not None else {}
    try:
        got = await request.app.state.backend._send(method, path, ident(request), **kw)
    except BackendError as e:
        return _problem(e)
    return JSONResponse(got if got is not None else {}, status_code=ok)


@router.get("/api/changes/settings")
async def settings(request: Request):
    """``{enabled, mayEdit, approver, kinds}``: whether a form's Submit is drawn for this person."""
    return await _call(request, "GET", "/changes/settings")


@router.post("/api/changes/preview")
async def preview(request: Request):
    """The review and confirm sheet: the difference, where it goes, who approves, what it recomputes. Writes nothing."""
    try:
        body = await json_body(request, limit=LIMIT)
    except BodyError as e:
        return _bad(e.detail, e.status)
    return await _call(request, "POST", "/changes/preview", body)


@router.post("/api/changes")
async def submit(request: Request):
    """Submits a form's draft ``{draft, reason}`` (or a change ``{kind, entity, items, reason}``); ``202`` with one answer per change."""
    try:
        body = await json_body(request, limit=LIMIT)
    except BodyError as e:
        return _bad(e.detail, e.status)
    return await _call(request, "POST", "/changes", body, ok=202)


@router.get("/api/changes")
async def list_changes(request: Request):
    q = {k: v for k, v in request.query_params.items() if k in ("state", "kind", "entity", "mine", "limit") and v}
    return await _call(request, "GET", "/changes" + (("?" + urlencode(q)) if q else ""))


@router.get("/api/changes/{change}")
async def one(request: Request, change: str):
    if not CHANGE.match(change):
        return _bad("a change id is chg- and a ULID")
    return await _call(request, "GET", "/changes/" + change)


@router.get("/api/entities/{kind}/{id_}/history")
async def history(request: Request, kind: str, id_: str):
    if not NAME.match(kind):
        return _bad("a kind is lower case, digits and -")
    return await _call(request, "GET", f"/entities/{kind}/{quote(id_, safe='')}/history")


# ---- Admin → Edits --------------------------------------------------------------------------------------------------------------

@router.get("/admin/edits")
async def edits_page(request: Request):
    backend, me = request.app.state.backend, ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    try:
        status = await backend._send("GET", "/admin/edits", me)
        overlays = await backend._send("GET", "/admin/edits/overlays", me)
    except BackendError as e:
        return render(request, "admin/forbidden.html", status_code=e.page_status, error=e)
    return render(request, "admin/edits.html", s=status or {}, overlays=(overlays or {}).get("overlays", []), error=None, screen="admin")


@router.post("/admin/edits/connectors/{name}")
async def connector_writes(request: Request, name: str):
    """Switches a connector's writes on or off, with the reason (administrators; audited)."""
    if not NAME.match(name):
        return RedirectResponse("/admin/edits", status_code=303)
    form = parse_qs((await request.body()).decode("utf-8", "replace"))
    on = form.get("on", ["false"])[0] == "true"
    reason = form.get("reason", [""])[0][:500]
    try:
        await request.app.state.backend._send("POST", f"/admin/edits/connectors/{name}", ident(request), json={"on": on, "reason": reason})
    except BackendError as e:
        return render(request, "admin/edits.html", status_code=e.status, s={}, overlays=[], error=e, screen="admin")
    return RedirectResponse("/admin/edits", status_code=303)
