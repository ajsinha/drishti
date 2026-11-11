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
"""One Explain for every number (HOW_IT_FITS.md section S3; USER_GUIDE.md, "Explain"). The console only shows: the
explanation is the server's (``/api/v1/explain``), asked as the person, so their entitlements, row rules and masks apply.

- ``GET /api/explain?kind=&measure=&part=&...`` and ``POST /api/explain`` (a pivot cell sends its view): the same-origin proxy that the
  preview card and the drawer (drishti-insight: web/static/elements/drishti-insight/src/drishti_insight-explain.js) read;
- ``GET /api/explain/kinds``: the kinds that can be explained on this server;
- ``GET /explain?kind=&...&tab=&body=``: the full page, all four parts at once, for an auditor or for printing; ``body`` is the JSON
  object a number asked with a body (a pivot cell's view) carries, so the page asks exactly what the drawer asked."""
from __future__ import annotations

import json

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

#: the page's own parameter, never sent to the server
PAGE_ONLY = ("tab",)


def _problem(e: BackendError) -> JSONResponse:
    from core.busy import problem as busy_problem

    return busy_problem(e, e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/api/explain/kinds")
async def kinds(request: Request):
    try:
        return JSONResponse(await request.app.state.backend.explain_number("GET", "/kinds", ident(request)), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/api/explain")
async def explain(request: Request):
    """One number's explanation (the parts asked), as the person; every parameter is passed on."""
    params = {k: v for k, v in request.query_params.items() if k not in PAGE_ONLY}
    try:
        return JSONResponse(await request.app.state.backend.explain_number("GET", "", ident(request), **params), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.post("/api/explain")
async def explain_body(request: Request):
    """The same with a body (a pivot cell's view); a body that is not a JSON object is a 400/415 before the server is asked."""
    body = await json_body(request)
    if not isinstance(body, dict):
        return JSONResponse({"code": "DRS-5001", "detail": "the body is a JSON object: {kind, measure, part, ...}"}, status_code=400)
    try:
        return JSONResponse(await request.app.state.backend.explain_number("POST", "", ident(request), body=body), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/explain")
async def page(request: Request):
    """The full page of one number's explanation: the request is the link's parameters (``tab`` scrolls to a part)."""
    q = request.query_params
    req = {k: v for k, v in q.items() if k not in PAGE_ONLY and k != "body"}
    if q.get("body"):
        try:
            body = json.loads(q["body"])
        except ValueError:
            body = None
        if not isinstance(body, dict):
            return JSONResponse({"code": "DRS-5001", "detail": "body is a JSON object: the view a pivot cell is explained with"}, status_code=400)
        req["body"] = body
    return render(request, "explain.html", screen="explain", explain_request=json.dumps(req), explain_tab=q.get("tab", ""),
                  back=request.headers.get("referer", ""))
