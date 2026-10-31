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

"""The client-side renderer's playground (docs/architecture/CLIENT_RENDERER.md section 13): ``/render/demo`` opens the current version's
static demo page, which renders a view of this console as the signed-in person. The library itself is static (``/static/render/<version>/``).

``/render/demo/start`` is what the playground opens first when its address names no entity: one the server holds and the person may open,
in their lens (the first hit of a search of a common kind), with the lens it was chosen in, or a message saying why there is none."""
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import render_dist
from core.backend import BackendError
from core.edition import lens_of
from routes.common import ident

router = APIRouter(include_in_schema=False)

# Kinds a person opens most, tried in this order before any other the person may use (one search each, at most MAX_TRIES)
COMMON = ("trade", "trade-valuation", "netting-set", "counterparty", "book", "desk", "company", "country", "fund", "index", "ir-curve")
MAX_TRIES = 6


@router.get("/render/demo")
async def demo(request: Request):
    query = ("?" + request.url.query) if request.url.query else ""
    return RedirectResponse(f"/static/render/{render_dist.VERSION}/demo.html{query}", status_code=302)


async def _kinds(request: Request) -> list[tuple[str, str]]:
    """(kind, command) of every command the person can use now (FUNC: on, of their packs and roles, in their lens), common kinds first."""
    try:
        got = await request.app.state.backend.functions(ident(request))
    except BackendError:
        return []
    seen, out = set(), []
    for pack in got.get("packs") or []:
        for c in pack.get("commands") or []:
            kind, code = str(c.get("kind") or ""), str(c.get("code") or "")
            if kind and code and kind not in seen:
                seen.add(kind)
                out.append((kind, code))
    rank = {k: i for i, k in enumerate(COMMON)}
    return sorted(out, key=lambda kc: rank.get(kc[0], len(COMMON)))


@router.get("/render/demo/start")
async def start(request: Request):
    """``{ref, lens, message}``: the entity the playground draws first, or none with the reason."""
    lens = lens_of(getattr(request.state, "edition", None))
    where = f" in the {lens['title']} lens" if lens else ""
    tried = []
    for kind, code in (await _kinds(request))[:MAX_TRIES]:
        tried.append(kind)
        try:
            rows = (await request.app.state.backend.search(f"{code} limit 1", ident(request))).get("rows") or []
        except BackendError:
            continue
        ref = (rows[0] or {}).get("ref") if rows else None
        if ref and ref.get("kind") and ref.get("id"):
            return JSONResponse({"ref": {"kind": ref["kind"], "id": str(ref["id"])}, "lens": lens,
                                 "message": f"The first {kind} this server holds that you may open{where}."}, headers={"Cache-Control": "no-store"})
    msg = (f"Nothing to draw yet: this server holds no {', '.join(tried)} you may open{where}. Type a kind and an id above, or paste a ViewModel."
           if tried else f"Nothing to draw yet: there is no kind you may open{where}. Type a kind and an id above, or paste a ViewModel.")
    return JSONResponse({"ref": None, "lens": lens, "message": msg}, headers={"Cache-Control": "no-store"})
