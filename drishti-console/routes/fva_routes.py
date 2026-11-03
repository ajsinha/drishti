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
"""FVA in the console (docs/guides/FVA_GUIDE.md sections 5 and 6): one FVA screen over one API (/api/v1/fva/scope), drawn three ways, the
decided mock-ups: ``FVA NSET:<id>`` opens view A (the netting set's FVA: the FCA, FBA, FVA and CVA tiles, the term bars by future date and
the formula, the default), ``FVA BANK`` (or ``FVA`` alone, a group, a legal entity) view B (the netting sets by FVA with the selected set's
bars), ``FVA CPTY:<id>`` view C (the counterparty: its totals and each set's bars side by side); a tab switches between them on the same
answer. ``PLAY FVA <netting set | counterparty>`` opens the FVA Playground (the Choices, the terms by date with the formula bar, the
explanation, Export to Excel). Every figure is the server's; the pages are shells that web/static/js/fva.js fills."""
from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "FVA"
SCOPE_WORDS = ("NSET", "CPTY", "GROUP", "LE", "DESK", "BOOK", "TRD")
#: the query words the pages pass through to the server
KEEP = ("scope", "eePoint", "fba", "survival", "spread", "run", "asOf")


def scope_of(words: list[str]) -> str:
    """The scope text of a command's words: ``NSET:NS-X``, ``NSET NS-X``, ``CPTY CP-X``, ``BANK``, or a bare netting set id."""
    if not words:
        return "BANK"
    text = " ".join(words).strip()
    if ":" in text or text.upper() == "BANK":
        return text.upper() if text.upper() == "BANK" else text
    if words[0].upper() in SCOPE_WORDS and len(words) > 1:
        return f"{words[0].upper()}:{' '.join(words[1:])}"
    if words[0].upper() in ("COUNTERPARTY",) and len(words) > 1:
        return f"CPTY:{words[1]}"
    return f"NSET:{text}"


def view_of(scope: str) -> str:
    """The view a scope opens on: a netting set A, a counterparty C, anything wider B."""
    s = scope.upper()
    if s.startswith("NSET:"):
        return "a"
    if s.startswith("CPTY:"):
        return "c"
    return "b"


def fva_command(words: list[str]) -> str | None:
    """Where ``FVA [<scope>]`` goes, or None when it is not the command."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    scope = scope_of(words[1:])
    return "/fva?" + urlencode({"scope": scope, "view": view_of(scope)})


def play_fva_command(words: list[str]) -> str:
    """Where ``PLAY FVA <netting set | counterparty>`` goes; ``words`` are those after FVA."""
    if not words:
        return "/play/fva"
    return "/play/fva?" + urlencode({"scope": scope_of(words)})


def redirect_for(typed: str):
    from fastapi.responses import RedirectResponse
    where = fva_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


def _params(request: Request, keep: tuple[str, ...] = KEEP) -> dict:
    qp = request.query_params
    return {k: qp.get(k) for k in keep if qp.get(k)}


@router.get("/fva")
async def fva_page(request: Request, scope: str = "", view: str = ""):
    """FVA: the screen's shell; view a (a netting set, the default for one), b (the netting sets by FVA), c (a counterparty)."""
    scope = scope.strip() or "BANK"
    view = view if view in ("a", "b", "c") else view_of(scope)
    return render(request, "terminal/fva.html", scope=scope, view=view, screen="fva", mode="screen", command_text=f"{WORD} {scope}")


@router.get("/play/fva")
async def playground_page(request: Request, scope: str = ""):
    """PLAY FVA: the playground's shell."""
    scope = scope.strip()
    return render(request, "terminal/fva.html", scope=scope, view="", screen="play-fva", mode="play", command_text=f"PLAY FVA {scope}".strip())


async def _get(request: Request, path: str, **params):
    try:
        return JSONResponse(await request.app.state.backend.fva("GET", path, ident(request), **params), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/api/fva/scope")
async def scope_answer(request: Request):
    return await _get(request, "/fva/scope", **_params(request))


@router.get("/api/fva/playground")
async def playground(request: Request):
    return await _get(request, "/fva/playground", **_params(request))


@router.get("/api/fva/export.xlsx")
async def export(request: Request):
    """The workbook of live formulas for what is on screen (its choices), streamed from the server."""
    from routes.play_routes import _stream
    q = _params(request, ("scope", "eePoint", "fba", "survival", "spread", "asOf"))
    return await _stream(request, "/fva/playground.xlsx" + ("?" + urlencode(q) if q else ""), {}, "fva-playground.xlsx")


@router.get("/api/fva/example.xlsx")
async def example(request: Request):
    from routes.play_routes import _stream
    return await _stream(request, "/fva/playground/template", {}, "fva-playground-example.xlsx")
