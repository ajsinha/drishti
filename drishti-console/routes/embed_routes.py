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

Cross-origin, bearer-token, CORS by an exact origin allow-list. Only mounted when ``embed.poc.enabled`` (core/app.py). The
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
from core.embed_poc import EmbedError
from routes import api_routes

router = APIRouter(prefix="/embed/v1", include_in_schema=False)
WEB = Path(__file__).resolve().parent.parent / "web"
API = "1.0"
# the console's own sheets, in the order base.html loads them, rewritten for a shadow root (see shadow_css)
SHEETS = ("css/tokens.css", "css/theme.css", "css/terminal.css", "css/layout.css", "css/gradients.css", "css/pivot.css", "css/about.css", "vendor/bootstrap-icons/bootstrap-icons.css")
_cache: dict = {}


def cors(request: Request, response: Response) -> Response:
    """Allow-list CORS: the request's exact Origin when some host application lists it, else nothing (the browser refuses)."""
    origin = request.headers.get("origin")
    if origin and request.app.state.embed_poc.origin_allowed(origin):
        response.headers["Access-Control-Allow-Origin"] = origin
        response.headers["Vary"] = "Origin"
        response.headers["Access-Control-Expose-Headers"] = "Drishti-Embed-Api"
    response.headers["Drishti-Embed-Api"] = API
    return response


def refuse(request: Request, status: int, code: str, detail: str) -> Response:
    return cors(request, JSONResponse({"code": code, "detail": detail}, status_code=status, headers={"Cache-Control": "no-store"}))


def identity(request: Request):
    """The embed caller as an Identity running as the masked role, and its claims. Raises EmbedError."""
    poc = request.app.state.embed_poc
    auth = request.headers.get("authorization", "")
    claims = poc.verify(auth[7:].strip() if auth.lower().startswith("bearer ") else "", request.headers.get("origin"))
    from core.auth import Identity

    me = request.app.state.auth._with_token(Identity(claims.user, claims.user, "", poc.masked_roles(claims)))
    request.state.identity = me
    return me, claims


@router.options("/{path:path}")
async def preflight(request: Request, path: str):
    origin = request.headers.get("origin")
    if not request.app.state.embed_poc.origin_allowed(origin):
        return Response(status_code=403)
    return cors(request, Response(status_code=204, headers={
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS", "Access-Control-Allow-Headers": "authorization, content-type, drishti-embed-api",
        "Access-Control-Max-Age": "600"}))


@router.post("/poc/token")
async def dev_token(request: Request):
    """DEV ONLY: the host's backend (server to server, so no Origin) exchanges its app secret for an embed token."""
    if request.headers.get("origin"):
        return refuse(request, 403, "DRS-8002", "the dev token is for a host's backend, never a browser")
    try:
        body = await json_body(request, limit=16_384)
        return JSONResponse(request.app.state.embed_poc.mint(body.get("app"), body.get("secret"), body.get("user"), body.get("roles")),
                            headers={"Cache-Control": "no-store"})
    except EmbedError as e:
        return refuse(request, e.status, e.code, e.detail)
    except (BodyError, ValueError, AttributeError):
        return refuse(request, 400, "DRS-5001", "a JSON body {app, secret, user, roles}")


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


def send(request: Request, body: bytes, media: str, etag: str) -> Response:
    if request.headers.get("if-none-match") == etag:
        return cors(request, Response(status_code=304, headers={"ETag": etag, "Cache-Control": "no-cache"}))
    headers = {"ETag": etag, "Cache-Control": "no-cache"}      # POC: revalidated each time; step 8 serves versioned immutable URLs
    if "gzip" in request.headers.get("accept-encoding", "") and media != "font/woff2":
        body, headers["Content-Encoding"] = gzip.compress(body, 6), "gzip"
        headers["Vary"] = "Accept-Encoding, Origin"
    return cors(request, Response(body, media_type=media, headers=headers))


@router.get("/poc/drishti-elements.js")
async def element_js(request: Request):
    body, etag = asset("js", lambda: (WEB / "embed" / "drishti-elements.js").read_bytes())
    return send(request, body, "text/javascript", etag)


