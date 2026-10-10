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

import re
from pathlib import Path

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from urllib.parse import quote

from fastapi.responses import JSONResponse, RedirectResponse
from starlette.middleware.base import BaseHTTPMiddleware

from core.backend import drs_advice, drs_message
from core.config import Settings
from core.nextpath import to_login

WEB = Path(__file__).resolve().parent.parent / "web"
ASSET_VERSION = "1.18.0"


def asset_fingerprint() -> str:
    """A short hash of the scripts and stylesheets (names, sizes, times): part of every asset URL, so browsers fetch a
    changed file at once instead of running a cached old one against a newer server."""
    import hashlib

    h = hashlib.sha1()
    for folder in ("js", "css", "calc"):
        for f in sorted((WEB / "static" / folder).glob("*")):
            st = f.stat()
            h.update(f"{f.name}:{st.st_size}:{st.st_mtime_ns};".encode())
    return h.hexdigest()[:8]
CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
       "font-src 'self'; connect-src 'self'; frame-src 'self'; worker-src 'self'; frame-ancestors 'self'")
# Calc's Web Worker, and only it, may compile WebAssembly (Pyodide is CPython compiled to it): 'wasm-unsafe-eval', never
# 'unsafe-eval'. It loads scripts and data from this origin only, has no DOM, and reaches the page only by messages.
# It may also fetch only the console's static files and the runtime (connect-src names those two paths on this host),
# never an /api route: Calc code in the worker carries the user's session cookie, so any other route it could reach it
# would reach as the user (QA 2026-10-01 SEC-17). Its reads go by message to the page, which fetches an allow-listed few.
WORKER_CSP = "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src {connect}"
_HOST = re.compile(r"^[A-Za-z0-9.\-]+(:\d{1,5})?$|^\[[0-9A-Fa-f:.]+\](:\d{1,5})?$")
WORKER = "/static/js/calc-worker.js"
PYODIDE = "/pyodide/"
# Rupaka phase 0 proof of concept (docs/architecture/RUPAKA_POC.md), only when bi.poc_enabled: /bi/poc, and only it, may compile
# WebAssembly on its main thread (Perspective's viewer): 'wasm-unsafe-eval', never 'unsafe-eval'. The two vendored workers
# (Perspective's engine, DuckDB-Wasm) are scripts of this origin with the worker policy above; neither is a blob.
POC_PAGE = "/bi/poc"
# Perspective's viewer also writes <style> elements (its shadow roots' own CSS): not allowed by style-src 'self'. These are the SHA-256
# hashes of the six it writes for the grid and the pivot of Perspective 3.8.0 (measured: the page logs each refused one with its
# hash); the charts and the settings panel need about eight more (see RUPAKA_POC.md): a new Perspective version means new hashes.
POC_STYLE_HASHES = ("sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", "sha256-5ZnN0JqHgS1XbqCOd7qSJmy89TJJwbmeYjC8fPeSuwc=",
                    "sha256-CjknTzl2uKtf8HzhOg2u184k8XVq0Q32LfRRPUqc0g4=", "sha256-ILUuaTblzLnuV4UciYvrUJkuNlQ570jCEpzE0t/Pe5s=",
                    "sha256-TlH50xiDI4R2hMVrktT2rsnuNnNdfvL/RTinBMjapl0=", "sha256-rjC4ta+DkxJjU72hK9nIKZxg1UN00ALy4b/FYtZ7KHc=")
POC_CSP = (CSP.replace("script-src 'self'", "script-src 'self' 'wasm-unsafe-eval'")
           .replace("style-src 'self'", "style-src 'self' " + " ".join(f"'{h}'" for h in POC_STYLE_HASHES)))
POC_WORKERS = ("/static/vendor/perspective/3.8.0/perspective/cdn/perspective-server.worker.js",)
POC_WORKER_SUFFIX = "/duckdb-browser-eh.worker.js"
EMBED = "/embed/"                           # embedded views for other web applications: off unless embed.enabled (routes/embed_routes.py)


