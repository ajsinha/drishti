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

import asyncio
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
ASSET_VERSION = "2.7.0"


def _bounded(value, low: int, high: int, default: int) -> int:
    """A numeric setting held between two bounds; anything that is not a number is the default."""
    try:
        return max(low, min(high, int(float(value))))
    except (TypeError, ValueError):
        return default


def _console_data_dir(settings) -> Path:
    """The console's own folder under the data folder: ``backend.data_dir`` (``DRISHTI_CONSOLE_DATA_DIR``), else ``<data dir>/console``
    (``DRISHTI_DATA_DIR``, else the repository's ``data``). It holds what the console keeps between runs (``net/servers.json``)."""
    import os

    own = str(settings.get("backend.data_dir") or "").strip()
    if own:
        return Path(own)
    return Path(os.environ.get("DRISHTI_DATA_DIR") or (WEB.parent.parent / "data")) / "console"


def _seconds(value) -> float:
    """``backend.check_seconds``: how often the console asks its server if it is there; 0 turns the watch off."""
    try:
        return max(0.0, float(value))
    except (TypeError, ValueError):
        return 5.0


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
# Calc's Graphic Walker frame (drishti_bi.walk, docs/guides/PYTHON_CALC.md): a third-party UI that needs eval (Vega compiles chart expressions) and writes its own styles
# (styled-components, a shadow root). So the one page that holds it, and only it, has this narrow policy, and it is a sandboxed frame: `sandbox allow-scripts` (no same-origin, so
# no cookie and no console API), `connect-src 'none'` and no navigation or forms, so the rows it is given by message cannot leave it. Calc's own page keeps the strict policy.
WALKER_PAGE = "/static/calc/walker.html"
WALKER_CSP = ("default-src 'none'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src data: blob:; font-src data:; worker-src blob:; connect-src 'none'; "
              "base-uri 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-scripts")
# Wave B (BI_DESIGN.md P3): no BI page loads WebAssembly any more (Perspective and DuckDB-Wasm are gone); only Calc's worker above may compile it.
EMBED = "/embed/"                           # embedded views for other web applications: off unless embed.enabled (routes/embed_routes.py)


PROTECTED = ("/t", "/bi", "/pyconsole", "/v/", "/go", "/form", "/studio", "/api/", "/admin", "/account", "/w", "/m", "/alerts", "/impact", "/s/", "/compare/", "/export/", "/pin/", "/p/", "/reports", "/build", "/share/", "/inbox")
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
        if getattr(request.app.state, "cluster", None) is not None:
            # several servers (backend.urls): each session keeps to its server while that answers (core/cluster.py)
            import hashlib

            from core import cluster
            session = request.cookies.get(getattr(auth, "cookie", "")) or ""
            cluster.SESSION.set(hashlib.sha256(session.encode()).hexdigest()[:16] if session else "")
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
        request.state.bi_me = None
        if request.state.identity is not None and not path.startswith(("/static/", PYODIDE, EMBED, "/api/", "/healthz", "/readyz")):
            from core.settings import changed_at
            request.state.settings = await request.app.state.user_settings.get(request.app.state.backend, request.state.identity,
                                                                               changed_at(request))
            if request.app.state.bi_on and not path.startswith("/bi/api/"):
                request.state.bi_me = await request.app.state.bi_me.get(request.app.state.backend, request.state.identity)
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
        stamp = getattr(request.state, "settings_changed_at", None)
        if stamp:                                  # a setting changed: every worker reads the settings again (core/settings.py)
            from core.settings import CHANGED_COOKIE
            response.set_cookie(CHANGED_COOKIE, f"{stamp:.6f}", path="/", httponly=True, samesite="lax",
                                secure=bool(getattr(request.app.state.auth, "secure_cookie", False)))
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
        policy = (worker_csp(request.headers.get("host", "")) if path == WORKER
                  else WALKER_CSP if path == WALKER_PAGE else CSP)
        response.headers.setdefault("Content-Security-Policy", policy)
        if path.startswith(PYODIDE) and response.status_code == 200:
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"   # the URL names the version
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "same-origin")
        response.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
        return response


