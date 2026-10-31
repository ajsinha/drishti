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

"""Rupaka phase 6 (docs/guides/BI_GUIDE.md): the Python runner's routes. A python visual asks the console to run its Python item on the rows
the signed-in person may see: the console asks the server for what to run (``POST /api/v1/bi/reports/<id>/python``) and for the rows (the same
masked Arrow stream a Perspective visual reads), runs the item in a sandboxed worker (``core/pyrunner.py``) and answers a number per group or a
chart. Nothing here decides access: the server did, when it built the rows. The traceback of a failed run is shown to authors only."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from core.pyrunner import PyError, check_source
from core.pytests import limit_arrow, run_tests
from routes.common import ident

router = APIRouter(include_in_schema=False)


def _is_author(request: Request) -> bool:
    return "bi-author" in ((getattr(request.state, "bi_me", None) or {}).get("powers") or [])


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _short(columns: list[str]) -> list[str]:
    """``trade.desk`` as the rows name it: ``desk`` (the server names a python visual's columns as their table does)."""
    return [c.split(".", 1)[-1] for c in columns]


@router.get("/bi/python/status")
async def status(request: Request):
    """What the runner is: switched on, ready (pandas and pyarrow installed), its pool, allow-list and counters, and whether the browser target (Pyodide) is installed."""
    runner, calc = request.app.state.pyrunner, request.app.state.calc
    return JSONResponse({**runner.status(), "browser": calc.runtime.describe()}, headers={"Cache-Control": "no-store"})


@router.post("/bi/python/run")
async def run(request: Request):
    """Runs the Python item of one python visual on the server. Body ``{report, page, visual, state}``; answers ``{kind, ...}``: ``measure`` (``by``, ``rows``),
    ``echarts`` (``option``), ``svg`` or ``png``, plus ``ms`` and ``rows``. A failure is ``{code, detail}`` (and ``traceback`` for authors)."""
    body = await json_body(request)
    report, page, visual = str(body.get("report", "")), str(body.get("page", "")), str(body.get("visual", ""))
    if not report or not page or not visual:
        return JSONResponse({"code": "DRS-9070", "detail": "send {report, page, visual, state}"}, status_code=422)
    backend, me = request.app.state.backend, ident(request)
    path = "/reports/" + quote(report, safe=":")
    try:
        item = await backend.bi("POST", path + "/python", me, {"page": page, "visual": visual})
        content, headers = await backend.bi_arrow(path + "/query", me, {"page": page, "visual": visual, "state": body.get("state") or {}}, format="arrow")
    except BackendError as e:
        return _problem(e)
    measure = item.get("measure")
    group_by = _short(measure.get("by") or []) if measure else None
    manifest = item["manifest"]
    if measure:
        manifest = {**manifest, "inputs": _short(measure.get("inputs") or manifest.get("inputs") or [])}
    try:
        answer = await request.app.state.pyrunner.run({**item, "manifest": manifest}, content, group_by=group_by, params=item.get("params") or {})
    except PyError as e:
        out = {"code": e.code, "detail": e.detail}
        if e.traceback and _is_author(request):
            out["traceback"] = e.traceback
        return JSONResponse(out, status_code=e.status, headers={"Cache-Control": "no-store"})
    answer["measure"] = measure.get("name") if measure else None
    answer["item"] = item.get("id")
    answer["version"] = item.get("version")
    answer["target"] = "server"
    low = {str(k).lower(): v for k, v in headers.items()}
    answer["inputRows"] = int(low.get("x-bi-rows") or 0)
    answer["masked"] = [x for x in (low.get("x-bi-masked") or "").split(",") if x]
    return JSONResponse(answer, headers={"Cache-Control": "no-store"})


async def run_item_tests(request: Request, manifest: dict) -> dict:
    """Runs the tests an item ships with, in the same sandbox a report uses. A test that reads a dataset gets the rows the signed-in person may see."""
    async def sample(spec: dict) -> bytes:
        from routes.explore_routes import extract_for
        ex = await extract_for(request, {"dataset": spec["dataset"], "fields": spec["fields"]})
        return limit_arrow(ex.arrow or b"", int(spec.get("limit") or 200))
    return await run_tests(request.app.state.pyrunner, manifest, sample)


def _failures(result: dict, limit: int = 3) -> str:
    bad = [r for r in result["results"] if r["status"] != "pass"]
    return "; ".join(f"{r['name']}: {r['message']}" for r in bad[:limit]) + ("; ..." if len(bad) > limit else "")


async def publish_gate(request: Request, path: str, body: dict | None):
    """The refusal (a response) when ``path`` publishes a Python item whose own tests fail, else None. ``path`` is ``items/<id>/publish`` or ``items/<id>/approve``.
    The tests run in the console's sandbox, the only place with Python: a bi-admin may publish anyway with ``force``, as for the server's own tests."""
    backend, me = request.app.state.backend, ident(request)
    try:
        item = await backend.bi("GET", "/" + path.rsplit("/", 1)[0], me)
        if item.get("kind") != "python" or "tests:" not in (item.get("body") or ""):
            return None
        checked = await backend.bi("POST", "/python/validate", me, {"yaml": item.get("body") or ""})
    except BackendError:
        return None                                          # the server's own answer comes when the call is forwarded
    m = checked.get("manifest")
    if not checked.get("valid") or not m or not m.get("tests"):
        return None
    result = await run_item_tests(request, m)
    if result["ok"]:
        return None
    if (body or {}).get("force") is True:
        powers = (await request.app.state.bi_me.get(backend, me) or {}).get("powers") or []
        if "bi-admin" in powers:
            return None
    n = result["failed"] + result["errors"]
    return JSONResponse({"code": "DRS-9076", "detail": f"'{item.get('title')}' cannot be published: {n} of its {result['total']} tests {'fails' if n == 1 else 'fail'} ({_failures(result)}). "
                         "Fix the item or the test; a bi-admin may publish it anyway with force."}, status_code=422)


@router.post("/bi/python/check")
async def check(request: Request):
    """Checks a Python item's body: the server's manifest checks (DRS-9070), the console's source checks (it parses, defines its entry, imports only what it
    lists) and the tests the item ships with, run in the sandbox. Body ``{yaml, tests?}`` (``tests: false`` skips the tests); answers
    ``{valid, problems, manifest, tests?}``. Needs the BI power (the server decides)."""
    body = await json_body(request)
    try:
        answer = await request.app.state.backend.bi("POST", "/python/validate", ident(request), {"yaml": str(body.get("yaml", ""))})
    except BackendError as e:
        return _problem(e)
    problems = list(answer.get("problems") or [])
    m = answer.get("manifest")
    if m:
        problems += check_source(m, request.app.state.pyrunner.packages)
    answer["problems"] = problems
    answer["valid"] = not any(p.get("level") == "error" for p in problems)
    if answer["valid"] and m and m.get("tests") and body.get("tests", True) is not False:
        answer["tests"] = await run_item_tests(request, m)
    return JSONResponse(answer, headers={"Cache-Control": "no-store"})
