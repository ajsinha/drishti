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

"""The embed API (docs/guides/INTEGRATION_GUIDE.md › Drishti Elements: the design, section 19; INTEGRATION_GUIDE.md section 13): the ViewModel as JSON for the client-side
renderer and Elements 2.x. The one embed module: its guards, its lifecycle headers and its live channel are here and nowhere else.

A bearer embed token on every data call (verified, masked, counted as that user), CORS by the exact origins of the host applications the server
registered, the console's own channel for live frames. The routes:

* ``/embed/v2/views/{kind}/{id}``: the ViewModel itself (``"contract": "1.0"``); ``Drishti-Subscribe`` names its live key;
* ``/embed/v2/views/{kind}/{id}/panels``, ``/about``: panels and About as JSON;
* ``/embed/v2/channel``: the live channel; a ``vm:`` subscription sends panel patches as panels (JSON), with no HTML;
* ``/embed/v2/render/<version>/...``: the renderer's files (``drishti-render.js``, ``drishti-elements.js`` for Elements 2.x, the sheet, ``m/``),
  immutable, a partial version (``1``, ``1.0``) answering ``302`` to the exact one, ``410 DRS-8006`` for one this console no longer serves;
* ``/embed/v2/elements/<version>/drishti-elements.js``: Elements 2.x by its own version (``2``, ``2.4``, ``2.4.0``), a ``302`` to the
  renderer that carries it;
* ``/embed/v2/js/``, ``/vendor/``, ``/css/``: the console's behaviour scripts, ECharts, the map shapes, the icon font and the chart dialog's
  sheet the renderer loads from beside itself, allow-listed.
"""
from __future__ import annotations

import dataclasses
import json
import mimetypes
import re
from datetime import date, datetime, timezone
from email.utils import format_datetime
from pathlib import Path

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, RedirectResponse, Response, StreamingResponse

from core import about_index, channel_relay, controls, servers
from core import elements_pin as _pin
from core import render_dist as rd
from core.backend import BackendError
from core.channel import ChannelSession
from core.csrf import BodyError, json_body
from core.embed import EmbedError, EmbedIdentity
from core.embed_stream import StreamGuard
from core.embed_wire import compress
from routes import api_routes

router = APIRouter(prefix="/embed/v2", include_in_schema=False)
WEB = Path(__file__).resolve().parent.parent / "web"
STATIC = WEB / "static"
API = "2.0"
BASE = "/embed/v2"
ELEMENTS = {"2.4.0": rd.VERSION, rd.VERSION: rd.VERSION}   # the embed elements by their API version -> the renderer version that carries its drishti-elements.js (2.4.0, the element API the renderer carries, so the alias "2" is the current one; earlier ones: 410)
# The Elements libraries the current renderer imports from beside the console's static root (/embed/v2/elements/<library>/src/<module>.js): and the parts of a library (drishti_chart-report.js)
# the painter (drishti-views) and what it draws with
LIBRARY_FILE = re.compile(r"drishti-(core|grid|chart|data|graph|tree|inputs|events|views)/(src/drishti_[a-z0-9-]+\.js|drishti_[a-z]+(?:-[a-z]+)?\.(css|js))")
# What the renderer loads from beside itself (its folder is /embed/v2/render/<v>/, so /embed/v2/ stands for the console's /static/)
SCRIPTS = {"pivot-engine", "pivot-grid", "colfilter", "tables", "tree-rows", "drill", "zoom", "multiselect", "controls", "geomap",
           "table-chart", "table-chart-engine", "table-chart-sources"}
VENDOR = re.compile(r"(echarts/echarts\.min\.js|echarts-gl/echarts-gl\.min\.js|echarts/maps/(world-countries|world-points)\.json|echarts/maps/regions/[A-Z]{3}\.json"
                    r"|bootstrap-icons/fonts/bootstrap-icons\.woff2)")
