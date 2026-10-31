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

"""The embed API, version 1 (docs/architecture/CLIENT_RENDERER.md › Drishti Elements: the design, section 6; build step 7).

Cross-origin, bearer-token, CORS by an exact origin allow-list. Only mounted when ``embed.enabled`` (core/app.py). The
view, the channel and the command reuse the console's own code (the view model, ``_view_event``, ``api_routes.channel``);
the element's script and sheet are served here, with CORS, because a module script and a font need it cross-origin.
"""
from __future__ import annotations

import asyncio
import dataclasses
import json
import re
import time
from pathlib import Path

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response, StreamingResponse

from core.backend import BackendError
from core import element_dist as dist
from core import element_sheet as es
from core import channel_relay
from core.channel import ChannelSession
from core.element_sheet import shadow_css  # noqa: F401 - kept importable from here
from core.csrf import BodyError, json_body
from core.embed import EmbedError, EmbedIdentity
from core.embed_stream import StreamGuard
from core.embed_wire import compress
from core import controls
from routes import api_routes

router = APIRouter(prefix="/embed/v1", include_in_schema=False)
WEB = Path(__file__).resolve().parent.parent / "web"
API = "1.0"
API_BASE = "/embed/v1"
LIVE_KEYS = ("view:", "vm:", "bilive:")    # what the embed channel carries: a view's frames (vm: as panels, embed API v2), a report's live ticks
_cache: dict = {}


async def cors(request: Request, response: Response) -> Response:
    """Allow-list CORS: the request's exact Origin when some host application lists it, else nothing (the browser refuses)."""
    origin = request.headers.get("origin")
    if origin and await request.app.state.embed.origin_allowed(request, origin):
        response.headers["Access-Control-Allow-Origin"] = origin
        vary = response.headers.get("Vary", "")
        response.headers["Vary"] = vary if "Origin" in vary else (vary + ", Origin" if vary else "Origin")
        response.headers["Access-Control-Expose-Headers"] = "Drishti-Embed-Api"
    response.headers["Drishti-Embed-Api"] = API
    for name, value in dist.lifecycle(request.app.state.embed.settings).get("headers", {}).items():       # embed.lifecycle: a major that is deprecated or has a sunset day
        response.headers[name] = value
    return response


def _elements(request: Request) -> str:
    """The library by the product's name from configuration (ui.product): "<product> Elements"."""
    return f"{request.app.state.settings.get('ui.product', '')} Elements".strip()


async def refuse(request: Request, status: int, code: str, detail: str, retry_after: str | None = None) -> Response:
    headers = {"Cache-Control": "no-store"}
    if retry_after:
        headers["Retry-After"] = str(retry_after)
    return await cors(request, JSONResponse({"code": code, "detail": detail}, status_code=status, headers=headers))


async def identity(request: Request):
    """The embed caller as an Identity that calls the server with its embed token, and the token's claims. Raises EmbedError.
    The server verifies the token (signature, audience, expiry, host application, user, origin, scopes, rates) on every call."""
    life = dist.lifecycle(request.app.state.embed.settings)
    if life["retired"]:
        raise EmbedError(410, "DRS-8006", f"the embed API v1 was retired on {life['sunset']}" + (f": {life['link']}" if life["link"] else ""))
    auth = request.headers.get("authorization", "")
    me, claims = await request.app.state.embed.identity(request, auth[7:].strip() if auth.lower().startswith("bearer ") else "",
                                                        request.headers.get("origin"))
    request.state.identity = me
    return me, claims


@router.options("/{path:path}")
async def preflight(request: Request, path: str):
    origin = request.headers.get("origin")
    if not await request.app.state.embed.origin_allowed(request, origin):
        return Response(status_code=403)
    return await cors(request, Response(status_code=204, headers={
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS", "Access-Control-Allow-Headers": "authorization, content-type, drishti-embed-api",
        "Access-Control-Max-Age": "600"}))


