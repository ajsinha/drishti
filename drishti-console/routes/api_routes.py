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

"""Same-origin JSON endpoints for the browser (the CSP allows connect-src 'self' only)."""
from __future__ import annotations

import asyncio
import json
import re

from fastapi import APIRouter, Query, Request

from fastapi.responses import JSONResponse, StreamingResponse

from core import controls
from core.csrf import json_body
from core.backend import BackendError
from core.channel import ChannelSession
from routes.common import ident, live_who

router = APIRouter(prefix="/api", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def form_suggestions(request: Request, pack: str | None, kind: str | None, spaced: bool) -> list:
    """Type-ahead for ``FORM <pack> <kind>``: only packs the person may open, and only kinds they may open in that pack."""
    from routes.common import packs
    me = ident(request)
    if kind is None and not (pack and spaced):          # still typing the pack's name
        want = (pack or "").lower()
        return [{"mnemonic": "FORM", "title": p["title"], "subtitle": f"search {p['name']} with a form", "complete": f"FORM {p['name']} ",
                 "href": f"/form/{p['name']}", "type": "form"}
                for p in await packs(request) if p.get("active", True) and p.get("kinds") and (not want or want in p["name"].lower() or want in p["title"].lower())]
    try:
        info = await request.app.state.backend.form(pack or "", "", me)
    except BackendError:
        return []
    want = (kind or "").lower()
    return [{"mnemonic": "FORM", "title": k["label"], "subtitle": f"{info['title']} · {k['kind']}", "complete": f"FORM {info['pack']} {k['kind']}",
             "href": f"/form/{info['pack']}?kind={k['kind']}", "type": "form"}
            for k in info["kinds"] if not want or want in k["kind"].lower() or want in k["label"].lower()]


RUPAKA = "RUPAKA"
BI_ENTRIES = (("", "BI home", "Recent, favourites, certified, shared with me", "/bi"), ("WORKSPACE", "My workspace", "Your drafts and the workspaces you belong to", "/bi/w/me"),
              ("CATALOGUE", "Catalogue", "Every published report and board you may open", "/bi/catalogue"))


async def rupaka_suggestions(request: Request, word: str, rest: str | None) -> list:
    """Type-ahead for the reserved word ``RUPAKA``: its three pages, then published reports whose id or title matches, only for people who hold the bi power."""
    me = ident(request)
    if not request.app.state.bi_on or not (await request.app.state.bi_me.get(request.app.state.backend, me)).get("enabled"):
        return []
    want = (rest or "").strip().lower()
    out = [{"mnemonic": RUPAKA, "title": title, "subtitle": sub, "complete": f"{RUPAKA} {key}".strip(), "href": href, "type": "bi"}
           for key, title, sub, href in BI_ENTRIES if rest is None or not want or key.lower().startswith(want)]
    if rest is not None and want:
        try:
            found = (await request.app.state.backend.bi("GET", "/catalogue", me, q=want, limit=8)).get("items") or []
        except BackendError:
            found = []
        out += [{"mnemonic": RUPAKA, "title": i["title"], "subtitle": f"{i['kind']} · {i['workspaceName']} · {i['owner']}", "complete": f"{RUPAKA} {i['title']}",
                 "href": f"/bi/i/{i['id']}", "type": "bi"} for i in found]
    return out


@router.get("/suggest")
async def suggest(request: Request, q: str = "", limit: int | None = None):
    """The command line's dropdown; without a limit the server's own (drishti.commands.suggest-limit, 25) applies."""
    bi = re.match(r"(?is)^\s*(rupaka)(?:\s+(.*))?$", q.replace("<GO>", ""))
    if bi:                                                     # the reserved word RUPAKA: the BI pages and the reports you may open
        return await rupaka_suggestions(request, bi.group(1), bi.group(2))
    keyword = []
    if re.match(r"(?i)^\s*(r|ru|rup|rupa|rupak)\s*$", q.replace("<GO>", "")) and await rupaka_suggestions(request, "", None):    # the first letters of it: offer the keyword
        keyword = [{"mnemonic": RUPAKA, "title": "Rupaka: business intelligence", "subtitle": "RUPAKA <GO> opens the BI home; RUPAKA CATALOGUE, RUPAKA WORKSPACE",
                    "complete": f"{RUPAKA} ", "href": "/bi", "type": "bi"}]
    typed = re.match(r"(?i)^\s*form(?:\s+(\S*))?(?:\s+(\S*))?\s*$", q.replace("<GO>", ""))
    if typed:                                                  # the reserved word FORM: packs you may open, then their kinds
        return await form_suggestions(request, typed.group(1), typed.group(2), q.endswith(" "))
    try:
        return keyword + await request.app.state.backend.suggest(q, ident(request), limit)
    except BackendError as e:
        return _problem(e)


@router.post("/tokens")
async def create_token(request: Request):
    """A personal API token for a script or spreadsheet; the answer holds the secret, shown once."""
    body = await json_body(request)
    days = body.get("days")
    try:
        scopes = body.get("scopes") or []
        if not isinstance(scopes, list):
            return JSONResponse({"code": "DRS-5001", "detail": "scopes is a list of scope names"}, status_code=400)
        return await request.app.state.backend.create_token(body.get("name", ""), int(days) if days else None, ident(request),
                                                            [str(s) for s in scopes])
    except (BackendError, ValueError) as e:
        if isinstance(e, ValueError):
            return JSONResponse({"code": "DRS-5001", "detail": "days is a number"}, status_code=400)
        return _problem(e)


@router.delete("/tokens/{id_}")
async def revoke_token(request: Request, id_: str):
    try:
        await request.app.state.backend.revoke_token(id_, ident(request))
    except BackendError as e:
        return _problem(e)
    return {"ok": True}


@router.get("/notes/{kind}/{id_:path}")
async def notes(request: Request, kind: str, id_: str):
    try:
        return await request.app.state.backend.notes(kind, id_, ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/notes/{kind}/{id_:path}")
async def add_note(request: Request, kind: str, id_: str):
    body = await json_body(request)
    try:
        return await request.app.state.backend.add_note(kind, id_, str(body.get("body") or ""), body.get("path") or None, ident(request))
    except BackendError as e:
        return _problem(e)


@router.put("/notes/{note_id}")
async def edit_note(request: Request, note_id: int):
    body = await json_body(request)
    try:
        return await request.app.state.backend.edit_note(note_id, str(body.get("body") or ""), ident(request))
    except BackendError as e:
        return _problem(e)


@router.delete("/notes/{note_id}")
async def delete_note(request: Request, note_id: int):
    try:
        await request.app.state.backend.delete_note(note_id, ident(request))
        return {"ok": True}
    except BackendError as e:
        return _problem(e)


@router.get("/series/{kind}/{id_:path}")
async def series(request: Request, kind: str, id_: str, path: str, days: int = 30):
    """A field's history for the chart that opens when a value is clicked."""
    try:
        return await request.app.state.backend.series(kind, id_, path, max(2, min(days, 260)), ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/history")
async def history(request: Request):
    """The user's recent commands, newest first (↑ on the command line)."""
    try:
        return await request.app.state.backend.command_history(ident(request))
    except BackendError as e:
        return _problem(e)


@router.put("/aliases")
async def put_aliases(request: Request):
    """Replaces the user's aliases; the server checks names and commands."""
    body = await json_body(request)
    try:
        return await request.app.state.backend.set_aliases(body, ident(request))
    except BackendError as e:
        return _problem(e)


@router.patch("/settings")
async def patch_settings(request: Request):
    """Personal settings from the page (the theme menu); the server validates."""
    body = await json_body(request)
    me = ident(request)
    if me is None:
        return JSONResponse({"code": "DRS-5010", "detail": "sign in first"}, status_code=401)
    try:
        out = await request.app.state.backend.patch_settings(body, me)
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)
    request.app.state.user_settings.forget(me.user)
    return out


@router.post("/packs")
async def choose_packs(request: Request):
    """The user chooses which of their packs to see."""
    body = await json_body(request)
    try:
        out = await request.app.state.backend.choose_packs(body.get("active", []), ident(request))
    except BackendError as e:
        return _problem(e)
    request.app.state.packs.forget(ident(request))
    return out


@router.post("/resolve")
async def resolve(request: Request):
    """A command's entity, for pickers that accept typed commands (workspaces)."""
    body = await json_body(request)
    try:
        return await request.app.state.backend.command(body.get("text", ""), ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/raw/{kind}/{id_:path}")
async def raw(request: Request, kind: str, id_: str):
    try:
        return await request.app.state.backend.raw(kind, id_, ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/view/{kind}/{id_:path}")
async def view(request: Request, kind: str, id_: str):
    try:
        ctl = controls.from_query(request.query_params)
        return await request.app.state.backend.view(kind, id_, ident(request), **({"controls": ctl} if ctl else {}))
    except BackendError as e:
        return _problem(e)


CHART_KINDS = {"line", "area"}


def _panel_html(request: Request, panel: dict, embed: bool = False) -> str:
    """Renders one panel with the same macro as first paint, so live and static views look identical."""
    module = request.app.state.templates.env.get_template("_macros/panels.html").module
    return str(module.panel(panel, embed))


def _view_event(request: Request, event: str, data: str, embed: bool = False) -> str:
    """A view stream event as the browser wants it: panel patches as ready HTML (charts as data), the view as its generation."""
    if event == "frame":
        frame = json.loads(data)
        for patch in frame.get("patches", []):
            panel = patch.get("panel")
            if panel and panel.get("kind") not in CHART_KINDS:
                patch["html"] = _panel_html(request, panel, embed)
                patch["panel"] = {"id": panel["id"], "kind": panel["kind"]}
        return json.dumps(frame, ensure_ascii=False)
    if event == "view":
        view = json.loads(data)
        return json.dumps({"generation": view.get("provenance", {}).get("generation")})
    return data


@router.get("/stream/{kind}/{id_:path}")
async def stream(request: Request, kind: str, id_: str):
    """Relays one view's live stream (kept for API clients; pages use /api/channel)."""
    backend = request.app.state.backend

    async def events():
        try:
            ctl = controls.from_query(request.query_params)
            upstream = backend.stream(kind, id_, ident(request), **({"controls": ctl} if ctl else {}))
            try:
                async for event, data in upstream:
                    if await request.is_disconnected():
                        break
                    yield f"event: {event}\ndata: {_view_event(request, event, data)}\n\n"
            finally:
                await asyncio.shield(upstream.aclose())
        except BackendError as e:
            yield f"event: gone\ndata: {json.dumps({'code': e.code, 'detail': e.detail})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})



MAX_CHANNEL_SUBSCRIPTIONS = 32    # default of live.max_subscriptions: what one browser's channel carries at most
CHANNELS: dict[str, dict] = {}     # open channels by id: the browser adds and removes subscriptions without reconnecting


def max_subscriptions(request: Request) -> int:
    """How many subscriptions one channel (one browser) carries at most (``live.max_subscriptions``)."""
    settings = getattr(request.app.state, "settings", None)
    value = settings.get("live.max_subscriptions", MAX_CHANNEL_SUBSCRIPTIONS) if settings is not None else MAX_CHANNEL_SUBSCRIPTIONS
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return MAX_CHANNEL_SUBSCRIPTIONS


@router.get("/channel/who")
async def channel_who(request: Request):
    """Whose session this browser has now (the fingerprint pages carry in meta drishti-live): the browser's live hub asks
    when a page of another session joins, to tell a newer sign-in from an old page."""
    return JSONResponse({"who": live_who(request)}, headers={"Cache-Control": "no-store"})


@router.get("/channel")
async def channel(request: Request, s: list[str] = Query(default=[])):
    """Everything live a BROWSER needs, on ONE connection: the views of all its tabs (a workspace's panes included), the
    alerts bell and monitors (s=view:trade/T-1, s=alerts, s=monitor:<name>). Browsers allow only six connections to a
    site over HTTP/1.1; a stream per view, and later a channel per tab, used them up, and every other page of the site
    then waited forever (UX-01). The browser's tabs share this channel through live-hub.js. Each event carries its
    subscription: {"ch": "view:trade/T-1", "d": …}; the first, ``channel``, says whose session it runs as (``who``).
    Every frame is built for the signed-in user of the request that opened it. Upstream streams are closed within
    seconds of the browser going away, even when they are quiet."""
    limit = max_subscriptions(request)
    asked = list(dict.fromkeys(x for x in s if x))[:limit * 2]
    session = ChannelSession(request.app.state.backend, ident(request), lambda event, data: _view_event(request, event, data),
                             limit=limit, who=live_who(request), registry=CHANNELS)
    return StreamingResponse(session.events(asked, request.is_disconnected), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    """Adds or removes subscriptions on an open channel ({"add": [...], "remove": [...]}): a workspace's panes arrive one
    by one, and reconnecting for each would make every view on the page think it had lost its connection."""
    ch = CHANNELS.get(cid)
    me = ident(request)
    if ch is None or ch["user"] != (me.user if me is not None else "") or ch.get("app", ""):
        return JSONResponse({"code": "DRS-5001", "detail": "no such channel: open a new one"}, status_code=404)
    body = await json_body(request)
    limit = max_subscriptions(request)
    for sub in (body.get("remove") or [])[:limit]:
        ch["remove"](str(sub))
    for sub in (body.get("add") or [])[:limit * 2]:
        ch["add"](str(sub))
    return JSONResponse({"ok": True})
