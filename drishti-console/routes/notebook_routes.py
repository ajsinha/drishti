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

"""The Notebook (``/bi/notebook``; docs/guides/USER_GUIDE.md › The Notebook) and the console's routes behind it: the one place for code over data.

A person opens a model (or one report visual's data) as a **masked extract**: the rows the server lets *them* see, cut to ``bi.notebook.max_rows``.
**SQL cells** are Drishti SQL, run by the server over the model as that person; **Python cells** run on the server's compute pool (``POST
/api/v1/bi/python/cell``: the server reads the extracts as that person and runs the cell in its strict sandbox). The console runs no code of the person's and
no SQL: it builds the extract, profiles it (no author code), keeps the notebook body and writes promotion drafts. Slicing is the Pivot's
(``/pivot``); the Analyse page's Slice, its SQL over the extract in SQLite and the browser Python console were cut, and their addresses lead here.
Nothing is kept between calls: every call rebuilds the extract for the person asking, so a mask or a row rule changed on the server applies on the
next run. Nothing here decides access; the server did, building the rows."""
from __future__ import annotations

import ast
import time
from pathlib import Path
from urllib.parse import quote, urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from core.csrf import json_body
from core.notebook import (NotebookError, Extract, Limits, check_extracts, check_fields, dsql_answer, dsql_request, from_arrow, from_json, model_ref,
                           parse_notebook, model_of, mq_rows, promote_python, promote_transform)
from core.promote_report import add_to_report, sql_to_slice, visual_for
from routes.bi_routes import QUERY_WAIT_SECONDS, _page
from routes.common import ident
from routes.python_routes import RUN_WAIT_SECONDS, problem

router = APIRouter(include_in_schema=False)
CONSOLE = Path(__file__).resolve().parent.parent
NO_STORE = {"Cache-Control": "no-store"}


def _limits(request: Request) -> Limits:
    return request.app.state.notebook_limits


def _fail(e: BackendError | NotebookError) -> JSONResponse:
    if isinstance(e, BackendError):
        return problem(e)
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status, headers=NO_STORE)


def _powers(request: Request) -> list[str]:
    return list((getattr(request.state, "bi_me", None) or {}).get("powers") or [])


async def _python(request: Request) -> dict:
    """The server's Python runtime: ``{runs, why, packages, ...}`` (runs false, with why, when it cannot run code safely or cannot be asked)."""
    try:
        return await request.app.state.backend.bi("GET", "/python/runtime", ident(request))
    except BackendError as e:
        return {"runs": False, "why": e.detail, "packages": []}


async def extract_for(request: Request, spec: dict, max_rows: int | None = None) -> Extract:
    """The extract ``spec`` names, built by the server for the signed-in person: ``{model|dataset, fields, filters?, dates?}`` or ``{report, page, visual, state}``.
    ``max_rows`` cuts it instead of ``bi.notebook.max_rows``."""
    limits, backend, me = _limits(request), request.app.state.backend, ident(request)
    try:
        import pyarrow  # noqa: F401
        arrow = True
    except ImportError:
        arrow = False
    top = max_rows or limits.max_rows
    cap = top if arrow else min(top, 5000)                                  # without pyarrow the server's JSON answer (json-max-rows) carries it
    if spec.get("report"):
        page, visual = str(spec.get("page", "")), str(spec.get("visual", ""))
        if not page or not visual:
            raise NotebookError("DRS-9070", 422, "send {report, page, visual} to open a visual's data")
        if not arrow:
            raise NotebookError("DRS-9074", 503, "opening a visual's data needs pyarrow on the console (pip install -r drishti-console/requirements.txt)")
        content, headers = await backend.bi_arrow("/reports/" + quote(str(spec["report"]), safe=":") + "/query", me,
                                                  {"page": page, "visual": visual, "state": spec.get("state") or {}}, format="arrow")
        low = {str(k).lower(): v for k, v in headers.items()}
        return from_arrow(content, cap, [x for x in (low.get("x-bi-masked") or "").split(",") if x])
    model = str(spec.get("model") or "") or model_of(str(spec.get("dataset") or ""))
    if not model:
        raise NotebookError("DRS-9070", 422, "send {model, fields}, {dataset, fields} or {report, page, visual}")
    mq = mq_rows(model, check_fields(spec.get("fields"), limits), cap, spec.get("filters"), spec.get("dates"))
    if arrow:
        content, headers = await backend.bi_arrow("/mq", me, mq, format="arrow")
        low = {str(k).lower(): v for k, v in headers.items()}
        return from_arrow(content, cap, [x for x in (low.get("x-bi-masked") or "").split(",") if x])
    return from_json(await backend.bi("POST", "/mq", me, mq, wait=QUERY_WAIT_SECONDS, format="json"), cap)


