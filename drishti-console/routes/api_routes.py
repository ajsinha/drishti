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
from urllib.parse import quote

from fastapi import APIRouter, Query, Request

from fastapi.responses import JSONResponse, StreamingResponse

from core import channel_relay, controls, servers
from core.csrf import json_body
from core.backend import BackendError
from core.channel import ChannelSession
from routes.common import ident, live_who

router = APIRouter(prefix="/api", include_in_schema=False)


def _problem(e: BackendError) -> JSONResponse:
    return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status)


async def what_suggestions(request: Request, words: str) -> list:
    """``WHAT``'s dropdown: the Knowledge home, then the glossary's terms that start with the words (each opens its entry), and for
    words that are no term a search of the Knowledge pages."""
    from core.knowledge import HOME
    from routes.common import library

    lib = await library(request)
    folder = (request.app.state.docs_dir / "knowledge").resolve()
    out = [] if words else [{"mnemonic": "WHAT", "title": "Knowledge: what the words of your data mean",
                             "subtitle": "WHAT <GO> opens the Knowledge home; WHAT <term> its glossary entry (WHAT PFE, WHAT netting set)",
                             "complete": "WHAT ", "href": f"/help/{lib.by_file.get(folder / HOME, '')}", "type": "what"}]
    for e in request.app.state.glossary.starting(words):
        slug = lib.by_file.get(folder / e.page)
        if slug:
            out.append({"mnemonic": "WHAT", "title": e.term + (f" ({e.name})" if e.name else ""), "subtitle": f"{e.title} · {e.meaning}",
                        "complete": f"WHAT {e.term}", "href": f"/help/{slug}#{e.anchor}", "type": "what"})
    if words and not out:
        out.append({"mnemonic": "WHAT", "title": f"Search the Knowledge pages for {words}", "subtitle": "No term of the glossary is called that",
                    "complete": f"WHAT {words}", "href": f"/what?q={quote(words)}", "type": "what"})
    return out


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
    """Type-ahead for the reserved word ``BI`` (alias ``RUPAKA``): its three pages, then published reports whose id or title matches, only for people who hold the bi power."""
    me = ident(request)
    if not request.app.state.bi_on or not (await request.app.state.bi_me.get(request.app.state.backend, me)).get("enabled"):
        return []
    want = (rest or "").strip().lower()
    kw = (word or RUPAKA).upper()                # echo the keyword as typed: BI or its alias RUPAKA
    out = [{"mnemonic": kw, "title": title, "subtitle": sub, "complete": f"{kw} {key}".strip(), "href": href, "type": "bi"}
           for key, title, sub, href in BI_ENTRIES if rest is None or not want or key.lower().startswith(want)]
    if rest is not None and want:
        try:
            found = (await request.app.state.backend.bi("GET", "/catalogue", me, q=want, limit=8)).get("items") or []
        except BackendError:
            found = []
        out += [{"mnemonic": kw, "title": i["title"], "subtitle": f"{i['kind']} · {i['workspaceName']} · {i['owner']}", "complete": f"{kw} {i['title']}",
                 "href": f"/bi/i/{i['id']}", "type": "bi"} for i in found]
    return out


@router.get("/suggest")
async def suggest(request: Request, q: str = "", limit: int | None = None):
    """The command line's dropdown; without a limit the server's own (drishti.commands.suggest-limit, 25) applies. The commands of an
    engine the person's role does not open are left out (core/menus.py, the Engines menu's rule)."""
    from core import menus

    if menus.engine_refused(request, q):
        return []
    return menus.suggestions_opened(request, await _suggest(request, q, limit))


