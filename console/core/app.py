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

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from urllib.parse import quote

from fastapi.responses import JSONResponse, RedirectResponse
from starlette.middleware.base import BaseHTTPMiddleware

from core.config import Settings

WEB = Path(__file__).resolve().parent.parent / "web"
ASSET_VERSION = "1.1.0"
CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
       "font-src 'self'; connect-src 'self'; frame-ancestors 'none'")


PROTECTED = ("/t", "/v/", "/go", "/studio", "/api/", "/admin", "/account")


class AuthGate(BaseHTTPMiddleware):
    """Resolves the caller's identity; with auth on, protected paths need a valid session."""

    async def dispatch(self, request, call_next):
        from core.auth import COOKIE

        auth = request.app.state.auth
        request.state.identity = auth.identity(request.cookies.get(COOKIE))
        path = request.url.path
        if request.state.identity is None and (path == "/t" or path.startswith(PROTECTED[1:])):
            if path.startswith("/api/"):
                return JSONResponse({"code": "DRS-5010", "detail": "sign in first"}, status_code=401)
            return RedirectResponse(f"/login?next={quote(str(request.url.path))}", status_code=303)
        return await call_next(request)


class SecurityHeaders(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        response = await call_next(request)
        response.headers.setdefault("Content-Security-Policy", CSP)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "same-origin")
        return response


def create_app(settings: Settings) -> FastAPI:
    from core.backend import BackendClient
    from core.auth import Auth
    from routes import admin_routes, api_routes, auth_routes, home_routes, studio_routes, terminal_routes

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if getattr(app.state, "backend", None) is None:
            app.state.backend = BackendClient(settings.get("backend.url"), float(settings.get("backend.timeout_seconds", 5)),
                                              int(settings.get("backend.pool_size", 64)))
        yield
        close = getattr(app.state.backend, "aclose", None)
        if close:
            await close()

    app = FastAPI(title="Drishti console", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.backend = None
    templates = Jinja2Templates(directory=str(WEB / "templates"))
    templates.env.globals.update(
        ASSET_V=ASSET_VERSION,
        PRODUCT=settings.get("ui.product", "Drishti"),
        TAGLINE=settings.get("ui.tagline", ""),
        DEFAULT_THEME=settings.get("ui.default_theme", "terminal"),
        DESK=settings.get("ui.desk", "Rates desk"),
        USER=settings.get("ui.user_display", "Ash"),
        CLOCK_TZ=settings.get("ui.clock_tz", "America/New_York"),
        CLOCK_LABEL=settings.get("ui.clock_label", "NY"),
        COPYRIGHT="Copyright © 2026 Ashutosh Sinha. All rights reserved. Proprietary and confidential.",
    )
    app.state.settings = settings
    app.state.auth = Auth(settings)
    app.state.templates = templates
    app.add_middleware(AuthGate)
    app.add_middleware(SecurityHeaders)
    app.mount("/static", StaticFiles(directory=str(WEB / "static")), name="static")
    app.include_router(home_routes.router)
    app.include_router(terminal_routes.router)
    app.include_router(api_routes.router)
    app.include_router(auth_routes.router)
    app.include_router(studio_routes.router)
    app.include_router(admin_routes.router)
    return app