# -- the element and what it needs ----------------------------------------------------------------------------------
def asset(name: str, build) -> tuple[bytes, str]:
    """A built asset, cached until a source file changes (its mtimes name the version)."""
    key = (name, (es.OUT_DIR / es.SHEET_FILE).stat().st_mtime_ns, max(f.stat().st_mtime_ns for f in (WEB / "embed").glob("*.js")))
    if _cache.get(name, (None,))[0] != key:
        _cache[name] = (key, build())
    body = _cache[name][1]
    return body, f"\"{abs(hash(key)):x}\""


async def send(request: Request, body: bytes, media: str, etag: str) -> Response:
    if request.headers.get("if-none-match") == etag:
        return await cors(request, Response(status_code=304, headers={"ETag": etag, "Cache-Control": "no-cache"}))
    headers = {"ETag": etag, "Cache-Control": "no-cache"}      # revalidated each time; the sheet also has an immutable, hash-named URL
    if media != "font/woff2":
        body, coding = compress(body, request.headers.get("accept-encoding"), min_bytes(request))
        if coding:
            headers["Content-Encoding"] = coding
    headers["Vary"] = "Accept-Encoding, Origin"
    return await cors(request, Response(body, media_type=media, headers=headers))


def min_bytes(request: Request) -> int:
    return int(request.app.state.embed.settings.get("compress_min_bytes", 512))


async def reply(request: Request, body: str | bytes, media: str, status: int = 200) -> Response:
    """A dynamic payload (a view, an About body, rows), never cached, compressed when the browser accepts it."""
    raw = body.encode("utf-8") if isinstance(body, str) else body
    raw, coding = compress(raw, request.headers.get("accept-encoding"), min_bytes(request))
    headers = {"Cache-Control": "no-store", "Vary": "Accept-Encoding, Origin"}
    if coding:
        headers["Content-Encoding"] = coding
    return await cors(request, Response(raw, status_code=status, media_type=media, headers=headers))


@router.get("/drishti-elements.js")
async def element_js(request: Request):
    body, etag = asset("js", lambda: (WEB / "embed" / "drishti-elements.js").read_bytes())
    return await send(request, body, "text/javascript", etag)


@router.get("/m/{name}.js")
async def element_module(request: Request, name: str):
    """The library's ES modules (web/embed/*.js, imported by drishti-elements.js as ./m/<name>.js): a name that is a file there, nothing else."""
    path = WEB / "embed" / f"{name}.js"
    if not re.fullmatch(r"[a-z][a-z-]*", name) or name == "drishti-elements" or not path.is_file():
        return await refuse(request, 404, "DRS-9404", f"no such module: {name}")
    body, etag = asset("m:" + name, path.read_bytes)
    return await send(request, body, "text/javascript", etag)


@router.get("/drishti-view.css")
async def element_css(request: Request):
    """The generated sheet (tools/elements_sheet.py, committed under web/elements/): never built per request."""
    body, etag = asset("css", lambda: (es.OUT_DIR / es.SHEET_FILE).read_bytes())
    return await send(request, body, "text/css", etag)


@router.get("/drishti-report.css")
async def report_css(request: Request):
    """The sheet of ``<drishti-report>`` (the BI sheets, generated like the view sheet), adopted after ``drishti-view.css``."""
    body, etag = asset("rcss", lambda: (es.OUT_DIR / es.REPORT_FILE).read_bytes())
    return await send(request, body, "text/css", etag)


@router.get("/elements/{version}/drishti-view.css")
async def versioned_css(request: Request, version: str):
    """The same sheet under its content hash: the URL names the bytes, so it is cacheable for ever (build step 8)."""
    manifest = json.loads((es.OUT_DIR / es.MANIFEST_FILE).read_text(encoding="utf-8"))
    if version != manifest["version"]:
        return await refuse(request, 404, "DRS-5001", f"no such element sheet: {version} (the current one is {manifest['version']})")
    response = await element_css(request)
    if response.status_code == 200:
        response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    return response