async def _suggest(request: Request, q: str, limit: int | None):
    ed = getattr(request.state, "edition", None) or request.app.state.editions.instance     # the words an edition (or lens) keeps
    bi = re.match(r"(?is)^\s*(bi|rupaka)(?:\s+(.*))?$", q.replace("<GO>", ""))
    if bi and ed.word_on(bi.group(1)):                                                     # the reserved word RUPAKA: the BI pages and the reports you may open
        return await rupaka_suggestions(request, bi.group(1), bi.group(2))
    keyword = []
    if re.match(r"(?i)^\s*(b|bi|r|ru|rup|rupa|rupak)\s*$", q.replace("<GO>", "")) and ed.word_on("BI" if q.strip().lower().startswith("b") else RUPAKA) \
            and await rupaka_suggestions(request, "", None):    # the first letters of it: offer the keyword
        kw = "BI" if q.strip().lower().startswith("b") else RUPAKA
        keyword = [{"mnemonic": kw, "title": "BI: business intelligence (Rupaka)", "subtitle": f"{kw} <GO> opens the BI home; {kw} CATALOGUE, {kw} WORKSPACE, {kw} <report>",
                    "complete": f"{kw} ", "href": "/bi", "type": "bi"}]
    func = re.match(r"(?is)^\s*func(?:\s+(.*))?$", q.replace("<GO>", ""))
    if func and ed.word_on("FUNC"):                                                   # the reserved word FUNC: the command directory, filtered by what follows
        words = (func.group(1) or "").strip()
        return [{"mnemonic": "FUNC", "title": "Functions: every command you can use" + (f", matching {words}" if words else ""),
                 "subtitle": "FUNC <GO> opens the list; FUNC <words> opens it filtered (a command, a word of what it does, a pack)",
                 "complete": f"FUNC {words}".strip(), "href": "/func" + (f"?q={quote(words)}" if words else ""), "type": "func"}]
    dmap = re.match(r"(?is)^\s*datamap(?:\s+(.*))?$", q.replace("<GO>", ""))
    if dmap and ed.word_on("DATAMAP"):                                                   # the reserved word DATAMAP: the data map, focused on what follows
        words = (dmap.group(1) or "").strip()
        return [{"mnemonic": "DATAMAP", "title": "Data map: every kind of data, how it joins, how much there is" + (f", at {words}" if words else ""),
                 "subtitle": "DATAMAP <GO> opens the map; DATAMAP <domain or kind> opens it focused (DATAMAP trade, DATAMAP counterparty)",
                 "complete": f"DATAMAP {words}".strip(), "href": "/datamap" + (f"?q={quote(words)}" if words else ""), "type": "datamap"}]
    dview = re.match(r"(?is)^\s*(?:export|data)(?:\s+(.*))?$", q.replace("<GO>", ""))
    if dview and ed.word_on("EXPORT"):                                                   # the reserved word EXPORT (DATA, its older alias): Export data, on the dataset that follows
        words = (dview.group(1) or "").strip()
        return [{"mnemonic": "EXPORT", "title": "Export data: every dataset, its dates; choose columns and filters, download a day" + (f", of {words}" if words else ""),
                 "subtitle": "EXPORT <GO> lists the datasets; EXPORT <dataset> [date] opens one (EXPORT trade-valuation 2026-09-30); download only, as .jsonl.gz; DATA works too",
                 "complete": f"EXPORT {words}".strip(), "href": "/data" + (f"?q={quote(words)}" if words else ""), "type": "data"}]
    smat = re.match(r"(?is)^\s*smat(?:\s+(.*))?$", q.replace("<GO>", ""))
    if smat and ed.word_on("SMAT"):                                                   # SMAT <id> [N]: the scenario matrix (smat_routes.py)
        from routes.smat_routes import smat_command
        words = (smat.group(1) or "").strip()
        return [{"mnemonic": "SMAT", "title": "Scenario matrix: scenario P&L by scenario day across the last N business dates" + (f", of {words}" if words else ""),
                 "subtitle": "SMAT <trade, position or VaR scope> [N] <GO> (SMAT DESK:RATES 20, SMAT POS <position>, SMAT TRD <trade> 30)",
                 "complete": f"SMAT {words}".strip(), "href": smat_command(["SMAT"] + words.split()) or "/smat", "type": "smat"}]
    play = re.match(r"(?is)^\s*play(?:\s+(.*))?$", q.replace("<GO>", ""))
    if play and ed.word_on("PLAY") and ed.word_on("VAR"):                          # PLAY VAR <scope|ids>: the VaR Playground (play_routes.py)
        from routes.play_routes import play_command
        words = (play.group(1) or "").strip()
        if not words.upper().startswith("VAR"):
            words = ("VAR " + words).strip()
        me = getattr(request.state, "identity", None)
        return [{"mnemonic": "PLAY", "title": "VaR Playground: every VaR figure worked in a grid and an Excel workbook of real formulas"
                 + (f", of {words[4:]}" if words[4:] else ""),
                 "subtitle": "PLAY VAR <scope or ids> <GO> (PLAY VAR DESK RATES, PLAY VAR BANK 10D SUM, PLAY VAR POS-10442 T-1001)",
                 "complete": f"PLAY {words}".strip(), "href": play_command(["PLAY"] + words.split(), (me.user if me else "") or "") or "/play",
                 "type": "play"}]
    what = re.match(r"(?is)^\s*what(?:\s+(.*))?$", q.replace("<GO>", ""))
    if what and ed.word_on("WHAT"):                                                   # the reserved word WHAT: the Knowledge pages, then the glossary's terms
        return await what_suggestions(request, (what.group(1) or "").strip())
    typed = re.match(r"(?i)^\s*form(?:\s+(\S*))?(?:\s+(\S*))?\s*$", q.replace("<GO>", ""))
    if typed and ed.word_on("FORM"):                           # the reserved word FORM: packs you may open, then their kinds
        return await form_suggestions(request, typed.group(1), typed.group(2), q.endswith(" "))
    if re.match(r"(?i)^\s*var\s+\S", q) and ed.word_on("VAR"):          # 2.9.7: a VAR command's horizons and confidences once a scope is typed
        from routes.var_routes import var_suggestions
        keyword = var_suggestions(q) + keyword
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
    request.app.state.user_settings.forget(me.user, request)
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
        return view_problem(request, e, kind)


