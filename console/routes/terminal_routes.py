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

"""The terminal: home, command dispatch (<GO>) and entity views."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core.backend import BackendError
from routes.common import ident, packs, render

router = APIRouter(include_in_schema=False)



@router.get("/t")
async def home(request: Request, error: str | None = None):
    current = await packs(request)
    examples = [(cmd, what, p["title"]) for p in current for cmd, what in p["examples"]]
    return render(request, "terminal/home.html", examples=examples, packs=current, error=error)


@router.get("/go")
async def go(request: Request, q: str = ""):
    try:
        r = await request.app.state.backend.command(q, ident(request))
    except BackendError as e:
        return RedirectResponse(f"/t?error={quote(e.detail)}", status_code=303)
    return RedirectResponse(f"/v/{r['ref']['kind']}/{quote(r['ref']['id'])}", status_code=303)


@router.get("/impact/{kind}/{id_}")
async def impact(request: Request, kind: str, id_: str):
    try:
        data = await request.app.state.backend.impact(kind, id_, ident(request))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.status if e.status < 500 else 502, kind=kind, id=id_, error=e)
    return render(request, "terminal/impact.html", kind=kind, id=id_, data=data, screen="impact")


@router.get("/v/{kind}/{id_}")
async def view(request: Request, kind: str, id_: str, embed: int = 0):
    try:
        vm = await request.app.state.backend.view(kind, id_, ident(request))
    except BackendError as e:
        return render(request, "terminal/missing.html", status_code=e.status if e.status < 500 else 502,
                      kind=kind, id=id_, error=e, embed=bool(embed))
    main = [p for p in vm["panels"] if p.get("area") != "right"]
    right = [p for p in vm["panels"] if p.get("area") == "right"]
    return render(request, "terminal/view.html", vm=vm, main=main, right=right, embed=bool(embed))
