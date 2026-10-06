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

"""The embed API of the proof of concept (docs/architecture/ELEMENTS.md, section 6; build step 0).

Cross-origin, bearer-token, CORS by an exact origin allow-list. Only mounted when ``embed.enabled`` (core/app.py). The
view, the channel and the command reuse the console's own code (the view model, ``_view_event``, ``api_routes.channel``);
the element's script and sheet are served here, with CORS, because a module script and a font need it cross-origin.
"""
from __future__ import annotations

import gzip
import json
import re
import time
from pathlib import Path

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse

from core.backend import BackendError
from core import element_sheet as es
from core.channel import ChannelSession
from core.element_sheet import shadow_css  # noqa: F401 - kept importable from here
from core.csrf import BodyError, json_body
from core.embed import EmbedError
from routes import api_routes

router = APIRouter(prefix="/embed/v1", include_in_schema=False)
WEB = Path(__file__).resolve().parent.parent / "web"
API = "1.0"
_cache: dict = {}


async def cors(request: Request, response: Response) -> Response:
    """Allow-list CORS: the request's exact Origin when some host application lists it, else nothing (the browser refuses)."""
    origin = request.headers.get("origin")
    if origin and await request.app.state.embed.origin_allowed(request, origin):
        response.headers["Access-Control-Allow-Origin"] = origin
        response.headers["Vary"] = "Origin"
        response.headers["Access-Control-Expose-Headers"] = "Drishti-Embed-Api"
    response.headers["Drishti-Embed-Api"] = API
    return response


async def refuse(request: Request, status: int, code: str, detail: str, retry_after: str | None = None) -> Response:
    headers = {"Cache-Control": "no-store"}
    if retry_after:
        headers["Retry-After"] = str(retry_after)
    return await cors(request, JSONResponse({"code": code, "detail": detail}, status_code=status, headers=headers))


async def identity(request: Request):
    """The embed caller as an Identity that calls the server with its embed token, and the token's claims. Raises EmbedError.
    The server verifies the token (signature, audience, expiry, host application, user, origin, scopes, rates) on every call."""
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
    key = (name, (es.OUT_DIR / es.SHEET_FILE).stat().st_mtime_ns, (WEB / "embed" / "drishti-elements.js").stat().st_mtime_ns)
    if _cache.get(name, (None,))[0] != key:
        _cache[name] = (key, build())
    body = _cache[name][1]
    return body, f"\"{abs(hash(key)):x}\""


async def send(request: Request, body: bytes, media: str, etag: str) -> Response:
    if request.headers.get("if-none-match") == etag:
        return await cors(request, Response(status_code=304, headers={"ETag": etag, "Cache-Control": "no-cache"}))
    headers = {"ETag": etag, "Cache-Control": "no-cache"}      # POC: revalidated each time; step 8 serves versioned immutable URLs
    if "gzip" in request.headers.get("accept-encoding", "") and media != "font/woff2":
        body, headers["Content-Encoding"] = gzip.compress(body, 6), "gzip"
        headers["Vary"] = "Accept-Encoding, Origin"
    return await cors(request, Response(body, media_type=media, headers=headers))


@router.get("/poc/drishti-elements.js")
async def element_js(request: Request):
    body, etag = asset("js", lambda: (WEB / "embed" / "drishti-elements.js").read_bytes())
    return await send(request, body, "text/javascript", etag)


@router.get("/poc/drishti-view.css")
async def element_css(request: Request):
    """The generated sheet (tools/elements_sheet.py, committed under web/elements/): never built per request."""
    body, etag = asset("css", lambda: (es.OUT_DIR / es.SHEET_FILE).read_bytes())
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


@router.get("/poc/icons.woff2")
async def icons_font(request: Request):
    return await send(request, (WEB / "static/vendor/bootstrap-icons/fonts/bootstrap-icons.woff2").read_bytes(), "font/woff2", '"icons"')


@router.get("/poc/charts.js")
async def charts_js(request: Request):
    """charts.js (waterfall, histogram, ...), served with CORS so a module can import it; the element loads it as a script."""
    return await send(request, (WEB / "static/js/charts.js").read_bytes(), "text/javascript", f'"{(WEB / "static/js/charts.js").stat().st_mtime_ns:x}"')


@router.get("/poc/echarts.js")
async def echarts_js(request: Request):
    path = WEB / "static/vendor/echarts/echarts.min.js"
    return await send(request, path.read_bytes(), "text/javascript", f'"{path.stat().st_mtime_ns:x}"')


# The console's own enhancer scripts, root-scoped (static/js; ELEMENTS.md section 12): the element loads these, with
# data-manual so they do not start by themselves, and calls init(shadowRoot, options) on each.
ENHANCERS = ("view", "charts", "tables", "tree-rows", "pivot", "pivot-engine", "pivot-grid", "about", "about-hints")


@router.get("/poc/js/{name}.js")
async def enhancer_js(request: Request, name: str):
    if name not in ENHANCERS:
        return refuse(request, 404, "DRS-9404", f"no such script: {name}")
    path = WEB / "static/js" / f"{name}.js"
    return send(request, path.read_bytes(), "text/javascript", f'"{path.stat().st_mtime_ns:x}"')