PROTECTED = ("/t", "/bi", "/v/", "/go", "/studio", "/api/", "/admin", "/account", "/w", "/m", "/alerts", "/impact", "/s/", "/compare/", "/export/", "/pin/", "/p/", "/reports", "/build", "/share/", "/inbox")
EXACT = ("/t", "/s")                        # pages whose path is a prefix of public ones (/s of /static)
# all a user whose password change is due may reach until it is done (besides public pages): QA 2026-10-01 SEC-06
WHILE_MUST_CHANGE = ("/account", "/account/password", "/logout")


def protected(path: str) -> bool:
    return path in EXACT or path.startswith(PROTECTED[1:])


class AuthGate(BaseHTTPMiddleware):
    """Resolves the caller's identity; with auth on, protected paths need a valid session."""

    async def dispatch(self, request, call_next):
        from core import servers

        catalogue = request.app.state.servers
        asked = request.query_params.get("srv")          # a shared link names its server
        picked = asked if catalogue.get(asked) else None
        servers.CURRENT.set(picked or catalogue.choose(request.cookies.get(servers.COOKIE)))
        request.state.server = catalogue.get(servers.current())
        request.state.servers = catalogue
        auth = request.app.state.auth
        # the session as the server knows it now (enabled, roles, password change due), not as the cookie remembers it
        path = request.url.path
        request.state.identity = (None if path.startswith(("/static/", PYODIDE, EMBED))   # /embed/: a bearer token, never the cookie (core/embed.py)
                                  else await auth.current(request.cookies.get(auth.cookie), request.app.state.backend))
        request.state.pack_switcher = []
        from core import asof

        stored = request.cookies.get(asof.COOKIE)
        pinned = request.query_params.get("asOf")
        request.state.asof = asof.set_current(pinned or stored)
        # a pinned link carries its own date and "known at" for this request only: the saved cookies are not read for it
        # and never written (a pin that names no instant has none, whatever the recipient's cookie holds)
        known = request.query_params.get("knownAt") if pinned else request.cookies.get(asof.KNOWN_COOKIE)
        request.state.known_at = asof.set_known(known) if request.state.asof != "live" else None
        request.state.pinned = bool(pinned) and request.state.asof != "live"
        # a stored date that is no date at all, or one the server refuses, is dropped: live, and the cookie is cleared
        # (QA 2026-10-01 GRAM-09: it used to break every page until it expired)
        request.state.asof_cleared = bool(stored) and not request.query_params.get("asOf") and asof.clean(stored) != stored.strip()
        request.state.business_date = None
        if not path.startswith(("/static/", PYODIDE, EMBED, "/api/", "/healthz", "/readyz", "/asof")):
            info = await request.app.state.business_dates.info(request.app.state.backend, request.state.identity, request.state.asof)
            if info.get("refused") and not request.query_params.get("asOf"):
                request.state.asof_cleared = True
                request.state.asof = asof.set_current("live")
                request.state.known_at = asof.set_known(None)
                info = await request.app.state.business_dates.info(request.app.state.backend, request.state.identity, "live")
            request.state.business_date = info
        request.state.settings = None
        if request.state.identity is not None and not path.startswith(("/static/", PYODIDE, EMBED, "/api/", "/healthz", "/readyz")):
            request.state.settings = await request.app.state.user_settings.get(request.app.state.backend, request.state.identity)
            try:
                request.state.pack_switcher = await request.app.state.packs.assigned(request.app.state.backend, request.state.identity)
            except Exception:  # noqa: BLE001 - the switcher is a convenience; never fail a page for it
                request.state.pack_switcher = []
        if request.state.identity is None and protected(path):
            if path.startswith("/api/"):
                return JSONResponse({"code": "DRS-5010", "detail": "sign in first"}, status_code=401)
            response = RedirectResponse(to_login(request.url.path, request.url.query), status_code=303)
        elif request.state.identity is not None and request.state.identity.must_change and protected(path) \
                and path not in WHILE_MUST_CHANGE:
            if path.startswith("/api/") or request.method != "GET":
                return JSONResponse({"code": "DRS-6010", "detail": "choose a new password first (My account)"}, status_code=403)
            response = RedirectResponse("/account?must=1", status_code=303)
        else:
            response = await call_next(request)
        if picked and picked != request.cookies.get(servers.COOKIE):
            from routes.server_routes import choose

            choose(response, request, picked)
        if request.state.asof_cleared:
            response.delete_cookie(asof.COOKIE, path="/")
            response.delete_cookie(asof.KNOWN_COOKIE, path="/")
        return response


