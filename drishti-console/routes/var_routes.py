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

"""The VaR explorer (docs/guides/VAR_GUIDE.md section 3): ``VAR DESK RATES <GO>`` opens
``/var?scope=DESK:RATES``. The page is a shell; web/static/js/var-explorer.js fills its panels from the same-origin JSON below, which
asks the server's VaR engine (/api/v1/var) with the user's own identity, so entitlements by node and masks apply as on every screen.

Command words after VAR (``VAR BANK``, ``VAR LE LE-NY``, ``VAR DIV MARKETS``, ``VAR DESK RATES``, ``VAR BOOK BOOK-RATES-1``,
``VAR POS <position>``, ``VAR TRD <trade>``, ``VAR CPTY <counterparty>``, ``VAR PORT <portfolio>``, ``VAR SCOPE <basket or portfolio>``,
``VAR CAPTURE <id>``) are read here. The stored ``var`` and ``var-node`` kinds were retired in 2.8.0 (docs/guides/VAR_GUIDE.md section 21):
an old id (``VAR VAR-COMM``, ``/v/var/VAR-COMM``, ``/v/var-node/VN-MGMT~DESK-RATES``) opens the explorer at the matching scope."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

#: the words after VAR that open the explorer, and the scope word each means
VAR_WORDS = {"BANK": "BANK", "LE": "LE", "DIV": "DIV", "DESK": "DESK", "BOOK": "BOOK", "NODE": "NODE", "POS": "POS", "TRD": "TRD",
             "TRADE": "TRD", "CPTY": "CPTY", "COUNTERPARTY": "CPTY", "PORT": "PORT", "PORTFOLIO": "PORT", "BASKET": "BASKET",
             "WHERE": "WHERE", "SCOPE": "SCOPE", "CAPTURE": "CAPTURE"}
#: the retired stored kinds whose ids and links open the explorer
RETIRED_KINDS = ("var", "var-node")


def retired_scope(old_id: str) -> str | None:
    """The scope an id of the retired stored VaR opens: ``VAR-<x>`` the desk ``DESK:<x>`` (``VAR-COMM``: ``DESK:COMM``, the node
    ``MGMT~DESK-COMM``), ``VN-<node>`` that node; None for anything else."""
    i = (old_id or "").strip()
    if i.upper().startswith("VAR-") and len(i) > 4:
        return "DESK:" + i[4:].upper()
    if i.upper().startswith("VN-") and len(i) > 3:
        return "NODE:" + i[3:]
    return None


def retired_link(kind: str, old_id: str) -> str | None:
    """Where ``/v/<kind>/<id>`` of a retired kind goes: the explorer at the id's scope (the bank when it names none), with a notice."""
    if kind not in RETIRED_KINDS:
        return None
    scope = retired_scope(old_id) or "BANK"
    return f"/var?scope={quote(scope)}&from={quote(old_id)}"


def var_command(words: list[str], user: str) -> str | None:
    """Where ``VAR <word> <rest>`` goes (``VAR VAR-COMM``: a retired id, its desk), or None when it is not an explorer command."""
    if not words or words[0].upper() != "VAR":
        return None
    if len(words) == 1:
        return "/var"
    word = words[1].upper()
    if word not in VAR_WORDS:
        scope = retired_scope(words[1]) if len(words) == 2 else None
        return f"/var?scope={quote(scope)}&from={quote(words[1])}" if scope else None
    rest = " ".join(words[2:]).strip()
    kind = VAR_WORDS[word]
    if kind == "BANK":
        return "/var?scope=BANK"
    if kind == "CAPTURE":
        return f"/var?capture={quote(rest)}" if rest else "/var"
    if not rest:
        return None
    if kind == "SCOPE":                       # a saved basket (mine: its name; another's: owner/name), a portfolio, or scope text
        if ":" in rest or " " in rest:
            scope = rest
        elif rest.upper().startswith("PF-"):
            scope = "PORT:" + rest.upper()
        else:
            scope = "BASKET:" + (rest if "/" in rest else f"{user}/{rest}")
    elif kind in ("POS", "TRD", "CPTY", "WHERE", "BASKET"):
        scope = f"{kind}:{rest}"
    else:
        scope = f"{kind}:{rest.replace(' ', '-')}"
    return f"/var?scope={quote(scope)}"


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/var")
async def page(request: Request, scope: str = "", capture: str = "", hierarchy: str = "MGMT"):
    """The explorer's shell; its panels load in place (no full-page reload when drilling)."""
    me = getattr(request.state, "identity", None)
    return render(request, "terminal/var.html", scope=scope or "BANK", capture=capture, hierarchy=hierarchy.upper() or "MGMT",
                  user=(me.user if me else "") or "", screen="var")


#: GET endpoints of the engine the page may ask for, and the parameters each passes on
_GETS = {"scope": ("scope",), "hints": ("q", "limit"), "scope/path": ("scope", "page", "size"), "scope/history": ("scope",),
         "scope/contributions": ("scope", "by", "scenario"), "tree": ("hierarchy",), "baskets": (), "captures": ("scope",),
         "compare": ("a", "b"), "status": (), "official": ()}


@router.get("/api/var/{path:path}")
async def api_get(request: Request, path: str):
    q = request.query_params
    try:
        if path.startswith("captures/") and path.endswith(("/csv", "/jsonl")):     # a capture's CSV or JSON Lines (2.9.4), as the server made it
            body, headers = await request.app.state.backend.var("GET", "/" + path, ident(request), raw=True, part=q.get("part", ""))
            return Response(body, media_type="text/csv" if path.endswith("/csv") else "application/jsonl",
                            headers={"Content-Disposition": headers.get("content-disposition", "attachment")})
        if path.startswith("captures/") or path.startswith("baskets/"):
            return await request.app.state.backend.var("GET", "/" + path, ident(request))
        if path not in _GETS:
            return JSONResponse({"code": "DRS-1001", "detail": f"no VaR endpoint {path}"}, status_code=404)
        params = {k: q.get(k, "") for k in _GETS[path]}
        if q.get("asOf"):
            params["asOf"] = q.get("asOf")
        return await request.app.state.backend.var("GET", "/" + path, ident(request), **params)
    except BackendError as e:
        return _problem(e)


@router.post("/api/var/{path:path}")
async def api_post(request: Request, path: str):
    if path not in ("baskets", "captures", "what-if"):
        return JSONResponse({"code": "DRS-1001", "detail": f"no VaR endpoint {path}"}, status_code=404)
    try:
        return await request.app.state.backend.var("POST", "/" + path, ident(request), body=await json_body(request),
                                                   asOf=request.query_params.get("asOf", ""))
    except BackendError as e:
        return _problem(e)


@router.delete("/api/var/baskets/{owner}/{name}")
async def api_delete_basket(request: Request, owner: str, name: str):
    try:
        await request.app.state.backend.var("DELETE", f"/baskets/{quote(owner)}/{quote(name)}", ident(request))
    except BackendError as e:
        return _problem(e)
    return Response(status_code=204)


def redirect_for(typed: str, user: str) -> RedirectResponse | None:
    """The terminal's ``/go`` asks here first: a VaR explorer command, or None."""
    where = var_command(typed.split(), user)
    return RedirectResponse(where, status_code=303) if where else None