CSS = {"table-chart"}
LIFECYCLE = ("Deprecation", "Sunset", "Link")      # the lifecycle headers (RFC 9745, RFC 8594, the successor link)
LIVE_KEYS = ("view:", "vm:", "bilive:")    # what the embed channel carries: a view's frames (vm: as panels), a report's live ticks
SEMVER = re.compile(r"\d+(\.\d+){0,2}")


async def cors(request: Request, response: Response) -> Response:
    """Allow-list CORS: the request's exact Origin when some host application lists it, else nothing (the browser refuses). The response carries this
    API's version and its lifecycle (``embed.lifecycle."2"``); a response's own lifecycle headers (a retired path's redirect says its deprecation)
    are kept over the API's."""
    origin = request.headers.get("origin")
    if origin and await request.app.state.embed.origin_allowed(request, origin):
        response.headers["Access-Control-Allow-Origin"] = origin
        vary = response.headers.get("Vary", "")
        response.headers["Vary"] = vary if "Origin" in vary else (vary + ", Origin" if vary else "Origin")
    own = {name: response.headers[name] for name in LIFECYCLE if name in response.headers}
    response.headers["Drishti-Embed-Api"] = API
    for name, value in {**lifecycle(request.app.state.embed.settings, "2").get("headers", {}), **own}.items():
        response.headers[name] = value
    response.headers["Access-Control-Expose-Headers"] = "Drishti-Embed-Api, Drishti-Subscribe"
    return response


async def refuse(request: Request, status: int, code: str, detail: str, retry_after: str | None = None, kind: str = "") -> Response:
    headers = {"Cache-Control": "no-store"}
    if retry_after:
        headers["Retry-After"] = str(retry_after)
    body = {"code": code, "detail": detail}
    if code == "DRS-4023":                         # the edition hides the kind: said plainly (the renderer shows it), never "check the kind and id"
        from core.edition import hidden_hint
        body["fix"] = hidden_hint(getattr(request.state, "edition", None), kind)
    return await cors(request, JSONResponse(body, status_code=status, headers=headers))


async def identity(request: Request):
    life = lifecycle(request.app.state.embed.settings, "2")
    if life["retired"]:
        raise EmbedError(410, "DRS-8006", f"the embed API v2 was retired on {life['sunset']}" + (f": {life['link']}" if life["link"] else ""))
    auth = request.headers.get("authorization", "")
    me, claims = await request.app.state.embed.identity(request, auth[7:].strip() if auth.lower().startswith("bearer ") else "", request.headers.get("origin"))
    request.state.identity = me
    return me, claims


def _day(text) -> date | None:
    try:
        return date.fromisoformat(str(text)[:10])
    except ValueError:
        return None


def lifecycle(settings: dict, major: str = "2", today: date | None = None) -> dict:
    """What ``embed.lifecycle.<major>`` says about an embed API major: ``{"deprecated": bool, "sunset": date|None, "retired": bool, "headers": {...}, "link": str}``.

    Configuration is ``embed.lifecycle: {"2": {deprecation: 2027-01-01, sunset: 2027-07-01, link: https://.../migrating}}``; absent means nothing is announced.
    ``Deprecation`` (RFC 9745) carries the date as ``@<epoch seconds>``, ``Sunset`` (RFC 8594) as an HTTP date; once the sunset day has come the major is retired
    (``410 DRS-8006``)."""
    entry = ((settings or {}).get("lifecycle") or {}).get(major) or {}
    today = today or datetime.now(timezone.utc).date()
    dep, sun = _day(entry.get("deprecation")), _day(entry.get("sunset"))
    headers: dict = {}
    if dep and today >= dep:
        headers["Deprecation"] = "@" + str(int(datetime(dep.year, dep.month, dep.day, tzinfo=timezone.utc).timestamp()))
    if sun:
        headers["Sunset"] = format_datetime(datetime(sun.year, sun.month, sun.day, tzinfo=timezone.utc), usegmt=True)
    link = str(entry.get("link") or "")
    if link and headers:
        headers["Link"] = f'<{link}>; rel="deprecation"; type="text/html"'
    return {"deprecated": "Deprecation" in headers, "sunset": sun, "retired": bool(sun and today >= sun), "headers": headers, "link": link}


