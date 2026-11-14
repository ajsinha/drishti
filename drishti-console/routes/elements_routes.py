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
"""The Drishti Elements gallery (docs/architecture/ELEMENTS.md section 10): ``/elements`` (and ``/elements/gallery``) open the static
gallery page, ``static/gallery/drishti_gallery.html``, with the address's query kept (``?mode=docs&c=pivot&tab=api``). The page is the
same file a developer opens from the file system: it needs no server. ``/elements`` is a Build page: the edition gate answers 404 when the
build feature is off for the person (core/edition.py FEATURE_PATHS), and Build → Learn lists it (config/sitemap.yaml)."""
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

router = APIRouter(include_in_schema=False)

#: the gallery page, under the console's static root
PAGE = "/static/gallery/drishti_gallery.html"


@router.get("/elements")
@router.get("/elements/gallery")
async def gallery(request: Request) -> RedirectResponse:
    """The gallery page, its query kept (the gallery reads its mode, component, tab and options from the address)."""
    query = ("?" + request.url.query) if request.url.query else ""
    return RedirectResponse(PAGE + query, status_code=302)


@router.get("/api/elements/visuals")
async def module_visuals(request: Request) -> JSONResponse:
    """The visual types the hosted modules bring (the custom-visual SDK, ELEMENTS.md section 7.4): each kind with its module, title and the
    library file that registers it in drishti-chart's registry. The gallery loads them and shows a page per kind; a module that is off
    or removed (or off for this person) brings none. Nothing of any person is kept."""
    from core import modules as _modules

    parts = request.app.state.module_parts
    return JSONResponse({"visuals": [v for v in parts.visuals() if _modules.module_on(v["module"])],
                         "modules": _modules.modules_for_page(None)}, headers={"Cache-Control": "no-store"})
