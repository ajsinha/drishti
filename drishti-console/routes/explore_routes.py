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

"""Rupaka phase 9, Analyse (docs/guides/ANALYSE.md): the page at ``/bi/explore`` and the console's routes behind it.

A person opens a dataset (or one report visual's data) as a **masked extract**: the rows the server lets *them* see, cut to ``bi.explore.max_rows``.
Slice (a pivot) runs in the page on that extract; SQL runs here, over the extract only, read-only and limited; Python cells run in the sandboxed worker of
``core/pyrunner.py`` (or in the browser, Pyodide) with ``df`` (the extract) and ``quant``. Nothing is kept between calls: every call rebuilds the extract
for the person asking, so a mask or a row rule changed on the server applies on the next run. Nothing here decides access; the server did, building the rows."""
from __future__ import annotations

import asyncio
import ast
import time
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from core.explore import (ExploreError, Extract, Limits, check_extracts, check_fields, dsql_answer, dsql_request, from_arrow, from_json, model_ref, parse_notebook,
                          model_of, mq_rows, promote_python, promote_transform, run_sql)
from core.promote_report import add_to_report, sql_to_slice, visual_for
from core.pyrunner import PyError
from core.quant import get_engine
from routes.bi_routes import _page
from routes.common import ident

router = APIRouter(include_in_schema=False)
CONSOLE = Path(__file__).resolve().parent.parent
_SQL_SLOTS = asyncio.Semaphore(4)


def _limits(request: Request) -> Limits:
    return request.app.state.explore_limits


def _fail(e: BackendError | ExploreError | PyError, author: bool = False) -> JSONResponse:
    out = {"code": e.code, "detail": e.detail}
    if isinstance(e, PyError) and e.traceback and author:
        out["traceback"] = e.traceback
    return JSONResponse(out, status_code=e.page_status if isinstance(e, BackendError) else e.status, headers={"Cache-Control": "no-store"})


def _powers(request: Request) -> list[str]:
    return list((getattr(request.state, "bi_me", None) or {}).get("powers") or [])


async def extract_for(request: Request, spec: dict) -> Extract:
    """The extract ``spec`` names, built by the server for the signed-in person: ``{dataset, fields, filters?, dates?}`` or ``{report, page, visual, state}``."""
    limits, backend, me = _limits(request), request.app.state.backend, ident(request)
    try:
        import pyarrow  # noqa: F401
        arrow = True
    except ImportError:
        arrow = False
    cap = limits.max_rows if arrow else min(limits.max_rows, 5000)          # without pyarrow the server's JSON answer (json-max-rows) carries it
    if spec.get("report"):
        page, visual = str(spec.get("page", "")), str(spec.get("visual", ""))
        if not page or not visual:
            raise ExploreError("DRS-9070", 422, "send {report, page, visual} to open a visual's data")
        if not arrow:
            raise ExploreError("DRS-9074", 503, "opening a visual's data needs pyarrow on the console (pip install -r drishti-console/requirements.txt)")
        content, headers = await backend.bi_arrow("/reports/" + quote(str(spec["report"]), safe=":") + "/query", me,
                                                  {"page": page, "visual": visual, "state": spec.get("state") or {}}, format="arrow")
        low = {str(k).lower(): v for k, v in headers.items()}
        return from_arrow(content, cap, [x for x in (low.get("x-bi-masked") or "").split(",") if x])
    model = str(spec.get("model") or "") or model_of(str(spec.get("dataset") or ""))
    if not model:
        raise ExploreError("DRS-9070", 422, "send {model, fields}, {dataset, fields} or {report, page, visual}")
    mq = mq_rows(model, check_fields(spec.get("fields"), limits), cap, spec.get("filters"), spec.get("dates"))
    if arrow:
        content, headers = await backend.bi_arrow("/mq", me, mq, format="arrow")
        low = {str(k).lower(): v for k, v in headers.items()}
        return from_arrow(content, cap, [x for x in (low.get("x-bi-masked") or "").split(",") if x])
    return from_json(await backend.bi("POST", "/mq", me, mq, format="json"), cap)