@router.get("/elements/{version}/{rest:path}")
async def versioned(request: Request, version: str, rest: str):
    """The library at an exact semantic version, immutable (``/elements/1.1.0/drishti-elements.js``, ``.../m/<name>.js``, ``.../integrity.json``); a partial
    version (``1``, ``1.1``) answers ``302`` to the exact one for a host that accepts updates (SRI needs the exact version). CLIENT_RENDERER.md › Drishti Elements: the design, section 13."""
    exact = dist.resolve(version)
    if exact is None:
        if dist.SEMVER.fullmatch(version):
            return await refuse(request, 410, "DRS-8006", f"this console serves {_elements(request)} {dist.VERSION}, not {version}: pin {dist.VERSION}, or use the alias {dist.VERSION.split('.')[0]}")
        return await refuse(request, 404, "DRS-9404", f"no such element version: {version}")
    if version != exact:
        return await cors(request, RedirectResponse(f"{API_BASE}/elements/{exact}/{rest}", status_code=302, headers={"Cache-Control": "public, max-age=60"}))
    if rest == "integrity.json":
        body, media, etag = dist.lock_text().encode("utf-8"), "application/json", f'"{dist.VERSION}-integrity"'
    else:
        path = dist.files().get(rest)
        if path is None:
            return await refuse(request, 404, "DRS-9404", f"no such file in {_elements(request)} {exact}: {rest}")
        body, media, etag = path.read_bytes(), "text/javascript", '"' + dist.sri(path.read_bytes()) + '"'
    response = await send(request, body, media, etag)
    if response.status_code == 200:
        response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    return response


@router.get("/icons.woff2")
async def icons_font(request: Request):
    return await send(request, (WEB / "static/vendor/bootstrap-icons/fonts/bootstrap-icons.woff2").read_bytes(), "font/woff2", '"icons"')


@router.get("/charts.js")
async def charts_js(request: Request):
    """charts.js (waterfall, histogram, ...), served with CORS so a module can import it; the element loads it as a script."""
    return await send(request, (WEB / "static/js/charts.js").read_bytes(), "text/javascript", f'"{(WEB / "static/js/charts.js").stat().st_mtime_ns:x}"')


@router.get("/world-countries.json")
async def world_countries(request: Request):
    """The countries of the world for a map visual (Natural Earth, public domain; vendored, see static/vendor/VENDOR.md), served with CORS like the chart library."""
    path = WEB / "static/vendor/echarts/maps/world-countries.json"
    return await send(request, path.read_bytes(), "application/json", f'"{path.stat().st_mtime_ns:x}"')


@router.get("/world-points.json")
async def world_points(request: Request):
    """The countries too small for the 1:110m world map, one point each (Natural Earth; vendored)."""
    path = WEB / "static/vendor/echarts/maps/world-points.json"
    return await send(request, path.read_bytes(), "application/json", f'"{path.stat().st_mtime_ns:x}"')


@router.get("/regions/{iso3}.json")
async def country_regions(request: Request, iso3: str):
    """One country's regions for a map that drills into it (Natural Earth admin-1, ISO 3166-2 codes; vendored). The name is a three-letter code and nothing else."""
    if not re.fullmatch(r"[A-Z]{3}", iso3):
        return await refuse(request, 404, "DRS-9404", f"no such country: {iso3}")
    path = WEB / "static/vendor/echarts/maps/regions" / f"{iso3}.json"
    if not path.is_file():
        return await refuse(request, 404, "DRS-9404", f"no regions for {iso3}")
    return await send(request, path.read_bytes(), "application/json", f'"{path.stat().st_mtime_ns:x}"')


@router.get("/echarts.js")
async def echarts_js(request: Request):
    path = WEB / "static/vendor/echarts/echarts.min.js"
    return await send(request, path.read_bytes(), "text/javascript", f'"{path.stat().st_mtime_ns:x}"')


# The console's own enhancer scripts, root-scoped (static/js; CLIENT_RENDERER.md › Drishti Elements: the design, section 12): the element loads these, with
# data-manual so they do not start by themselves, and calls init(shadowRoot, options) on each.
ENHANCERS = ("view", "geomap", "charts", "colfilter", "tables", "tree-rows", "pivot", "pivot-engine", "pivot-grid", "about", "about-hints", "zoom", "multiselect", "controls",
             "bi-lib", "bi-visuals", "bi-visuals-format", "bi-grids", "bi-matrix", "bi-multiples", "bi-frame", "bi-live", "bi-phone")


