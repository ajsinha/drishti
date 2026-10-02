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

"""Sutra Studio: a YAML editor for Sutras (Rachana 1). Edit with completion and checks from the server's Rachana
schema, preview against any entity or pasted JSON, read the Summary, start from inference, save (authors)."""
from __future__ import annotations

from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse

from core.csrf import json_body
from core.backend import BackendError
from core import sutra_diff, sutra_summary
from routes.common import ident, render

router = APIRouter(prefix="/studio", include_in_schema=False)

NEW_SUTRA = """rachana: 1
sutra: my-layout
version: 1
description: What this layout shows, and for which entities.
match: { kind: trade, where: "$.productType == 'IRS'" }
title: { pill: "Trade", id: $.tradeId }
strip:
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
panels:
  - { id: refs, kind: links, title: Linked entities, area: right }
"""


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail, "problems": getattr(e, "problems", [])}, status_code=e.status)


@router.get("")
async def studio(request: Request, sutra: str = "irs-vanilla@3", kind: str = "trade", id: str = "IRS-48213"):
    backend, me = request.app.state.backend, ident(request)
    sutras = await backend.sutras(me)
    source, picked = NEW_SUTRA, ""
    if "@" in sutra:
        name, _, version = sutra.partition("@")
        try:
            source, picked = await backend.sutra_source(name, int(version), me), sutra
        except (BackendError, ValueError):
            pass
    settings = await backend.studio_settings(me)
    pending = 0
    if settings.get("review"):
        try:
            pending = len((await backend.proposals(me, status="pending")).get("proposals", []))
        except BackendError:
            pending = 0
    return render(request, "studio/studio.html", sutras=sutras, source=source, picked=picked, ref_kind=kind, ref_id=id,
                  can_save=bool(settings.get("save")), review=bool(settings.get("review")), pending=pending)


@router.get("/reviews")
async def reviews(request: Request, status: str = "pending"):
    """Sutra governance (W19): proposals waiting for review, or all of them."""
    try:
        data = await request.app.state.backend.proposals(ident(request), status="" if status == "all" else status)
    except BackendError as e:
        return render(request, "studio/reviews.html", status_code=e.status, proposals=[], status=status, error=e)
    return render(request, "studio/reviews.html", proposals=data.get("proposals", []), status=status, error=None)


@router.get("/reviews/{id_}")
async def review(request: Request, id_: str):
    try:
        p = await request.app.state.backend.proposal(id_, ident(request))
    except BackendError as e:
        return render(request, "studio/reviews.html", status_code=e.status, proposals=[], status="pending", error=e)
    return render(request, "studio/review.html", p=p, diff=sutra_diff.review(_against(p), p.get("text") or ""), error=None)


@router.post("/reviews/{id_}/{action}")
async def decide(request: Request, id_: str, action: str):
    if action not in ("approve", "reject", "withdraw"):
        return RedirectResponse(f"/studio/reviews/{id_}", status_code=303)
    form = parse_qs((await request.body()).decode("utf-8", "replace"))
    try:
        await request.app.state.backend.decide(id_, action, ident(request), comment=form.get("comment", [""])[0][:500])
    except BackendError as e:
        p = await request.app.state.backend.proposal(id_, ident(request))
        return render(request, "studio/review.html", status_code=e.status, p=p, diff=sutra_diff.review(_against(p), p.get("text") or ""), error=e)
    return RedirectResponse(f"/studio/reviews/{id_}", status_code=303)


def _against(p: dict) -> str:
    """What a proposal is compared with: the live text of its version, or for a new version the latest earlier one."""
    return p.get("liveText") or p.get("previousText") or ""




@router.get("/source/{name}/{version}")
async def source(request: Request, name: str, version: int):
    try:
        return PlainTextResponse(await request.app.state.backend.sutra_source(name, version, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/preview")
async def preview(request: Request):
    body = await json_body(request)
    try:
        vm = await request.app.state.backend.preview(body.get("yaml", ""), body.get("kind", ""), body.get("id", ""), ident(request),
                                                     body.get("document"))
    except BackendError as e:
        return _problem(e)
    html = request.app.state.templates.get_template("studio/_preview.html").render(vm=vm)
    return HTMLResponse(html)


@router.get("/tests/{sutra}")
async def tests(request: Request, sutra: str):
    """The entities this author keeps for trying the Sutra."""
    try:
        return await request.app.state.backend.studio_tests(sutra, ident(request))
    except BackendError as e:
        return _problem(e)


@router.put("/tests/{sutra}")
async def save_tests(request: Request, sutra: str):
    try:
        return await request.app.state.backend.set_studio_tests(sutra, await json_body(request, []), ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/test")
async def run_test(request: Request):
    """Previews the Sutra on one entity and says how it went: problems in the Sutra, or panels that could not bind."""
    import time

    body = await json_body(request)
    t0 = time.perf_counter()
    try:
        vm = await request.app.state.backend.preview(body.get("yaml", ""), body.get("kind", ""), body.get("id", ""), ident(request))
    except BackendError as e:
        return {"ok": False, "code": e.code, "detail": e.detail, "problems": getattr(e, "problems", []),
                "ms": round((time.perf_counter() - t0) * 1000, 1)}
    failed = [{"panel": p.get("id"), "error": p.get("error")} for p in vm.get("panels", []) if p.get("error")]
    empty = [p.get("id") for p in vm.get("panels", []) if p.get("empty") and not p.get("error")]
    return {"ok": not failed, "panels": len(vm.get("panels", [])), "failed": failed, "empty": empty,
            "layout": (vm.get("provenance") or {}).get("layout"), "ms": round((time.perf_counter() - t0) * 1000, 1)}


@router.post("/summary")
async def summary(request: Request):
    """The Summary tab: the YAML Sutra read back as a page (derived, read-only)."""
    body = await json_body(request)
    tpl = request.app.state.templates.get_template("studio/_summary.html")
    try:
        return JSONResponse({"html": tpl.render(s=sutra_summary.summarize(str(body.get("yaml", ""))), error=None)})
    except ValueError as e:
        return JSONResponse({"html": tpl.render(s=None, error=str(e)), "error": str(e)})


@router.get("/schema")
async def schema(request: Request):
    """The Rachana language's JSON Schema with this server's kinds, formats and functions (for completion and checks)."""
    try:
        return JSONResponse(await request.app.state.backend.rachana_schema(ident(request)), headers={"Cache-Control": "private, max-age=60"})
    except BackendError as e:
        return _problem(e)


@router.get("/inferred/{kind}/{id_}")
async def inferred(request: Request, kind: str, id_: str, name: str = ""):
    try:
        return PlainTextResponse(await request.app.state.backend.inferred(kind, id_, name, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/inferred")
async def inferred_from_sample(request: Request):
    body = await json_body(request)
    try:
        return PlainTextResponse(await request.app.state.backend.inferred_from(
            body.get("kind", ""), body.get("id", ""), body.get("name", ""), body.get("document"), ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/save")
async def save(request: Request):
    body = await json_body(request)
    try:
        return await request.app.state.backend.save_sutra(body.get("yaml", ""), ident(request), note=str(body.get("note", ""))[:300])
    except BackendError as e:
        return _problem(e)