def view_problem(request: Request, e: BackendError, kind: str) -> JSONResponse:
    """A refused view for the renderer: with ``DRS-4023`` (the edition or the person's lens hides the kind) the fix names the lens plainly,
    and ``lens`` says which one the person is inside (CLIENT_RENDERER.md 10)."""
    if e.code != "DRS-4023":
        return _problem(e)
    from core.edition import hidden_hint, lens_of

    edition = getattr(request.state, "edition", None)
    return JSONResponse({"code": e.code, "detail": e.detail, "fix": hidden_hint(edition, kind), "lens": lens_of(edition)}, status_code=e.status)


CHART_KINDS = {"line", "area"}


def _panel_html(request: Request, panel: dict, embed: bool = False, ref: dict | None = None) -> str:
    """Renders one panel with the same macro as first paint, so live and static views look identical."""
    module = request.app.state.templates.env.get_template("_macros/panels.html").module
    return str(module.panel(panel, embed, ref))


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
    """Relays one view's live stream (kept for API clients; pages use /api/channel). ``?raw=1`` relays the server's own events unchanged
    (the whole ViewModel, then frames whose panel patches are panels, not HTML): what the client-side renderer applies (CLIENT_RENDERER.md 10)."""
    backend = request.app.state.backend
    raw = request.query_params.get("raw") == "1"

    async def events():
        try:
            ctl = controls.from_query(request.query_params)
            upstream = backend.stream(kind, id_, ident(request), **({"controls": ctl} if ctl else {}))
            try:
                async for event, data in upstream:
                    if await request.is_disconnected():
                        break
                    yield f"event: {event}\ndata: {data if raw else _view_event(request, event, data)}\n\n"
            finally:
                await asyncio.shield(upstream.aclose())
        except BackendError as e:
            yield f"event: gone\ndata: {json.dumps({'code': e.code, 'detail': e.detail})}\n\n"

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})



MAX_CHANNEL_SUBSCRIPTIONS = 32    # default of live.max_subscriptions: what one browser's channel carries at most


def channels(app) -> dict[str, dict]:
    """The open channels of ``app`` by id (the browser adds and removes subscriptions without reconnecting): per app, never per process."""
    st = app.state
    reg = getattr(st, "channels", None)
    if reg is None:
        reg = st.channels = {}
    return reg


