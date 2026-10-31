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

"""The embed API v2 (docs/architecture/CLIENT_RENDERER.md › Drishti Elements: the design, section 19; CLIENT_RENDERER.md section 13): the ViewModel as JSON for the client-side
renderer and Elements 2.0, beside the embed API v1 (HTML for Elements 1.x, unchanged) for one release.

The same guards as v1: a bearer embed token on every data call (verified, masked, counted as that user), CORS by the exact origins of the
host applications the server registered, the console's own channel for live frames. What v2 adds:

* ``/embed/v2/views/{kind}/{id}``: the ViewModel itself (``"contract": "1.0"``), not HTML; ``Drishti-Subscribe`` names its live key;
* ``/embed/v2/views/{kind}/{id}/panels``, ``/about``, ``/panels/{panel}/records``: panels and About as JSON, a pivot's rows;
* ``/embed/v2/channel``: the live channel; a ``vm:`` subscription sends panel patches as panels (JSON), with no HTML;
* ``/embed/v2/render/<version>/...``: the renderer's files (``drishti-render.js``, ``drishti-elements.js`` for Elements 2.0, the sheet, ``m/``),
  immutable, a partial version (``1``, ``1.0``) answering ``302`` to the exact one, ``410 DRS-8006`` for one this console no longer serves;
* ``/embed/v2/elements/<version>/drishti-elements.js``: Elements 2.x by its own version (``2``, ``2.0``, ``2.0.0``), a ``302`` to the
  renderer that carries it;
* ``/embed/v2/js/``, ``/vendor/``, ``/css/``: the console's behaviour scripts, ECharts, the map shapes, the icon font and the chart dialog's
  sheet the renderer loads from beside itself, allow-listed.
"""
from __future__ import annotations

import json
import mimetypes
import re
from pathlib import Path

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response

from core import about_index, controls
from core import element_dist as dist
from core import render_dist as rd
from core.backend import BackendError
from core.embed import EmbedError
from core.embed_wire import compress
from routes import embed_routes as v1

router = APIRouter(prefix="/embed/v2", include_in_schema=False)
WEB = Path(__file__).resolve().parent.parent / "web"
STATIC = WEB / "static"
API = "2.0"
BASE = "/embed/v2"
ELEMENTS = {"2.0.0": "1.0.0"}           # Elements 2.x by its own version -> the renderer version that carries its drishti-elements.js
# What the renderer loads from beside itself (its folder is /embed/v2/render/<v>/, so /embed/v2/ stands for the console's /static/)
SCRIPTS = {"pivot-engine", "pivot-grid", "colfilter", "tables", "tree-rows", "pivot", "drill", "zoom", "multiselect", "controls", "geomap",
           "table-chart", "table-chart-engine", "table-chart-sources"}
VENDOR = re.compile(r"(echarts/echarts\.min\.js|echarts-gl/echarts-gl\.min\.js|echarts/maps/(world-countries|world-points)\.json|echarts/maps/regions/[A-Z]{3}\.json"
                    r"|bootstrap-icons/fonts/bootstrap-icons\.woff2)")
CSS = {"table-chart"}


async def cors(request: Request, response: Response) -> Response:
    """v1's allow-list CORS, with this API's version and lifecycle (``embed.lifecycle."2"``)."""
    response = await v1.cors(request, response)
    response.headers["Drishti-Embed-Api"] = API
    for name in ("Deprecation", "Sunset", "Link"):
        if name in response.headers:
            del response.headers[name]
    for name, value in dist.lifecycle(request.app.state.embed.settings, "2").get("headers", {}).items():
        response.headers[name] = value
    response.headers["Access-Control-Expose-Headers"] = "Drishti-Embed-Api, Drishti-Subscribe"
    return response


async def refuse(request: Request, status: int, code: str, detail: str, retry_after: str | None = None) -> Response:
    headers = {"Cache-Control": "no-store"}
    if retry_after:
        headers["Retry-After"] = str(retry_after)
    return await cors(request, JSONResponse({"code": code, "detail": detail}, status_code=status, headers=headers))


