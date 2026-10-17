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

"""The Py-node endpoint (docs/architecture/BI_DESIGN.md sections 3.5 and 4.3, wave B): the Java planner places a plan's Python nodes (``PyTransform``,
``PyAggregate``, ``PyColumn``) on the console tier and calls these routes; the console runs the item in its fork-server sandbox (``core/pyrunner.py``)
and answers. The server sends rows it already built for one reader (masked, row-filtered); the console never asks the server for anything here.

    POST /internal/py/transform   {manifest, params, inputs: [base64 Arrow IPC], max_rows, max_bytes}
        200 the frame as Parquet (application/vnd.apache.parquet), headers X-Drishti-Rows, X-Drishti-Ms, X-Drishti-Columns ([{name, type}])
    POST /internal/py/measure     {manifest, params, group_by: [names], columns: [names], rows: [[...]]}
        200 {ok: true, by, rows: [[group values..., value]], ms, cached}
    POST /internal/py/column      {manifest, params, columns: [names], rows: [[...]]}
        200 {ok: true, type, values: [one per row], ms, cached}
    a refusal (any)               {ok: false, code: DRS-9070..9076, status, detail} (JSON)

Every call carries ``X-Drishti-Runner-Token``, the shared secret of ``bi.python.runner_token`` (the server's ``drishti.bi.transform.console-token``; one
variable, ``DRISHTI_BI_TRANSFORM_CONSOLE_TOKEN``, sets both). Without a configured token the endpoint is off (every call ``DRS-9074``); a wrong or missing
token is ``DRS-9073``. No session, cookie or user: the token is the only way in, and the routes are outside the console's signed-in paths."""
from __future__ import annotations

import base64
import hmac
import json

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core import tmpdir
from core.pyrunner import PyError
from core.pytests import arrow_of

router = APIRouter(include_in_schema=False)
MAX_BODY = 256 * 1024 * 1024


def refuse(code: str, status: int, detail: str) -> JSONResponse:
    return JSONResponse({"ok": False, "code": code, "status": status, "detail": detail}, status_code=status, headers={"Cache-Control": "no-store"})


async def _body(request: Request):
    """The call's JSON when its token is right; else the refusal to answer with."""
    runner = request.app.state.pyrunner
    if not runner.runner_token:
        return None, refuse("DRS-9074", 503, "the console's Py-node endpoint is off: set bi.python.runner_token (DRISHTI_BI_TRANSFORM_CONSOLE_TOKEN) on the console and the server")
    sent = request.headers.get("x-drishti-runner-token", "")
    if not hmac.compare_digest(sent.encode(), runner.runner_token.encode()):
        return None, refuse("DRS-9073", 403, "the Py-node call did not carry the runner token (X-Drishti-Runner-Token)")
    if not (request.headers.get("content-type") or "").startswith("application/json"):
        return None, refuse("DRS-9070", 415, "send JSON")
    raw = await request.body()
    if len(raw) > MAX_BODY:
        return None, refuse("DRS-9072", 413, f"the call is over {MAX_BODY:,} bytes")
    try:
        body = json.loads(raw)
    except ValueError:
        return None, refuse("DRS-9070", 400, "the body is not JSON")
    if not isinstance(body, dict) or not isinstance(body.get("manifest"), dict) or not body["manifest"].get("source"):
        return None, refuse("DRS-9070", 422, "send {manifest: {name, interface, source, ...}, params, ...}")
    return body, None


def _failed(e: PyError) -> JSONResponse:
    return refuse(e.code, e.status, e.detail)


def _rows(body: dict) -> tuple[list[str], list[list]]:
    cols = [str(c) for c in body.get("columns") or []]
    rows = body.get("rows") or []
    if not isinstance(rows, list) or any(not isinstance(r, list) or len(r) != len(cols) for r in rows):
        raise PyError("DRS-9070", 422, "rows must be lists as long as columns")
    return cols, rows


@router.post("/internal/py/measure")
async def measure(request: Request):
    body, bad = await _body(request)
    if bad:
        return bad
    m = {**body["manifest"], "interface": "measure"}
    try:
        cols, rows = _rows(body)
        answer = await request.app.state.pyrunner.run({"manifest": m}, arrow_of(cols, rows), group_by=[str(x) for x in body.get("group_by") or []],
                                                       params=body.get("params") or {})
    except PyError as e:
        return _failed(e)
    return JSONResponse({"ok": True, "by": answer.get("by") or [], "rows": answer.get("rows") or [], "ms": answer.get("ms"), "cached": bool(answer.get("cached"))},
                        headers={"Cache-Control": "no-store"})


@router.post("/internal/py/column")
async def column(request: Request):
    body, bad = await _body(request)
    if bad:
        return bad
    m = {**body["manifest"], "interface": "column"}
    try:
        cols, rows = _rows(body)
        answer = await request.app.state.pyrunner.run({"manifest": m}, arrow_of(cols, rows), params=body.get("params") or {})
    except PyError as e:
        return _failed(e)
    return JSONResponse({"ok": True, "type": answer.get("type"), "values": answer.get("values") or [], "ms": answer.get("ms"), "cached": bool(answer.get("cached"))},
                        headers={"Cache-Control": "no-store"})


@router.post("/internal/py/transform")
async def transform(request: Request):
    body, bad = await _body(request)
    if bad:
        return bad
    m = {**body["manifest"], "interface": "transform"}
    runner = request.app.state.pyrunner
    try:
        inputs = [base64.b64decode(x, validate=True) for x in body.get("inputs") or []]
    except (ValueError, TypeError):
        return refuse("DRS-9070", 422, "inputs are base64 Arrow IPC streams")
    folder = tmpdir.mkdtemp("drishti-pynode-", dir=runner.scratch)
    dest = f"{folder}/out.parquet"
    try:
        answer = await runner.run_transform(m, inputs, params=body.get("params") or {}, dest=dest, max_rows=int(body.get("max_rows") or 100_000))
        with open(dest, "rb") as f:
            data = f.read()
        limit = int(body.get("max_bytes") or 0)
        if limit and len(data) > limit:
            return refuse("DRS-9072", 413, f"the frame is {len(data):,} bytes; the limit is {limit:,} (drishti.bi.transform.max-bytes)")
    except PyError as e:
        return _failed(e)
    finally:
        import shutil
        shutil.rmtree(folder, ignore_errors=True)
    return Response(data, media_type="application/vnd.apache.parquet",
                    headers={"X-Drishti-Rows": str(answer.get("rows") or 0), "X-Drishti-Ms": str(answer.get("ms") or 0),
                             "X-Drishti-Columns": json.dumps(answer.get("columns") or []), "Cache-Control": "no-store"})
