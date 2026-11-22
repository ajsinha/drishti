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

"""Role Homes, the console's side (docs/architecture/ROLE_HOME_SUTRAS.md): ``/home/role`` opens the signed-in person's Home. The server
decides which Homes the person is offered (their role, the modules on, the active mnemonics) and serves the Home's Sutra (compiled with the
administrator's lock) and each home table as the person (row rules and masks applied there); this passes them to the page and the
person's own choice of start Home back. No figure is computed here and no panel is drawn here: the page hands the Sutra and its tables to
the Drishti Elements renderer (static/js/drishti_renderer.js), which draws the fragments; ``arrange`` is the Sutra's
``user-can-arrange`` as the server compiled it, and the arrangement itself is ``/me/arrange/<sutra>`` (routes/terminal_routes.py)."""
from __future__ import annotations

import asyncio
import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)
NAME = re.compile(r"^[a-z][a-z0-9-]{1,63}$")
TABLE = re.compile(r"table:\s*(home_[a-z_]+)")


async def _get(request: Request, path: str):
    return await request.app.state.backend._send("GET", path, ident(request))


@router.get("/home/role")
async def start(request: Request):
    """The person's start Home, or the public landing when no Home is offered to them."""
    try:
        got = await _get(request, "/homes")
    except BackendError:
        return RedirectResponse("/", status_code=303)
    name = (got or {}).get("start")
    return RedirectResponse(f"/home/role/{quote(name)}" if name else "/", status_code=303)


@router.get("/home/role/{name}")
async def home(request: Request, name: str):
    if not NAME.match(name):
        return RedirectResponse("/home/role", status_code=303)
    try:
        offered = await _get(request, "/homes")
        page = await _get(request, f"/homes/{quote(name)}")
        status = await _get(request, f"/homes/{quote(name)}/status")
        wanted = sorted(set(TABLE.findall(page.get("sutra") or "")))
        rows = await asyncio.gather(*[_get(request, f"/homes/{quote(name)}/tables/{t}") for t in wanted])
    except BackendError as e:
        if e.status in (403, 404):                                  # not offered to this person: their own Home, or the landing
            return RedirectResponse("/home/role", status_code=303)
        raise
    tables = {t: (r or {}).get("rows") or [] for t, r in zip(wanted, rows)}
    return render(request, "role_home.html", home=page, homes=(offered or {}).get("homes") or [], picker=bool((offered or {}).get("picker")),
                  status=status, tables=tables)


@router.put("/api/home/me")
async def choose(request: Request):
    """The person's own start Home (their setting ``home``); the server refuses a Home they are not offered."""
    try:
        body = await json_body(request, 1024)
    except BodyError as e:
        return JSONResponse({"code": "DRS-5001", "detail": str(e)}, status_code=400)
    try:
        got = await request.app.state.backend._send("PUT", "/homes/me", ident(request), json={"home": str(body.get("home") or "")})
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= e.status < 600 else 502)
    return JSONResponse(got or {})
