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

"""An edition's Home landings and the lens (docs/architecture/EDITIONS.md, sections 7 and 8; docs/admin/EDITIONS_GUIDE.md).

Landings: an edition lists the landings it offers (``home.landings``: Drishti Risk's ``desk``, ``command`` and ``map``, Drishti World's
``desk``, ``explorer`` and ``map``, Drishti Market's ``desk``, ``explorer`` and ``quality``, Drishti Trading's ``trader``, ``desk`` and
``explorer``), its default (``home.landing``), the page that draws them (``home.template``) and their titles (``home.titles``); a person
picks one from the links on Home and the choice is kept in their settings (``homeLanding``), as other preferences are.
``home.landing-by-role`` ({role: landing}) is read and, when configured, wins over the person's choice: the hook for role-based access
later; Drishti Trading's ``trading.landing-by-role`` (the same shape, for the person's trading role) picks the first landing below their
choice. ``/api/home/search`` is the landings' one read: the server's search grammar, for a command the edition shows; Drishti Market's
figures and checks are worked out by core/market_landing.py and served by ``/api/home/market/{desk,moves,quality}``; Drishti Trading's
by core/trading_landing.py, served by routes/trading_routes.py.

The lens: ``/lens/<name>`` opens an edition the server offers as a lens (``drishti.lenses``, one or more) for this person; opening another
while in one switches; ``/lens/leave`` closes it. Kept in the person's settings (``lens``), so every server of a cluster and every console
worker sees it. A lens hides; it does not secure.
"""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, RedirectResponse

from core import asof, market_landing, trading_landing
from core.backend import BackendError
from core.edition import Edition
from routes.common import ident, render

router = APIRouter(include_in_schema=False)


def edition_of(request) -> Edition:
    return getattr(request.state, "edition", None) or request.app.state.editions.instance


def landing_for(request, ed: Edition) -> str | None:
    """The landing this person sees, or None for the product's Home (no landings, or ``home.landing: today``)."""
    offered = ed.landings()
    if not offered or ed.landing == "today":
        return None
    asked = request.query_params.get("landing")
    if asked == "today":
        return None
    me = getattr(request.state, "identity", None)
    by_role = ed.landing_by_role()                      # the RBAC hook: wins only when configured
    for role in (me.roles if me else ()):
        if by_role.get(role) in offered:
            return by_role[role]
    if asked in offered:
        return asked
    mine = getattr(request.state, "settings", None) or {}
    chosen = str(mine.get("homeLanding") or "")
    if chosen in offered:
        return chosen
    # Drishti Trading: the person's trading role (desk-head, trader) picks the first landing, below their own choice (EDITIONS.md 14)
    role = trading_landing.trading_role(ed, request.app.state.settings, me, mine) if ed._get("trading") else ""
    if role and ed.landing_by_role("trading.landing-by-role").get(role) in offered:
        return ed.landing_by_role("trading.landing-by-role")[role]
    return ed.landing if ed.landing in offered else offered[0]


async def render_landing(request, ed: Edition, landing: str, cards: list):
    """One of the edition's landings: a shell (``home.template``) whose panels read the servers' own answers, drawn by the script the
    template names (Drishti Risk: the engines' /api/var and /api/exposure, risk-landing.js; Drishti World: the packs' kinds through
    /api/home/search and /api/raw, world-landing.js). What the page needs of the person is read here: their monitors (Risk's
    ``command``) and their recent commands of the edition (World's ``explorer``)."""
    titles = ed.landing_titles()
    offered = [{"id": x, "title": titles[x], "href": f"/home/landing/{x}"} for x in ed.landings()]
    chips = [str(c) for c in (ed._get("home.chips") or []) if ed.typed_shown(str(c))]
    monitors, recents = [], []
    backend, me = request.app.state.backend, ident(request)
    if landing == "command":                              # Risk's B: the person's monitors
        try:
            mine = await backend.mine("GET", "/monitors", me)
            monitors = [str(m.get("name") if isinstance(m, dict) else m) for m in (mine or [])][:8]
        except BackendError:
            monitors = []
    if landing == "explorer":                             # World's B: the person's recent commands the edition shows
        try:
            seen = []
            for c in await backend.command_history(me) or []:
                c = str(c).strip()
                if c and c not in seen and ed.typed_shown(c):
                    seen.append(c)
            recents = seen[:6]
        except BackendError:
            recents = []
    return render(request, ed.landing_template, landing=landing, landings=offered, cards=cards, chips=chips, monitors=monitors,
                  recents=recents, topics=[str(t) for t in ed._get("home.topics") or []], stale_days=stale_days(ed),
                  hierarchies=[str(h) for h in ed._get("home.hierarchies") or ["MGMT"]], screen="home")


