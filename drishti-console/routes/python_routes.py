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

"""Python items in the console (docs/guides/REPORTS_GUIDE.md › Python in BI): the page's calls, passed to the server, where every Python run happens (the
compute pool ``python``, docs/guides/NOTEBOOK_GUIDE.md section 19). The console runs no Python of anyone's and reads no rows for it: the server
reads them as the signed-in person and runs the code in its strict sandbox. A failure is ``{code, detail}``, with the author's own ``traceback`` when
the server gave one (it does only for authors)."""
from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident

router = APIRouter(include_in_schema=False)
NO_STORE = {"Cache-Control": "no-store"}
#: A Python run waits for its pool and its own time limit: the server's wait, not the console's usual one.
RUN_WAIT_SECONDS = 180


def problem(e: BackendError) -> JSONResponse:
    """The server's refusal as the page reads it: ``{code, detail, traceback?}``."""
    out = {"code": e.code, "detail": e.detail}
    tb = (getattr(e, "extra", None) or {}).get("traceback")
    if tb:
        out["traceback"] = tb
    return JSONResponse(out, status_code=e.page_status, headers=NO_STORE)


async def server(request: Request, method: str, path: str, body: dict | None = None) -> JSONResponse:
    """One call of ``/api/v1/bi/python/<path>`` as the signed-in person, its answer passed back unchanged."""
    try:
        out = await request.app.state.backend.bi(method, "/python/" + path, ident(request), body, wait=RUN_WAIT_SECONDS)
    except BackendError as e:
        return problem(e)
    return JSONResponse(out, headers=NO_STORE)


@router.get("/bi/python/status")
async def status(request: Request):
    """Whether Python runs on this server (and why not), the pool, the pinned packages and the counters."""
    return await server(request, "GET", "runtime")


@router.post("/bi/python/run")
async def run(request: Request):
    """Body ``{report, page, visual, state}``: one python visual, run on the server over the rows the person may see. Answers ``{kind, ...}``: ``measure``
    (``by``, ``rows``), ``drishti-chart`` (``spec``), ``echarts`` (``option``), ``vega-lite`` (``spec``), ``svg`` or ``png``, plus ``ms``, ``inputRows`` and ``masked``."""
    body = await json_body(request)
    if not body.get("report") or not body.get("page") or not body.get("visual"):
        return JSONResponse({"code": "DRS-9070", "detail": "send {report, page, visual, state}"}, status_code=422)
    return await server(request, "POST", "visual", {k: body.get(k) for k in ("report", "page", "visual", "state")})


@router.post("/bi/python/check")
async def check(request: Request):
    """Body ``{yaml, tests?}``: the manifest's problems, the source's (parsed on the server, not run) and the item's own tests, run on the pool."""
    body = await json_body(request)
    return await server(request, "POST", "check", {"yaml": str(body.get("yaml", "")), "tests": body.get("tests", True) is not False})
