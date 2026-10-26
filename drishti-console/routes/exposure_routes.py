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

"""The exposure explorer (docs/guides/EXPOSURE_GUIDE.md section 3): ``EXP <scope> <GO>`` opens ``/exposure?scope=<scope>``. The page
is a shell; web/static/js/exposure-explorer.js fills it from the same-origin JSON below, which asks the server's exposure engine
(/api/v1/exposure) as the user, so entitlements by counterparty, group and legal entity and the name mask apply as on every screen.

Commands: ``EXP`` (the bank), ``EXP NSET:NS-ALDERSHOT-FRA`` as typed, and the spaced forms ``EXP NSET NS-ALDERSHOT-FRA``, ``EXP CPTY
CP-ALDERSHOT``, ``EXP GROUP GRP-ALDERSHOT``, ``EXP LE LE-NY``, ``EXP TRD <trade>`` made into the scope grammar; any scope text
(``EXP CPTY:CP-X - NSET:NS-X-FRA``). The computed kind ``exposure-result`` stays an ordinary kind (``/v/exposure-result/<scope>``,
the API, the CLI); the explorer is its main screen. The stored ``exposure-profile`` kind was retired in 2.9.0: an old id (``EXP
EXP-ALDERSHOT-FRA``) and an old link (``/v/exposure-profile/EXP-ALDERSHOT-FRA``) open the same netting set in the explorer, with a
notice."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

KIND = "exposure-result"
#: the words after EXP that name a scope, and the scope word each means
EXP_WORDS = {"NSET": "NSET", "NETTING-SET": "NSET", "CPTY": "CPTY", "COUNTERPARTY": "CPTY", "GROUP": "GROUP", "GRP": "GROUP", "LE": "LE",
             "DESK": "DESK", "BOOK": "BOOK", "TRD": "TRD", "TRADE": "TRD", "BANK": "BANK"}
RETIRED_KINDS = ("exposure-profile",)


def retired_scope(old_id: str) -> str | None:
    """The scope an id of the retired stored exposure profile opens: ``EXP-<cp>-<le>`` the netting set ``NSET:NS-<cp>-<le>``."""
    i = (old_id or "").strip()
    if i.upper().startswith("EXP-") and len(i) > 4:
        return "NSET:NS-" + i[4:].upper()
    return None


def explorer(scope: str, old: str | None = None) -> str:
    """The explorer's address for a scope (``from``: the retired id it was opened by)."""
    return f"/exposure?scope={quote(scope, safe=':')}" + (f"&from={quote(old)}" if old else "")


def retired_link(kind: str, old_id: str) -> str | None:
    """Where ``/v/<kind>/<id>`` of the retired kind goes: the explorer at its netting set (the bank when it names none)."""
    if kind not in RETIRED_KINDS:
        return None
    return explorer(retired_scope(old_id) or "BANK", old_id)


def exp_command(words: list[str]) -> str | None:
    """Where ``EXP <words>`` goes: the explorer at the scope it means, or None when the words are not an exposure command."""
    if not words or words[0].upper() != "EXP":
        return None
    if len(words) == 1:
        return explorer("BANK")
    if len(words) == 2:
        old = retired_scope(words[1])
        if old:
            return explorer(old, words[1])
    word = words[1].upper()
    if word in EXP_WORDS and ":" not in words[1]:
        kind = EXP_WORDS[word]
        if kind == "BANK" or len(words) == 2:
            return explorer("BANK") if kind == "BANK" else None
        rest = ",".join(w.strip(",") for w in words[2:] if w.strip(",")) if kind in ("NSET", "CPTY", "TRD") else "".join(words[2:])
        return explorer(f"{kind}:{rest}")
    if len(words) == 2 and ":" not in words[1]:                  # a bare id: EXP NS-ALDERSHOT-FRA, EXP CP-ALDERSHOT
        for prefix, word in (("NS-", "NSET"), ("CP-", "CPTY"), ("GRP-", "GROUP"), ("LE-", "LE")):
            if words[1].upper().startswith(prefix):
                return explorer(f"{word}:{words[1].upper()}")
    return explorer(" ".join(words[1:]))


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/exposure")
async def page(request: Request, scope: str = "", capture: str = ""):
    """The explorer's shell; its panels load in place (no full-page reload when drilling)."""
    me = getattr(request.state, "identity", None)
    return render(request, "terminal/exposure.html", scope=scope or "BANK", capture=capture, user=(me.user if me else "") or "",
                  screen="exposure")


#: GET endpoints of the engine the page may ask for, and the parameters each passes on
_GETS = {"scope": ("scope", "q", "collateral"), "scope/profile": ("scope", "q", "collateral", "dates", "format"),
         "scope/distribution": ("scope", "q", "collateral", "date", "bins", "bands"),
         "scope/contributions": ("scope", "q", "collateral", "by", "figure", "date", "alone", "profile"), "tree": ("q",),
         "captures": ("scope",), "compare": ("a", "b"), "status": (), "official": (), "retention": ()}


@router.get("/api/exposure/{path:path}")
async def api_get(request: Request, path: str):
    q = request.query_params
    try:
        csv = path.startswith("captures/") and path.endswith("/csv") or path == "scope/profile" and q.get("format") == "csv"
        if path.startswith("captures/") and not csv:
            return await request.app.state.backend.exposure("GET", "/" + path, ident(request))
        if path not in _GETS and not csv:
            return JSONResponse({"code": "DRS-1001", "detail": f"no exposure endpoint {path}"}, status_code=404)
        params = {k: q.get(k, "") for k in _GETS.get(path, ())}
        if q.get("asOf"):
            params["asOf"] = q.get("asOf")
        if csv:
            body, headers = await request.app.state.backend.exposure("GET", "/" + path, ident(request), raw=True, **params)
            return Response(body, media_type="text/csv",
                            headers={"Content-Disposition": headers.get("content-disposition", "attachment")})
        return await request.app.state.backend.exposure("GET", "/" + path, ident(request), **params)
    except BackendError as e:
        return _problem(e)


@router.post("/api/exposure/{path:path}")
async def api_post(request: Request, path: str):
    if path not in ("captures", "what-if"):
        return JSONResponse({"code": "DRS-1001", "detail": f"no exposure endpoint {path}"}, status_code=404)
    q = request.query_params
    try:
        return await request.app.state.backend.exposure("POST", "/" + path, ident(request), body=await json_body(request),
                                                        **({"asOf": q.get("asOf")} if q.get("asOf") else {}))
    except BackendError as e:
        return _problem(e)


def redirect_for(typed: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here: an exposure command (the explorer), or None."""
    where = exp_command(typed.split())
    return RedirectResponse(where, status_code=303) if where else None
