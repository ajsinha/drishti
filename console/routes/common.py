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

from pathlib import Path
from typing import Any

from fastapi import Request


def render(request: Request, template: str, status_code: int = 200, **context: Any):
    templates = request.app.state.templates
    context.setdefault("me", getattr(request.state, "identity", None))
    context.setdefault("pack_switcher", getattr(request.state, "pack_switcher", []))
    context.setdefault("AUTH_ENABLED", request.app.state.auth.enabled)
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