def min_bytes(request: Request) -> int:
    return int(request.app.state.embed.settings.get("compress_min_bytes", 512))


async def reply(request: Request, body, status: int = 200, headers: dict | None = None) -> Response:
    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    raw, coding = compress(raw, request.headers.get("accept-encoding"), min_bytes(request))
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
        body, coding = compress(body, request.headers.get("accept-encoding"), min_bytes(request))
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
        if SEMVER.fullmatch(version):
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
        return await refuse(request, 410 if SEMVER.fullmatch(version) else 404, "DRS-8006", f"this console serves Elements {', '.join(sorted(ELEMENTS))} at {BASE}/elements/, not {version}")
    return await cors(request, RedirectResponse(f"{BASE}/render/{ELEMENTS[exact]}/drishti-elements.js", status_code=302, headers={"Cache-Control": "public, max-age=60"}))


@router.get("/elements/{library}/{rest:path}")
async def library(request: Request, library: str, rest: str):
    """A module of the Elements libraries the current renderer is drawn with (revalidated: the libraries are not per-version folders yet)."""
    rel = f"{library}/{rest}"
    if not LIBRARY_FILE.fullmatch(rel) or not _pin.static_path("elements/" + rel).is_file():   # Drishti Elements' from the vendored copy
        return await refuse(request, 404, "DRS-9404", f"no such file: {rel}")
    return await static(request, _pin.static_path("elements/" + rel))


@router.get("/js/{name}.js")
async def script(request: Request, name: str):
    name = name.removeprefix("drishti_")
    if name not in SCRIPTS:
        return await refuse(request, 404, "DRS-9404", f"no such script: {name}")
    return await static(request, STATIC / "js" / f"drishti_{name}.js")


@router.get("/css/{name}.css")
async def sheet(request: Request, name: str):
    name = name.removeprefix("drishti_")
    if name not in CSS:
        return await refuse(request, 404, "DRS-9404", f"no such sheet: {name}")
    return await static(request, STATIC / "css" / f"drishti_{name}.css")


@router.get("/vendor/{rest:path}")
async def vendor(request: Request, rest: str):
    pinned = f"drishti-elements/{_pin.VERSION}/"
    if rest.startswith(pinned):                   # the vendored Drishti Elements, as the renderer's modules import it (beside the static root)
        inner = rest[len(pinned):]
        ok = (inner.startswith("elements/") and LIBRARY_FILE.fullmatch(inner[len("elements/"):])) or (inner.startswith("vendor/") and VENDOR.fullmatch(inner[len("vendor/"):]))
        if not ok or not (_pin.ROOT / inner).is_file():
            return await refuse(request, 404, "DRS-9404", f"no such file: {rest}")
        return await static(request, _pin.ROOT / inner)
    if not VENDOR.fullmatch(rest) or not _pin.static_path("vendor/" + rest).is_file():   # the engines come inside Drishti Elements
        return await refuse(request, 404, "DRS-9404", f"no such file: {rest}")
    return await static(request, _pin.static_path("vendor/" + rest))


# -- the data: the ViewModel and its parts as JSON, as the embed caller (masked) --------------------------------------------------
@router.get("/views/{kind}/{id_:path}/about")
async def about(request: Request, kind: str, id_: str, generation: int = 0):
    """About this page as JSON, ``{explain, index}``, which the renderer draws."""
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
        vm = await request.app.state.backend.view(kind, id_, me, controls=controls.of(request), panels=ids)
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
    ctl = controls.of(request)
    try:
        vm = await request.app.state.backend.view(kind, id_, me, **({"controls": ctl} if ctl else {}))
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after, kind=kind)
    vm.setdefault("contract", "1.0")                 # a server older than the stamp: its ViewModels are contract 1.0
    live = bool((vm.get("provenance") or {}).get("live")) and getattr(request.state, "asof", "live") == "live"
    ref = vm.get("ref") or {"kind": kind, "id": id_}
    return await reply(request, vm, headers={"Drishti-Subscribe": f"vm:{ref['kind']}/{ref['id']}"} if live else None)