@router.get("/js/{name}.js")
async def enhancer_js(request: Request, name: str):
    if name not in ENHANCERS:
        return await refuse(request, 404, "DRS-9404", f"no such script: {name}")
    path = WEB / "static/js" / f"{name}.js"
    return await send(request, path.read_bytes(), "text/javascript", f'"{path.stat().st_mtime_ns:x}"')


@router.get("/views/{kind}/{id_:path}/panels/{panel}/records")
async def pivot_records(request: Request, kind: str, id_: str, panel: str):
    """The rows a pivot is computed from (the console's /api/pivot/records, for the embed caller's masked identity)."""
    try:
        me, _claims = await identity(request)
        return await reply(request, json.dumps(await request.app.state.backend.panel_records(kind, id_, panel, me), ensure_ascii=False), "application/json")
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail)


@router.get("/views/{kind}/{id_:path}/about")
async def about(request: Request, kind: str, id_: str, generation: int = 0):
    """The body of the About drawer (an HTML fragment, as /v/{kind}/{id}/about renders it), for the embed caller."""
    from core import about_index

    env = request.app.state.templates.env
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    extra = {"accept_language": request.headers["accept-language"]} if request.headers.get("accept-language") else {}
    try:
        ex = await request.app.state.backend.explain(kind, id_, me, generation or None, **extra)
        html = env.get_template("terminal/_about.html").render(ex=ex, error=None, kind=kind, id=id_, index=about_index.build(ex))
        status = 200
    except BackendError as e:
        html = env.get_template("terminal/_about.html").render(ex=None, error=e, kind=kind, id=id_)
        status = e.page_status
    return await reply(request, html, "text/html", status)


@router.get("/search")
async def search(request: Request, q: str = "", limit: int = 0):
    """``<drishti-search>``: the type-ahead across Drishti for the embed caller (scope ``embed:search``; read-only, masked, cut to the application's kinds by the server)."""
    try:
        me, _claims = await identity(request)
        out = await request.app.state.backend.embed_search(q[:200], me, min(max(limit, 0), 50) or None)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    return await reply(request, json.dumps(out, ensure_ascii=False), "application/json")


@router.get("/resolve")
async def resolve(request: Request, text: str = ""):
    try:
        me, _claims = await identity(request)
        out = await request.app.state.backend.command(text, me)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    return await reply(request, json.dumps(out, ensure_ascii=False), "application/json")


# -- the view ---------------------------------------------------------------------------------------------------------
def _render(request: Request, vm: dict, asof: str = "live") -> tuple[str, list, list]:
    """The head, the panels and the provenance banners, each from the macros terminal/view.html uses (_macros/view.html)."""
    env = request.app.state.templates.env
    parts = env.get_template("_macros/view.html").module
    head = str(parts.vhead(vm)).strip()
    banners = str(parts.provenance_banners(vm, asof, {"selected": asof}, True)).strip()
    panels = [{"id": p["id"], "kind": p["kind"], "title": p.get("title") or "", "area": p.get("area") or "main", "span": p.get("span"),
               "height": p.get("height"), "html": str(parts.panels([p], True)).strip()} for p in vm.get("panels", [])]
    return head, panels, [banners] if banners else []


@router.get("/views/{kind}/{id_:path}/panels")
async def panels_fragment(request: Request, kind: str, id_: str, panels: str = ""):
    """Some panels of a view as ready HTML, built with the controls' values (``?panels=cashflows&ctl.legs=1``): what the element asks for when
    a reader changes a control. Also the strip and the controls (a cascading child's options may have moved). As the console's own
    ``/v/<kind>/<id>/panels``, for the embed caller's masked identity."""
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    ctl = controls.from_query(request.query_params)
    ids = [p for p in panels.split(",") if p and p != "-"] if panels else None
    try:
        vm = await request.app.state.backend.view(kind, id_, me, controls=ctl, panels=ids)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    parts = request.app.state.templates.env.get_template("_macros/view.html").module
    out = [{"id": p["id"], "kind": p["kind"], "title": p.get("title") or "", "html": str(parts.panels([p], True)).strip()} for p in vm.get("panels", [])]
    body = {"panels": out, "strip": str(parts.strip(vm)).strip(), "controls": vm.get("controls") or []}
    return await reply(request, json.dumps(body, ensure_ascii=False), "application/json")