async def extras_for(request: Request, specs) -> list[tuple[dict, Extract]]:
    """The other extracts of a notebook (``[{name, dataset, fields}]``), each built by the server for the signed-in person, as the first is."""
    return [(s, await extract_for(request, {"dataset": s["dataset"], "fields": s["fields"]})) for s in check_extracts(specs or [], _limits(request))]


async def primary_for(request: Request, spec, extras: list[tuple[dict, Extract]]) -> Extract:
    """The extract ``df`` and the table ``extract`` are: the one named, else (a notebook that only joins) the first of the others."""
    if spec:
        return await extract_for(request, spec)
    if extras:
        return extras[0][1]
    raise ExploreError("DRS-9070", 422, "send {extract: {dataset, fields}} or {extracts: [{name, dataset, fields}]}")


DUCKDB_FOLDER = "vendor/duckdb-wasm"


def duckdb_base(static: Path) -> str:
    """The URL folder of the installed DuckDB-Wasm (tools/fetch-duckdb-wasm.sh), or '' when it is not installed."""
    for stamp in sorted((static / DUCKDB_FOLDER).glob("*/.drishti-duckdb-wasm")):
        folder = stamp.parent
        if all((folder / n).exists() for n in ("duckdb-browser.mjs", "duckdb-browser-eh.worker.js", "duckdb-eh.wasm")):
            return f"/static/{DUCKDB_FOLDER}/{folder.name}"
    return ""


@router.get("/bi/explore")
async def page(request: Request):
    limits = _limits(request)
    duck = "" if limits.browser_engine == "server" else duckdb_base(CONSOLE / "web" / "static")
    return _page(request, "bi/explore.html", "explore", limits=limits, may_python="bi-author" in _powers(request),
                 quant=get_engine(limits.quant_engine).describe(), duckdb=duck)


@router.get("/bi/explore/status")
async def status(request: Request):
    """What Analyse offers this person: its limits, whether Python cells can run (server and browser) and the quant engine's instruments."""
    limits, runner, calc = _limits(request), request.app.state.pyrunner, request.app.state.calc
    return JSONResponse({"limits": {k: v for k, v in vars(limits).items()}, "python": {**runner.status(), "browser": calc.runtime.describe(),
                                                                                       "mayRun": "bi-author" in _powers(request)},
                         "quant": get_engine(limits.quant_engine).describe()}, headers={"Cache-Control": "no-store"})


@router.post("/bi/explore/extract")
async def extract(request: Request):
    """Body ``{dataset, fields, filters?, dates?}`` or ``{report, page, visual, state}``: the reader's rows as ``{columns[{name, type, masked}], rows, rowCount, total, truncated, masked}``."""
    spec = await json_body(request)
    try:
        return JSONResponse((await extract_for(request, spec)).as_json(), headers={"Cache-Control": "no-store"})
    except (BackendError, ExploreError) as e:
        return _fail(e)


@router.post("/bi/explore/sql")
async def sql(request: Request):
    """Body ``{extract: {...as above}, extracts?: [{name, dataset, fields}], sql}``: one SELECT over the table ``extract`` and a table for each other extract
    (named as the extract is); ``{columns, rows, rowCount, truncated, ms, inputRows, masked}``."""
    body = await json_body(request)
    limits = _limits(request)
    try:
        extras = await extras_for(request, body.get("extracts"))
        ex = await primary_for(request, body.get("extract"), extras)
        async with _SQL_SLOTS:
            out = await asyncio.to_thread(run_sql, ex, str(body.get("sql", "")), limits, {s["name"]: e for s, e in extras})
    except (BackendError, ExploreError) as e:
        return _fail(e)
    return JSONResponse({**out, "inputRows": len(ex.rows), "masked": ex.masked, "truncatedInput": ex.truncated}, headers={"Cache-Control": "no-store"})


