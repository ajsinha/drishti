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

Landings: an edition lists the landings it offers (``home.landings``, the Drishti Risk ones are ``desk``, ``command`` and ``map``) and its
default (``home.landing``); a person picks one from the links on Home and the choice is kept in their settings (``homeLanding``), as other
preferences are. ``home.landing-by-role`` ({role: landing}) is read and, when configured, wins over the person's choice: the hook for
role-based access later.

The lens: ``/lens/<name>`` opens an edition the server offers as a lens (``drishti.lenses``) for this person; ``/lens/leave`` closes it. Kept
in the person's settings (``lens``), so every server of a cluster and every console worker sees it. A lens hides; it does not secure.
"""
from __future__ import annotations

from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import RedirectResponse

from core.backend import BackendError
from core.edition import Edition
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

LANDINGS = {"desk": "Risk desk", "command": "Command first", "map": "Risk map"}


def edition_of(request) -> Edition:
    return getattr(request.state, "edition", None) or request.app.state.editions.instance


def landing_for(request, ed: Edition) -> str | None:
    """The landing this person sees, or None for the product's Home (no landings, or ``home.landing: today``)."""
    offered = [x for x in ed.landings() if x in LANDINGS]
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
    chosen = str((getattr(request.state, "settings", None) or {}).get("homeLanding") or "")
    if chosen in offered:
        return chosen
    return ed.landing if ed.landing in offered else offered[0]


async def render_landing(request, ed: Edition, landing: str, cards: list):
    """One of the edition's landings: a shell whose panels read the engines' own answers (VaR, exposure, limits, hierarchies,
    intraday status, official capture) through the console's /api/var and /api/exposure, drawn by static/js/risk-landing.js."""
    offered = [{"id": x, "title": LANDINGS[x], "href": f"/home/landing/{x}"} for x in ed.landings() if x in LANDINGS]
    chips = [str(c) for c in (ed._get("home.chips") or []) if ed.typed_shown(str(c))]
    monitors = []
    if landing == "command":                              # B's watch list: the person's monitors
        try:
            mine = await request.app.state.backend.mine("GET", "/monitors", ident(request))
            monitors = [str(m.get("name") if isinstance(m, dict) else m) for m in (mine or [])][:8]
        except BackendError:
            monitors = []
    return render(request, "home/risk_landing.html", landing=landing, landings=offered, cards=cards, chips=chips, monitors=monitors,
                  hierarchies=[str(h) for h in ed._get("home.hierarchies") or ["MGMT"]], screen="home")


@router.get("/home/landing/{name}")
async def choose_landing(request: Request, name: str):
    """Remembers the person's landing (their settings, ``homeLanding``) and opens Home with it."""
    me = getattr(request.state, "identity", None)
    if me is not None and name in LANDINGS:
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
