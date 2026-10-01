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

"""Sutra Studio: a Markdown editor for Sutras. Edit, preview against any entity or pasted JSON, read the rendered
document, start from inference, save (authors)."""
from __future__ import annotations

import difflib
import json
from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, RedirectResponse

from core.backend import BackendError
from core import sutra_doc
from routes.common import ident, render

router = APIRouter(prefix="/studio", include_in_schema=False)

NEW_SUTRA = """# My layout (`my-layout` v1)

What this layout is for, and who reads it. The engine reads only the `sutra` block below; everything
else is documentation for people and AI assistants.

```sutra
sutra: my-layout
version: 1
match: { kind: trade, where: "$.productType == 'IRS'" }
title: { pill: "Trade", id: $.tradeId }
strip:
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
panels:
  - { id: refs, kind: links, title: Linked entities, area: right }
```

## Why this layout

- The strip leads with the number the reader checks first.
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
    return render(request, "studio/review.html", p=p, diff=_diff(p.get("liveText") or "", p.get("text") or ""), error=None)


@router.post("/reviews/{id_}/{action}")
async def decide(request: Request, id_: str, action: str):
    if action not in ("approve", "reject", "withdraw"):
        return RedirectResponse(f"/studio/reviews/{id_}", status_code=303)
    form = parse_qs((await request.body()).decode("utf-8", "replace"))
    try:
        await request.app.state.backend.decide(id_, action, ident(request), comment=form.get("comment", [""])[0][:500])
    except BackendError as e:
        p = await request.app.state.backend.proposal(id_, ident(request))
        return render(request, "studio/review.html", status_code=e.status, p=p, diff=_diff(p.get("liveText") or "", p.get("text") or ""), error=e)
    return RedirectResponse(f"/studio/reviews/{id_}", status_code=303)


def _diff(live: str, proposed: str) -> list[tuple[str, str]]:
    """A unified diff of the live Sutra and the proposal, as (kind, line) with kind add, del, hunk or ctx."""
    out = []
    for line in difflib.unified_diff(live.splitlines(), proposed.splitlines(), "live", "proposed", n=3, lineterm=""):
        if line.startswith(("---", "+++")):
            continue
        kind = "hunk" if line.startswith("@@") else "add" if line.startswith("+") else "del" if line.startswith("-") else "ctx"
        out.append((kind, line))
    return out


@router.get("/source/{name}/{version}")
async def source(request: Request, name: str, version: int):
    try:
        return PlainTextResponse(await request.app.state.backend.sutra_source(name, version, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/preview")
async def preview(request: Request):
    body = json.loads(await request.body() or b"{}")
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
        return await request.app.state.backend.set_studio_tests(sutra, json.loads(await request.body() or b"[]"), ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/test")
async def run_test(request: Request):
    """Previews the Sutra on one entity and says how it went: problems in the Sutra, or panels that could not bind."""
    import time

    body = json.loads(await request.body() or b"{}")
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


@router.post("/render")
async def render_doc(request: Request):
    """The Document tab: the Markdown Sutra rendered as a page, with its outline."""
    body = json.loads(await request.body() or b"{}")
    return JSONResponse(sutra_doc.render(body.get("yaml", "")))


@router.get("/inferred/{kind}/{id_}")
async def inferred(request: Request, kind: str, id_: str, name: str = ""):
    try:
        return PlainTextResponse(await request.app.state.backend.inferred(kind, id_, name, ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/inferred")
async def inferred_from_sample(request: Request):
    body = json.loads(await request.body() or b"{}")
    try:
        return PlainTextResponse(await request.app.state.backend.inferred_from(
            body.get("kind", ""), body.get("id", ""), body.get("name", ""), body.get("document"), ident(request)))
    except BackendError as e:
        return _problem(e)


@router.post("/save")
async def save(request: Request):
    body = json.loads(await request.body() or b"{}")
    try:
        return await request.app.state.backend.save_sutra(body.get("yaml", ""), ident(request), note=str(body.get("note", ""))[:300])
    except BackendError as e:
        return _problem(e)
