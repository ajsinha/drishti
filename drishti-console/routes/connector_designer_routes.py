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

"""Build -> New connector: the connector and binding designer (``/build/connector/designer``; docs/guides/ADMIN_GUIDE.md, "Connectors and bindings").

One workspace in six steps (type, settings, test, preview, bind, review and apply). The page is the one way to make a connector; it adds no path of its own
to the server: it reads and writes through Admin -> Connectors' API (``/admin/connectors/api/...``: plugins, render, validate, test, preview, put) and the
bindings service (``/admin/api/bindings/...``: propose, then a second administrator approves). The one call this router answers is the context the page opens
with: the installed types, the kinds, the connectors that exist and the bindings as they stand."""
from __future__ import annotations

import logging
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(prefix="/build/connector", include_in_schema=False)
LOG = logging.getLogger("drishti.console.build.connector")


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/designer")
async def page(request: Request):
    """The workspace: six steps with their summaries on the left. Administrators only (connectors and bindings are the site's)."""
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    return render(request, "build/connector_designer.html", me=me, screen="build", name=str(request.query_params.get("new") or ""))


@router.get("/api/context")
async def context(request: Request):
    """What the designer opens with and refreshes after an apply: the installed connector types (with their settings), the connector names that exist, the
    kinds a pack knows, and the bindings (every kind with its state and what serves it, the functions that are off, the proposals that wait)."""
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-2001", "detail": "administrators only"}, status_code=403)
    b = request.app.state.backend
    try:
        plugins = (await b.admin("GET", "/connectors/plugins", me)).get("plugins", [])
        listing = await b.admin("GET", "/connectors", me)
        bindings = await b.admin("GET", "/bindings", me)
    except BackendError as e:
        return _problem(e)
    return {"user": me.user, "plugins": plugins, "connectors": [c.get("name") for c in listing.get("connectors", [])], "knownKinds": listing.get("knownKinds", []),
            "directory": listing.get("directory"), "bindings": bindings}


@router.get("/api/suggestion/{name}")
async def suggestion(request: Request, name: str):
    """A pack's suggestion for a connector name (the old New connector's pre-fill, kept for the pack's ``?new=<name>`` links): its plugin and settings."""
    me = ident(request)
    if not me.is_admin:
        return JSONResponse({"code": "DRS-2001", "detail": "administrators only"}, status_code=403)
    b = request.app.state.backend
    try:
        s = await b.admin("GET", f"/connectors/{quote(name)}/suggestion", me)
        parsed = await b.admin("POST", f"/connectors/{quote(name)}/parse", me, {"text": s.get("text", "")})
    except BackendError as e:
        return _problem(e)
    return {"pack": s.get("pack"), **parsed}