async def identity(request: Request):
    life = dist.lifecycle(request.app.state.embed.settings, "2")
    if life["retired"]:
        raise EmbedError(410, "DRS-8006", f"the embed API v2 was retired on {life['sunset']}" + (f": {life['link']}" if life["link"] else ""))
    auth = request.headers.get("authorization", "")
    me, claims = await request.app.state.embed.identity(request, auth[7:].strip() if auth.lower().startswith("bearer ") else "", request.headers.get("origin"))
    request.state.identity = me
    return me, claims


async def reply(request: Request, body, status: int = 200, headers: dict | None = None) -> Response:
    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    raw, coding = compress(raw, request.headers.get("accept-encoding"), v1.min_bytes(request))
    h = {"Cache-Control": "no-store", "Vary": "Accept-Encoding, Origin", **(headers or {})}
    if coding:
        h["Content-Encoding"] = coding
    return await cors(request, Response(raw, status_code=status, media_type="application/json", headers=h))


async def static(request: Request, path: Path, media: str | None = None, immutable: bool = False) -> Response:
    body = path.read_bytes()
    etag = '"' + rd.sri(body) + '"'
    if request.headers.get("if-none-match") == etag:
        return await cors(request, Response(status_code=304, headers={"ETag": etag}))
    media = media or {".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".woff2": "font/woff2", ".ts": "text/plain"}.get(path.suffix) \
        or mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    headers = {"ETag": etag, "Cache-Control": "public, max-age=31536000, immutable" if immutable else "no-cache", "Vary": "Accept-Encoding, Origin"}
    if media != "font/woff2":
        body, coding = compress(body, request.headers.get("accept-encoding"), v1.min_bytes(request))
        if coding:
            headers["Content-Encoding"] = coding
    return await cors(request, Response(body, media_type=media, headers=headers))


@router.options("/{path:path}")
async def preflight(request: Request, path: str):
    origin = request.headers.get("origin")
    if not await request.app.state.embed.origin_allowed(request, origin):
        return Response(status_code=403)
    return await cors(request, Response(status_code=204, headers={
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS", "Access-Control-Allow-Headers": "authorization, content-type, accept, drishti-embed-api",
        "Access-Control-Max-Age": "600"}))


# -- the renderer and what it loads ----------------------------------------------------------------------------------------------
def resolve(spec: str, have: list[str]) -> str | None:
    """The exact version a requested one names: itself, or for a partial (``1``, ``1.0``) the newest that matches; else None."""
    if spec in have:
        return spec
    found = [v for v in have if v.startswith(spec + ".")]
    return max(found, key=lambda v: tuple(int(x) for x in v.split("."))) if found else None


@router.get("/render/{version}/{rest:path}")
async def renderer(request: Request, version: str, rest: str):
    have = rd.versions()
    exact = resolve(version, have)
    if exact is None:
        if dist.SEMVER.fullmatch(version):
            return await refuse(request, 410, "DRS-8006", f"this console serves Drishti Render {', '.join(have)}, not {version}: pin {rd.VERSION}, or use the alias {rd.VERSION.split('.')[0]}")
        return await refuse(request, 404, "DRS-9404", f"no such renderer version: {version}")
    if version != exact:
        return await cors(request, RedirectResponse(f"{BASE}/render/{exact}/{rest}", status_code=302, headers={"Cache-Control": "public, max-age=60"}))
    files = rd.files(exact)
    path = files.get(rest) or ((rd.ROOT / exact / rest) if rest == rd.RECORD else None)
    if path is None or not path.is_file():
        return await refuse(request, 404, "DRS-9404", f"no such file in Drishti Render {exact}: {rest}")
    return await static(request, path, immutable=True)


@router.get("/elements/{version}/drishti-elements.js")
async def elements(request: Request, version: str):
    exact = resolve(version, sorted(ELEMENTS))
    if exact is None or ELEMENTS[exact] not in rd.versions():
        return await refuse(request, 410 if dist.SEMVER.fullmatch(version) else 404, "DRS-8006", f"this console serves Elements {', '.join(sorted(ELEMENTS))} at {BASE}/elements/, not {version}")
    return await cors(request, RedirectResponse(f"{BASE}/render/{ELEMENTS[exact]}/drishti-elements.js", status_code=302, headers={"Cache-Control": "public, max-age=60"}))


@router.get("/js/{name}.js")
async def script(request: Request, name: str):
    if name not in SCRIPTS:
        return await refuse(request, 404, "DRS-9404", f"no such script: {name}")
    return await static(request, STATIC / "js" / f"{name}.js")


@router.get("/css/{name}.css")
async def sheet(request: Request, name: str):
    if name not in CSS:
        return await refuse(request, 404, "DRS-9404", f"no such sheet: {name}")
    return await static(request, STATIC / "css" / f"{name}.css")


@router.get("/vendor/{rest:path}")
async def vendor(request: Request, rest: str):
    if not VENDOR.fullmatch(rest) or not (STATIC / "vendor" / rest).is_file():
        return await refuse(request, 404, "DRS-9404", f"no such file: {rest}")
    return await static(request, STATIC / "vendor" / rest)


# -- the data: the ViewModel and its parts as JSON, as the embed caller (masked) --------------------------------------------------
@router.get("/views/{kind}/{id_:path}/panels/{panel}/records")
async def records(request: Request, kind: str, id_: str, panel: str):
    try:
        me, _claims = await identity(request)
        return await reply(request, await request.app.state.backend.panel_records(kind, id_, panel, me))
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail)


