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

"""ISDA SIMM in the console (docs/guides/SIMM_GUIDE.md sections 7 to 9): ``SIMM <GO>`` opens the SIMM screen (the decided mock-up A, every
netting set with its IM to collect and post and the selected one's breakdown beside, with C, the margin against the counterparty's call,
as its second tab); ``SIMM <netting set>`` opens it on that netting set, ``SIMM CPTY <id>`` on a counterparty's netting sets; ``PLAY SIMM
<netting set>`` opens the SIMM Playground (the Choices panel with the parameter version, the concentration and HVR switches and the side,
a switch compared side by side with the difference explained, the grid of weighted sensitivities with its formula bar and the
explanation, Save recipe and Export to Excel); ``PLAY SIMM RUN <id>`` replays a recipe. Every figure is the server's (``/api/v1/risk/simm/...``,
as the person); the pages are shells that plugins/banking/static/js/drishti_simm.js fills."""
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

WORD = "SIMM"
RECIPE = re.compile(r"^SM-[A-Z0-9-]{4,40}$", re.I)
#: the query words the pages pass through to the server
KEEP = ("nettingSet", "scope", "counterparty", "version", "concentration", "hvr", "side", "compare", "direction", "class", "asOf")


def _target(words: list[str]) -> dict:
    """The page's query of a command's words: a netting set, CPTY <id>, or a scope in VaR's words (an analytic)."""
    if not words:
        return {}
    if words[0].upper() in ("CPTY", "COUNTERPARTY") and len(words) > 1:
        return {"counterparty": words[1].upper()}
    text = " ".join(words)
    if len(words) == 1 and ":" not in text:
        return {"nettingSet": text.upper() if text.upper() == "EXAMPLE" else text}
    if ":" in text:
        return {"nettingSet": text}
    return {"nettingSet": f"{words[0].upper()}:{' '.join(words[1:])}"}


def simm_command(words: list[str]) -> str | None:
    """Where ``SIMM [<netting set> | CPTY <id>]`` goes, or None when it is not the command."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    q = _target(words[1:])
    return "/simm" + (f"?{urlencode(q)}" if q else "")


def play_simm_command(words: list[str]) -> str:
    """Where ``PLAY SIMM <netting set>`` (or ``PLAY SIMM RUN <id>``) goes; ``words`` are those after SIMM."""
    if len(words) >= 2 and words[0].upper() == "RUN":
        return "/play/simm?" + urlencode({"recipe": words[1].upper()})
    q = _target(words)
    q.pop("counterparty", None)
    return "/play/simm" + (f"?{urlencode(q)}" if q else "")


def redirect_for(typed: str):
    from fastapi.responses import RedirectResponse
    where = simm_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


def _params(request: Request, keep: tuple[str, ...] = KEEP) -> dict:
    qp = request.query_params
    p: dict = {k: qp.get(k) for k in keep if qp.get(k)}
    overrides = qp.getlist("override")
    if overrides:
        p["override"] = overrides
    return p


@router.get("/simm")
async def simm_page(request: Request, nettingSet: str = "", counterparty: str = ""):
    """SIMM: the screen's shell (mock-up A, the netting-set list with the breakdown, and C, against the counterparty's call)."""
    target = nettingSet.strip() or (f"CPTY {counterparty.strip()}" if counterparty.strip() else "")
    return render(request, "terminal/simm.html", netting_set=nettingSet.strip(), counterparty=counterparty.strip(), screen="simm", mode="screen",
                  command_text=f"{WORD} {target}".strip())


@router.get("/play/simm")
async def playground_page(request: Request, nettingSet: str = "", recipe: str = ""):
    """PLAY SIMM: the playground's shell; PLAY SIMM RUN <id>: the same, replaying a recipe."""
    recipe = recipe.strip().upper() if RECIPE.match(recipe.strip()) else ""
    return render(request, "terminal/simm.html", netting_set=nettingSet.strip(), counterparty="", recipe=recipe, screen="play-simm", mode="play",
                  command_text=("PLAY SIMM RUN " + recipe) if recipe else f"PLAY SIMM {nettingSet.strip()}".strip())


async def _get(request: Request, path: str, **params):
    try:
        return JSONResponse(await risk_api.risk(request.app.state.backend, "GET", path, ident(request), **params), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/api/simm")
async def simm(request: Request):
    return await _get(request, "/simm", **_params(request))


@router.get("/api/simm/netting-sets")
async def netting_sets(request: Request):
    return await _get(request, "/simm/netting-sets", **_params(request))


@router.get("/api/simm/explain")
async def explain(request: Request):
    return await _get(request, "/simm/explain", **_params(request))


@router.get("/api/simm/against")
async def against(request: Request):
    return await _get(request, "/simm/against", **_params(request))


@router.get("/api/simm/versions")
async def versions(request: Request):
    return await _get(request, "/simm/versions")


@router.get("/api/simm/playground")
async def playground(request: Request):
    return await _get(request, "/simm/playground", **_params(request))


@router.post("/api/simm/recipes")
async def save_recipe(request: Request):
    try:
        return JSONResponse(await risk_api.risk(request.app.state.backend, "POST", "/simm/playground/recipes", ident(request), **_params(request)))
    except BackendError as e:
        return _problem(e)


@router.get("/api/simm/recipes/{rid}")
async def replay(request: Request, rid: str):
    if not RECIPE.match(rid):
        return JSONResponse({"code": "DRS-5001", "detail": "a SIMM recipe id is SM-..."}, status_code=400)
    return await _get(request, f"/simm/playground/recipes/{rid.upper()}")


@router.get("/api/simm/export.xlsx")
async def export(request: Request):
    """The workbook of live formulas for what is on screen (its switches, side and overrides), streamed from the server; a recipe is saved."""
    from core.workbook_stream import stream
    query = []
    for k, v in _params(request, ("nettingSet", "scope", "version", "concentration", "hvr", "side", "asOf")).items():
        for x in (v if isinstance(v, list) else [v]):
            query.append((k, x))
    return await stream(request, risk_api.path("/simm/playground.xlsx" + ("?" + urlencode(query) if query else "")), {}, "simm-playground.xlsx")


@router.get("/api/simm/template.xlsx")
async def template(request: Request):
    from core.workbook_stream import stream
    return await stream(request, risk_api.path("/simm/playground/template"), {}, "simm-playground-template.xlsx")


# ---- the core's hooks (core/plugins.py, DOMAIN_MODULES.md C2) ----------------------------------------------------------------------
_plugins.command(redirect_for, needs=("SIMM",))                               # SIMM [<netting set> | CPTY <id>]: ISDA SIMM