@router.get("/resolve")
async def resolve_command(request: Request, text: str = ""):
    """A typed command ("TRD 1000008") to the entity it names, for the host's search box (DrishtiElements.resolve)."""
    try:
        me, _claims = await identity(request)
        out = await request.app.state.backend.command(text, me)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    except BackendError as e:
        return await refuse(request, e.page_status, e.code, e.detail, e.retry_after)
    return await reply(request, out)


# -- the live channel (core/channel.py, shared with /api/channel); a vm: subscription sends panels as JSON ----------------------------------
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
                             limit=limit, app=claims["azp"], registry=api_routes.channels(request.app),
                             cid_suffix=channel_relay.suffix(request.app))
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


def _owner_is(ch: dict | None, user: str, app: str) -> bool:
    """The channel's user and host application, on the current request's server (the same name on two servers is two people)."""
    return ch is not None and ch.get("user") == user and ch.get("app", "") == app and ch.get("server", servers.DEFAULT) == servers.current()


def _owned(request: Request, cid: str, me, claims):
    ch = api_routes.channels(request.app).get(cid)
    return ch if _owner_is(ch, me.user, claims["azp"]) else None


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    hosts = request.app.state.embed
    try:
        me, claims = await identity(request)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    ch = _owned(request, cid, me, claims)
    remote = api_routes.channels(request.app).get(cid) is None and channel_relay.owner_port(cid) is not None    # held by another worker of this console
    if ch is None and not remote:
        return await refuse(request, 404, "DRS-5001", "no such channel: open a new one")
    try:
        body = await json_body(request, limit=16_384)
    except (BodyError, ValueError):
        return await refuse(request, 400, "DRS-5001", "a JSON body {add, remove}")
    limit = api_routes.max_subscriptions(request)
    if remote:
        got = await channel_relay.forward(request.app, cid, "embed-change", {"user": me.user, "app": claims["azp"], "add": body.get("add") or [],
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


def _relayed_owner(app, cid: str, payload: dict):
    """The channel when the relayed caller owns it; the call runs in the caller's scope (its server, core/channel_relay.py)."""
    ch = api_routes.channels(app).get(cid)
    return ch if _owner_is(ch, str(payload.get("user", "")), str(payload.get("app", ""))) else None


def _relayed_change(app, cid: str, payload: dict) -> dict:
    """A change another worker received, and checked the caller of, for a channel this worker holds (core/channel_relay.py)."""
    ch = _relayed_owner(app, cid, payload)
    if ch is None:
        return {"ok": False}
    _change(app.state.embed, ch, payload, int(payload.get("limit") or api_routes.MAX_CHANNEL_SUBSCRIPTIONS))
    return {"ok": True}


def _relayed_token(app, cid: str, payload: dict) -> dict:
    """A fresh token another worker verified, for a stream this worker holds."""
    claims = payload.get("claims") or {}
    guard = app.state.embed.guards.get(cid)
    if guard is None or _relayed_owner(app, cid, {"user": claims.get("sub", ""), "app": claims.get("azp", "")}) is None:
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
        if guard is None and api_routes.channels(request.app).get(cid) is None and channel_relay.owner_port(cid) is not None:   # another worker's
            got = await channel_relay.forward(request.app, cid, "embed-token", {"identity": dataclasses.asdict(me), "claims": claims}) or {}
            if not got.get("ok"):
                raise EmbedError(int(got.get("status") or 404), str(got.get("code") or "DRS-5001"), str(got.get("detail") or "no such channel: open a new one"))
        else:
            if guard is None or _owned(request, cid, me, claims) is None:
                raise EmbedError(404, "DRS-5001", "no such channel: open a new one")
            guard.refresh(me, claims)
    except EmbedError as e:
        return await refuse(request, e.status, e.code, e.detail, e.retry_after)
    return await cors(request, JSONResponse({"ok": True, "expiresAt": int(claims["exp"])}, headers={"Cache-Control": "no-store"}))
