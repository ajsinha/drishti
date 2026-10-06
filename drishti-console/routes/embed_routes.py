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
from fastapi.responses import JSONResponse, Response

from core.backend import BackendError
from core.csrf import BodyError, json_body
from core.embed import EmbedError
from routes import api_routes

router = APIRouter(prefix="/embed/v1", include_in_schema=False)
WEB = Path(__file__).resolve().parent.parent / "web"
API = "1.0"
# the console's own sheets, in the order base.html loads them, rewritten for a shadow root (see shadow_css)
SHEETS = ("css/tokens.css", "css/theme.css", "css/terminal.css", "css/layout.css", "css/gradients.css", "vendor/bootstrap-icons/bootstrap-icons.css")
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
def shadow_css(text: str) -> str:
    """A console sheet for a shadow root: the page-level selectors (:root, html, body) become the host element, and the
    icon font's @font-face goes (a face declared inside a shadow root is not reliably used; the element adds it with FontFace)."""
    text = re.sub(r"@font-face\s*\{[^}]*\}", "", text)
    text = re.sub(r":root\[data-theme=\"([^\"]+)\"\]", r':host([data-theme="\1"])', text)
    text = re.sub(r":root:not\(\[data-theme\]\)", ":host(:not([data-theme]))", text)
    text = re.sub(r"html\[data-theme\]", ":host([data-theme])", text)
    text = re.sub(r"(?<![\w-]):root\b", ":host", text)
    text = re.sub(r"(?m)^(\s*)html,\s*body\b", r"\1:host", text)
    text = re.sub(r"(?m)^(\s*)body\.(terminal|embed)\b", r"\1:host", text)
    text = re.sub(r"(?m)^(\s*)body\b", r"\1:host", text)
    return text


def asset(name: str, build) -> tuple[bytes, str]:
    """A built asset, cached until a source file changes (its mtimes name the version)."""
    key = (name, tuple((WEB / "static" / s).stat().st_mtime_ns for s in SHEETS), (WEB / "embed" / "drishti-elements.js").stat().st_mtime_ns)
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
    body, etag = asset("css", lambda: "\n".join(shadow_css((WEB / "static" / s).read_text(encoding="utf-8")) for s in SHEETS).encode())
    return await send(request, body, "text/css", etag)


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


# -- the view ---------------------------------------------------------------------------------------------------------
def _render(request: Request, vm: dict) -> tuple[str, list]:
    env = request.app.state.templates.env
    module = env.get_template("_macros/panels.html").module
    head = env.get_template("embed/vhead.html").render(vm=vm).strip()
    panels = [{"id": p["id"], "kind": p["kind"], "title": p.get("title") or "", "area": p.get("area") or "main", "span": p.get("span"),
               "height": p.get("height"), "html": str(module.panel(p))} for p in vm.get("panels", [])]
    return head, panels


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
    head, panels = _render(request, vm)
    prov = vm.get("provenance") or {}
    asof = getattr(request.state, "asof", "live")
    live = bool(prov.get("live")) and asof == "live"
    masked = int(prov.get("masked") or 0)            # counted by the server (provenance.masked), not from the markup
    body = {"api": API, "ref": vm["ref"], "mnemonic": vm.get("mnemonic"), "title": vm["title"], "head": head, "strip": vm.get("strip", []),
            "banners": [], "panels": panels, "provenance": prov,
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
    """The console's channel, unchanged, for the embed caller; only view subscriptions are accepted."""
    try:
        await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail)
    response = await api_routes.channel(request, [k for k in s if k.startswith("view:")])
    response.headers["Cache-Control"] = "no-store"
    return await cors(request, response)


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail)
    ch = api_routes.CHANNELS.get(cid)
    if ch is None or ch["user"] != me.user:      # build step 7 also checks the host application (ChannelSession)
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
