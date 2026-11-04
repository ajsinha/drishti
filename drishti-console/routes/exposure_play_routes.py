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
"""The Exposure Playground in the console (docs/guides/EXPOSURE_GUIDE.md section 29): ``PLAY EXP <netting set> <GO>`` opens
``/play/exp?nettingSet=...`` (``PLAY EXP EXAMPLE``: the hand-built case X1), one netting set's EE, ENE, PFE, EPE and EEPE worked from its
simulated paths on the VaR Playground's frame: the choices (confidences, rank rule, collateral model, contribution date), the profile
beside the engine, the paths at a date with every collateral step, the contributions, the explanation and Export to Excel (the trade
columns chosen, the rest summed into Rest of set, the size estimated before the download). The page is a shell that
web/static/js/exposure-play.js fills from the server's ``/api/v1/exposure/playground`` as the person; the browser computes nothing."""
from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

#: the query words the page passes through to the server
KEEP = ("nettingSet", "q", "rule", "collateral", "date", "paths", "trades", "at", "asOf")


def play_exp_command(words: list[str]) -> str:
    """Where ``PLAY EXP <netting set>`` goes; ``words`` are those after EXP (``NSET NS-X``, ``NSET:NS-X``, ``NS-X`` or ``EXAMPLE``)."""
    words = [w for w in words if w.upper() != "<GO>"]
    if not words:
        return "/play/exp"
    if len(words) >= 2 and words[0].upper() == "NSET":
        words = words[1:]
    ns = " ".join(words).strip()
    if ns.upper().startswith("NSET:"):
        ns = ns[5:].strip()
    return "/play/exp?" + urlencode({"nettingSet": ns.upper()})


def _problem(e: BackendError) -> JSONResponse:
    from core.busy import problem as busy_problem

    return busy_problem(e, e.status)                               # a busy refusal keeps its Retry-After and words


def _params(request: Request, keep: tuple[str, ...] = KEEP) -> dict:
    qp = request.query_params
    return {k: qp.get(k) for k in keep if qp.get(k)}


@router.get("/play/exp")
async def page(request: Request, nettingSet: str = ""):
    """PLAY EXP: the playground's shell; its grid, explanation and export load in place."""
    ns = nettingSet.strip()
    return render(request, "terminal/exposure_play.html", netting_set=ns, screen="play-exp", command_text=f"PLAY EXP {ns}".strip())


@router.get("/api/exposure-play/grid")
async def grid(request: Request):
    """The profile, the headline, the paths at a date, the contributions and the export's choices, as the person sees them."""
    p = _params(request)
    if not p.get("nettingSet"):
        return JSONResponse({"code": "DRS-5440", "detail": "give a netting set (nettingSet=NS-ALDERSHOT-FRA) or EXAMPLE"}, status_code=400)
    try:
        return JSONResponse(await request.app.state.backend.exposure("GET", "/playground", ident(request), **p),
                            headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/api/exposure-play/estimate")
async def estimate(request: Request):
    """Each export choice's size, recalculation time and the applications that open it, before it is made."""
    p = _params(request, ("nettingSet", "paths", "asOf"))
    try:
        return JSONResponse(await request.app.state.backend.exposure("GET", "/playground/estimate", ident(request), **p))
    except BackendError as e:
        return _problem(e)


@router.get("/api/exposure-play/export.xlsx")
async def export(request: Request):
    """The workbook of live formulas for what is on screen, with the trade columns chosen, streamed from the server."""
    from routes.play_routes import _stream
    p = _params(request, ("nettingSet", "q", "rule", "collateral", "date", "paths", "trades", "asOf"))
    if not p.get("nettingSet"):
        return JSONResponse({"code": "DRS-5440", "detail": "give a netting set or EXAMPLE"}, status_code=400)
    return await _stream(request, "/exposure/playground.xlsx", p, "exposure-playground.xlsx")


@router.get("/api/exposure-play/template.xlsx")
async def template(request: Request):
    """The workbook of the hand-built case X1: two trades, five paths, every figure checkable by hand."""
    from routes.play_routes import _stream
    return await _stream(request, "/exposure/playground/template", {}, "exposure-playground-template.xlsx")
