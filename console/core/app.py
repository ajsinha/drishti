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

"""Application factory: one FastAPI app serving pages, static assets and security headers."""
from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.base import BaseHTTPMiddleware

from core.config import Settings

WEB = Path(__file__).resolve().parent.parent / "web"
ASSET_VERSION = "0.1.0"
CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
       "font-src 'self'; connect-src 'self'; frame-ancestors 'none'")


class SecurityHeaders(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        response = await call_next(request)
        response.headers.setdefault("Content-Security-Policy", CSP)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "same-origin")
        return response


def create_app(settings: Settings) -> FastAPI:
    from routes import home_routes

    app = FastAPI(title="Drishti console", docs_url=None, redoc_url=None, openapi_url=None)
    templates = Jinja2Templates(directory=str(WEB / "templates"))
    templates.env.globals.update(
        ASSET_V=ASSET_VERSION,
        PRODUCT=settings.get("ui.product", "Drishti"),
        TAGLINE=settings.get("ui.tagline", ""),
        DEFAULT_THEME=settings.get("ui.default_theme", "terminal"),
        COPYRIGHT="Copyright © 2026 Ashutosh Sinha. All rights reserved. Proprietary and confidential.",
    )
    app.state.settings = settings
    app.state.templates = templates
    app.add_middleware(SecurityHeaders)
    app.mount("/static", StaticFiles(directory=str(WEB / "static")), name="static")
    app.include_router(home_routes.router)
    return app
