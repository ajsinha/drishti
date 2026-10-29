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

"""Public pages: the landing page (Home), with its fact of the day (core/facts.py) and quick cards."""
from __future__ import annotations

from pathlib import Path
from urllib.parse import quote, unquote

from fastapi import APIRouter, Request
from fastapi.responses import FileResponse, JSONResponse

from core.nextpath import safe_next
from core.packs import samples
from routes import edition_routes
from routes.common import library, packs, render

ICONS = Path(__file__).resolve().parent.parent / "web" / "static" / "img" / "icons"

router = APIRouter(include_in_schema=False)

GENERIC = "<MNEMONIC> <ID> <GO>"


@router.get("/")
async def landing(request: Request):
    """The public landing page. Its example commands come from the packs switched on, never from a pack that may be
    absent (UX-05); with no examples it shows the command's form instead of an entity."""
    settings = request.app.state.settings
    active = await packs(request)
    ed = edition_routes.edition_of(request)
    current = [s for s in samples(active) if ed.command_shown(s["cmd"].split()[0])]   # an edition's hidden commands are not shown as examples
    commands = [s["cmd"] + " <GO>" for s in current][: int(settings.get("ui.landing_examples", 4) or 4)] or [GENERIC]
    known = {s["cmd"].upper() for s in current}
    showcase = [{**s, "cmd": s.get("cmd") if str(s.get("cmd", "")).upper() in known else ""} for s in settings.get("ui.showcase") or []]
    back = None
    if getattr(request.state, "identity", None) is not None:            # signed in: offer the way back to where the logo was clicked
        target = safe_next(request.query_params.get("from"), default="")
        if target and target.split("?", 1)[0] not in ("/", "/login"):
            back = {"href": target, "label": back_label(target)}
    me = getattr(request.state, "identity", None)
    facts_on = me is not None and request.app.state.facts.enabled
    raw_cards = ed.home_cards if ed.lens and ed.home_cards is not None else settings.get("ui.home_cards") or []
    cards = [c for c in home_cards(raw_cards, active) if ed.typed_shown(c["cmd"])] if me is not None else []
    if me is not None and ed.active and (landing := edition_routes.landing_for(request, ed)) is not None:
        return await edition_routes.render_landing(request, ed, landing, cards)        # the edition's landing (home.landing, the person's choice)
    return render(request, "landing.html", showcase=showcase, commands=commands, back=back, facts_on=facts_on, home_cards=cards)


def home_cards(cards: list, current: list) -> list:
    """The quick cards under the Home headline (``ui.home_cards``): each one opens a command; a card naming a pack is shown only while
    that pack is on for the user, one naming none (a reserved word such as DATAMAP) always."""
    on = {p.get("name") for p in current}
    out = []
    for c in cards:
        if not isinstance(c, dict) or not c.get("cmd") or not c.get("title"):
            continue
        if c.get("pack") and c["pack"] not in on:
            continue
        out.append({"title": str(c["title"]), "cmd": str(c["cmd"]), "href": "/go?q=" + quote(str(c["cmd"]) + " <GO>"),
                    "icon": str(c.get("icon") or "")})
    return out


@router.get("/api/home/facts")
async def home_facts(request: Request):
    """The fact of the day for the signed-in user (core/facts.py): ``{date, facts}``, the day's fact first, computed from the server's
    answers on the user's business date for the packs the user has on. Empty when none holds; the page then hides the tile."""
    from core import servers
    from core.facts import Fetcher, links, rotate

    me = getattr(request.state, "identity", None)
    svc = request.app.state.facts
    if me is None or not svc.enabled:
        return JSONResponse({"date": None, "facts": []}, headers={"Cache-Control": "no-store"})
    current = await packs(request)
    eligible = svc.eligible(current)
    info = await request.app.state.business_dates.info(request.app.state.backend, me, getattr(request.state, "asof", "live"))
    day = str((info or {}).get("selected") or (info or {}).get("current") or "")
    if not eligible or not day:
        return JSONResponse({"date": day or None, "facts": []}, headers={"Cache-Control": "no-store"})
    key = (servers.current(), me.user, tuple(sorted(me.roles)), day, tuple(sorted(p.get("name", "") for p in current)))
    found = await svc.today(key, eligible, Fetcher(request.app.state.backend, me, day), day)
    lib = await library(request)
    out = [links(f, request.app.state.glossary, lib) for f in rotate(found, day)]
    return JSONResponse({"date": day, "facts": out}, headers={"Cache-Control": "no-store"})