@router.post("/bi/explore/dsql")
async def dsql(request: Request):
    """Body ``{extract: {dataset, ...}, sql}``: one Drishti SQL statement over the model the extract reads (BI_DESIGN.md 3.4), run by the server as the
    signed-in person (their row rules and masks), the cell's row limit applied; ``{columns, rows, rowCount, truncated, ms, engine: server, plan, masked}``.
    The console never reads the SQL: the server parses, binds and plans it (the same plan as the model query it is equal to)."""
    body = await json_body(request)
    limits = _limits(request)
    spec = body.get("extract") if isinstance(body.get("extract"), dict) else {}
    ref = model_ref(str(spec.get("dataset") or body.get("model") or ""))
    if not ref:
        return _fail(ExploreError("DRS-9070", 422, "Drishti SQL reads a model or a dataset's implicit model; this extract is a version 1 dataset: "
                                                   "choose 'over the extract' for this cell"))
    t0 = time.monotonic()
    try:
        out = await request.app.state.backend.bi("POST", "/sql", ident(request), dsql_request(str(body.get("sql", "")), ref, limits))
    except (BackendError, ExploreError) as e:
        if isinstance(e, BackendError) and e.status in (404, 405):
            return _fail(ExploreError("DRS-9070", 501, "this server does not run Drishti SQL yet: choose 'over the extract' for this cell"))
        return _fail(e)
    return JSONResponse({**dsql_answer(out, limits, round((time.monotonic() - t0) * 1000)), "model": ref}, headers={"Cache-Control": "no-store"})


def cell_packages(source: str, allowed: list[str]) -> list[str]:
    """The allow-listed packages a cell imports (the worker imports them before its sandbox closes)."""
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        raise ExploreError("DRS-9070", 422, f"line {e.lineno}: {e.msg}") from None
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


@router.post("/bi/explore/python")
async def python(request: Request):
    """Body ``{extract, extracts?, source, params?}``: runs one Python cell on the server over ``df`` (the extract as a pandas table), ``extract("<name>")`` (any
    other extract of the notebook, by its name or its dataset) and ``quant``; answers
    ``{kind: cell, stdout, table|option|svg|png|value}`` plus ``ms``, ``inputRows``, ``masked``. Needs ``bi-author``. Failures are DRS-9070 to 9074."""
    body = await json_body(request)
    limits, runner = _limits(request), request.app.state.pyrunner
    author = "bi-author" in _powers(request)
    source = str(body.get("source", ""))
    try:
        if not author:
            raise ExploreError("DRS-9073", 403, "running Python cells needs the bi-author power")
        if not source.strip() or len(source) > limits.cell_max_chars:
            raise ExploreError("DRS-9072", 413, f"a cell holds 1 to {limits.cell_max_chars:,} characters (bi.explore.cell_max_chars)")
        packages = cell_packages(source, runner.packages)
        extras = await extras_for(request, body.get("extracts"))
        ex = await primary_for(request, body.get("extract"), extras)
        if ex.arrow is None or any(e.arrow is None for _, e in extras):
            raise ExploreError("DRS-9074", 503, "Python cells need pyarrow on the console (pip install -r drishti-console/requirements.txt)")
        manifest = {"name": "analyse-cell", "interface": "cell", "source": source, "packages": packages, "inputs": [], "target": "server",
                    "timeout": limits.cell_timeout, "memory": limits.cell_memory, "quant_root": str(CONSOLE), "quant_engine": limits.quant_engine,
                    "params": body.get("params") if isinstance(body.get("params"), dict) else {}}
        blobs: dict[str, bytes] = {}
        for s, e in extras:                                              # an extract is read by its name or by the dataset it came from (the first of a dataset)
            blobs[s["name"]] = e.arrow
            blobs.setdefault(s["dataset"], e.arrow)
        answer = await runner.run({"manifest": manifest}, ex.arrow, extracts=blobs)
    except (BackendError, ExploreError, PyError) as e:
        return _fail(e, author)
    answer.update(inputRows=len(ex.rows), masked=sorted({m for m in ex.masked} | {m for _, e in extras for m in e.masked}), target="server", truncatedInput=ex.truncated,
                  extracts=[{"name": s["name"], "rows": len(e.rows), "masked": e.masked} for s, e in extras])
    return JSONResponse(answer, headers={"Cache-Control": "no-store"})


