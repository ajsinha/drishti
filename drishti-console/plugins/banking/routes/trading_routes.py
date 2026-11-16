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

"""Drishti Trading's and Drishti Market's landings (docs/guides/ADMIN_GUIDE.md › The design): the console draws, the modules compute
(THIN_CONSOLE_DESIGN.md step S8a). The figures are worked out on the server by the trading module (``/api/v1/trading/landing``) and the
market-data module (``/api/v1/market-data/landing``), as the person; these routes pass the person's call on and give back the answer.

``GET /api/home/trading/{part}``: ``me`` (who "my" is, the picker's choices when the person may pick; ``check`` added here: whether the
person's edition shows the VaR and exposure words the new-trade check needs), ``valuations``, ``blotter`` (the grid's declaration is made
here from the live result set the server answered with: presentation), ``risk``, ``limits``, ``pnl-var`` and ``traders``; ``?for=desk`` a
trader's desk. ``POST /api/home/trading/check``. Each for a signed-in person whose edition or lens shows the trades (``TRD``; the check also
``VAR`` and ``EXP``); else ``404 DRS-4023``. ``GET /home/trading/view-as?as=trader:<id>|desk:<id>`` keeps the person's choice in their settings
(``tradingView``) when the server says they may pick; ``as=`` empty forgets it.

``GET /api/home/market/{desk,moves,quality}``: Drishti Market's figures, for a person whose edition shows the curves (``CRV``). The trading
role's landing rule asks the server for the person's trading role (core/plugins.py, DOMAIN_MODULES.md C6)."""
from __future__ import annotations

import re
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import live_results
from core import plugins as _plugins
from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident
from routes.edition_routes import _patch, edition_of

router = APIRouter(include_in_schema=False)

TRADING = "/trading/landing"
MARKET = "/market-data/landing"
PARTS = ("me", "valuations", "blotter", "risk", "limits", "pnl-var", "traders")
VIEW = re.compile(r"^(trader|desk):[A-Za-z0-9][A-Za-z0-9._~-]{0,79}$")
NO_STORE = {"Cache-Control": "no-store"}


def _refused(request, needs: tuple[str, ...], words: tuple[str, ...] = ()) -> JSONResponse | None:
    ed = edition_of(request)
    if getattr(request.state, "identity", None) is None:
        return JSONResponse({"detail": "sign in first"}, status_code=401)
    if not ed.active or not all(ed.command_shown(c) for c in needs) \
            or not all(ed.word_on(w) for w in words):
        return JSONResponse({"code": "DRS-4023", "detail": f"trading is not part of {ed.title if ed.active else 'a landing'}"}, status_code=404)
    return None


def _problem(e: BackendError) -> dict:
    return {"available": False, "reason": f"{e.code or e.status} {e.detail}"}


def event_spec(spec: dict, business: str) -> dict:
    """The blotter's grid declaration with what the old table showed: an Event pill (cancelled by status, new by action or booked on the
    day, else amended: the server's rule, as the grid's ``cases``) after the trade, and the valuations' Notional last."""
    cols = list(spec["columns"])
    event = {"field": "EVENT", "label": "Event", "type": "status", "width": 96, "otherwise": "amended",
             "cases": [{"when": {"TRADE_STATUS": "CANCELLED"}, "text": "cancelled"}, {"when": {"TRADE_ACTION": "NEW"}, "text": "new"},
                       {"when": {"TRADE_DATE": business + "*"}, "text": "new"}]}
    return {**spec, "columns": cols[:1] + [event] + cols[1:] + [{"field": "NOTIONAL", "label": "Notional", "type": "number", "format": "amount0"}]}


def _blotter(out: dict) -> dict:
    """The server's blotter answer with the grid's declaration drawn from its live result set (``page``, not sent on)."""
    page = out.pop("page", None)
    if page is not None and out.get("available"):
        values = {e["trade"]: e.get("notional") for e in out.get("rows") or [] if e.get("notional") is not None}
        spec = event_spec(live_results.grid_spec(live_results.as_search(page)), str(out.get("businessDate") or ""))
        out["spec"] = {**spec, "columns": [dict(c, lookup={"key": "__id", "values": values}) if c["field"] == "NOTIONAL" else c
                                           for c in spec["columns"]]}
    return out


