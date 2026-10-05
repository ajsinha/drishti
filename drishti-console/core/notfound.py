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

"""An unknown console URL: the console's own 404 page for a page, an RFC 7807-style problem for /api (UX-11)."""
from __future__ import annotations

from fastapi.responses import JSONResponse

from routes.common import render


async def not_found(request, exc):
    """Handles a 404 raised for no route; a route that raised its own 404 keeps its own detail."""
    detail = getattr(exc, "detail", None)
    own = detail not in (None, "", "Not Found")
    if request.url.path.startswith("/api/"):
        return JSONResponse({"code": "DRS-1001", "detail": detail if own else f"no such console route: {request.url.path}"},
                            status_code=404)
    return render(request, "terminal/notfound.html", status_code=404, path=request.url.path, detail=detail if own else "")
