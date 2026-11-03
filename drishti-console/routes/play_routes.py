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

"""The VaR Playground (docs/architecture/VAR_PLAYGROUND.md, docs/guides/VAR_GUIDE.md section 3.10): ``PLAY VAR <scope|ids> <GO>`` opens
``/play?scope=...`` (or ``/play?ids=...``), every VaR figure of the scope worked out in a spreadsheet-like grid (positions across,
scenarios down, at most 500 positions, the rest in one Rest of scope column) with a tall explanation beside it, and exported as an .xlsx of
real formulas that recalculates in Excel, LibreOffice and Google Sheets. The page is a shell: web/static/js/var-playground.js fills it
from ``/api/play/grid``, which asks the server's VaR engine (``/api/v1/var/playground``) as the person, so their nodes, roles and masks
apply. ``/api/play/export.xlsx`` streams the workbook (100, 500, 1,000, 10,000 or all positions), ``/api/play/estimate`` says what each
choice is before it is made, ``/api/play/template.xlsx`` is the empty workbook.

The word is an edition's (``commands.words.PLAY``: on in Drishti Risk and Drishti Trading): off, the command and every path here answer
``DRS-4023`` (core/edition_gate.py). The scope words are the VaR explorer's (``PLAY VAR DESK RATES``, ``PLAY VAR BANK 10D SUM``,
``PLAY VAR DESK:DESK-RATES-NY IN EUR``); words that are not a scope are ids (``PLAY VAR POS-10442 T-1001``: positions or trades, at most
``drishti.var.playground.max-ids``)."""
from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, StreamingResponse
from starlette.background import BackgroundTask

from core.backend import BackendError
from routes import var_routes
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "PLAY"
#: the metric after PLAY: each metric's playground adds its word here (VAR_PLAYGROUND.md section 13); FRTB: routes/frtb_routes.py; SIMM:
#: routes/simm_routes.py; EXP: routes/exposure_play_routes.py
METRICS = ("VAR", "FRTB", "SIMM", "EXP")
#: the link's lens words and the engine's names for them
LENS = {"h": "horizon", "m": "method", "q": "confidence", "esq": "esConfidence", "set": "set"}


def play_command(words: list[str], user: str) -> str | None:
    """Where ``PLAY VAR <words>`` goes, or None when it is not a playground command. A scope in the VaR explorer's words keeps its link's
    lens and currency; anything else is a list of ids."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    if len(words) == 1 or words[1].upper() not in METRICS:
        return "/play" if len(words) == 1 else None
    rest = words[2:]
    if words[1].upper() == "FRTB":                            # PLAY FRTB <scope>, PLAY FRTB RUN <id>: the FRTB-SA Playground
        from routes.frtb_routes import play_frtb_command
        return play_frtb_command(rest)
    if words[1].upper() == "SIMM":                            # PLAY SIMM <netting set>, PLAY SIMM RUN <id>: the SIMM Playground
        from routes.simm_routes import play_simm_command
        return play_simm_command(rest)
    if words[1].upper() == "EXP":                             # PLAY EXP <netting set>, PLAY EXP EXAMPLE: the Exposure Playground
        from routes.exposure_play_routes import play_exp_command
        return play_exp_command(rest)
    if not rest:
        return "/play"
    where = var_routes.var_command(["VAR"] + rest, user)
    if where and where.startswith("/var?scope=") and "&from=" not in where:
        return "/play?" + where.split("?", 1)[1]
    plain, ccy = var_routes.split_currency(["VAR", "BANK"] + rest)    # BANK holds the place of a scope: the lens comes off the end
    plain, lens = var_routes.split_lens(plain)
    ids = plain[2:]
    if any(":" in w for w in ids):                            # scope text: PLAY VAR DESK:RATES + POS:X
        q = {"scope": " ".join(ids)}
    else:
        q = {"ids": ",".join(ids)}
    q.update({k: v for k, v in lens.items() if v and k != "ccy"})
    if ccy or lens.get("ccy"):
        q["ccy"] = ccy or lens.get("ccy")
    return "/play?" + urlencode(q)


def redirect_for(typed: str, user: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here (after the edition's word check): a playground command, or None."""
    where = play_command(typed.split(), user)
    return RedirectResponse(where, status_code=303) if where else None


