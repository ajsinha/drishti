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
import contextlib
import contextvars
import json
import secrets
import time
from urllib.parse import quote

from fastapi import APIRouter, Query, Request

from fastapi.responses import JSONResponse, StreamingResponse

from core.csrf import json_body
from core import asof
from core.backend import BackendError
from routes.common import ident, live_who

router = APIRouter(prefix="/api", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


@router.get("/suggest")
async def suggest(request: Request, q: str = "", limit: int | None = None):
    """The command line's dropdown; without a limit the server's own (drishti.commands.suggest-limit, 25) applies."""
    try:
        return await request.app.state.backend.suggest(q, ident(request), limit)
    except BackendError as e:
        return _problem(e)


@router.post("/tokens")
async def create_token(request: Request):
    """A personal API token for a script or spreadsheet; the answer holds the secret, shown once."""
    body = await json_body(request)
    days = body.get("days")
    try:
        return await request.app.state.backend.create_token(body.get("name", ""), int(days) if days else None, ident(request))
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


@router.get("/notes/{kind}/{id_}")
async def notes(request: Request, kind: str, id_: str):
    try:
        return await request.app.state.backend.notes(kind, id_, ident(request))
    except BackendError as e:
        return _problem(e)


@router.post("/notes/{kind}/{id_}")
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


@router.get("/series/{kind}/{id_}")
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


@router.get("/raw/{kind}/{id_}")
async def raw(request: Request, kind: str, id_: str):
    try:
        return await request.app.state.backend.raw(kind, id_, ident(request))
    except BackendError as e:
        return _problem(e)


@router.get("/view/{kind}/{id_}")
async def view(request: Request, kind: str, id_: str):
    try:
        return await request.app.state.backend.view(kind, id_, ident(request))
    except BackendError as e:
        return _problem(e)


CHART_KINDS = {"line", "area"}


def _panel_html(request: Request, panel: dict) -> str:
    """Renders one panel with the same macro as first paint, so live and static views look identical."""
    module = request.app.state.templates.env.get_template("_macros/panels.html").module
    return str(module.panel(panel))


def _view_event(request: Request, event: str, data: str) -> str:
    """A view stream event as the browser wants it: panel patches as ready HTML (charts as data), the view as its generation."""
    if event == "frame":
        frame = json.loads(data)
        for patch in frame.get("patches", []):
            panel = patch.get("panel")
            if panel and panel.get("kind") not in CHART_KINDS:
                patch["html"] = _panel_html(request, panel)
                patch["panel"] = {"id": panel["id"], "kind": panel["kind"]}
        return json.dumps(frame, ensure_ascii=False)
    if event == "view":
        view = json.loads(data)
        return json.dumps({"generation": view.get("provenance", {}).get("generation")})
    return data


@router.get("/stream/{kind}/{id_}")
async def stream(request: Request, kind: str, id_: str):
    """Relays one view's live stream (kept for API clients; pages use /api/channel)."""
    backend = request.app.state.backend

    async def events():
        try:
            upstream = backend.stream(kind, id_, ident(request))
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
    backend, me = request.app.state.backend, ident(request)
    limit = max_subscriptions(request)
    asked = list(dict.fromkeys(x for x in s if x))[:limit * 2]
    queue: asyncio.Queue = asyncio.Queue(maxsize=2000)

    opened: dict[str, list] = {}                      # subscription -> its upstream HTTP responses, closed to end it
    stopping = asyncio.Event()

    async def pump(sub: str):
        responses = opened.setdefault(sub, [])
        if sub.startswith("view:") and "/" in sub:
            kind, _, id_ = sub[5:].partition("/")
            upstream, is_view = backend.stream(kind, id_, me, opened=responses), True
        elif sub == "alerts":
            upstream, is_view = backend.sse("/me/alerts/stream", me, opened=responses), False
        elif sub.startswith("monitor:"):
            upstream, is_view = backend.sse(f"/me/monitors/{quote(sub[8:])}/stream", me, opened=responses), False
        else:
            return
        try:
            async for event, data in upstream:
                await queue.put((sub, event, _view_event(request, event, data) if is_view else data))
        except BackendError as e:
            await queue.put((sub, "gone", json.dumps({"code": e.code, "detail": e.detail})))
        except Exception as e:  # noqa: BLE001 - one broken subscription must not end the others (nor a closed channel)
            if not stopping.is_set() and sub in tasks:
                await queue.put((sub, "gone", json.dumps({"code": "DRS-5003", "detail": str(e)})))

    async def close_sub(sub: str, task):
        for r in list(opened.pop(sub, [])):
            try:
                await r.aclose()                        # the pump's pending read fails and it returns
            except Exception:  # noqa: BLE001 - already closed
                pass
        await asyncio.sleep(0.5)
        if task is not None:
            task.cancel()                               # still opening its request

    async def stop():
        """Ends every upstream stream by closing its response (never by cancelling a reading task: see detached)."""
        stopping.set()
        CHANNELS.pop(cid, None)
        await asyncio.gather(*(close_sub(s_, t) for s_, t in list(tasks.items())), return_exceptions=True)

    def add(sub: str):
        if not sub or sub in tasks or ended[0]:
            return
        if len(tasks) >= limit:                       # say so: a view that silently never ticks looks broken
            with contextlib.suppress(asyncio.QueueFull):
                queue.put_nowait((sub, "gone", json.dumps({"code": "DRS-5003", "detail": f"this browser already follows {limit} "
                                                           "live subscriptions (live.max_subscriptions): close some tabs or panes"})))
            return
        tasks[sub] = detached(pump(sub))

    def remove(sub: str):
        task = tasks.pop(sub, None)
        if task is not None:
            detached(close_sub(sub, task))

    def detached(coro):
        # Upstream work runs in tasks with a fresh context holding only the business date and "known at". Anything
        # started from the request's context inherits Starlette's (anyio's) cancel scope, which, once the request has
        # ended, cancels every await in it for good: the streams' clean-up never completed and their connections to the
        # server stayed open (the console ran out of connections, and the UI froze). Ending a channel therefore closes
        # the responses from a detached task too.
        ctx = contextvars.Context()
        ctx.run(asof.set_current, asof.current())
        ctx.run(asof.set_known, asof.known_at())
        return asyncio.get_running_loop().create_task(coro, context=ctx)

    tasks: dict[str, asyncio.Task] = {}
    last_pull = [time.monotonic()]
    ended = [False]

    def end():
        if not ended[0]:
            ended[0] = True
            detached(stop())

    async def watchdog():
        # Starlette may stop reading this stream when the browser goes away without closing it. A live channel is read
        # at least every two seconds; one nobody has read for five has lost its reader.
        while not ended[0]:
            await asyncio.sleep(1.0)
            if time.monotonic() - last_pull[0] > 5.0:
                end()

    cid = secrets.token_urlsafe(12)
    who = live_who(request)

    async def events():
        for x in asked:
            add(x)
        CHANNELS[cid] = {"user": me.user if me is not None else "", "add": add, "remove": remove}
        detached(watchdog())
        try:
            yield f"event: channel\ndata: {json.dumps({'ch': '', 'd': {'id': cid, 'subs': asked, 'who': who}})}\n\n"
            idle = 0
            checked = time.monotonic()
            while True:
                last_pull[0] = time.monotonic()
                if time.monotonic() - checked > 1.0:                # busy or quiet: notice a closed tab within a second
                    checked = time.monotonic()
                    if await request.is_disconnected():
                        break
                current = list(tasks.values())
                if current and queue.empty() and all(t.done() for t in current):
                    yield "event: end\ndata: {}\n\n"                # every subscription has ended: say so, and close
                    break
                try:
                    sub, event, data = await asyncio.wait_for(queue.get(), timeout=2.0)
                    idle = 0
                except asyncio.TimeoutError:
                    if await request.is_disconnected():
                        break
                    idle += 1
                    if idle % 7 == 0:                               # a comment every ~15 s keeps proxies from closing it
                        yield ": hb\n\n"
                    continue
                try:
                    body = json.loads(data)
                except (TypeError, ValueError):
                    body = data
                yield f"event: {event}\ndata: {json.dumps({'ch': sub, 'd': body}, ensure_ascii=False)}\n\n"
        finally:
            end()

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    """Adds or removes subscriptions on an open channel ({"add": [...], "remove": [...]}): a workspace's panes arrive one
    by one, and reconnecting for each would make every view on the page think it had lost its connection."""
    ch = CHANNELS.get(cid)
    me = ident(request)
    if ch is None or ch["user"] != (me.user if me is not None else ""):
        return JSONResponse({"code": "DRS-5001", "detail": "no such channel: open a new one"}, status_code=404)
    body = await json_body(request)
    limit = max_subscriptions(request)
    for sub in (body.get("remove") or [])[:limit]:
        ch["remove"](str(sub))
    for sub in (body.get("add") or [])[:limit * 2]:
        ch["add"](str(sub))
    return JSONResponse({"ok": True})