def worker_csp(host: str) -> str:
    """The worker's policy for the host the browser used. A host that is not plainly a host (nothing to trust in a
    header) gets 'none': Calc then cannot start, which is the safe failure."""
    host = (host or "").strip()
    if not _HOST.match(host):
        return WORKER_CSP.format(connect="'none'")
    return WORKER_CSP.format(connect=f"{host}/static/ {host}{PYODIDE}")


class SecurityHeaders(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        response = await call_next(request)
        path = request.url.path
        poc_worker = path in POC_WORKERS or (path.startswith("/static/vendor/duckdb-wasm/") and path.endswith(POC_WORKER_SUFFIX))
        policy = (worker_csp(request.headers.get("host", "")) if path == WORKER or poc_worker
                  else POC_CSP if path == POC_PAGE else CSP)
        response.headers.setdefault("Content-Security-Policy", policy)
        if path.startswith(PYODIDE) and response.status_code == 200:
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"   # the URL names the version
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "same-origin")
        response.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
        return response


def create_app(settings: Settings) -> FastAPI:
    from core.backend import BackendClient
    from core.auth import Auth
    from core.servers import Servers, Switch

    catalogue = Servers(settings)
    from routes import (admin_routes, api_routes, asof_routes, auth_routes, build_routes, calc_routes, export_routes, help_routes, home_routes,
                        layout_routes, monitor_routes, pivot_routes, report_routes, review_routes, server_routes, ship_routes, studio_routes, terminal_routes,
                        workspace_routes, collab_routes, thread_routes, collab_admin_routes, embed_admin_routes)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if getattr(app.state, "backend", None) is None:      # one pooled client per server (tests bring their own)
            app.state.backend = Switch({s.id: BackendClient(s.url, float(settings.get("backend.timeout_seconds", 5)),
                                                            int(settings.get("backend.pool_size", 64))) for s in catalogue.all()},
                                       catalogue.default)
        yield
        for client in (app.state.backend.each() if isinstance(app.state.backend, Switch) else [app.state.backend]):
            close = getattr(client, "aclose", None)
            if close:
                await close()

    app = FastAPI(title=f"{settings.get('ui.product', '')} console".strip(), docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.backend = None
    templates = Jinja2Templates(directory=str(WEB / "templates"))
    templates.env.globals.update(
        drs_message=drs_message,
        drs_advice=lambda code, kind="", id_="": drs_advice(settings.get("ui.error_advice"), code, kind, id_),
        ASSET_V=f"{ASSET_VERSION}-{asset_fingerprint()}",
        PRODUCT=settings.get("ui.product", ""),
        PRODUCT_MEANING=settings.get("ui.product_meaning", ""),
        PRODUCT_NATIVE=settings.get("ui.product_native", ""),
        NOTICE=settings.get("ui.notice", ""),
        TAGLINE=settings.get("ui.tagline", ""),
        DEFAULT_THEME=settings.get("ui.default_theme", "terminal"),
        DESK=settings.get("ui.desk", "Rates desk"),
        USER=settings.get("ui.user_display", "Ash"),
        CLOCK_TZ=settings.get("ui.clock_tz", "America/New_York"),
        CLOCK_LABEL=settings.get("ui.clock_label", "NY"),
        COPYRIGHT=settings.get("ui.copyright", ""),
        BI_POC=str(settings.get("bi.poc_enabled", False)).lower() in ("true", "1", "yes", "on"),
        ABOUT_PREFETCH=str(settings.get("ui.about_prefetch", True)).lower() not in ("false", "0", "no", "off"),
    )
    from core.asof import to_local

    templates.env.globals["known_local"] = to_local
    from jinja2 import pass_context

    from core.asof import local_when, zone_label

    @pass_context
    def when_local(ctx, instant):
        """A UTC instant in the zone of the top bar's "known at", its label after it: ``2026-10-05 14:30 New York``."""
        zone = (ctx.get("business_date") or {}).get("zone") or ctx.get("CLOCK_TZ") or "America/New_York"
        return local_when(instant, zone, zone_label(zone))

    templates.env.globals["when_local"] = when_local
    app.state.settings = settings
    app.state.servers = catalogue
    app.state.auth = (Auth(settings) if len(catalogue) == 1 and catalogue.default == "default"
                      else Switch({s.id: Auth(settings, s) for s in catalogue.all()}, catalogue.default))
    from core.oidc import Oidc

    app.state.oidc = Oidc(settings, app.state.auth)
    from core.settings import UserSettings

    app.state.user_settings = UserSettings()
    console_dir = WEB.parent
    docs_dir = Path(settings.get("help.docs_dir", "../docs"))
    from core.packs import Packs

    app.state.packs = Packs(settings, console_dir)
    app.state.docs_dir = docs_dir if docs_dir.is_absolute() else (console_dir / docs_dir).resolve()
    app.state.libraries = {}
    from core.examples import Examples

    examples_dir = Path(settings.get("studio.examples_dir", "../docs/guides/examples"))
    app.state.examples = Examples(examples_dir if examples_dir.is_absolute() else (console_dir / examples_dir).resolve(),
                                  str(settings.get("ui.studio_example", "") or ""))
    from core import builder as screen_builder

    app.state.builder_limits = screen_builder.Limits.from_settings(settings)
    app.state.pack_upload_limit = int(settings.get("packs.deploy_max_mb", 50)) * 1048576       # Admin → Packs → Deploy archive; the server refuses the same way
    from core.asof import BusinessDates

    app.state.business_dates = BusinessDates()
    app.state.templates = templates
    from core.csrf import BodyError, SameOrigin, problem

    app.add_middleware(AuthGate)
    app.add_middleware(SameOrigin, allowed=settings.get("auth.allowed_origins") or ())    # before any session work
    app.add_middleware(SecurityHeaders)
    app.add_exception_handler(BodyError, problem)
    from core.notfound import not_found

    app.add_exception_handler(404, not_found)
    app.mount("/static", StaticFiles(directory=str(WEB / "static")), name="static")
    from core.calc import Calc

    app.state.calc = Calc(settings, WEB)
    from core.layouts import Layouts

    app.state.layouts = Layouts(settings)
    from core.pivots import Pivots
    app.state.pivots = Pivots()
    if app.state.calc.runtime.installed:          # the Python runtime of Calc, from this origin only (tools/fetch-pyodide.sh)
        app.mount(app.state.calc.runtime.base.rstrip("/"), StaticFiles(directory=str(app.state.calc.runtime.folder)), name="pyodide")
    app.include_router(home_routes.router)
    app.include_router(terminal_routes.router)
    app.include_router(api_routes.router)
    app.include_router(auth_routes.router)
    app.include_router(studio_routes.router)
    app.include_router(build_routes.router)
    app.include_router(review_routes.router)
    app.include_router(ship_routes.router)
    app.include_router(admin_routes.router)
    app.include_router(help_routes.router)
    app.include_router(workspace_routes.router)
    app.include_router(monitor_routes.router)
    app.include_router(asof_routes.router)
    app.include_router(export_routes.router)
    app.include_router(server_routes.router)
    app.include_router(report_routes.router)
    app.include_router(calc_routes.router)
    app.include_router(layout_routes.router)
    app.include_router(pivot_routes.router)
    app.include_router(collab_routes.router)
    app.include_router(thread_routes.router)
    app.include_router(collab_admin_routes.router)
    app.include_router(embed_admin_routes.router)
    if str(settings.get("bi.poc_enabled", False)).lower() in ("true", "1", "yes", "on"):   # off by default: no /bi/poc paths exist otherwise
        from routes import bi_poc_routes

        app.include_router(bi_poc_routes.router)
    from core.embed import EmbedHosts

    app.state.embed = EmbedHosts(settings)
    if app.state.embed.enabled:                   # off by default: nothing under /embed/ exists otherwise
        from routes import embed_routes

        app.include_router(embed_routes.router)
    return app
