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
"""The CCR cube in the console (docs/guides/EXPOSURE_GUIDE.md section 30): not a page of its own but the pivot screen (terminal/pivot.html)
on source ``ccr`` with the CCR lens (``lens=ccr``: the headline strip, the lens controls and views A, B and C drawn by
web/static/js/ccr-lens.js; A the landing). ``CCUBE <GO>`` opens the source's landing view (the catalogue's defaultView: the limit board,
groups and counterparties by tenor bucket, peak PFE and its use); ``CCUBE GROUP:<g>`` or ``CPTY:<c>`` filters the board to it,
``CCUBE NSET:<n>`` opens view B on the netting set, ``CCUBE ... BY rows[,columns] MEASURE m,... Q 0.99 DATE d VIEW B <GO>`` lays what
was typed over it; ``CCUBE @owner/name <GO>`` opens a saved pivot view. The lens's own answers are the server's ``/api/v1/pivot/ccr/...``,
asked as the person through ``/api/pivot/ccr/...``; the grid is the pivot engine's (pivot_routes.py); views B and C read the exposure,
SA-CCR and FVA engines through their own proxies (exposure_routes.py, saccr_routes.py, fva_routes.py). Nothing is computed here."""
from __future__ import annotations

import json
from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "CCUBE"
KEYWORDS = ("BY", "MEASURE", "MEASURES", "DATE", "Q", "VIEW", "COLLATERAL")
#: the measure words of the CCR cube's grammar and the column headers they get (the source ccr's native measures)
LABELS = {"pfe": "Peak PFE", "ee": "Peak EE", "epe": "EPE", "eepe": "EEPE", "ce": "CE", "limit": "Limit", "limit_use": "Use",
          "headroom": "Headroom", "nettingsets": "Netting sets", "ead": "EAD SA-CCR", "fva": "FVA"}
#: the scope words a CCUBE command takes, and the dimension each filters
SCOPES = {"GROUP": "GROUP", "CPTY": "COUNTERPARTY", "LE": "LEGAL_ENTITY", "NSET": "NETTING_SET"}
VIEWS = ("A", "B", "C")


def _measure(m: str) -> dict:
    m = m.strip()
    m = m[:-2] if m.endswith("()") else m
    name = "nettingSets" if m.lower() == "nettingsets" else m.lower()
    return {"measure": name + "()", "as": LABELS.get(name.lower(), name)}


def parse(words: list[str]) -> dict:
    """``CCUBE`` words after the command as a partial pivot view and the lens's choices: a scope word as a filter (``NSET:`` also picks
    view B), rows and columns, values, the confidence and collateral as the source's options, the date and the view. Nothing is computed."""
    out: dict = {}
    options: dict = {}
    i = 0
    while i < len(words):
        w = words[i].upper()
        if w in KEYWORDS:
            arg = words[i + 1] if i + 1 < len(words) else ""
            if w == "BY":
                dims = [d for d in arg.split(",") if d]
                out["rows"] = [("COUNTERPARTY" if d.upper() == "CPTY" else d.upper()) for d in dims[:1]]
                out["columns"] = [("COUNTERPARTY" if d.upper() == "CPTY" else d.upper()) for d in dims[1:2]]
            elif w.startswith("MEASURE"):
                vals = [_measure(m) for m in arg.split(",") if m.strip()]
                if vals:
                    out["values"] = vals
            elif w == "DATE":
                out["date"] = arg
            elif w == "Q":
                options["q"] = arg
            elif w == "COLLATERAL":
                options["collateral"] = arg.lower()
            elif w == "VIEW" and arg.upper() in VIEWS:
                out["lensView"] = arg.upper()
            i += 2
            continue
        word, _, rest = words[i].partition(":")
        if word.upper() in SCOPES and rest:
            dim = SCOPES[word.upper()]
            out.setdefault("filters", {})[dim] = {"in": [x for x in rest.split(",") if x]}
            if dim == "NETTING_SET":
                out.setdefault("lensView", "B")
                out["lensScope"] = "NSET:" + rest
            elif dim == "COUNTERPARTY":
                out["lensScope"] = "CPTY:" + rest
        i += 1
    if options:
        out["options"] = options
    return out


def ccube_command(words: list[str]) -> str | None:
    """Where ``CCUBE ...`` goes, or None when it is not the command."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    rest = [w for w in words[1:] if w.upper() != "BANK"]
    if not rest:
        return "/ccube"
    if len(rest) == 1 and rest[0].startswith("@") and "/" in rest[0]:
        return "/ccube?" + urlencode({"view": rest[0][1:]})
    return "/ccube?" + urlencode({"text": " ".join(rest)})


def redirect_for(typed: str):
    where = ccube_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    from core.busy import problem as busy_problem

    return busy_problem(e, e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/ccube")
async def page(request: Request, text: str = "", view: str = "", date: str = "", tab: str = ""):
    """CCUBE: the pivot screen on the source ccr with the CCR lens (A the landing; B a netting set; C pre-deal and XVA)."""
    preset = parse(text.split()) if text.strip() else {}
    if date and "date" not in preset:
        preset["date"] = date
    lens_view = preset.pop("lensView", "A")
    lens_scope = preset.pop("lensScope", "")
    if tab.upper() in VIEWS:
        lens_view = tab.upper()
    command = f"{WORD} @{view}" if view else f"{WORD} {text or 'BANK'}".strip()
    return render(request, "terminal/pivot.html", screen="ccube", source="ccr", view_id=view.strip(), date=preset.get("date", ""),
                  command_text=command, lens="ccr", lens_view=lens_view, lens_scope=lens_scope, preset=json.dumps(preset) if preset else "")


async def _call(request: Request, method: str, path: str, body=None, **params):
    try:
        return JSONResponse(await request.app.state.backend.pivot(method, "/ccr" + path, ident(request), body=body, **params),
                            headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


@router.post("/api/pivot/ccr/headline")
async def headline(request: Request):
    b = await json_body(request)
    return await _call(request, "POST", "/headline", b if isinstance(b, dict) else {})


@router.get("/api/pivot/ccr/fan")
async def fan(request: Request):
    q = request.query_params
    return await _call(request, "GET", "/fan", **{k: q.get(k, "") for k in ("scope", "q", "collateral", "asOf")})


@router.post("/api/pivot/ccr/pre-deal")
async def pre_deal(request: Request):
    """View C's pre-deal check: the exposure engine's what-if with the limit's use and headroom before and after (the server's)."""
    b = await json_body(request)
    q = request.query_params
    return await _call(request, "POST", "/pre-deal", b if isinstance(b, dict) else {}, asOf=q.get("asOf", ""))