@router.get("/views/{kind}/{id_:path}/about")
async def about(request: Request, kind: str, id_: str, generation: int = 0):
    """About this page as JSON, ``{explain, index}``, which the renderer draws (v1 answers the console's HTML)."""
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    extra = {"accept_language": request.headers["accept-language"]} if request.headers.get("accept-language") else {}
    try:
        ex = await request.app.state.backend.explain(kind, id_, me, generation or None, **extra)
    except BackendError as e:
        return await reply(request, {"error": {"code": e.code, "detail": e.detail}}, e.page_status)
    return await reply(request, {"explain": ex, "index": about_index.build(ex)})


@router.get("/views/{kind}/{id_:path}/panels")
async def panels(request: Request, kind: str, id_: str, panels: str = ""):
    """Some panels of a view as JSON, built with the controls' values: what a control asks for (``?panels=a,b&ctl.legs=1``)."""
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    ids = [p for p in panels.split(",") if p and p != "-"] if panels else None
    try:
        vm = await request.app.state.backend.view(kind, id_, me, controls=controls.from_query(request.query_params), panels=ids)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    return await reply(request, {"panels": [{"id": p["id"], "kind": p["kind"], "title": p.get("title") or "", "panel": p} for p in vm.get("panels") or []],
                                 "stripCells": vm.get("strip") or [], "controls": vm.get("controls") or []})


@router.get("/views/{kind}/{id_:path}")
async def view(request: Request, kind: str, id_: str):
    """The ViewModel, as the server built it for the embed caller (masked), with its contract; ``Drishti-Subscribe`` is its live key."""
    try:
        me, _claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    ctl = controls.from_query(request.query_params)
    try:
        vm = await request.app.state.backend.view(kind, id_, me, **({"controls": ctl} if ctl else {}))
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    vm.setdefault("contract", "1.0")                 # a server older than the stamp: its ViewModels are contract 1.0
    live = bool((vm.get("provenance") or {}).get("live")) and getattr(request.state, "asof", "live") == "live"
    ref = vm.get("ref") or {"kind": kind, "id": id_}
    return await reply(request, vm, headers={"Drishti-Subscribe": f"vm:{ref['kind']}/{ref['id']}"} if live else None)


@router.get("/resolve")
async def resolve_command(request: Request, text: str = ""):
    """A typed command ("TRD 1000008") to the entity it names, for the host's search box (DrishtiElements.resolve), as v1's."""
    try:
        me, _claims = await identity(request)
        out = await request.app.state.backend.command(text, me)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    return await reply(request, out)


# -- the live channel: v1's own (core/channel.py), whose vm: subscriptions send panels as JSON ----------------------------------------
@router.get("/channel")
async def channel(request: Request, s: list[str] = Query(default=[])):
    response = await v1.channel(request, s)
    response.headers["Drishti-Embed-Api"] = API
    return response


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    response = await v1.channel_change(request, cid)
    response.headers["Drishti-Embed-Api"] = API
    return response


@router.post("/channel/{cid}/token")
async def channel_token(request: Request, cid: str):
    response = await v1.channel_token(request, cid)
    response.headers["Drishti-Embed-Api"] = API
    return response