@router.get("/api/home/search")
async def landing_search(request: Request, q: str = ""):
    """The landings' read (World's panels): the server's search grammar (``ECON order by GDP_USD_BN desc limit 20``), read-only, for a
    signed-in person whose edition (or lens) shows the command; the server filters again by the person's lens (X-Drishti-Lens). A hidden
    command answers 404 DRS-4023, as on the command line."""
    ed = edition_of(request)
    head = q.split()[0].upper() if q.split() else ""
    if getattr(request.state, "identity", None) is None:
        return JSONResponse({"detail": "sign in first"}, status_code=401)
    if not ed.active or not head or not ed.command_shown(head) or head in ("BI", "RUPAKA", "FORM", "FUNC", "WHAT", "DATAMAP", "VAR"):
        return JSONResponse({"code": "DRS-4023", "detail": f"{head or 'the command'} is not part of {ed.title if ed.active else 'a landing'}"},
                            status_code=404)
    try:
        return JSONResponse(await request.app.state.backend.search(q, ident(request)), headers={"Cache-Control": "no-store"})
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)


def stale_days(ed: Edition) -> int:
    """Drishti Market's ``quality.stale-days`` (default 2): data older than the business date by more business days than this is stale."""
    try:
        n = int(ed._get("quality.stale-days", 2))
    except (TypeError, ValueError):
        n = 2
    return max(0, min(n, 60))


@router.get("/api/home/market/{part}")
async def market_figures(request: Request, part: str):
    """Drishti Market's landings: ``desk`` (A's tiles and USD curves), ``moves`` (A's biggest moves) and ``quality`` (C's checks), worked
    out from the market-data pack's own answers with this person's identity, lens and date box, read-only and cached briefly
    (core/market_landing.py). For a signed-in person whose edition (or lens) shows the curves (CRV); else 404 DRS-4023."""
    ed = edition_of(request)
    if getattr(request.state, "identity", None) is None:
        return JSONResponse({"detail": "sign in first"}, status_code=401)
    if part not in ("desk", "moves", "quality"):
        return JSONResponse({"code": "DRS-4004", "detail": f"no market figures {part}: desk, moves or quality"}, status_code=404)
    if not ed.active or not ed.commands() or not ed.command_shown("CRV"):
        return JSONResponse({"code": "DRS-4023", "detail": f"market data is not part of {ed.title if ed.active else 'a landing'}"},
                            status_code=404)
    me = ident(request)
    key = (me.user if me else "", ed.name, asof.current(), str(asof.headers()))
    backend = request.app.state.backend
    if part == "desk":
        out = await market_landing.desk(backend, me, key)
    elif part == "moves":
        out = await market_landing.moves(backend, me, key)
    else:
        out = await market_landing.quality(backend, me, stale_days(ed), key)
    return JSONResponse(out, headers={"Cache-Control": "no-store"})


@router.get("/home/landing/{name}")
async def choose_landing(request: Request, name: str):
    """Remembers the person's landing (their settings, ``homeLanding``) and opens Home with it."""
    me = getattr(request.state, "identity", None)
    if me is not None and name in edition_of(request).landings():
        await _patch(request, {"homeLanding": name})
    return RedirectResponse("/", status_code=303)


@router.get("/lens/leave")
async def leave_lens(request: Request):
    """Back to the full product: the person's lens is forgotten (their settings)."""
    if getattr(request.state, "identity", None) is not None:
        await _patch(request, {"lens": None})
    return RedirectResponse("/", status_code=303)


@router.get("/lens/{name}")
async def open_lens(request: Request, name: str):
    """Opens an edition as a lens for this person: only one the server offers (drishti.lenses), only on a full instance."""
    editions = request.app.state.editions
    if getattr(request.state, "identity", None) is None:
        return RedirectResponse(f"/login?next={quote('/lens/' + name)}", status_code=303)
    if editions.instance.active or name not in editions.lenses:
        return RedirectResponse(f"/t?error={quote(f'DRS-4023 no lens {name} on this server (drishti.lenses)')}", status_code=303)
    await _patch(request, {"lens": name})
    return RedirectResponse("/", status_code=303)


async def _patch(request, values: dict) -> None:
    me = ident(request)
    try:
        await request.app.state.backend.patch_settings(values, me)
    except BackendError:
        pass
    request.app.state.user_settings.forget(me.user if me else "", request)