@router.get("/bi/notebook")
async def page(request: Request):
    limits = _limits(request)
    py = await _python(request)
    return _page(request, "bi/notebook.html", "notebook", limits=limits, may_python="bi-author" in _powers(request), python_runs=bool(py.get("runs")),
                 python_why=py.get("why") or "")


@router.get("/bi/explore")
async def old_explore(request: Request):
    """The Analyse page's old address: the Notebook (a notebook's ``?item=`` is kept)."""
    q = {k: v for k, v in request.query_params.items() if k == "item"}
    return RedirectResponse("/bi/notebook" + ("?" + urlencode(q) if q else ""), status_code=308)


@router.get("/pyconsole")
async def old_pyconsole(request: Request):
    """The browser Python console's old address: Python over a masked extract is the Notebook's, on the server."""
    return RedirectResponse("/bi/notebook", status_code=308)


@router.get("/bi/notebook/status")
async def status(request: Request):
    """What the Notebook offers this person: its limits and whether Python cells run (on the server's compute pool) for them."""
    limits = _limits(request)
    return JSONResponse({"limits": {k: v for k, v in vars(limits).items()},
                         "python": {**{k: v for k, v in (await _python(request)).items() if k in ("runs", "why", "packages")}, "where": "server",
                                    "mayRun": "bi-author" in _powers(request)}}, headers=NO_STORE)


@router.post("/bi/notebook/extract")
async def extract(request: Request):
    """Body ``{dataset, fields, filters?, dates?}`` or ``{report, page, visual, state}``: the reader's rows as ``{columns[{name, type, masked}], rows, rowCount, total, truncated, masked}``."""
    spec = await json_body(request)
    try:
        return JSONResponse((await extract_for(request, spec)).as_json(), headers=NO_STORE)
    except (BackendError, NotebookError) as e:
        return _fail(e)


@router.post("/bi/notebook/profile")
async def profile(request: Request):
    """Body ``{extract: {...}}``: the profile of the reader's extract (per column its type, what its text looks like, nulls, distinct values, a key hint, min
    and max, the most common values, a histogram), no author code; the same panel the Transform editor shows. ``{kind: profile, rows, columns, inputRows, masked}``."""
    body = await json_body(request)
    spec = body.get("extract") if isinstance(body.get("extract"), dict) else {}
    try:
        out = await request.app.state.backend.bi("POST", "/python/profile", ident(request), {"extract": spec, "limit": _limits(request).max_rows}, wait=RUN_WAIT_SECONDS)
    except BackendError as e:
        return _fail(e)
    return JSONResponse(out, headers=NO_STORE)


@router.post("/bi/notebook/python")
async def python(request: Request):
    """Body ``{extract, extracts?, source, params?}``: one Python cell, run on the server's compute pool over ``df`` (the extract, read there as the signed-in
    person) and ``extract("<name>")`` (the notebook's other extracts). Answers ``{kind: cell, stdout, table|option|svg|png|value, ms, inputRows, masked}``;
    a failure is ``{code, detail, traceback}`` (the traceback is the author's own code only). Needs bi-author (the server decides)."""
    body = await json_body(request)
    limits = _limits(request)
    source = str(body.get("source", ""))
    if not source.strip() or len(source) > limits.cell_max_chars:
        return _fail(NotebookError("DRS-9072", 413, f"a cell holds 1 to {limits.cell_max_chars:,} characters (bi.notebook.cell_max_chars)"))
    try:
        extras = check_extracts(body.get("extracts") or [], limits)
    except NotebookError as e:
        return _fail(e)
    ask = {"source": source, "extract": body.get("extract") if isinstance(body.get("extract"), dict) else {}, "extracts": extras, "limit": limits.max_rows,
           "timeout": limits.cell_timeout, "memory": limits.cell_memory, "params": body.get("params") if isinstance(body.get("params"), dict) else {}}
    try:
        out = await request.app.state.backend.bi("POST", "/python/cell", ident(request), ask, wait=RUN_WAIT_SECONDS)
    except BackendError as e:
        return _fail(e)
    return JSONResponse(out, headers=NO_STORE)


def _sql_name(request: Request) -> str:
    """The server's SQL by the product's name from configuration (ui.product): "<product> SQL"."""
    return f"{request.app.state.settings.get('ui.product', '')} SQL".strip()