def create_app(settings: Settings) -> FastAPI:
    from core import tmpdir

    tmpdir.start(str(settings.get("tmp_dir", "") or ""))      # the console's own temp folder, before anything makes a temporary file
    from core import external_lib

    external_lib.activate(str(settings.get("external_lib", "") or ""))   # <base>/python importable (core/external_lib.py)
    from core.backend import BackendClient
    from core.auth import Auth
    from core.servers import Servers, Switch

    catalogue = Servers(settings)
    from routes import (admin_routes, alert_routes, api_routes, asof_routes, auth_routes, build_routes, pack_routes, calc_routes, export_routes, help_routes, home_routes,
                        designer_routes, designer_grid_routes, dist_routes, layout_routes, monitor_routes, pivot_routes, report_routes, review_routes, server_routes, ship_routes, studio_routes, terminal_routes,
                        workspace_routes, collab_routes, thread_routes, collab_admin_routes, embed_admin_routes, connector_routes, form_routes,
                        ai_routes)

    from routes import artifact_routes, compute_admin_routes, drafts_routes, history_routes, lake_maint_routes, lake_routes, net_routes

    def _lake_schedules(app):
        from core.lake_maint import maintenance
        try:
            m = maintenance(app)
        except Exception:  # noqa: BLE001 - a console without the lake settings still starts
            return None
        return asyncio.create_task(m.loop_forever(app)) if m.lakes.cfg.schedules else None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        pool = getattr(app.state, "cluster", None)
        if getattr(app.state, "backend", None) is None:      # one pooled client per server (tests bring their own)
            app.state.backend = Switch({s.id: BackendClient(s.url, float(settings.get("backend.timeout_seconds", 5)),
                                                            int(settings.get("backend.pool_size", 64)),
                                                            servers=pool if s.id == catalogue.default else None) for s in catalogue.all()},
                                       catalogue.default)
        # several servers (backend.urls, Drishti*net): health every check_every, the member list every discover_every (core/cluster.py)
        cluster_task = (asyncio.create_task(pool.run(lambda: app.state.auth.service().headers()))
                        if pool is not None and pool.enabled else None)
        if pool is not None:
            import socket

            async def _report(event):
                try:
                    await app.state.backend.net("POST", "/console-event", app.state.auth.service(),
                                                {"from": event["from"], "to": event["to"], "why": event["why"], "console": socket.gethostname()})
                except Exception:  # noqa: BLE001 - best effort: the console's log has the line anyway
                    pass

            def _on_failover(event):
                try:
                    asyncio.get_running_loop().create_task(_report(event))
                except RuntimeError:
                    pass
            pool.on_failover = _on_failover
        ai = getattr(app.state, "ai", None)
        watcher = asyncio.create_task(ai.supervise()) if ai is not None and (ai.enabled or ai.mcp_enabled) else None
        backend_task = asyncio.create_task(app.state.backend_watch.run()) if app.state.backend_watch.enabled else None
        maint_task = _lake_schedules(app)                    # Admin -> Lakes -> Maintain: the schedules (core/lake_maint.py)
        runner = getattr(app.state, "pyrunner", None)
        warm_task = asyncio.create_task(runner.warm()) if runner is not None and runner.warm_on_start else None   # wave C: warm Python workers
        from core import channel_relay

        await channel_relay.start(app)                      # several workers: live-channel calls reach the worker holding the stream
        yield
        await channel_relay.stop()
        if warm_task is not None and not warm_task.done():
            warm_task.cancel()
        if maint_task is not None:
            maint_task.cancel()
        if backend_task is not None:
            backend_task.cancel()
        if cluster_task is not None:
            cluster_task.cancel()
            pool.save()                                      # servers.json at shutdown too
        runner = getattr(app.state, "pyrunner", None)
        if runner is not None:
            await runner.close()                              # the fork-server ends with the console
        if getattr(app.state, "tmp_owner", False):          # the process that runs this app: uvicorn re-raises the stop signal after this, so atexit would not run
            from core import tmpdir

            tmpdir.clean_up()
        if watcher is not None:
            watcher.cancel()
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
        BI_LIVE={
            "min_interval_ms": _bounded(settings.get("bi.live.min_interval_ms", 250), 100, 5000, 250),
            "stale_seconds": _bounded(settings.get("bi.live.stale_seconds", 30), 5, 600, 30),
            "rotate_seconds": _bounded(settings.get("bi.board.rotate_seconds", 30), 3, 3600, 30),
        },
        REFRESH_MIN=_bounded(settings.get("views.refresh_min_seconds", 5), 1, 3600, 5),
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
    from core.backend_watch import BackendWatch

    from core.cluster import ServerPool

    # several servers (backend.urls): the pool of core/cluster.py; None when only backend.url is configured (as before)
    pool = ServerPool(settings, _console_data_dir(settings))
    app.state.cluster = pool if pool.enabled else None
    app.state.backend_watch = BackendWatch(catalogue, _seconds(settings.get("backend.check_seconds", 5)),
                                           url_of=(lambda s: pool.current() if s.id == catalogue.default else s.url) if pool.enabled else None)
    from core.user_stamps import UserStamps

    # a change through one worker process (a setting, packs, a layout, a disabled user) reaches every worker of this console at once
    app.state.user_stamps = stamps = UserStamps(_console_data_dir(settings) / "user-stamps")
    user_ttl = _seconds(settings.get("ui.user_cache_seconds", 10))
    app.state.auth = (Auth(settings, stamps=stamps) if len(catalogue) == 1 and catalogue.default == "default"
                      else Switch({s.id: Auth(settings, s, stamps=stamps) for s in catalogue.all()}, catalogue.default))
    from core.oidc import Oidc

    app.state.oidc = Oidc(settings, app.state.auth)
    from core.settings import UserSettings

    app.state.user_settings = UserSettings(user_ttl, stamps)
    from core.bi import BiMe

    app.state.bi_me = BiMe(user_ttl, stamps)
    app.state.bi_on = str(settings.get("bi.enabled", True)).lower() in ("true", "1", "yes", "on")      # false: no BI menu, no /bi pages
    console_dir = WEB.parent
    docs_dir = Path(settings.get("help.docs_dir", "../docs"))
    from core.packs import Packs

    app.state.packs = Packs(settings, console_dir, stamps)
    app.state.docs_dir = docs_dir if docs_dir.is_absolute() else (console_dir / docs_dir).resolve()
    from core.guides import tutorial_count_word

    templates.env.globals["designer_on"] = lambda: bool(settings.get("ui.designer", False))   # the Sutra designer: Build -> New screen offers Describe
    templates.env.globals["TUTORIALS_WORD"] = tutorial_count_word(app.state.docs_dir)       # "Eighteen": the Tutorials card counts the written tutorials, it is never typed
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
    from starlette.requests import ClientDisconnect

    from core.csrf import BodyError, SameOrigin, gone, problem

    app.add_middleware(AuthGate)
    app.add_middleware(SameOrigin, allowed=settings.get("auth.allowed_origins") or ())    # before any session work
    app.add_middleware(SecurityHeaders)
    app.add_exception_handler(BodyError, problem)
    app.add_exception_handler(ClientDisconnect, gone)
    from core.notfound import not_found

    app.add_exception_handler(404, not_found)
    from core.staticzip import CompressedStatic

    static = CompressedStatic(directory=str(WEB / "static"), enabled=str(settings.get("static.compress", True)).lower() in ("true", "1", "yes", "on"),
                              min_bytes=int(settings.get("static.compress_min_bytes", 1024)), cache_dir=str(settings.get("static.cache_dir", "") or ""),
                              brotli_quality=int(settings.get("static.brotli_quality", 5)))
    app.state.static = static
    app.mount("/static", static, name="static")
    from core.calc import Calc

    app.state.calc = Calc(settings, WEB)
    from core.pyrunner import PyRunner

    app.state.pyrunner = PyRunner(settings)          # the BI Python runner: nothing starts until a python visual runs (docs/guides/PYTHON_BI.md)
    from core.layouts import Layouts

    app.state.layouts = Layouts(settings, stamps)
    from core.pivots import Pivots
    app.state.pivots = Pivots(stamps, user_ttl)
    if app.state.calc.runtime.installed:          # the Python runtime of Calc, from this origin only (tools/fetch-pyodide.sh)
        app.mount(app.state.calc.runtime.base.rstrip("/"), StaticFiles(directory=str(app.state.calc.runtime.folder)), name="pyodide")
    app.include_router(home_routes.router)
    app.include_router(history_routes.router)          # history queries: before the terminal (/v/{kind}/{id}) and export routes
    app.include_router(terminal_routes.router)
    app.include_router(drafts_routes.router)            # form drafts (SUTRA_NEXT.md 10), before the general API routes
    app.include_router(drafts_routes.lookup_router)     # an entity field's live search (/api/forms/lookup)
    app.include_router(api_routes.router)
    app.include_router(auth_routes.router)
    app.include_router(studio_routes.router)
    app.include_router(build_routes.router)
    app.include_router(pack_routes.router)
    app.include_router(review_routes.router)
    app.include_router(ship_routes.router)
    app.include_router(admin_routes.router)
    app.include_router(help_routes.router)
    app.include_router(workspace_routes.router)
    app.include_router(alert_routes.router)
    app.include_router(monitor_routes.router)
    app.include_router(asof_routes.router)
    app.include_router(export_routes.router)
    app.include_router(server_routes.router)
    app.include_router(report_routes.router)
    app.include_router(dist_routes.router)
    app.include_router(calc_routes.router)
    app.include_router(layout_routes.router)
    app.include_router(designer_routes.router)          # the Sutra designer (ui.designer, off by default)
    app.include_router(designer_grid_routes.router)     # its Grid mode
    app.include_router(pivot_routes.router)
    app.include_router(collab_routes.router)
    app.include_router(thread_routes.router)
    app.include_router(collab_admin_routes.router)
    app.include_router(embed_admin_routes.router)
    app.include_router(compute_admin_routes.router)     # Admin -> Compute (docs/admin/COMPUTE.md)
    app.include_router(connector_routes.router)
    app.include_router(artifact_routes.router)
    app.include_router(lake_routes.router)
    app.include_router(net_routes.router)
    app.include_router(lake_maint_routes.router)
    app.include_router(form_routes.router)
    app.include_router(ai_routes.router)
    if app.state.bi_on:
        from core.explore import Limits
        from routes import bi_routes, explore_routes, model_routes, pynode_routes, pyconsole_routes, python_routes, transform_routes, wizard_routes

        app.state.explore_limits = Limits.from_settings(settings)      # Analyse (docs/guides/ANALYSE.md): extract, SQL and cell limits
        app.include_router(bi_routes.router)
        app.include_router(wizard_routes.router)
        app.include_router(model_routes.router)
        app.include_router(python_routes.router)
        app.include_router(explore_routes.router)
        app.include_router(transform_routes.router)        # wave C: the Transform editor (/bi/transform), step by step
        app.include_router(pyconsole_routes.router)        # wave C: the browser Python console (/pyconsole) and its masked extracts
        app.include_router(pynode_routes.router)          # the Py-node endpoint the server's planner calls (/internal/py/*: its token only)
    from core.ai import AiRuntime

    app.state.ai = AiRuntime(settings, backend=lambda: app.state.backend)       # off by default: no file is read, no model or MCP server is called
    from core.embed import EmbedHosts

    app.state.embed = EmbedHosts(settings)
    if app.state.embed.enabled:                   # off by default: nothing under /embed/ exists otherwise
        from routes import embed_routes

        app.include_router(embed_routes.router)
        from routes import embed_report_routes

        app.include_router(embed_report_routes.router)
    return app
