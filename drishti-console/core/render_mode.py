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

"""Which renderer draws a console page (docs/architecture/CLIENT_RENDERER.md section 16; ``ui.render`` in CONFIGURATION.md).

``ui.render.mode`` is ``server`` (the Jinja macros, as always; the default for this release) or ``client`` (Drishti Render draws the page in
the browser from its ViewModel); ``ui.render.pages`` names the pages it applies to, page by page, so one page can be turned back without
a release. ``?render=server`` or ``?render=client`` on a page's address overrides both for that one request (comparing the two, a
diagnosis); it changes nothing a person may see, since both draw the same ViewModel with the same masks.
"""
from __future__ import annotations

from urllib.parse import quote

MODES = ("server", "client")
# The pages that can be drawn by the client-side renderer, and the default list (the pages that moved in phase 2)
PAGES = ("view", "pane")             # view: an entity page (/v/<kind>/<id>, what /go, a share and a history day open); pane: one in a workspace pane (?embed=1)
DEFAULT_PAGES = PAGES


def pages(settings) -> tuple[str, ...]:
    raw = settings.get("ui.render.pages", None) if settings is not None else None
    if raw is None or raw == "":
        return DEFAULT_PAGES
    items = raw if isinstance(raw, (list, tuple)) else str(raw).split(",")
    return tuple(p for p in (str(x).strip().lower() for x in items) if p in PAGES)


def mode(settings) -> str:
    raw = settings.get("ui.render.mode", None) if settings is not None else None
    if isinstance(settings.get("ui.render", None) if settings is not None else None, str):    # the short form ui.render: client
        raw = settings.get("ui.render")
    m = str(raw or "server").strip().lower()
    return m if m in MODES else "server"


def client(request, page: str) -> bool:
    """Whether ``page`` is drawn by the client-side renderer for this request."""
    asked = (request.query_params.get("render") or "").strip().lower()
    if asked in MODES:
        return asked == "client"
    settings = getattr(request.app.state, "settings", None)
    return settings is not None and mode(settings) == "client" and page in pages(settings)


def view_boot(request, vm: dict, panels: list, grid: dict | None, embed: bool, design_on: bool) -> dict:
    """What a client-drawn view page embeds for view-render.js: the ViewModel as this page arranges it (the reader's layout and saved
    pivots applied to ``panels``; the grid with the reader's own arrangement), the business date shown, and the console's options."""
    asof = getattr(request.state, "asof", "live")
    return {"vm": {**vm, "panels": panels}, "grid": grid, "asOf": asof,
            "businessDate": getattr(request.state, "business_date", None) or {}, "designOn": bool(design_on),
            "backToLive": None if embed else "/asof?d=live&next=" + quote(request.url.path)}