@router.get("/views/{kind}/{id_:path}")
async def view(request: Request, kind: str, id_: str):
    """The initial view: the head, the strip and every panel as the console's own macros render them, plus the channel key."""
    started = time.perf_counter()
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    ctl = controls.from_query(request.query_params)
    try:
        vm = await request.app.state.backend.view(kind, id_, me, **({"controls": ctl} if ctl else {}))
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    asof = getattr(request.state, "asof", "live")
    head, panels, banners = _render(request, vm, asof)
    prov = vm.get("provenance") or {}
    live = bool(prov.get("live")) and asof == "live"
    masked = int(prov.get("masked") or 0)            # counted by the server (provenance.masked), not from the markup
    body = {"api": API, "ref": vm["ref"], "mnemonic": vm.get("mnemonic"), "title": vm["title"], "head": head, "strip": vm.get("strip", []),
            "banners": banners, "panels": panels, "provenance": prov, "controls": vm.get("controls") or [], "selects": vm.get("selects") or [],
            "masked": {"count": masked, "panels": list(prov.get("maskedPanels") or [])},
            "subscribe": f"view:{vm['ref']['kind']}/{vm['ref']['id']}" if live else None,
            "asOf": asof, "timings": {"console": round((time.perf_counter() - started) * 1000, 1)}}
    return await reply(request, json.dumps(body, ensure_ascii=False), "application/json")