@router.post("/bi/notebook/sql")
async def sql(request: Request):
    """Body ``{extract: {dataset, ...}, sql}``: one Drishti SQL statement over the model the extract reads (HOW_IT_FITS.md §A28 3.4), run by the server as the
    signed-in person (their row rules and masks), the cell's row limit applied; ``{columns, rows, rowCount, truncated, ms, engine: server, plan, masked}``.
    The console never reads the SQL: the server parses, binds and plans it (the same plan as the model query it is equal to)."""
    body = await json_body(request)
    limits = _limits(request)
    spec = body.get("extract") if isinstance(body.get("extract"), dict) else {}
    ref = model_ref(str(spec.get("dataset") or body.get("model") or ""))
    if not ref:
        return _fail(NotebookError("DRS-9070", 422, f"{_sql_name(request)} reads a model: open a model or a dataset first"))
    t0 = time.monotonic()
    try:
        out = await request.app.state.backend.bi("POST", "/sql", ident(request), dsql_request(str(body.get("sql", "")), ref, limits), wait=QUERY_WAIT_SECONDS)
    except (BackendError, NotebookError) as e:
        return _fail(e)
    return JSONResponse({**dsql_answer(out, limits, round((time.monotonic() - t0) * 1000)), "model": ref}, headers=NO_STORE)


async def _packages(request: Request) -> list[str]:
    """The packages a Python item may list: the server's pinned allow-list (pandas alone when the server cannot be asked)."""
    return list((await _python(request)).get("packages") or ["pandas"])


def cell_packages(source: str, allowed: list[str]) -> list[str]:
    """The allow-listed packages a cell imports (named in a promoted item's manifest)."""
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        raise NotebookError("DRS-9070", 422, f"line {e.lineno}: {e.msg}") from None
    found = []
    for n in ast.walk(tree):
        names = [a.name for a in n.names] if isinstance(n, ast.Import) else [n.module or ""] if isinstance(n, ast.ImportFrom) and n.level == 0 else []
        for name in names:
            top = name.split(".")[0]
            if top in allowed and top not in found:
                found.append(top)
    if "pandas" not in found:
        found.append("pandas")
    return found


@router.post("/bi/notebook/check")
async def notebook_check(request: Request):
    """Body ``{yaml}`` or ``{notebook}``: checks a notebook body before it is saved as a ``notebook`` item. Answers ``{valid, problems, cells, notebook, yaml, notes}``:
    the notebook as data and as the YAML to store (the page holds no YAML library, so it reads and writes the body through this route); ``notes`` name
    the cells of a cut kind an older notebook held (a slice), left out."""
    import yaml as _yaml
    body = await json_body(request)
    text = str(body.get("yaml", "")) if "yaml" in body else _yaml.safe_dump(body.get("notebook") or {}, sort_keys=False, default_flow_style=None, width=200, allow_unicode=True)
    try:
        doc = parse_notebook(text, _limits(request))
    except NotebookError as e:
        return JSONResponse({"valid": False, "problems": [{"level": "error", "code": e.code, "message": e.detail}], "cells": 0}, headers=NO_STORE)
    notes = doc.pop("notes", [])
    return JSONResponse({"valid": True, "problems": [], "cells": len(doc.get("cells") or []), "notebook": doc, "yaml": text, "notes": notes}, headers=NO_STORE)


@router.post("/bi/notebook/promote")
async def promote(request: Request):
    """Body ``{to: measure|visual|transform, name, source, columns}`` (a Python cell to a Python item draft) or ``{to: report, name, dataset, sql, columns}`` (a SQL
    cell to a report visual). Answers ``{kind, title, yaml, notes}``; the page saves it as a draft item in the person's workspace. Nothing is published."""
    body = await json_body(request)
    limits, to = _limits(request), str(body.get("to", ""))
    name = str(body.get("name") or "From the Notebook")[:80]
    try:
        if to in ("measure", "visual"):
            source = str(body.get("source", ""))
            if not source.strip() or len(source) > limits.cell_max_chars:
                raise NotebookError("DRS-9070", 422, "the cell is empty or too long to promote")
            cols = [str(c) for c in body.get("columns") or []][:limits.max_fields]
            text = promote_python(source, name, to, cols, cell_packages(source, await _packages(request)))
            notes = ["The cell's df became the function's table; a measure must return one number and a visual an ECharts option, SVG or PNG.",
                     "Check it (Check the Python on the item page), then publish. A measure also needs a dataset measure that names it."]
            return JSONResponse({"kind": "python", "title": name, "yaml": text, "notes": notes}, headers=NO_STORE)
        if to == "transform":
            source = str(body.get("source", ""))
            if not source.strip() or len(source) > limits.cell_max_chars:
                raise NotebookError("DRS-9070", 422, "the cell is empty or too long to promote")
            text = promote_transform(source, name, check_extracts(body.get("extracts") or [], limits), cell_packages(source, await _packages(request)))
            notes = ["Each extract(\"name\") became a parameter of run(); the cell's result is returned and must be a pandas DataFrame with usable column names.",
                     "Check it (Check the Python on the item page) and publish it, then make a dataset of its table: source: { python: <this item>, inputs: { <name>: <a model query> } }, "
                     "one input per parameter of run(), in this order (the dataset wizard's source A Python Transform; HOW_IT_FITS.md §A28 › The model layer: the specification of wave W1, section 1.2)."]
            return JSONResponse({"kind": "python", "title": name, "yaml": text, "notes": notes}, headers=NO_STORE)
        if to == "report":
            return JSONResponse(await _sql_report(request, body, name), headers=NO_STORE)
        raise NotebookError("DRS-9070", 422, "to is measure, visual, transform or report")
    except (BackendError, NotebookError) as e:
        return _fail(e)


