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

"""The dataset wizard (docs/guides/DATASETS.md, "Build a dataset in the browser"): the page, and the two small conversions that let a business user never type YAML.

The wizard keeps the dataset as one JSON document. ``POST /bi/wizard/yaml`` writes that document as the dataset's YAML (key order kept, nothing invented);
``POST /bi/wizard/parse`` reads a dataset's YAML back into the document, so "Edit in the wizard" round-trips: a key the wizard has no control for stays in
the document and is written out again. Comments in the original text are not kept (the review step says so). Nothing here decides access: saving goes
through ``/bi/api/workspaces/.../items`` and the server decides."""
from __future__ import annotations

import yaml
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.csrf import json_body
from routes.bi_routes import _page

router = APIRouter(include_in_schema=False)

MAX_TEXT = 400_000


def _refuse(detail: str, status: int = 422) -> JSONResponse:
    return JSONResponse({"code": "DRS-9003", "detail": detail}, status_code=status)


@router.get("/bi/datasets/new")
async def wizard(request: Request):
    """The dataset wizard; ``?edit=<item id>`` opens an existing dataset in it, ``?workspace=<id>`` names where the draft is saved."""
    return _page(request, "bi/dataset_wizard.html", "datasets", edit=request.query_params.get("edit", ""), workspace=request.query_params.get("workspace", ""))


@router.post("/bi/wizard/yaml")
async def to_yaml(request: Request):
    """``{doc}`` (the dataset as JSON) to ``{yaml}``."""
    body = await json_body(request)
    doc = body.get("doc")
    if not isinstance(doc, dict) or not doc:
        return _refuse("doc is the dataset as an object")
    try:
        text = yaml.safe_dump(doc, sort_keys=False, default_flow_style=None, width=200, allow_unicode=True)
    except yaml.YAMLError as e:
        return _refuse("the dataset cannot be written as YAML: " + str(e).splitlines()[0])
    return JSONResponse({"yaml": text}, headers={"Cache-Control": "no-store"})


@router.post("/bi/wizard/parse")
async def from_yaml(request: Request):
    """``{yaml}`` to ``{doc, mode}``; ``mode`` is ``tables`` (from the packs or a connector), ``composed`` (``from:``/``join:``) or ``derived`` (a Python Transform)."""
    body = await json_body(request)
    text = str(body.get("yaml", ""))
    if len(text) > MAX_TEXT:
        return _refuse("the dataset text is too long for the wizard; edit it as YAML", 413)
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as e:
        return _refuse("the YAML does not parse: " + str(e).splitlines()[0])
    if not isinstance(doc, dict):
        return _refuse("a dataset is a YAML mapping")
    mode = "derived" if "derived" in doc else "composed" if "from" in doc else "tables"
    return JSONResponse({"doc": doc, "mode": mode}, headers={"Cache-Control": "no-store"})