@router.get("/poc/records/{kind}/{id_:path}/{panel}")
async def pivot_records(request: Request, kind: str, id_: str, panel: str):
    """The rows a pivot is computed from (the console's /api/pivot/records, for the embed caller's masked identity)."""
    try:
        me, _claims = identity(request)
        return cors(request, JSONResponse(await request.app.state.backend.panel_records(kind, id_, panel, me), headers={"Cache-Control": "no-store"}))
    except EmbedError as e:
        return refuse(request, e.status, e.code, e.detail)
    except BackendError as e:
        return refuse(request, e.page_status, e.code, e.detail)


@router.get("/poc/about/{kind}/{id_:path}")
async def about(request: Request, kind: str, id_: str, generation: int = 0):
    """The body of the About drawer (an HTML fragment, as /v/{kind}/{id}/about renders it), for the embed caller."""
    from core import about_index

    env = request.app.state.templates.env
    try:
        me, _claims = identity(request)
    except EmbedError as e:
        return refuse(request, e.status, e.code, e.detail)
    extra = {"accept_language": request.headers["accept-language"]} if request.headers.get("accept-language") else {}
    try:
        ex = await request.app.state.backend.explain(kind, id_, me, generation or None, **extra)
        html = env.get_template("terminal/_about.html").render(ex=ex, error=None, kind=kind, id=id_, index=about_index.build(ex))
        status = 200
    except BackendError as e:
        html = env.get_template("terminal/_about.html").render(ex=None, error=e, kind=kind, id=id_)
        status = e.page_status
    return cors(request, Response(html, status_code=status, media_type="text/html", headers={"Cache-Control": "no-store"}))


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


@router.get("/views/{kind}/{id_:path}")
async def view(request: Request, kind: str, id_: str):
    """The initial view: the head, the strip and every panel as the console's own macros render them, plus the channel key."""
    started = time.perf_counter()
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail)
    try:
        vm = await request.app.state.backend.view(kind, id_, me)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    asof = getattr(request.state, "asof", "live")
    head, panels, banners = _render(request, vm, asof)
    prov = vm.get("provenance") or {}
    live = bool(prov.get("live")) and asof == "live"
    masked = int(prov.get("masked") or 0)            # counted by the server (provenance.masked), not from the markup
    body = {"api": API, "ref": vm["ref"], "mnemonic": vm.get("mnemonic"), "title": vm["title"], "head": head, "strip": vm.get("strip", []),
            "banners": banners, "panels": panels, "provenance": prov,
            "masked": {"count": masked, "panels": list(prov.get("maskedPanels") or [])},
            "subscribe": f"view:{vm['ref']['kind']}/{vm['ref']['id']}" if live else None,
            "asOf": asof, "timings": {"console": round((time.perf_counter() - started) * 1000, 1)}}
    return await cors(request, Response(json.dumps(body, ensure_ascii=False), media_type="application/json", headers={"Cache-Control": "no-store"}))


@router.get("/resolve")
async def resolve(request: Request, text: str = ""):
    try:
        me, _claims = await identity(request)
        out = await request.app.state.backend.command(text, me)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    return await cors(request, JSONResponse(out, headers={"Cache-Control": "no-store"}))


# -- the channel ------------------------------------------------------------------------------------------------------
@router.get("/channel")
async def channel(request: Request, s: list[str] = Query(default=[])):
    """The console's live channel (``core.channel.ChannelSession``, shared with ``/api/channel``) for the embed caller; only
    view subscriptions are accepted, and the channel is owned by the user and the host application."""
    try:
        me, claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail)
    limit = api_routes.max_subscriptions(request)
    asked = list(dict.fromkeys(x for x in s if x.startswith("view:")))[:limit * 2]
    session = ChannelSession(request.app.state.backend, me, lambda event, data: api_routes._view_event(request, event, data, embed=True),
                             limit=limit, app=claims["azp"], registry=api_routes.CHANNELS)
    return await cors(request, StreamingResponse(session.events(asked, request.is_disconnected), media_type="text/event-stream",
                                           headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"}))


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    try:
        me, claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail)
    ch = api_routes.CHANNELS.get(cid)
    if ch is None or ch["user"] != me.user or ch.get("app", "") != claims["azp"]:
        return await refuse(request, 404, "DRS-5001", "no such channel: open a new one")
    try:
        body = await json_body(request, limit=16_384)
    except (BodyError, ValueError):
        return await refuse(request, 400, "DRS-5001", "a JSON body {add, remove}")
    limit = api_routes.max_subscriptions(request)
    for sub in (body.get("remove") or [])[:limit]:
        ch["remove"](str(sub))
    for sub in [x for x in (body.get("add") or []) if str(x).startswith("view:")][:limit * 2]:
        ch["add"](str(sub))
    return await cors(request, JSONResponse({"ok": True}, headers={"Cache-Control": "no-store"}))
