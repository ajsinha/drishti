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

"""Admin → Data loads → Lakes (/admin/lakes): upload a large JSON / JSON Lines file, scan it, build or add to a Delta lake with the
CLI's engine (tools/lake/build.py) as a job on the console's machine, bind the lake to a pack's Delta connector and announce the
load. Administrators only; see core/lakes.py."""
from __future__ import annotations

import asyncio
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from core.csrf import BodyError, json_body
from core.lakes import LakeError, Lakes, LakeSettings
from routes.common import ident, render

router = APIRouter(prefix="/admin", include_in_schema=False)


def _lakes(request: Request) -> Lakes:
    st = request.app.state
    if not hasattr(st, "lakes"):
        st.lakes = Lakes(LakeSettings.from_settings(st.settings, Path(__file__).resolve().parent.parent))
    return st.lakes


def _err(e: LakeError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


def _admin(request: Request):
    me = ident(request)
    if not me.is_admin:
        raise LakeError(403, "administrators only", "DRS-5002")
    return me


@router.get("/lakes")
async def page(request: Request):
    me = ident(request)
    if not me.is_admin:
        return render(request, "admin/forbidden.html", status_code=403)
    lakes = _lakes(request)
    try:
        status = await request.app.state.backend.admin("GET", "/status", me)
    except BackendError:
        status = {}
    try:
        figures = await asyncio.to_thread(lakes.figures)
    except Exception:                     # noqa: BLE001 - the screen opens without the table rather than not at all
        figures = []
    return render(request, "admin/lakes.html", cfg=lakes.cfg.as_dict(), status=status, runs=lakes.runs(10), tables=figures)


@router.get("/api/lakes/targets")
async def targets(request: Request):
    """The roots this console may write, the tables under them, the Delta connectors with the packs that use them, recent runs."""
    try:
        me = _admin(request)
        lakes = _lakes(request)
        tables = lakes.tables()
    except LakeError as e:
        return _err(e)
    connectors = []
    try:
        listing = await request.app.state.backend.admin("GET", "/connectors", me)
        for c in (listing.get("connectors") if isinstance(listing, dict) else listing) or []:
            if c.get("plugin") == "delta":
                connectors.append({"name": c.get("name"), "kinds": c.get("kinds") or [], "state": c.get("state"), "origin": c.get("origin"),
                                   "packs": [u.get("pack") for u in c.get("usedBy") or [] if isinstance(u, dict)] or ([c["template"]] if c.get("template") else [])})
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    return {"roots": [str(r) for r in lakes.cfg.roots], "tables": tables, "connectors": connectors, "runs": lakes.runs(10), "config": lakes.cfg.as_dict()}


@router.post("/api/lakes/upload")
async def upload(request: Request):
    """The file as the raw body (application/octet-stream), streamed to the console's temporary folder as it arrives."""
    try:
        me = _admin(request)
        kind = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
        if kind != "application/octet-stream":
            raise BodyError(415, "send the file as application/octet-stream")
        declared = request.headers.get("content-length")
        u = await _lakes(request).receive(request.query_params.get("name", ""), me.user, request.stream(),
                                          int(declared) if declared and declared.isdigit() else None)
    except LakeError as e:
        return _err(e)
    return u.view()


@router.post("/api/lakes/uploads/{uid}/scan")
async def scan(request: Request, uid: str):
    """The file's fields with their values and the suggested key, date and classifiers (a streamed read of up to lakes.scan_docs)."""
    try:
        me = _admin(request)
        lakes = _lakes(request)
        return await lakes.scan(lakes.upload(uid, me.user))
    except LakeError as e:
        return _err(e)
    except (ValueError, OSError) as e:
        return JSONResponse({"code": "DRS-5001", "detail": f"the file could not be read: {e}"}, status_code=400)


@router.post("/api/lakes/jobs")
async def start(request: Request):
    """Starts a build: {upload, root, domain, kind, key, date | asOf, partitionBy[], promote[], mode, pack, connector, bind, signal}."""
    body = await json_body(request)
    try:
        me = _admin(request)
        job = _lakes(request).start(body, me)
    except LakeError as e:
        return _err(e)
    return JSONResponse(job.view(), status_code=202)


@router.get("/api/lakes/jobs/{jid}")
async def job(request: Request, jid: str):
    try:
        me = _admin(request)
        j = _lakes(request).jobs.get(jid)
        if j is None or j.owner != me.user:
            raise LakeError(404, "no such build", "DRS-5033")
    except LakeError as e:
        return _err(e)
    await _lakes(request).after(j, me, request.app.state.backend)
    return j.view()
