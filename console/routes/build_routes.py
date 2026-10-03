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

"""Screen Builder, steps 2 and 3: the shape extractor page (/build/shape). The browser sends the files' text; this enforces the
limits, forwards the samples to the server's POST /api/v1/builder/shape, and keeps the user's last set for a while
(core.builder.SampleSets). Nothing is written anywhere; counts only are logged."""
from __future__ import annotations

import json
import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, Response

from core import builder
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(prefix="/build", include_in_schema=False)
LOG = logging.getLogger("drishti.console.build")


def _refuse(status: int, detail: str, **more) -> JSONResponse:
    return JSONResponse({"code": "DRS-5005" if status == 413 else "DRS-5001", "detail": detail, **more}, status_code=status)


def _summary(request: Request, s: builder.SampleSet) -> dict:
    return {"files": s.files, "sampleCount": len(s.samples), "shape": s.shape,
            "expiresInSeconds": request.app.state.sample_sets.seconds_left(s)}


@router.get("/shape")
async def shape_page(request: Request):
    return render(request, "build/shape.html", limits=request.app.state.builder_limits.as_dict(), ttl_hours=request.app.state.sample_sets.ttl / 3600, screen="build")


@router.post("/shape")
async def shape(request: Request):
    """Body ``{files: [{name, text}]}`` (.json: one document; .jsonl: one per line). 413 DRS-5005 over a whole-request
    limit; a file that cannot be used is listed with its problems and the rest are shaped."""
    limits = request.app.state.builder_limits
    if int(request.headers.get("content-length") or 0) > limits.max_total_bytes * 2 + 65536:     # JSON escaping can double text
        return _refuse(413, f"the request is over the limit of {limits.max_total_bytes // 1048576} MB (builder.max_total_mb)")
    body = await json_body(request)
    try:
        samples, report = builder.read_files(body.get("files") if isinstance(body, dict) else None, limits)
    except builder.TooBig as e:
        return _refuse(413, str(e))
    except ValueError as e:
        raise BodyError(400, str(e))
    if not samples:
        return _refuse(422, "no usable sample: every file had a problem", files=report)
    try:
        result = await request.app.state.backend.builder_shape(samples, ident(request))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail, "files": report}, status_code=e.status)
    kept = request.app.state.sample_sets.put(ident(request).user, samples, report, result)
    LOG.info("builder shape: %d file(s), %d sample(s), %d with problems", len(report), len(samples), sum(1 for f in report if f["problems"]))
    return _summary(request, kept)


@router.post("/design")
async def design(request: Request):
    """Step 3: drafts a Sutra from the user's last sample set (the server's POST /api/v1/builder/design) and keeps it for
    "Open in Studio". Answers the server's result with ``previewHtml``, the preview of the first sample as Studio shows it."""
    kept = request.app.state.sample_sets.get(ident(request).user)
    if kept is None:
        return JSONResponse({"code": "DRS-1001", "detail": "no sample set to draft from: upload files first"}, status_code=404)
    kind = str(request.app.state.settings.get("builder.studio_kind", "sample") or "sample")
    try:
        result = await request.app.state.backend.builder_design(kept.samples, kind, ident(request))
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    request.app.state.sample_sets.set_draft(ident(request).user, result.get("yaml") or "")
    vm = result.pop("preview", None)
    result["previewHtml"] = request.app.state.templates.get_template("studio/_preview.html").render(vm=vm) if vm else ""
    LOG.info("builder design: %d sample(s), %d panel(s) pruned", len(kept.samples), len(result.get("pruned") or []))
    return result


@router.get("/shape/last")
async def last(request: Request):
    """The user's last set and its shape, until it expires (404 DRS-1001 when there is none)."""
    kept = request.app.state.sample_sets.get(ident(request).user)
    if kept is None:
        return JSONResponse({"code": "DRS-1001", "detail": "no sample set: upload files to make one (a set is kept for a day, then forgotten)"}, status_code=404)
    return _summary(request, kept)


@router.delete("/shape/last")
async def forget(request: Request):
    request.app.state.sample_sets.drop(ident(request).user)
    return Response(status_code=204)


@router.get("/shape/download")
async def download(request: Request, kind: str = "shape"):
    """``kind=shape``: shape.json (the schema with its x-drishti annotations); ``kind=schema``: plain JSON Schema."""
    kept = request.app.state.sample_sets.get(ident(request).user)
    if kept is None:
        return JSONResponse({"code": "DRS-1001", "detail": "no sample set to download from: upload files first"}, status_code=404)
    if kind not in ("shape", "schema"):
        return _refuse(400, "kind must be 'shape' or 'schema'")
    schema = kept.shape.get("schema") or {}
    text = json.dumps(schema if kind == "shape" else builder.plain_schema(schema), indent=2, ensure_ascii=False)
    name = "shape.json" if kind == "shape" else "schema.json"
    return Response(text, media_type="application/json", headers={"Content-Disposition": f'attachment; filename="{name}"', "Cache-Control": "no-store"})
