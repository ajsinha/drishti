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

"""RUPAKA PHASE 0 PROOF OF CONCEPT (docs/architecture/RUPAKA_POC.md): the /bi/poc page and the same-origin routes it uses.

Throw-away code that tests the risky parts of the BI design: Perspective on a live feed, a masked Arrow extract sliced by
DuckDB-Wasm in the browser, the strict CSP with WebAssembly. Included only when ``bi.poc_enabled`` is true (the server must
have ``drishti.bi.poc.enabled`` too); otherwise none of these paths exist and they answer 404."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

PERSPECTIVE = "/static/vendor/perspective/3.8.0"
DUCKDB_FOLDER = "vendor/duckdb-wasm"
ARROW = "application/vnd.apache.arrow.stream"
KEPT_HEADERS = ("x-poc-rows", "x-poc-layout", "x-poc-masked", "server-timing")


def duckdb_base(static: Path) -> str:
    """The URL folder of the installed DuckDB-Wasm (tools/fetch-duckdb-wasm.sh), or '' when it is not installed."""
    for stamp in sorted((static / DUCKDB_FOLDER).glob("*/.drishti-duckdb-wasm")):
        folder = stamp.parent
        if all((folder / n).exists() for n in ("duckdb-browser.mjs", "duckdb-browser-eh.worker.js", "duckdb-eh.wasm")):
            return f"/static/{DUCKDB_FOLDER}/{folder.name}"
    return ""


@router.get("/bi/poc")
async def page(request: Request):
    from core.app import WEB

    return render(request, "bi/poc.html", screen="bi-poc", perspective=PERSPECTIVE, duckdb=duckdb_base(WEB / "static"))


@router.post("/bi/poc/query")
async def query(request: Request):
    """The server's masked aggregation as Arrow IPC bytes, passed through unchanged (with its timing and mask headers)."""
    body = await json_body(request)
    try:
        content, headers = await request.app.state.backend.poc_query(body, ident(request))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)
    kept = {k: v for k, v in headers.items() if k.lower() in KEPT_HEADERS}
    kept["Cache-Control"] = "no-store"
    return Response(content, media_type=ARROW, headers=kept)


@router.get("/bi/poc/bench")
async def bench(request: Request, runs: int = 30):
    try:
        return await request.app.state.backend.poc_bench(max(1, min(runs, 500)), ident(request))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status)