def _guard(request: Request, session: ChannelSession):
    """The console channel's session re-check (the embed's StreamGuard, with the session as its check): every ``auth.recheck_seconds``
    the server is asked whether the session still stands; a signed-out, disabled or deleted user's channel ends (DRS-5010), a valid one
    carries on with the identity re-minted (current roles, a fresh token). None when auth is off."""
    from core.embed_stream import StreamGuard

    auth = request.app.state.auth
    if isinstance(auth, servers.Switch):
        auth = auth._target()                                    # the channel's server's Auth (its cookie, its token secret)
    if not auth.enabled or session.me is None:
        return None
    cookie = request.cookies.get(auth.cookie)
    first = session.me

    async def check():
        me = await auth.current(cookie, session.backend)
        if me is None or me.user != first.user or me.session != first.session:
            return "DRS-5010", "signed out: sign in again"
        session.me = me
        return None
    return StreamGuard(session, None, recheck=max(1.0, float(getattr(auth, "recheck", 10.0) or 0.0)), check=check)


def end_channels(app, server: str, user: str, session_id: str | None = None) -> int:
    """Ends the console channels of ``user`` on ``server`` (of one sign-in session when named): at sign-out. Returns how many."""
    n = 0
    for ch in list(channels(app).values()):
        s = ch.get("session")
        if s is None or ch.get("app", "") or ch.get("server") != server or ch.get("user") != user:
            continue
        if session_id is not None and getattr(s.me, "session", None) != session_id:
            continue
        s.end()
        n += 1
    return n


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
    then waited forever (UX-01). The browser's tabs share this channel through drishti_live-hub.js. Each event carries its
    subscription: {"ch": "view:trade/T-1", "d": …}; the first, ``channel``, says whose session it runs as (``who``).
    Every frame is built for the signed-in user of the request that opened it. Upstream streams are closed within
    seconds of the browser going away, even when they are quiet."""
    limit = max_subscriptions(request)
    asked = list(dict.fromkeys(x for x in s if x))[:limit * 2]
    session = ChannelSession(request.app.state.backend, ident(request), lambda event, data: _view_event(request, event, data),
                             limit=limit, who=live_who(request), registry=channels(request.app), cid_suffix=channel_relay.suffix(request.app))
    guard = _guard(request, session)

    async def stream():
        if guard is not None:
            guard.start()
        try:
            async for chunk in session.events(asked, request.is_disconnected):
                yield chunk
        finally:
            if guard is not None:
                guard.stop()

    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/channel/{cid}")
async def channel_change(request: Request, cid: str):
    """Adds or removes subscriptions on an open channel ({"add": [...], "remove": [...]}): a workspace's panes arrive one
    by one, and reconnecting for each would make every view on the page think it had lost its connection."""
    ch = channels(request.app).get(cid)
    me = ident(request)
    user = me.user if me is not None else ""
    gone = JSONResponse({"code": "DRS-5001", "detail": "no such channel: open a new one"}, status_code=404)
    if ch is None and channel_relay.owner_port(cid) is not None:          # held by another worker of this console: passed on to it
        body = await json_body(request)                                   # (with this request's scope: its server, core/channel_relay.py)
        got = await channel_relay.forward(request.app, cid, "console-change", {"user": user, "add": body.get("add") or [],
                                                                               "remove": body.get("remove") or [], "limit": max_subscriptions(request)})
        return JSONResponse({"ok": True}) if got and got.get("ok") else gone
    if not _owns(ch, user):
        return gone
    _change(ch, await json_body(request), max_subscriptions(request))
    return JSONResponse({"ok": True})


def _change(ch: dict, body: dict, limit: int) -> None:
    for sub in (body.get("remove") or [])[:limit]:
        ch["remove"](str(sub))
    for sub in (body.get("add") or [])[:limit * 2]:
        ch["add"](str(sub))


def _owns(ch: dict | None, user: str) -> bool:
    """A console channel (no host application) of ``user`` on the current request's server: the same name on two servers is two people."""
    return ch is not None and not ch.get("app", "") and ch.get("user") == user and ch.get("server", servers.DEFAULT) == servers.current()


def _relayed_change(app, cid: str, payload: dict) -> dict:
    """A change another worker received for a channel this one holds (core/channel_relay.py); that worker checked the caller, and the call
    runs in its scope (its server)."""
    ch = channels(app).get(cid)
    if not _owns(ch, str(payload.get("user", ""))):
        return {"ok": False}
    _change(ch, payload, int(payload.get("limit") or MAX_CHANNEL_SUBSCRIPTIONS))
    return {"ok": True}


channel_relay.HANDLERS["console-change"] = _relayed_change
