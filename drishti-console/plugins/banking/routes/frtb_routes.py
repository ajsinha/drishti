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

"""FRTB-SA in the console (docs/guides/FRTB_SA_GUIDE.md sections 7 to 9): ``FRSA <scope> <GO>`` opens the capital screen (the decided
mock-up B, the capital stack with the class × measure grid and the bucket drill, and A's three scenarios as its second tab); ``PLAY FRTB
<scope> <GO>`` opens the FRTB-SA Playground (the grid of weighted sensitivities with its formula bar and the explanation beside it, the
Choices panel with every method switch, a switch compared side by side with the difference explained by step, Save recipe and Export to
Excel); ``PLAY FRTB RUN <id> <GO>`` replays a saved recipe and says whether it reproduced to the cent. Every figure is the server's
(``/api/v1/risk/frtb/...``, as the person); the pages are shells that plugins/banking/static/js/drishti_frtb-sa.js fills. The scope words are the VaR explorer's;
``EXAMPLE`` runs the design's hand-built cases."""
from __future__ import annotations

import re
from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, render
from plugins.banking import risk_api
from core import plugins as _plugins

router = APIRouter(include_in_schema=False)

WORD = "FRSA"
#: the switches as the page names them (query word) and the server's parameter
SWITCHES = {"curvature-source": "curvatureSource", "girr-curvature-shock": "girrCurvatureShock", "fx-curvature-divisor": "fxCurvatureDivisor",
            "girr-correlation": "girrCorrelation", "scenario": "scenario"}
RECIPE = re.compile(r"^FR-[A-Z0-9-]{4,40}$", re.I)


def _scope(words: list[str]) -> str:
    """The scope text of a command's words: VaR's words (DESK RATES → DESK:RATES) or a scope already written with colons."""
    if not words:
        return ""
    if len(words) == 1 or any(":" in w for w in words) or words[0].upper() in ("BANK", "EXAMPLE"):
        return " ".join(words)
    return f"{words[0].upper()}:{' '.join(words[1:])}"


def frsa_command(words: list[str]) -> str | None:
    """Where ``FRSA <scope>`` goes, or None when it is not the command."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    scope = _scope(words[1:])
    return "/frtb" + (f"?{urlencode({'scope': scope})}" if scope else "")


def play_frtb_command(words: list[str]) -> str:
    """Where ``PLAY FRTB <scope>`` (or ``PLAY FRTB RUN <id>``) goes; ``words`` are those after FRTB."""
    if len(words) >= 2 and words[0].upper() == "RUN":
        return "/play/frtb?" + urlencode({"recipe": words[1].upper()})
    scope = _scope(words)
    return "/play/frtb" + (f"?{urlencode({'scope': scope})}" if scope else "")


def redirect_for(typed: str):
    from fastapi.responses import RedirectResponse
    where = frsa_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


def _params(request: Request, keep: tuple[str, ...] = ("scope", "ccy", "asOf", "compare", "by", "class", "q", "limit")) -> dict:
    qp = request.query_params
    p: dict = {k: qp.get(k) for k in keep if qp.get(k)}
    for page_word, server in SWITCHES.items():
        v = qp.get(page_word) or qp.get(server)
        if v:
            p[server] = v
    overrides = qp.getlist("override")
    if overrides:
        p["override"] = overrides
    return p


@router.get("/frtb")
async def capital_page(request: Request, scope: str = ""):
    """FRSA: the capital screen's shell (mock-up B with A's scenarios as a tab)."""
    return render(request, "terminal/frtb.html", scope=scope.strip(), screen="frtb", mode="capital",
                  command_text=f"{WORD} {scope.strip()}".strip())


@router.get("/play/frtb")
async def playground_page(request: Request, scope: str = "", recipe: str = ""):
    """PLAY FRTB: the playground's shell; PLAY FRTB RUN <id>: the same, replaying a recipe."""
    recipe = recipe.strip().upper() if RECIPE.match(recipe.strip()) else ""
    return render(request, "terminal/frtb.html", scope=scope.strip(), recipe=recipe, screen="play-frtb", mode="play",
                  command_text=("PLAY FRTB RUN " + recipe) if recipe else f"PLAY FRTB {scope.strip()}".strip())


async def _get(request: Request, path: str, **params):
    try:
        return JSONResponse(await risk_api.risk(request.app.state.backend, "GET", path, ident(request), **params), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/api/frtb/sa")
async def sa(request: Request):
    return await _get(request, "/frtb/sa", **_params(request))


@router.get("/api/frtb/explain")
async def explain(request: Request):
    return await _get(request, "/frtb/sa/explain", **_params(request))


@router.get("/api/frtb/contributions")
async def contributions(request: Request):
    return await _get(request, "/frtb/sa/contributions", **_params(request))


@router.get("/api/frtb/playground")
async def playground(request: Request):
    return await _get(request, "/frtb/playground", **_params(request))


@router.get("/api/frtb/sensitivities")
async def sensitivities(request: Request):
    return await _get(request, "/sensitivities", **_params(request))


@router.get("/api/frtb/risk-factors")
async def risk_factors(request: Request):
    return await _get(request, "/risk-factors", **_params(request))


@router.post("/api/frtb/recipes")
async def save_recipe(request: Request):
    try:
        return JSONResponse(await risk_api.risk(request.app.state.backend, "POST", "/frtb/playground/recipes", ident(request), **_params(request)))
    except BackendError as e:
        return _problem(e)


@router.get("/api/frtb/recipes/{rid}")
async def replay(request: Request, rid: str):
    if not RECIPE.match(rid):
        return JSONResponse({"code": "DRS-5001", "detail": "a recipe id is FR-..."}, status_code=400)
    return await _get(request, f"/frtb/playground/recipes/{rid.upper()}")


@router.get("/api/frtb/export.xlsx")
async def export(request: Request):
    """The workbook of live formulas for what is on screen (its switches and overrides), streamed from the server; a recipe is saved with it."""
    from core.workbook_stream import stream
    query = []
    for k, v in _params(request, ("scope", "ccy", "asOf")).items():
        for x in (v if isinstance(v, list) else [v]):
            query.append((k, x))
    return await stream(request, risk_api.path("/frtb/playground.xlsx" + ("?" + urlencode(query) if query else "")), {}, "frtb-sa-playground.xlsx")


@router.get("/api/frtb/template.xlsx")
async def template(request: Request):
    from core.workbook_stream import stream
    return await stream(request, risk_api.path("/frtb/playground/template"), {}, "frtb-sa-playground-hand-built-cases.xlsx")


# ---- the core's hooks (core/plugins.py, DOMAIN_MODULES.md C2) ----------------------------------------------------------------------
_plugins.command(redirect_for, needs=("VAR", "FRSA"))                         # FRSA <scope>: FRTB-SA capital