@router.get("/poc/drishti-view.css")
async def element_css(request: Request):
    body, etag = asset("css", lambda: "\n".join(shadow_css((WEB / "static" / s).read_text(encoding="utf-8")) for s in SHEETS).encode())
    return send(request, body, "text/css", etag)


@router.get("/poc/icons.woff2")
async def icons_font(request: Request):
    return send(request, (WEB / "static/vendor/bootstrap-icons/fonts/bootstrap-icons.woff2").read_bytes(), "font/woff2", '"icons"')


@router.get("/poc/charts.js")
async def charts_js(request: Request):
    """charts.js (waterfall, histogram, ...), served with CORS so a module can import it; the element loads it as a script."""
    return send(request, (WEB / "static/js/charts.js").read_bytes(), "text/javascript", f'"{(WEB / "static/js/charts.js").stat().st_mtime_ns:x}"')


@router.get("/poc/echarts.js")
async def echarts_js(request: Request):
    path = WEB / "static/vendor/echarts/echarts.min.js"
    return send(request, path.read_bytes(), "text/javascript", f'"{path.stat().st_mtime_ns:x}"')


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
        me, _claims = identity(request)
    except EmbedError as e:
        return refuse(request, e.status, e.code, e.detail)
    try:
        vm = await request.app.state.backend.view(kind, id_, me)
    except BackendError as e:
        return refuse(request, e.page_status, e.code, e.detail)
    head, panels = _render(request, vm)
    prov = vm.get("provenance") or {}
    asof = getattr(request.state, "asof", "live")
    live = bool(prov.get("live")) and asof == "live"
    # POC: masked values counted by the mask in what is shown; build step 3 puts the count in the server's provenance
    shown = head + "".join(p["html"] for p in panels)
    masked = shown.count("•••")
    body = {"api": API, "ref": vm["ref"], "mnemonic": vm.get("mnemonic"), "title": vm["title"], "head": head, "strip": vm.get("strip", []),
            "banners": [], "panels": panels, "provenance": {**prov, "masked": masked},
            "masked": {"count": masked, "panels": [p["id"] for p in panels if "•••" in p["html"]]},
            "subscribe": f"view:{vm['ref']['kind']}/{vm['ref']['id']}" if live else None,
            "asOf": asof, "timings": {"console": round((time.perf_counter() - started) * 1000, 1)}}
    return cors(request, Response(json.dumps(body, ensure_ascii=False), media_type="application/json", headers={"Cache-Control": "no-store"}))


@router.get("/resolve")
async def resolve(request: Request, text: str = ""):
    try:
        me, _claims = identity(request)
        out = await request.app.state.backend.command(text, me)
    except EmbedError as e:
        return refuse(request, e.status, e.code, e.detail)
    except BackendError as e:
        return refuse(request, e.page_status, e.code, e.detail)
    return cors(request, JSONResponse(out, headers={"Cache-Control": "no-store"}))


# -- the channel ------------------------------------------------------------------------------------------------------
@router.get("/channel")
async def channel(request: Request, s: list[str] = Query(default=[])):
    """The console's channel, unchanged, for the embed caller; only view subscriptions are accepted."""
    try:
        identity(request)
    except EmbedError as e:
        return refuse(request, e.status, e.code, e.detail)
    response = await api_routes.channel(request, [k for k in s if k.startswith("view:")])
    response.headers["Cache-Control"] = "no-store"
    return cors(request, response)


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    try:
        me, _claims = identity(request)
    except EmbedError as e:
        return refuse(request, e.status, e.code, e.detail)
    ch = api_routes.CHANNELS.get(cid)
    if ch is None or ch["user"] != me.user:      # build step 7 also checks the host application (ChannelSession)
        return refuse(request, 404, "DRS-5001", "no such channel: open a new one")
    try:
        body = await json_body(request, limit=16_384)
    except (BodyError, ValueError):
        return refuse(request, 400, "DRS-5001", "a JSON body {add, remove}")
    limit = api_routes.max_subscriptions(request)
    for sub in (body.get("remove") or [])[:limit]:
        ch["remove"](str(sub))
    for sub in [x for x in (body.get("add") or []) if str(x).startswith("view:")][:limit * 2]:
        ch["add"](str(sub))
    return cors(request, JSONResponse({"ok": True}, headers={"Cache-Control": "no-store"}))