@router.get("/api/home/trading/{part}")
async def trading_part(request: Request, part: str):
    if (no := _refused(request, ("TRD",))) is not None:
        return no
    if part not in PARTS:
        return JSONResponse({"code": "DRS-4004", "detail": f"no trading figures {part}: {', '.join(PARTS)}"}, status_code=404)
    backend, me, ed = request.app.state.backend, ident(request), edition_of(request)
    params = {"for": "desk"} if request.query_params.get("for") == "desk" else {}
    try:
        out = await backend.engine("GET", f"{TRADING}/{part}", me, **params)
    except BackendError as e:
        return JSONResponse(_problem(e), headers=NO_STORE)
    if part == "me":
        out["check"] = ed.word_on("VAR") and ed.word_on("EXP")
    elif part == "blotter":
        out = _blotter(out)
    return JSONResponse(out, headers=NO_STORE)


@router.post("/api/home/trading/check")
async def trading_check(request: Request):
    if (no := _refused(request, ("TRD",), ("VAR", "EXP"))) is not None:
        return no
    body = await json_body(request, limit=8192)              # a body that is not JSON: 400 or 415 (core/csrf.py)
    if not isinstance(body, dict):
        return JSONResponse({"ok": False, "reason": "a check is {product, notional, counterparty, side, book}"}, status_code=400)
    try:
        out = await request.app.state.backend.engine("POST", f"{TRADING}/check", ident(request), body=body)
    except BackendError as e:
        out = {"ok": False, "reason": f"{e.code or e.status} {e.detail}"}
    return JSONResponse(out, headers=NO_STORE)


@router.get("/home/trading/view-as")
async def view_as(request: Request):
    """Keeps the person's View as choice (their settings, ``tradingView``), only when the server says they may pick; then Home."""
    as_ = str(request.query_params.get("as") or "").strip()
    if getattr(request.state, "identity", None) is None:
        return RedirectResponse(f"/login?next={quote('/')}", status_code=303)
    if (no := _refused(request, ("TRD",))) is not None:
        return no
    try:
        me = await request.app.state.backend.engine("GET", f"{TRADING}/me", ident(request))
    except BackendError as e:
        return RedirectResponse(f"/t?error={quote(f'{e.code or e.status} {e.detail}')}", status_code=303)
    if not (me.get("scope") or {}).get("canPick"):
        return RedirectResponse(f"/t?error={quote('DRS-4023 your scope is set by your administrator (trading.people)')}", status_code=303)
    if as_ and not VIEW.match(as_):
        return RedirectResponse(f"/t?error={quote('DRS-4004 View as is trader:<id> or desk:<id>')}", status_code=303)
    await _patch(request, {"tradingView": as_ or None})
    return RedirectResponse("/", status_code=303)


@router.get("/api/home/market/{part}")
async def market_figures(request: Request, part: str):
    """Drishti Market's landings: ``desk``, ``moves`` and ``quality``, the market-data module's answers as the person. For a signed-in
    person whose edition (or lens) shows the curves (CRV); else 404 DRS-4023."""
    ed = edition_of(request)
    if getattr(request.state, "identity", None) is None:
        return JSONResponse({"detail": "sign in first"}, status_code=401)
    if part not in ("desk", "moves", "quality"):
        return JSONResponse({"code": "DRS-4004", "detail": f"no market figures {part}: desk, moves or quality"}, status_code=404)
    if not ed.active or not ed.command_shown("CRV"):
        return JSONResponse({"code": "DRS-4023", "detail": f"market data is not part of {ed.title if ed.active else 'a landing'}"},
                            status_code=404)
    try:
        out = await request.app.state.backend.engine("GET", f"{MARKET}/{part}", ident(request))
    except BackendError as e:
        out = {"problems": [f"{e.code or e.status} {e.detail}"]}
    return JSONResponse(out, headers=NO_STORE)


async def _trading_landing(request, ed, offered: list[str], me, mine: dict) -> str | None:
    """Drishti Trading: the person's trading role (desk-head, trader; the server's answer) picks the first landing, below their own choice
    (LENSES.md section 6)."""
    if not ed._get("trading"):
        return None
    try:
        role = str((await request.app.state.backend.engine("GET", f"{TRADING}/role", ident(request))).get("role") or "")
    except BackendError:
        return None
    return ed.landing_by_role("trading.landing-by-role").get(role) if role else None


_plugins.landing_rule(_trading_landing)
