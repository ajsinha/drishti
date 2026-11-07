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
from fastapi.responses import RedirectResponse

router = APIRouter(include_in_schema=False)

#: the gallery page, under the console's static root
PAGE = "/static/gallery/drishti_gallery.html"


@router.get("/elements")
@router.get("/elements/gallery")
async def gallery(request: Request) -> RedirectResponse:
    """The gallery page, its query kept (the gallery reads its mode, component, tab and options from the address)."""
    query = ("?" + request.url.query) if request.url.query else ""
    return RedirectResponse(PAGE + query, status_code=302)