@router.post("/bi/explore/notebook/check")
async def notebook_check(request: Request):
    """Body ``{yaml}`` or ``{notebook}``: checks a notebook body before it is saved as a ``notebook`` item. Answers ``{valid, problems, cells, notebook, yaml}``:
    the notebook as data and as the YAML to store (the page holds no YAML library, so it reads and writes the body through this route)."""
    import yaml as _yaml
    body = await json_body(request)
    text = str(body.get("yaml", "")) if "yaml" in body else _yaml.safe_dump(body.get("notebook") or {}, sort_keys=False, default_flow_style=None, width=200, allow_unicode=True)
    try:
        doc = parse_notebook(text, _limits(request))
    except ExploreError as e:
        return JSONResponse({"valid": False, "problems": [{"level": "error", "code": e.code, "message": e.detail}], "cells": 0}, headers={"Cache-Control": "no-store"})
    return JSONResponse({"valid": True, "problems": [], "cells": len(doc.get("cells") or []), "notebook": doc, "yaml": text}, headers={"Cache-Control": "no-store"})


@router.post("/bi/explore/promote")
async def promote(request: Request):
    """Body ``{to: measure|visual, name, source, columns}`` (a Python cell to a Python item draft) or ``{to: report, name, dataset, slice}`` (a slice to a report
    draft). Answers ``{kind, title, yaml, notes}``; the page saves it as a draft item in the person's workspace. Nothing is published."""
    body = await json_body(request)
    limits, to = _limits(request), str(body.get("to", ""))
    name = str(body.get("name") or "From Analyse")[:80]
    try:
        if to in ("measure", "visual"):
            source = str(body.get("source", ""))
            if not source.strip() or len(source) > limits.cell_max_chars:
                raise ExploreError("DRS-9070", 422, "the cell is empty or too long to promote")
            cols = [str(c) for c in body.get("columns") or []][:limits.max_fields]
            text = promote_python(source, name, to, cols, cell_packages(source, request.app.state.pyrunner.packages))
            notes = ["The cell's df became the function's table; a measure must return one number and a visual an ECharts option, SVG or PNG.",
                     "Check it (Check the Python on the item page), then publish. A measure also needs a dataset measure that names it."]
            return JSONResponse({"kind": "python", "title": name, "yaml": text, "notes": notes}, headers={"Cache-Control": "no-store"})
        if to == "transform":
            source = str(body.get("source", ""))
            if not source.strip() or len(source) > limits.cell_max_chars:
                raise ExploreError("DRS-9070", 422, "the cell is empty or too long to promote")
            text = promote_transform(source, name, check_extracts(body.get("extracts") or [], limits), cell_packages(source, request.app.state.pyrunner.packages))
            notes = ["Each extract(\"name\") became a parameter of run(); the cell's result is returned and must be a pandas DataFrame with usable column names.",
                     "Check it (Check the Python on the item page) and publish it, then make a derived dataset: derived: { transform: <this item>, inputs: { alias: { dataset, by, measures } } }, "
                     "one input per dataset, in this order (docs/guides/DATASETS.md section 11)."]
            return JSONResponse({"kind": "python", "title": name, "yaml": text, "notes": notes}, headers={"Cache-Control": "no-store"})
        if to == "report":
            return JSONResponse(await _slice_report(request, body, name), headers={"Cache-Control": "no-store"})
        raise ExploreError("DRS-9070", 422, "to is measure, visual, transform or report")
    except (BackendError, ExploreError) as e:
        return _fail(e)


