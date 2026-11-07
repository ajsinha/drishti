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

"""One workbook streamer for every server workbook the console hands on (docs/architecture/DOMAIN_MODULES.md section 5, C4): the
playgrounds' workbooks of any module, the capacity planner, why VaR moved. The server's .xlsx is passed through as it streams, as the
person, with its file name; a refusal answers the server's code and words."""
from __future__ import annotations

from urllib.parse import urlencode

from fastapi import Request
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.background import BackgroundTask

from core.backend import BackendError
from routes.common import ident

XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


async def stream(request: Request, path: str, params: dict, fallback: str):
    """The server's workbook at ``path`` (from /api/v1, with ``params``), streamed to the browser; ``fallback``: the file name when the
    server names none."""
    try:
        upstream = await request.app.state.backend.open_download(path + ("?" + urlencode(params) if params else ""), ident(request))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)
    disposition = upstream.headers.get("content-disposition") or f'attachment; filename="{fallback}"'
    return StreamingResponse(upstream.aiter_bytes(), media_type=XLSX, background=BackgroundTask(upstream.aclose),
                             headers={"Content-Disposition": disposition, "Cache-Control": "no-store"})
