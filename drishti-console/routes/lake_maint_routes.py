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

"""Admin → Lakes → Maintain (/admin/api/lakes/maintain/...): the tables with their owner and settings, dry runs and runs in the
background (progress, cancel), the settings per table, the schedules and the history. Administrators only; see core/lake_maint.py
and docs/admin/OPERATIONS.md."""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.csrf import json_body
from core.lake_maint import identity_for, maintenance
from core.lakes import LakeError
from routes.lake_routes import _admin, _err, lakes_of

router = APIRouter(prefix="/admin/api/lakes/maintain", include_in_schema=False)


@router.get("")
async def overview(request: Request):
    """{tables[{root, table, domain, owned, owner, reason, settings, files, size}], schedules[], history[] (last 50), configFile, zone, running[]}."""
    try:
        _admin(request)
        figures = await asyncio.to_thread(lakes_of(request.app).figures)
        return await asyncio.to_thread(maintenance(request.app).overview, figures)
    except LakeError as e:
        return _err(e)


@router.post("/command")
async def command(request: Request):
    """{tables: {root: [domain/table]}, dryRun} → {command}: the lake maintain line doing the same from a terminal or cron."""
    body = await json_body(request)
    try:
        _admin(request)
        m = maintenance(request.app)
        sel = m.check_selection(body.get("tables"), owned_only=False)
        return {"command": m.command(sel, bool(body.get("dryRun"))), "configFile": str(m.config_file)}
    except LakeError as e:
        return _err(e)


@router.post("/runs")
async def start(request: Request):
    """{tables: {root: [domain/table]}, dryRun} → 202 and the run; poll GET /runs/{id}."""
    body = await json_body(request)
    try:
        me = _admin(request)
        app = request.app
        run = maintenance(app).start(body.get("tables"), bool(body.get("dryRun")), me.user, lambda: identity_for(app, me.user, me.roles))
    except LakeError as e:
        return _err(e)
    return JSONResponse(run.view(), status_code=202)


@router.get("/runs/{rid}")
async def run(request: Request, rid: str):
    try:
        _admin(request)
        m = maintenance(request.app)
        r = m.runs.get(rid)
        if r is None:
            raise LakeError(404, "no such maintenance run", "DRS-5033")
    except LakeError as e:
        return _err(e)
    await m.after(request.app.state.backend)          # a finished run's data-load signals, as the admin who started it
    return r.view()


@router.post("/runs/{rid}/cancel")
async def cancel(request: Request, rid: str):
    """Stops the run before its next table (the table in hand finishes: a table is never left half maintained)."""
    await json_body(request)
    try:
        _admin(request)
        return maintenance(request.app).cancel(rid).view()
    except LakeError as e:
        return _err(e)


@router.put("/settings")
async def settings(request: Request):
    """{tables: {root: [domain/table]}, settings: {keep-business-days, target-file-mb, checkpoint, vacuum-hours}} → saved, warnings."""
    body = await json_body(request)
    try:
        _admin(request)
        return await asyncio.to_thread(maintenance(request.app).save_settings, body.get("tables"), body.get("settings"))
    except LakeError as e:
        return _err(e)


@router.post("/schedules")
async def add_schedule(request: Request):
    """{name, tables: {root: [domain/table]}, days: [mon..sun], at: HH:MM | cron: text | afterBuild: true} → the schedule."""
    body = await json_body(request)
    try:
        me = _admin(request)
        return JSONResponse(await asyncio.to_thread(maintenance(request.app).add_schedule, body, me.user, me.roles), status_code=201)
    except LakeError as e:
        return _err(e)


@router.post("/schedules/pause")
async def pause_schedule(request: Request):
    """{root, name, paused} → the schedule paused or resumed."""
    body = await json_body(request)
    try:
        _admin(request)
        return await asyncio.to_thread(maintenance(request.app).pause_schedule, str(body.get("root") or ""), str(body.get("name") or ""),
                                       bool(body.get("paused")))
    except LakeError as e:
        return _err(e)


@router.post("/schedules/delete")
async def delete_schedule(request: Request):
    """{root, name} → the schedule removed from the file."""
    body = await json_body(request)
    try:
        _admin(request)
        return await asyncio.to_thread(maintenance(request.app).delete_schedule, str(body.get("root") or ""), str(body.get("name") or ""))
    except LakeError as e:
        return _err(e)
