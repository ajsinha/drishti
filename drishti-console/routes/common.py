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

"""Shared helpers for page routes."""
from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from fastapi import Request
from fastapi.responses import JSONResponse

from core.nextpath import login_url


def live_who(request: Request) -> str:
    """Whose live data a page shows: a fingerprint of the server, user and sign-in session (never the session id itself).
    The browser's one live connection (live-hub.js) is shared only by pages with the same fingerprint as the
    connection's own (the ``channel`` event's ``who``), so frames built for one session never reach another's page."""
    from core import servers

    me = getattr(request.state, "identity", None)
    server = getattr(request.state, "server", None)
    parts = (server.id if server is not None else servers.current(), me.user if me else "", (me.session or "") if me else "")
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()[:24]


def render(request: Request, template: str, status_code: int = 200, **context: Any):
    context.setdefault("LIVE_WHO", live_who(request))
    templates = request.app.state.templates
    context.setdefault("me", getattr(request.state, "identity", None))
    context.setdefault("pack_switcher", getattr(request.state, "pack_switcher", []))
    context.setdefault("AUTH_ENABLED", request.app.state.auth.enabled)
    context.setdefault("SIGN_IN_URL", login_url(request.url.path, request.url.query))
    catalogue = request.app.state.servers
    context.setdefault("SERVER", catalogue.get(getattr(request.state, "server", None) and request.state.server.id) or catalogue.get(catalogue.default))
    context.setdefault("SERVERS", catalogue.listed() if len(catalogue) > 1 else [])
    context.setdefault("asof", getattr(request.state, "asof", "live"))
    context.setdefault("known_at", getattr(request.state, "known_at", None))
    context.setdefault("settings", getattr(request.state, "settings", None) or {})
    context.setdefault("business_date", getattr(request.state, "business_date", None) or {})
    return templates.TemplateResponse(request, template, context, status_code=status_code)


def ident(request: Request):
    """The signed-in identity (set by the auth gate), carrying the token for backend calls."""
    return request.state.identity


async def packs(request: Request) -> list:
    """The enabled domain packs (from the server), with what each contributes to the console."""
    return await request.app.state.packs.current(request.app.state.backend, getattr(request.state, "identity", None))


async def library(request: Request):
    """The help library for the enabled packs, built once per set of packs."""
    from core.guides import Library

    current = await packs(request)
    key = tuple(p["name"] for p in current)
    libs = request.app.state.libraries
    if key not in libs:
        console_dir = Path(__file__).resolve().parent.parent
        libs[key] = Library(console_dir, console_dir / "config" / "help.yaml", request.app.state.docs_dir, current)
    return libs[key]


def local_zone(request: Request) -> tuple[str, str]:
    """The zone and label collaboration times are shown in. One rule: the person's own "Clock time zone" (Account), if they chose
    one; else the business zone the top bar's "known at" uses; else the console's clock zone. The label is the zone's city name."""
    from core.asof import zone_label

    chosen = (getattr(request.state, "settings", None) or {}).get("clockZone")
    zone = chosen or (getattr(request.state, "business_date", None) or {}).get("zone") or request.app.state.templates.env.globals.get("CLOCK_TZ") or "America/New_York"
    return zone, zone_label(zone)


def problem(e) -> JSONResponse:
    """A server problem as the JSON the collaboration pages read (``code``, ``detail``), with the server's ``Retry-After`` passed on."""
    headers = {"Retry-After": str(e.retry_after)} if getattr(e, "retry_after", None) else None
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.page_status, headers=headers)


def localise(value: Any, zone: str, label: str) -> Any:
    """Adds ``<name>Local`` beside every ISO instant stored under a name ending ``At`` (``createdAt``, ``hiddenAt``) in the JSON the
    collaboration pages draw, in place: the page never works a time zone out for itself."""
    from core.asof import local_when

    if isinstance(value, list):
        for v in value:
            localise(v, zone, label)
    elif isinstance(value, dict):
        for k, v in list(value.items()):
            if (k.endswith("At") or k == "at") and isinstance(v, str):
                value[k + "Local"] = local_when(v, zone, label)
            else:
                localise(v, zone, label)
    return value
