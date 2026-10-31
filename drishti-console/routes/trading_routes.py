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

"""Drishti Trading's landings (docs/admin/EDITIONS.md › The design as built, section 14): the read-only figures of the Trader desk and Desk head, worked out
by core/trading_landing.py from the servers' own answers as the person, and their "View as" choice.

``GET /api/home/trading/{part}``: ``me`` (who "my" is, and the picker's choices when the person may pick), ``valuations`` (P&L today, by
book and trader, movers, DV01, the largest counterparty share), ``blotter`` (today's trades), ``risk`` (VaR, limits, positions),
``limits`` (limit use by book over the last business dates), ``pnl-var`` (the desk's P&L against VaR) and ``traders`` (by trader); with
``?for=desk`` a trader's figures are their desk's (the Desk head landing);
``POST /api/home/trading/check`` (the new-trade check). Each for a signed-in person whose edition or lens shows the trades (``TRD``; the
check also ``VWIF`` and ``XWIF``); else ``404 DRS-4023``. ``GET /home/trading/view-as?as=trader:<id>|desk:<id>`` keeps the person's
choice in their settings (``tradingView``) when they may pick (no mapping, or an administrator); ``as=`` empty forgets it."""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import trading_landing as tl
from core.backend import BackendError
from core.csrf import json_body
from routes.common import ident
from routes.edition_routes import _patch, edition_of

router = APIRouter(include_in_schema=False)

PARTS = {"valuations": tl.valuations, "blotter": tl.blotter, "risk": tl.risk, "limits": tl.limits, "pnl-var": tl.pnl_vs_var,
         "traders": tl.traders}


def _refused(request, needs: tuple[str, ...]) -> JSONResponse | None:
    ed = edition_of(request)
    if getattr(request.state, "identity", None) is None:
        return JSONResponse({"detail": "sign in first"}, status_code=401)
    if not ed.active or not ed.commands() or not all(ed.command_shown(c) for c in needs):
        return JSONResponse({"code": "DRS-4023", "detail": f"trading is not part of {ed.title if ed.active else 'a landing'}"}, status_code=404)
    return None


async def mine(request) -> dict:
    """The person's settings (their View as choice): a page has them already; an /api/ request reads them (cached per worker)."""
    s = getattr(request.state, "settings", None)
    if s is None:
        from core.settings import changed_at
        try:
            s = await request.app.state.user_settings.get(request.app.state.backend, request.state.identity, changed_at(request))
        except Exception:  # noqa: BLE001 - no settings: no choice
            s = {}
    return s or {}


async def _scope(request):
    ed = edition_of(request)
    cfg = tl.config(ed, request.app.state.settings)
    scope = await tl.resolve(request.app.state.backend, ident(request), ed, request.app.state.settings, await mine(request))
    return ed, cfg, scope


@router.get("/api/home/trading/{part}")
async def trading_part(request: Request, part: str):
    if (no := _refused(request, ("TRD",))) is not None:
        return no
    if part != "me" and part not in PARTS:
        return JSONResponse({"code": "DRS-4004", "detail": f"no trading figures {part}: me, {', '.join(PARTS)}"}, status_code=404)
    ed, cfg, scope = await _scope(request)
    backend, me = request.app.state.backend, ident(request)
    if request.query_params.get("for") == "desk":               # the Desk head landing of a trader: their desk
        scope = await tl.desk_of(backend, me, scope)
    if part == "me":
        out = {"scope": scope.as_dict(), "near": tl.near_limit(cfg), "admin": bool(me.is_admin),
               "check": all(ed.command_shown(c) for c in ("VWIF", "XWIF"))}
        if scope.can_pick:
            out["options"] = await tl.options(backend, me)
        return JSONResponse(out, headers={"Cache-Control": "no-store"})
    if not scope.kind:
        return JSONResponse({"available": False, "reason": "pick a trader or a desk to view as", "scope": scope.as_dict()},
                            headers={"Cache-Control": "no-store"})
    try:
        out = tl.public(await PARTS[part](backend, me, scope, cfg))
    except BackendError as e:
        out = {"available": False, "reason": f"{e.code or e.status} {e.detail}"}
    return JSONResponse(out, headers={"Cache-Control": "no-store"})


@router.post("/api/home/trading/check")
async def trading_check(request: Request):
    if (no := _refused(request, ("TRD", "VWIF", "XWIF"))) is not None:
        return no
    body = await json_body(request, limit=8192)              # a body that is not JSON: 400 or 415 (core/csrf.py)
    if not isinstance(body, dict):
        return JSONResponse({"ok": False, "reason": "a check is {product, notional, counterparty, side, book}"}, status_code=400)
    _ed, cfg, scope = await _scope(request)
    if not scope.kind:
        return JSONResponse({"ok": False, "reason": "pick a trader or a desk to view as first"})
    return JSONResponse(await tl.check(request.app.state.backend, ident(request), scope, cfg, body), headers={"Cache-Control": "no-store"})


@router.get("/home/trading/view-as")
async def view_as(request: Request):
    """Keeps the person's View as choice (their settings, ``tradingView``), only when they may pick; then Home."""
    as_ = str(request.query_params.get("as") or "").strip()
    if getattr(request.state, "identity", None) is None:
        return RedirectResponse(f"/login?next={quote('/')}", status_code=303)
    if (no := _refused(request, ("TRD",))) is not None:
        return no
    ed, _cfg, scope = await _scope(request)
    me = ident(request)
    mapped = tl.config(ed, request.app.state.settings)["people"].get(str(me.user).lower())
    if not me.is_admin and (mapped or scope.source == "record"):
        return RedirectResponse(f"/t?error={quote('DRS-4023 your scope is set by your administrator (trading.people)')}", status_code=303)
    if as_ and tl.parse_view(as_) is None:
        return RedirectResponse(f"/t?error={quote('DRS-4004 View as is trader:<id> or desk:<id>')}", status_code=303)
    await _patch(request, {"tradingView": as_ or None})
    return RedirectResponse("/", status_code=303)
