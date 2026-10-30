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

"""The client-side renderer's playground (docs/architecture/CLIENT_RENDERER.md section 13): ``/render/demo`` opens the current version's
static demo page, which renders a view of this console as the signed-in person. The library itself is static (``/static/render/<version>/``)."""
from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core import render_dist

router = APIRouter(include_in_schema=False)


@router.get("/render/demo")
async def demo(request: Request):
    query = ("?" + request.url.query) if request.url.query else ""
    return RedirectResponse(f"/static/render/{render_dist.VERSION}/demo.html{query}", status_code=302)