def link(scope: str, **lens) -> str:
    """The playground's address for a VaR scope (the VaR page's Playground link)."""
    q = {"scope": scope, **{k: v for k, v in lens.items() if v}}
    return "/play?" + urlencode(q)


@router.get("/play")
async def page(request: Request, scope: str = "", ids: str = "", ccy: str = "", h: str = "", m: str = "", q: str = "", esq: str = "",
               set: str = ""):  # noqa: A002 (the link's word)
    """The playground's shell; the grid, the explanation and the export load in place (web/static/js/var-playground.js)."""
    lens = var_routes.lens_of(request, h, m, q, esq, set)
    target = scope.strip() or ids.strip()
    return render(request, "terminal/play.html", scope=scope.strip(), ids=ids.strip(), target=target, screen="play",
                  ccy=ccy.upper() if var_routes.CCY.match(ccy or "") else "", lens=lens,
                  command_text=f"{WORD} VAR {target}".strip() if target else f"{WORD} VAR")


def _params(request: Request) -> dict:
    """The engine's parameters from the page's: the scope or ids, the currency, the lens, the view, the count and the date."""
    qp = request.query_params
    p = {}
    for k in ("scope", "ids", "ccy", "view", "count", "asOf"):
        if qp.get(k):
            p[k] = qp.get(k)
    for short, name in LENS.items():
        if qp.get(short) or qp.get(name):
            p[name] = qp.get(short) or qp.get(name)
    return p


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/api/play/grid")
async def grid(request: Request):
    """The grid, the figures, the checks and the export's choices, as the person sees them."""
    p = _params(request)
    p.pop("count", None)
    if not p.get("scope") and not p.get("ids"):
        return JSONResponse({"code": "DRS-5001", "detail": "give a VaR scope (scope=DESK:RATES) or ids (ids=POS-1,T-1001)"}, status_code=400)
    try:
        return JSONResponse(await request.app.state.backend.var("GET", "/playground", ident(request), **p), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/api/play/estimate")
async def estimate(request: Request):
    """Each export choice's size, recalculation time and the applications that open it, before it is made."""
    p = {k: v for k, v in _params(request).items() if k in ("scope", "ids", "ccy", "set", "asOf")}
    try:
        return JSONResponse(await request.app.state.backend.var("GET", "/playground/estimate", ident(request), **p))
    except BackendError as e:
        return _problem(e)


async def _stream(request: Request, path: str, params: dict, fallback: str):
    try:
        upstream = await request.app.state.backend.open_download(path + ("?" + urlencode(params) if params else ""), ident(request))
    except BackendError as e:
        return _problem(e)
    disposition = upstream.headers.get("content-disposition") or f'attachment; filename="{fallback}"'
    return StreamingResponse(upstream.aiter_bytes(), media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                             background=BackgroundTask(upstream.aclose),
                             headers={"Content-Disposition": disposition, "Cache-Control": "no-store"})


@router.get("/api/play/export.xlsx")
async def export(request: Request):
    """The workbook for exactly what is on screen, with the count chosen (100, 500, 1,000, 10,000 or all), streamed from the server."""
    p = _params(request)
    p.pop("view", None)
    if not p.get("scope") and not p.get("ids"):
        return JSONResponse({"code": "DRS-5001", "detail": "give a VaR scope or ids"}, status_code=400)
    return await _stream(request, "/var/playground.xlsx", p, "var-playground.xlsx")


@router.get("/api/play/template.xlsx")
async def template(request: Request, positions: int = 10, scenarios: int = 509):
    """The empty workbook: paste scenario PVs into Data, fill Config and FX, and every figure recalculates offline."""
    return await _stream(request, "/var/playground/template", {"positions": positions, "scenarios": scenarios}, "var-playground-template.xlsx")