# -- the channel ------------------------------------------------------------------------------------------------------
@router.get("/channel")
async def channel(request: Request, s: list[str] = Query(default=[])):
    """The console's live channel (``core.channel.ChannelSession``, shared with ``/api/channel``) for the embed caller; only
    view subscriptions are accepted, and the channel is owned by the user and the host application. The token's life is watched
    by a ``StreamGuard`` (``event: token`` at expiry, a fresh one on ``POST /channel/{cid}/token``, else ``gone`` DRS-8001)."""
    hosts = request.app.state.embed
    try:
        me, claims = await identity(request)
        room = hosts.limits.room(hosts.guards)
        if room <= 0:
            raise EmbedError(429, "DRS-8004", f"the console already holds {hosts.limits.max_streams} embed streams (embed.max_streams)", "5")
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    limit = min(api_routes.max_subscriptions(request), room)
    asked = list(dict.fromkeys(x for x in s if x.startswith(LIVE_KEYS)))[:limit * 2]
    session = ChannelSession(request.app.state.backend, me, lambda event, data: api_routes._view_event(request, event, data, embed=True),
                             limit=limit, app=claims["azp"], registry=api_routes.CHANNELS)
    guard = StreamGuard(session, claims, grace=float(hosts.settings.get("token_grace_seconds", 30)),
                        recheck=float(hosts.settings.get("recheck_seconds", 60)))
    hosts.guards[session.cid] = guard

    async def stream():
        guard.start()
        try:
            async for chunk in session.events(asked, request.is_disconnected):
                yield chunk
        finally:
            guard.stop()
            hosts.guards.pop(session.cid, None)

    return await cors(request, StreamingResponse(stream(), media_type="text/event-stream",
                                           headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"}))


def _owned(request: Request, cid: str, me, claims):
    ch = api_routes.CHANNELS.get(cid)
    return ch if ch is not None and ch["user"] == me.user and ch.get("app", "") == claims["azp"] else None


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    hosts = request.app.state.embed
    try:
        me, claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    ch = _owned(request, cid, me, claims)
    remote = api_routes.CHANNELS.get(cid) is None and channel_relay.owner_port(cid) is not None    # held by another worker of this console
    if ch is None and not remote:
        return await refuse(request, 404, "DRS-5001", "no such channel: open a new one")
    try:
        body = await json_body(request, limit=16_384)
    except (BodyError, ValueError):
        return await refuse(request, 400, "DRS-5001", "a JSON body {add, remove}")
    limit = api_routes.max_subscriptions(request)
    if remote:
        got = await channel_relay.forward(cid, "embed-change", {"user": me.user, "app": claims["azp"], "add": body.get("add") or [],
                                                                "remove": body.get("remove") or [], "limit": limit})
        if not got or not got.get("ok"):
            return await refuse(request, 404, "DRS-5001", "no such channel: open a new one")
    else:
        _change(hosts, ch, body, limit)
    return await cors(request, JSONResponse({"ok": True}, headers={"Cache-Control": "no-store"}))


def _change(hosts, ch: dict, body: dict, limit: int) -> None:
    session = ch["session"]
    for sub in (body.get("remove") or [])[:limit]:
        ch["remove"](str(sub))
    session.limit = min(limit, len(session.tasks) + hosts.limits.room(hosts.guards))        # embed.max_streams, across all channels
    for sub in [x for x in (body.get("add") or []) if str(x).startswith(LIVE_KEYS)][:limit * 2]:
        ch["add"](str(sub))


def _relayed_owner(cid: str, payload: dict):
    ch = api_routes.CHANNELS.get(cid)
    return ch if ch is not None and ch["user"] == str(payload.get("user", "")) and ch.get("app", "") == str(payload.get("app", "")) else None


def _relayed_change(app, cid: str, payload: dict) -> dict:
    """A change another worker received, and checked the caller of, for a channel this worker holds (core/channel_relay.py)."""
    ch = _relayed_owner(cid, payload)
    if ch is None:
        return {"ok": False}
    _change(app.state.embed, ch, payload, int(payload.get("limit") or api_routes.MAX_CHANNEL_SUBSCRIPTIONS))
    return {"ok": True}


def _relayed_token(app, cid: str, payload: dict) -> dict:
    """A fresh token another worker verified, for a stream this worker holds."""
    claims = payload.get("claims") or {}
    guard = app.state.embed.guards.get(cid)
    if guard is None or _relayed_owner(cid, {"user": claims.get("sub", ""), "app": claims.get("azp", "")}) is None:
        return {"ok": False, "status": 404, "code": "DRS-5001", "detail": "no such channel: open a new one"}
    who = dict(payload.get("identity") or {})
    who["roles"] = tuple(who.get("roles") or ("viewer",))
    try:
        guard.refresh(EmbedIdentity(**who), claims)
    except EmbedError as e:
        return {"ok": False, "status": e.status, "code": e.code, "detail": e.detail}
    return {"ok": True}


channel_relay.HANDLERS["embed-change"] = _relayed_change
channel_relay.HANDLERS["embed-token"] = _relayed_token


@router.post("/channel/{cid}/token")
async def channel_token(request: Request, cid: str):
    """A fresh embed token for an open stream, in the ``Authorization`` header (never the URL): same user, same host application."""
    hosts = request.app.state.embed
    try:
        me, claims = await identity(request)
        guard = hosts.guards.get(cid)
        if guard is None and api_routes.CHANNELS.get(cid) is None and channel_relay.owner_port(cid) is not None:     # another worker's stream
            got = await channel_relay.forward(cid, "embed-token", {"identity": dataclasses.asdict(me), "claims": claims}) or {}
            if not got.get("ok"):
                raise EmbedError(int(got.get("status") or 404), str(got.get("code") or "DRS-5001"), str(got.get("detail") or "no such channel: open a new one"))
        else:
            if guard is None or _owned(request, cid, me, claims) is None:
                raise EmbedError(404, "DRS-5001", "no such channel: open a new one")
            guard.refresh(me, claims)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    return await cors(request, JSONResponse({"ok": True, "expiresAt": int(claims["exp"])}, headers={"Cache-Control": "no-store"}))
