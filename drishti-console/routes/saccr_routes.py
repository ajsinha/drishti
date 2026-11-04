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

"""SA-CCR in the console (docs/guides/SACCR_GUIDE.md sections 7 and 8): ``SACCR <GO>`` opens the SA-CCR page on the bank's netting sets;
``SACCR <netting set>`` (or ``SACCR NSET:<id>``) on one set, ``SACCR CPTY <id>`` (or ``CPTY:<id>``) on a counterparty's sets, ``SACCR
LE:<id>`` on a legal entity's, ``SACCR EXAMPLE`` on the hand-built cases. One page, three views over one answer of the server
(``/api/v1/saccr/scope``): A the EAD waterfall (the default), B the netting-set list with the selected set's breakdown, C a counterparty's
sets side by side. ``PLAY SACCR <netting set>`` opens the SA-CCR Playground (the grid of the set's trades with the formula bar and the
explanation, Export to Excel). Every figure is the server's; the pages are shells that web/static/js/saccr.js fills. A retired stored id
(``SACCR SACCR-ALDERSHOT-NY``) opens its netting set (``NS-ALDERSHOT-NY``)."""
from __future__ import annotations

from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "SACCR"
VIEWS = ("A", "B", "C")
#: the query words the pages pass through to the server
KEEP = ("scope", "nettingSet", "irBucketOffset", "cap", "strict", "asOf", "by")


def scope_of(words: list[str]) -> str:
    """The server's scope of a command's words: a netting set, CPTY <id>, LE:<id>, BANK, EXAMPLE; a retired SACCR-<cp>-<le> id its set."""
    if not words:
        return "BANK"
    first = words[0].upper()
    if first in ("CPTY", "COUNTERPARTY") and len(words) > 1:
        return "CPTY:" + words[1].upper()
    if first in ("LE", "NSET") and len(words) > 1:
        return f"{first}:{words[1]}"
    text = " ".join(words).strip()
    if text.upper().startswith("SACCR-"):                         # the retired stored id: the netting set of the same name
        return "NSET:NS-" + text[6:]
    if text.upper() in ("BANK", "EXAMPLE") or text.upper().startswith("EXAMPLE:"):
        return text.upper()
    if ":" in text:
        head, _, tail = text.partition(":")
        return f"{head.upper()}:{tail.strip()}"
    return "NSET:" + text


def view_of(scope: str) -> str:
    """The default view of a scope: one netting set opens on its waterfall (A), a counterparty on its sets side by side (C), more on the
    list (B). The person can switch view on the page."""
    if scope.startswith("NSET:"):
        return "A"
    if scope.startswith("CPTY:"):
        return "C"
    return "B" if scope in ("BANK",) or scope.startswith("LE:") or scope == "EXAMPLE" else "A"


def saccr_command(words: list[str]) -> str | None:
    """Where ``SACCR [<netting set> | CPTY <id> | LE:<id> | BANK | EXAMPLE]`` goes, or None when it is not the command."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    s = scope_of(words[1:])
    return "/saccr?" + urlencode({"scope": s, "view": view_of(s)})


def play_saccr_command(words: list[str]) -> str:
    """Where ``PLAY SACCR <netting set>`` goes; ``words`` are those after SACCR."""
    return "/play/saccr?" + urlencode({"scope": scope_of(words) if words else "EXAMPLE"})


def redirect_for(typed: str):
    where = saccr_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


def _params(request: Request) -> dict:
    qp = request.query_params
    return {k: qp.get(k) for k in KEEP if qp.get(k)}


@router.get("/saccr")
async def saccr_page(request: Request, scope: str = "BANK", view: str = ""):
    """SA-CCR: the page's shell; the view (A, B, C) is a tab of the same answer."""
    scope = scope.strip() or "BANK"
    v = view.strip().upper() if view.strip().upper() in VIEWS else view_of(scope)
    return render(request, "terminal/saccr.html", scope=scope, view=v, screen="saccr", mode="screen", command_text=f"{WORD} {scope}")


@router.get("/play/saccr")
async def playground_page(request: Request, scope: str = "EXAMPLE"):
    """PLAY SACCR: the playground's shell."""
    scope = scope.strip() or "EXAMPLE"
    return render(request, "terminal/saccr.html", scope=scope, view="", screen="play-saccr", mode="play", command_text=f"PLAY SACCR {scope}")


async def _get(request: Request, path: str, **params):
    try:
        return JSONResponse(await request.app.state.backend.saccr("GET", path, ident(request), **params), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.get("/api/saccr/scope")
async def scope_json(request: Request):
    return await _get(request, "/saccr/scope", **_params(request))


@router.get("/api/saccr/contributions")
async def contributions(request: Request):
    return await _get(request, "/saccr/scope/contributions", **_params(request))


@router.get("/api/saccr/trade/{trade_id}")
async def trade(request: Request, trade_id: str):
    return await _get(request, f"/saccr/trade/{trade_id}", **_params(request))


@router.get("/api/saccr/parameters")
async def parameters(request: Request):
    return await _get(request, "/saccr/parameters")


@router.get("/api/saccr/playground")
async def playground(request: Request):
    return await _get(request, "/saccr/playground", **_params(request))


@router.get("/api/saccr/export.xlsx")
async def export(request: Request):
    """The workbook of live formulas for what is on screen, streamed from the server."""
    from routes.play_routes import _stream
    q = _params(request)
    return await _stream(request, "/saccr/playground.xlsx" + ("?" + urlencode(q) if q else ""), {}, "saccr-playground.xlsx")


@router.get("/api/saccr/template.xlsx")
async def template(request: Request):
    from routes.play_routes import _stream
    return await _stream(request, "/saccr/playground/template", {}, "saccr-playground-template.xlsx")