async def _sql_report(request: Request, body: dict, name: str) -> dict:
    """A SQL cell as a visual (core/promote_report.py reads it) in a new report draft, or added to the existing report draft ``target``.
    Body: ``{dataset, sql, columns}``; ``columns``/``qualified`` map the extract's short names to table.column; ``target`` (an item id) and ``page`` say
    where in an existing report."""
    import re
    import yaml
    dataset, sql = str(body.get("dataset") or ""), body.get("sql")
    if not dataset or not isinstance(sql, str):
        raise NotebookError("DRS-9070", 422, "send {dataset, sql, columns}")
    if len(sql) > _limits(request).sql_max_chars:
        raise NotebookError("DRS-9070", 422, "the SQL is too long to promote")
    backend, me = request.app.state.backend, ident(request)
    ref = model_of(dataset)
    described = await backend.bi("GET", "/models/" + quote(ref, safe=":@") + "/describe", me)
    model = {"tables": [{"name": d.get("alias")} for d in described.get("datasets") or []], "measures": described.get("measures") or []}
    shorts, quals = [str(c) for c in body.get("columns") or []], [str(c) for c in body.get("qualified") or []]
    mapping = dict(zip(shorts, quals))
    qualify = lambda f: mapping.get(f, f)  # noqa: E731
    notes: list[str] = []
    sl = sql_to_slice(sql, shorts)
    notes += sl.pop("notes")
    vid = "sql"
    visual = visual_for(model, sl, name, vid, qualify, notes)
    if visual is None:
        raise NotebookError("DRS-9070", 422, "the SQL has no fields or measures to build a visual from")
    target = str(body.get("target") or "")
    if not target:
        slug = re.sub(r"-{2,}", "-", re.sub(r"[^a-z0-9_-]", "-", name.lower())).strip("-")[:48].strip("-") or "from-the-notebook"          # a readable id: "trades-the-basics-sql"
        report = {"rachana": 2, "report": slug, "version": 1, "title": name, "description": "Started from a Notebook SQL cell.",
                  "model": ref, "pages": [{"id": vid, "title": "SQL", "visuals": [visual]}]}
        return {"kind": "report", "title": name, "yaml": yaml.safe_dump(report, sort_keys=False, default_flow_style=None, width=200), "notes": notes, "target": None}
    item = await backend.bi("GET", "/items/" + quote(target, safe=":"), me)
    if item.get("kind") != "report" or not (item.get("can") or {}).get("edit"):
        raise NotebookError("DRS-9070", 422, "the target is not a report draft you can edit")
    checked = await backend.bi("POST", "/reports/validate", me, {"yaml": item.get("body") or ""})
    if not checked.get("report"):
        raise NotebookError("DRS-9070", 422, "the report cannot be read, so a visual cannot be added: " + "; ".join(p.get("message", "") for p in (checked.get("problems") or [])[:2]))
    try:
        report = add_to_report(checked["report"], visual, str(body.get("page") or ""), ref, notes)
    except ValueError as e:
        raise NotebookError("DRS-9070", 422, str(e)) from None
    written = await backend.bi("POST", "/reports/format", me, {"report": report})
    notes.append("The report was written again by the designer's writer: comments in its YAML are not kept.")
    return {"kind": "report", "title": item.get("title") or name, "yaml": written["yaml"], "notes": notes, "target": target, "visual": visual["id"]}
