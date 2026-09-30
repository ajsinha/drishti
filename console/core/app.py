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
ASSET_VERSION = "1.9.0"
CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
       "font-src 'self'; connect-src 'self'; frame-src 'self'; frame-ancestors 'self'")


PROTECTED = ("/t", "/v/", "/go", "/studio", "/api/", "/admin", "/account", "/w", "/m", "/alerts", "/impact", "/s/", "/compare/", "/export/")
EXACT = ("/t", "/s")                        # pages whose path is a prefix of public ones (/s of /static)


class AuthGate(BaseHTTPMiddleware):
    """Resolves the caller's identity; with auth on, protected paths need a valid session."""

    async def dispatch(self, request, call_next):
        from core.auth import COOKIE

        auth = request.app.state.auth
        request.state.identity = auth.identity(request.cookies.get(COOKIE))
        request.state.pack_switcher = []
        path = request.url.path
        from core import asof

        request.state.asof = asof.set_current(request.query_params.get("asOf") or request.cookies.get(asof.COOKIE))
        request.state.known_at = asof.set_known(request.cookies.get(asof.KNOWN_COOKIE)) if request.state.asof != "live" else None
        request.state.business_date = None
        if not path.startswith(("/static/", "/api/", "/healthz", "/asof")):
            request.state.business_date = await request.app.state.business_dates.info(
                request.app.state.backend, request.state.identity, request.state.asof)
        if request.state.identity is not None and not path.startswith(("/static/", "/api/", "/healthz")):
            try:
                request.state.pack_switcher = await request.app.state.packs.assigned(request.app.state.backend, request.state.identity)
            except Exception:  # noqa: BLE001 - the switcher is a convenience; never fail a page for it
                request.state.pack_switcher = []
        if request.state.identity is None and (path in EXACT or path.startswith(PROTECTED[1:])):
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
        response.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
        return response


def create_app(settings: Settings) -> FastAPI:
    from core.backend import BackendClient
    from core.auth import Auth
    from routes import (admin_routes, api_routes, asof_routes, auth_routes, export_routes, help_routes, home_routes, monitor_routes,
                        studio_routes, terminal_routes, workspace_routes)

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
    from core.asof import to_local

    templates.env.globals["known_local"] = to_local
    app.state.settings = settings
    app.state.auth = Auth(settings)
    from core.oidc import Oidc

    app.state.oidc = Oidc(settings, app.state.auth)
    console_dir = WEB.parent
    docs_dir = Path(settings.get("help.docs_dir", "../docs"))
    from core.packs import Packs

    app.state.packs = Packs(settings, console_dir)
    app.state.docs_dir = docs_dir if docs_dir.is_absolute() else (console_dir / docs_dir).resolve()
    app.state.libraries = {}
    from core.asof import BusinessDates

    app.state.business_dates = BusinessDates()
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
    app.include_router(help_routes.router)
    app.include_router(workspace_routes.router)
    app.include_router(monitor_routes.router)
    app.include_router(asof_routes.router)
    app.include_router(export_routes.router)
    return app
