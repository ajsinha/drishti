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

"""The MR cube in the console (docs/guides/MR_CUBE_GUIDE.md): not a page of its own but the pivot screen (terminal/pivot.html) on source
``var`` with the MR lens (``lens=mr``: the headline strip, the lens controls, the time-travel slider and tabs A, B and C drawn by
web/static/js/drishti_mr-lens.js). ``MCUBE <GO>`` opens the source's landing view (the catalogue's defaultView: desks by risk class, VaR);
``MCUBE <scope> [BY rows[,columns]] [MEASURE m,...] [DATE d] [SORT desc] <GO>`` lays what was typed over it (the MR cube's preset of the
PIVOT grammar); ``MCUBE @owner/name <GO>`` opens a saved pivot view. The lens's own answers are the server's ``/api/v1/pivot/mr/...``,
asked as the person through the same-origin proxy ``/api/pivot/mr/...``; the grid is the pivot engine's (pivot_routes.py)."""
from __future__ import annotations

import json
from urllib.parse import urlencode

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

WORD = "MCUBE"
KEYWORDS = ("BY", "MEASURE", "MEASURES", "DATE", "SORT")
#: the measure words of the MR cube's grammar and the column headers they get (the VaR source's native measures)
LABELS = {"var": "VaR", "es": "ES", "pvar": "Parametric VaR", "comp_var": "Comp VaR", "comp_es": "Comp ES", "incr_var": "Incr VaR", "pv": "PV", "trades": "Trades",
          "limit": "Limit", "limit_use": "Limit use", "svar": "Stressed VaR", "dv01": "DV01", "cs01": "CS01", "sbm": "SBM capital"}


def parse(words: list[str]) -> dict:
    """``MCUBE`` words after the command as a partial pivot view: the scope (an option of the VaR source), rows and columns, values, the date
    and a descending sort of the first row dimension by the first value. Nothing is computed here."""
    out: dict = {}
    scope: list[str] = []
    i = 0
    while i < len(words):
        w = words[i].upper()
        if w in KEYWORDS:
            arg = words[i + 1] if i + 1 < len(words) else ""
            if w == "BY":
                dims = [d for d in arg.split(",") if d]
                out["rows"] = dims[:1]
                out["columns"] = dims[1:2]
                i += 2
            elif w.startswith("MEASURE"):
                vals = []
                for m in (x.strip().lower() for x in arg.split(",") if x.strip()):
                    m = m[:-2] if m.endswith("()") else m
                    vals.append({"measure": m + "()", "as": LABELS.get(m, m)})
                if vals:
                    out["values"] = vals
                i += 2
            elif w == "DATE":
                out["date"] = arg
                i += 2
            else:                                                      # SORT desc
                out["sort"] = "desc" if arg.lower() == "desc" else "asc"
                i += 2 if arg.lower() in ("desc", "asc") else 1
            continue
        scope.append(words[i])
        i += 1
    if scope:
        out["options"] = {"scope": " ".join(scope)}
    if "sort" in out:
        s = out.pop("sort")
        rows = out.get("rows") or []
        first = (out.get("values") or [{"as": "VaR"}])[0]["as"]
        if rows:
            out["sort"] = {rows[0]: {"by": first, "desc": s == "desc"}}
    return out


def mcube_command(words: list[str]) -> str | None:
    """Where ``MCUBE ...`` goes, or None when it is not the command."""
    if words and words[-1].upper() == "<GO>":
        words = words[:-1]
    if not words or words[0].upper() != WORD:
        return None
    if len(words) == 1:
        return "/mcube"
    if len(words) == 2 and words[1].startswith("@") and "/" in words[1]:
        return "/mcube?" + urlencode({"view": words[1][1:]})
    return "/mcube?" + urlencode({"text": " ".join(words[1:])})


def redirect_for(typed: str):
    where = mcube_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None


def _problem(e: BackendError) -> JSONResponse:
    from core.busy import problem as busy_problem

    return busy_problem(e, e.status if 400 <= (e.status or 0) < 600 else 502)


@router.get("/mcube")
async def page(request: Request, text: str = "", view: str = "", date: str = ""):
    """MCUBE: the pivot screen on the VaR source with the MR lens."""
    preset = parse(text.split()) if text.strip() else {}
    if date and "date" not in preset:
        preset["date"] = date
    command = f"{WORD} @{view}" if view else f"{WORD} {text}".strip()
    return render(request, "terminal/pivot.html", screen="mcube", source="var", view_id=view.strip(), date=preset.get("date", ""),
                  command_text=command, lens="mr", preset=json.dumps(preset) if preset else "")


async def _call(request: Request, method: str, path: str, body=None, **params):
    try:
        return JSONResponse(await request.app.state.backend.pivot(method, "/mr" + path, ident(request), body=body, **params),
                            headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return _problem(e)


async def _body(request: Request) -> dict:
    """The request's JSON object through the console's one reader (400 for bad JSON, 415 when not sent as JSON)."""
    b = await json_body(request)
    return b if isinstance(b, dict) else {}


@router.get("/api/pivot/mr/versions")
async def versions(request: Request):
    return await _call(request, "GET", "/versions")


@router.get("/api/pivot/mr/explain")
async def explain(request: Request, scope: str = "", from_: str = "", to: str = "", confidence: str = ""):
    q = request.query_params
    return await _call(request, "GET", "/explain", scope=q.get("scope", scope), to=q.get("to", to), confidence=q.get("confidence", confidence),
                       **{"from": q.get("from", from_)})


@router.get("/api/pivot/mr/sensitivities")
async def sensitivities(request: Request):
    q = request.query_params
    return await _call(request, "GET", "/sensitivities", **{k: q.get(k, "") for k in ("scope", "grid", "by", "class", "measure", "ccy", "asOf")})


@router.post("/api/pivot/mr/{action}")
async def post(request: Request, action: str):
    if action not in ("headline", "detail", "history", "what-if"):
        return JSONResponse({"detail": "no such MR cube action"}, status_code=404)
    return await _call(request, "POST", "/" + action, await _body(request))