def back_label(path: str) -> str:
    """A short name for the page a signed-in visitor came from: the entity id of a view, else the page's first path part."""
    parts = [p for p in path.split("?", 1)[0].split("/") if p]
    if len(parts) >= 3 and parts[0] in ("v", "share"):
        return unquote(parts[-1]) if parts[0] == "v" else "the shared view"
    names = {"t": "the terminal", "build": "Build", "admin": "Admin", "help": "Help", "inbox": "your inbox", "w": "your workspace",
             "account": "your account", "alerts": "alerts", "monitors": "monitors", "compare": "Compare"}
    return names.get(parts[0], "where you were") if parts else "where you were"


@router.get("/manifest.webmanifest", include_in_schema=False)
def manifest(request: Request):
    """The web manifest, from configuration (EDITIONS.md section 5.10): the product's name (an edition's or a lens's), its tagline and
    copyright, its icons (the eye's unless ui.apple_touch_icon / ui.favicon name others). Replaces the fixed static file."""
    g = request.app.state.templates.env.globals
    ed = edition_routes.edition_of(request)
    name = (ed.branding("product") if ed.lens else "") or str(g.get("PRODUCT") or "")
    tagline = (ed.branding("tagline") if ed.lens else "") or str(g.get("TAGLINE") or "")
    icons = [{"src": "/static/img/icons/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
             {"src": "/static/img/icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
             {"src": "/static/img/icons/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
             {"src": "/static/img/icons/favicon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any"}]
    if g.get("APPLE_TOUCH_ICON") or g.get("FAVICON"):
        icons = [{"src": str(g.get("APPLE_TOUCH_ICON") or g.get("FAVICON")), "sizes": "any", "purpose": "any"}]
    body = {"name": name, "short_name": name, "description": " ".join(x for x in (tagline, str(g.get("COPYRIGHT") or "")) if x),
            "start_url": "/t", "scope": "/", "display": "standalone", "background_color": "#0e1628", "theme_color": "#0e1628", "icons": icons}
    return JSONResponse(body, media_type="application/manifest+json", headers={"Cache-Control": "no-cache"})


@router.get("/favicon.ico", include_in_schema=False)
def favicon():
    """Browsers ask for /favicon.ico on their own (feeds, error pages, bookmarks); it is the logo mark, built by tools/make_icons.py."""
    return FileResponse(ICONS / "favicon.ico", media_type="image/x-icon", headers={"Cache-Control": "public, max-age=86400"})


@router.get("/healthz")
def healthz(request: Request):
    """Liveness: the console process answers. Cheap on purpose; a restart would not fix a server that is down. Says too
    whether Calc's Python runtime is installed (it is optional: views work without it)."""
    runtime = request.app.state.calc.runtime
    return {"status": "UP", "pythonRuntime": runtime.version if runtime.installed else "not installed: run tools/fetch-pyodide.sh"}


@router.get("/healthz/backend")
async def healthz_backend(request: Request):
    """Whether the console can reach the server this browser uses, as the console's watch last saw it (core/backend_watch.py):
    {reachable, since, lastError, version, server, checkSeconds}. What server-watch.js polls while a page shows the banner. No secrets."""
    from core import servers

    sid = servers.current()
    watch = request.app.state.backend_watch
    return JSONResponse({**await watch.state(sid), "server": sid, "checkSeconds": watch.seconds}, headers={"Cache-Control": "no-store"})


@router.get("/readyz")
async def readyz(request: Request):
    """Readiness: the console can reach the server (route traffic here only then). 503 while it cannot."""
    try:
        await request.app.state.backend.business_date(request.app.state.auth.service())
    except Exception as e:  # noqa: BLE001 - any failure means not ready
        return JSONResponse({"status": "DOWN", "server": "unreachable", "detail": str(getattr(e, "detail", e))[:200]}, status_code=503)
    return {"status": "UP", "server": "reachable"}