async def _slice_report(request: Request, body: dict, name: str) -> dict:
    """A slice, or a SQL cell, as a visual (core/promote_report.py) in a new report draft, or added to the existing report draft ``target``.
    Body: ``{dataset, slice: {rows, columns, values}, filters?}`` or ``{dataset, sql, columns}``; ``columns``/``qualified`` map the extract's short names to table.column;
    ``target`` (an item id) and ``page`` say where in an existing report."""
    import re
    import yaml
    dataset = str(body.get("dataset") or "")
    sl, sql = body.get("slice"), body.get("sql")
    if not dataset or not (isinstance(sl, dict) or isinstance(sql, str)):
        raise ExploreError("DRS-9070", 422, "send {dataset, slice: {rows, columns, values: [{field, agg}]}} or {dataset, sql, columns}")
    backend, me = request.app.state.backend, ident(request)
    ref = model_of(dataset)
    described = await backend.bi("GET", "/models/" + quote(ref, safe=":@") + "/describe", me)
    model = {"tables": [{"name": d.get("alias")} for d in described.get("datasets") or []], "measures": described.get("measures") or []}
    shorts, quals = [str(c) for c in body.get("columns") or []], [str(c) for c in body.get("qualified") or []]
    mapping = dict(zip(shorts, quals))
    qualify = lambda f: mapping.get(f, f)  # noqa: E731
    notes: list[str] = []
    if isinstance(sql, str):
        if len(sql) > _limits(request).sql_max_chars:
            raise ExploreError("DRS-9070", 422, "the SQL is too long to promote")
        read = sql_to_slice(sql, shorts)
        notes += read.pop("notes")
        sl, vid = read, "sql"
    else:
        vid = "slice"
        sl = {**sl, "filters": [{"field": f.get("field"), "op": f.get("op") or "in", "values": f.get("values") if f.get("values") is not None else [f.get("value")]}
                                 for f in (body.get("filters") or sl.get("filters") or []) if f.get("field") and (f.get("values") or f.get("value") not in (None, ""))]}
    visual = visual_for(model, sl, name, vid, qualify, notes)
    if visual is None:
        raise ExploreError("DRS-9070", 422, "the " + ("SQL" if vid == "sql" else "slice") + " has no fields or measures to build a visual from")
    target = str(body.get("target") or "")
    if not target:
        slug = re.sub(r"-{2,}", "-", re.sub(r"[^a-z0-9_-]", "-", name.lower())).strip("-")[:48].strip("-") or "from-analyse"          # a readable id: "trades-the-basics-sql"
        report = {"rachana": 2, "report": slug, "version": 1, "title": name, "description": "Started from an Analyse " + ("SQL cell." if vid == "sql" else "slice."),
                  "model": ref, "pages": [{"id": vid, "title": "Slice" if vid == "slice" else "SQL", "visuals": [visual]}]}
        return {"kind": "report", "title": name, "yaml": yaml.safe_dump(report, sort_keys=False, default_flow_style=None, width=200), "notes": notes, "target": None}
    item = await backend.bi("GET", "/items/" + quote(target, safe=":"), me)
    if item.get("kind") != "report" or not (item.get("can") or {}).get("edit"):
        raise ExploreError("DRS-9070", 422, "the target is not a report draft you can edit")
    checked = await backend.bi("POST", "/reports/validate", me, {"yaml": item.get("body") or ""})
    if not checked.get("report"):
        raise ExploreError("DRS-9070", 422, "the report cannot be read, so a visual cannot be added: " + "; ".join(p.get("message", "") for p in (checked.get("problems") or [])[:2]))
    try:
        report = add_to_report(checked["report"], visual, str(body.get("page") or ""), ref, notes)
    except ValueError as e:
        raise ExploreError("DRS-9070", 422, str(e)) from None
    written = await backend.bi("POST", "/reports/format", me, {"report": report})
    notes.append("The report was written again by the designer's writer: comments in its YAML are not kept.")
    return {"kind": "report", "title": item.get("title") or name, "yaml": written["yaml"], "notes": notes, "target": target, "visual": visual["id"]}
