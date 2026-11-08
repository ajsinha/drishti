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

"""Wave C (2.4.0), the Transform editor (docs/guides/REPORTS_GUIDE.md › Python in BI: measures, visuals and data prep written in Python, section 17; docs/architecture/ARCHITECTURE.md §28 section 11.1, items P1 to P3 and P5): the page at ``/bi/transform`` and its routes.

A person builds a Python Transform step by step without typing YAML: inputs (a pack kind read through a connector, or a model query), then steps picked
from the snippets library or written by hand. After every change the page asks ``POST /bi/transform/steps``: the console asks the server for each input
**as that person** (their kind entitlements, row rules and masks; cut to ``bi.transform.preview_rows``) and runs ``run`` on its compute pool (``POST
/api/v1/bi/python/steps``) once with the frame after each step described (rows, columns, first rows, a profile) and answers it. Saving writes two drafts in the person's
workspace (the Python item and the Python dataset that runs it); nothing is published. Running steps needs ``bi-author``, as any Python of a person's own."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from core.prep_snippets import SnippetError, SnippetLibrary
from core.transform_editor import EditorError, compose, parse
from routes.bi_routes import _page
from routes.common import ident
from routes.python_routes import RUN_WAIT_SECONDS, problem

router = APIRouter(include_in_schema=False)
CONSOLE = Path(__file__).resolve().parent.parent


class EditorSettings:
    """``bi.transform.*``: the rows each input starts from, the step run's limits, the snippets library."""

    def __init__(self, settings):
        def num(key, default, lo, hi):
            try:
                return max(lo, min(hi, int(settings.get("bi.transform." + key, default))))
            except (TypeError, ValueError):
                return default
        self.preview_rows = num("preview_rows", 1000, 10, 50_000)
        self.timeout = num("timeout_seconds", 20, 1, 120)
        self.memory = num("memory_mb", 512, 64, 4096)
        extra = str(settings.get("bi.transform.snippets_file", "") or "")
        files = [CONSOLE / "config" / "prep-snippets.yaml"] + ([Path(extra) if Path(extra).is_absolute() else CONSOLE / extra] if extra else [])
        self.snippets = SnippetLibrary(files)


def _editor(request: Request) -> EditorSettings:
    e = getattr(request.app.state, "transform_editor", None)
    if e is None:
        e = request.app.state.transform_editor = EditorSettings(request.app.state.settings)
    return e


def _author(request: Request) -> bool:
    return "bi-author" in ((getattr(request.state, "bi_me", None) or {}).get("powers") or [])


def _refuse(code: str, status: int, detail: str, **more) -> JSONResponse:
    return JSONResponse({"code": code, "detail": detail, **more}, status_code=status, headers={"Cache-Control": "no-store"})


@router.get("/bi/transform")
async def page(request: Request):
    e = _editor(request)
    return _page(request, "bi/transform.html", "transform", may_python=_author(request), preview_rows=e.preview_rows)


@router.get("/bi/transform/snippets")
async def snippets(request: Request):
    """The snippets library: ``{snippets: [{id, group, title, description, params[{name, label, kind, options?, default?}]}]}``."""
    return JSONResponse({"snippets": _editor(request).snippets.list()}, headers={"Cache-Control": "no-store"})


@router.post("/bi/transform/snippet")
async def snippet(request: Request):
    """Body ``{id, params, inputs}``: the step that snippet makes with these values, ``{label, code, snippet}`` (each value written as a literal)."""
    body = await json_body(request)
    try:
        step = _editor(request).snippets.render(str(body.get("id") or ""), body.get("params") or {}, [str(x) for x in body.get("inputs") or []])
    except SnippetError as e:
        return _refuse("DRS-9070", 422, str(e))
    return JSONResponse(step, headers={"Cache-Control": "no-store"})


@router.post("/bi/transform/compose")
async def compose_route(request: Request):
    """Body ``{spec}``: the spec checked, and the Python item's and the Python dataset's YAML it is saved as (``{spec, source, python, dataset}``)."""
    body = await json_body(request)
    e = _editor(request)
    try:
        return JSONResponse(compose(body.get("spec") or {}, e.timeout, e.memory), headers={"Cache-Control": "no-store"})
    except EditorError as x:
        return _refuse("DRS-9070", 422, str(x))


@router.post("/bi/transform/parse")
async def parse_route(request: Request):
    """Body ``{python, dataset?}`` (YAML): the spec the editor opens, the steps from the source's ``# step:`` comments. The YAML tab's way back to steps."""
    body = await json_body(request)
    try:
        return JSONResponse({"spec": parse(str(body.get("python") or ""), str(body.get("dataset") or ""))}, headers={"Cache-Control": "no-store"})
    except EditorError as x:
        return _refuse("DRS-9070", 422, str(x))


@router.post("/bi/transform/steps")
async def steps(request: Request):
    """Body ``{spec, asOf?}``: the step-by-step run, on the server: each input read there as the signed-in person (masked, cut to the preview rows), then
    ``run`` runs once on the compute pool. Answers ``{inputs, keys, steps, planned, complete, failed?}`` with each input's ``masked`` and ``asOf`` and the
    times. A step's own error is in ``failed`` (with its traceback for authors); a refusal before any step is ``{code, detail}``."""
    if not _author(request):
        return _refuse("DRS-9073", 403, "running a Transform's steps runs Python: it needs the bi-author power")
    body = await json_body(request)
    e = _editor(request)
    try:
        c = compose(body.get("spec") or {}, e.timeout, e.memory)
    except EditorError as x:
        return _refuse("DRS-9070", 422, str(x))
    spec = c["spec"]
    ask = {"name": spec["name"], "source": c["source"], "params": spec["params"], "limit": e.preview_rows, "timeout": e.timeout, "memory": e.memory,
           "inputs": [{"name": i["name"], "input": i["input"]} for i in spec["inputs"]]}
    if body.get("asOf"):
        ask["asOf"] = str(body["asOf"])[:10]
    try:
        answer = await request.app.state.backend.bi("POST", "/python/steps", ident(request), ask, wait=RUN_WAIT_SECONDS)
    except BackendError as x:
        return problem(x)
    answer["source"] = c["source"]
    return JSONResponse(answer, headers={"Cache-Control": "no-store"})
