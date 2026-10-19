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

"""Wave C (2.4.0), the browser Python console (docs/guides/ANALYSE.md section 15; BI_DESIGN.md section 12.2): personal, just-in-time analysis in
Pyodide on a masked extract of a model, and promotion of a result to run on the console backend.

It is deliberately **not a BI page** (BI_DESIGN.md principle P3: no BI page computes). It lives at ``/pyconsole``, outside ``/bi``, and is what Calc already is
for a terminal view: the person's own code, in Calc's Web Worker (``calc-worker.js``, the only script allowed to compile WebAssembly), on rows they may see.
The server side is two routes:

* ``POST /pyconsole/extract``: the extract, built by the server **as the signed-in person** (a model query of rows: their row rules, a masked column
  arrives masked and is named in ``masked``), cut to ``bi.pyconsole.max_rows``, as JSON for the page to hand to the worker. The worker never asks for data.
* ``POST /pyconsole/promote``: the same code run again **on the console backend** (the Analyse cell sandbox, ``core/pyrunner.py``) over the extract rebuilt
  for the person, and kept as a notebook draft in their workspace (opened in Analyse, shared, reviewed, published like any notebook; whoever opens it
  gets their own rows). Needs ``bi-author``, as any Python cell on the console does."""
from __future__ import annotations

import datetime as dt
import re

import yaml
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import json_body
from core.explore import ExploreError, parse_notebook
from core.pyrunner import PyError
from routes.bi_routes import _page
from routes.common import ident
from routes.explore_routes import CONSOLE, _fail, _limits, _powers, cell_packages, extract_for

router = APIRouter(include_in_schema=False)


def _settings(request: Request) -> tuple[bool, int]:
    s = request.app.state.settings
    on = str(s.get("bi.pyconsole.enabled", True)).lower() in ("true", "1", "yes", "on")
    try:
        rows = max(10, min(100_000, int(s.get("bi.pyconsole.max_rows", 20_000))))
    except (TypeError, ValueError):
        rows = 20_000
    return on, rows


def _plain(v):
    if isinstance(v, (dt.date, dt.datetime)):
        return v.isoformat()
    if isinstance(v, float) and v != v:
        return None
    return v


def _spec(body: dict) -> dict:
    spec = body.get("extract") if isinstance(body.get("extract"), dict) else {}
    if spec.get("report"):
        raise ExploreError("DRS-9070", 422, "the browser console opens a model or a dataset: {dataset, fields, dates?}")
    return {k: spec[k] for k in ("dataset", "model", "fields", "filters", "dates") if k in spec}


@router.get("/pyconsole")
async def page(request: Request):
    on, rows = _settings(request)
    calc = request.app.state.calc
    if not on:
        return JSONResponse({"code": "DRS-9074", "detail": "the browser Python console is switched off (bi.pyconsole.enabled)"}, status_code=404)
    return _page(request, "pyconsole/console.html", "pyconsole", runtime=calc.runtime.describe(), max_rows=rows, may_promote="bi-author" in _powers(request))


@router.post("/pyconsole/extract")
async def extract(request: Request):
    """Body ``{extract: {dataset, fields, dates?}}``: the person's masked rows for the browser, ``{columns[{name, type, masked}], rows, rowCount, total,
    truncated, masked, maxRows}`` (dates as ISO text)."""
    on, rows = _settings(request)
    if not on:
        return JSONResponse({"code": "DRS-9074", "detail": "the browser Python console is switched off"}, status_code=404)
    body = await json_body(request)
    try:
        ex = await extract_for(request, _spec(body), max_rows=rows)
    except (BackendError, ExploreError) as e:
        return _fail(e)
    out = ex.as_json()
    out["rows"] = [[_plain(v) for v in r] for r in out["rows"]]
    out["maxRows"] = rows
    return JSONResponse(out, headers={"Cache-Control": "no-store"})


@router.post("/pyconsole/promote")
async def promote(request: Request):
    """Body ``{extract: {dataset, fields, dates?}, code, title}``: runs the code on the console backend over the extract rebuilt for the person (``df``; what it
    ``show()``s last, or its ``result``, is its answer) and saves it as a notebook draft. Answers ``{answer, item: {id, href}, inputRows, masked}``; a code
    that fails is not saved (``{code, detail, traceback}``, the traceback for authors)."""
    on, rows = _settings(request)
    if not on:
        return JSONResponse({"code": "DRS-9074", "detail": "the browser Python console is switched off"}, status_code=404)
    body = await json_body(request)
    limits, runner = _limits(request), request.app.state.pyrunner
    author = "bi-author" in _powers(request)
    code = str(body.get("code") or "")
    title = re.sub(r"\s+", " ", str(body.get("title") or "From the browser console")).strip()[:120] or "From the browser console"
    try:
        if not author:
            raise ExploreError("DRS-9073", 403, "promoting runs Python on the console: it needs the bi-author power")
        if not code.strip() or len(code) > limits.cell_max_chars:
            raise ExploreError("DRS-9072", 413, f"the code holds 1 to {limits.cell_max_chars:,} characters (bi.explore.cell_max_chars)")
        spec = _spec(body)
        packages = cell_packages(code, runner.packages)
        ex = await extract_for(request, spec, max_rows=rows)
        if ex.arrow is None:
            raise ExploreError("DRS-9074", 503, "promoting needs pyarrow on the console (pip install -r drishti-console/requirements.txt)")
        manifest = {"name": "pyconsole-promote", "interface": "cell", "source": code, "packages": packages, "inputs": [], "target": "server",
                    "timeout": limits.cell_timeout, "memory": limits.cell_memory, "quant_root": str(CONSOLE), "quant_engine": limits.quant_engine, "params": {}}
        answer = await runner.run({"manifest": manifest}, ex.arrow)
        doc = {"rachana": 1, "notebook": re.sub(r"[^a-z0-9-]+", "-", title.lower()).strip("-")[:64] or "from-the-browser-console", "title": title,
               "dataset": str(spec.get("dataset") or ("mq:" + str(spec.get("model")) if spec.get("model") else "")), "fields": list(spec.get("fields") or []),
               "cells": [{"type": "python", "source": code if code.endswith("\n") else code + "\n", "target": "server"}]}
        text = yaml.safe_dump(doc, sort_keys=False, default_flow_style=None, width=200, allow_unicode=True)
        parse_notebook(text, limits)
        item = await request.app.state.backend.bi("POST", "/workspaces/me/items", ident(request),
                                                  {"kind": "notebook", "title": title, "description": "Promoted from the browser Python console: runs on the console backend.",
                                                   "body": text})
    except (BackendError, ExploreError, PyError) as e:
        return _fail(e, author)
    iid = str(item.get("id") or "")
    return JSONResponse({"answer": answer, "item": {"id": iid, "href": "/bi/explore?item=" + iid, "page": "/bi/i/" + iid}, "inputRows": len(ex.rows),
                         "masked": ex.masked, "truncatedInput": ex.truncated, "target": "server"}, headers={"Cache-Control": "no-store"})
