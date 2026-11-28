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

"""Your Home: where everyone lands after signing in (docs/guides/USER_GUIDE.md, "Your Home").

``/home`` is the person's role Home as cards: the Homes the server offers them (their roles, the modules on), the cards those modules and the
console declare (core/home_cards.py), each card's rows read from the home tables the server serves *for the person*, and the ViewModel the
Drishti Elements painter draws. Nothing is computed or drawn here. What a person pins, hides, moves or snoozes is their own setting on the
server (``homeCards``), read and written as them on every request: nothing per person is kept in this process.
"""
from __future__ import annotations

import asyncio
import functools
import json
from datetime import datetime, timezone
from urllib.parse import quote

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from core import home_cards as hc
from core.backend import BackendError
from core.csrf import BodyError, json_body
from routes.common import ident, render

router = APIRouter(include_in_schema=False)

MERGED = ("home_kpis", "home_exceptions", "home_changes", "home_digest")      # a person with several roles sees every Home's rows of these
ACTIONS = ("pin", "unpin", "hide", "show", "up", "down", "snooze", "reset", "visited", "viewas")
TONES = {"critical": "bad", "high": "bad", "error": "bad", "warn": "warn", "medium": "warn", "low": "muted", "info": "muted"}


@functools.lru_cache(maxsize=4)
def _load(stamp: int):
    return hc.common_cards()


def _common():
    """The console's own card file, read again when it changes (static configuration: nothing of a person's)."""
    try:
        stamp = hc.CONFIG.stat().st_mtime_ns
    except OSError:
        stamp = 0
    return _load(stamp)


async def _get(request: Request, path: str):
    return await request.app.state.backend._send("GET", path, ident(request))


async def _quiet(request: Request, path: str, default):
    """One read the Home can do without: the card or the strip it feeds is left out, the Home still opens."""
    try:
        got = await _get(request, path)
    except BackendError:
        return default
    return got if got is not None else default


async def _table(request: Request, homes: list[str], table: str, since: str | None = None) -> list[dict]:
    names = homes if table in MERGED else homes[:1]
    tail = f"?since={quote(since)}" if since else ""
    got = await asyncio.gather(*[_quiet(request, f"/homes/{quote(h)}/tables/{table}{tail}", {}) for h in names])
    rows, seen = [], set()
    for g in got:
        for r in (g or {}).get("rows") or []:
            k = json.dumps(r, sort_keys=True, default=str)
            if k not in seen:
                seen.add(k)
                rows.append(r)
    return rows


def _module_needs(decls: list[dict], tables: dict[str, list[dict]]) -> list[dict]:
    """What the modules add to Needs you: each declaration (``home: needs:``) names a home table, the column of the words, the column of the
    detail, a tone and the command to open; every row is one item."""
    out = []
    for d in decls:
        tone = str(d.get("tone") or "warn")
        for r in tables.get(str(d["table"]), []):
            what = str(r.get(str(d["what"])) or "")
            if what:
                detail = str(r.get(str(d.get("detail") or "")) or "")
                out.append({"key": f"{d['table']}:{what}|{detail}", "what": what, "detail": detail,
                            "tone": tone if tone in ("bad", "warn", "muted") else "warn", "link": hc._go(str(d["open"])) if d.get("open") else None})
    return out


def _needs(alerts, approvals, loads, exceptions, prefs: dict, now: datetime, more=()) -> list[dict]:
    """What waits for the person: alerts raised for them, approvals in their inbox, loads that are late, missing or failed, and the high
    exceptions of their Home. Snoozed items are left out; the order is the worst first."""
    out = []
    for a in alerts or []:
        if a.get("stage") == "resolved" or not _wake(a.get("snoozedUntil"), now):
            continue
        out.append({"key": f"alert:{a.get('id')}", "what": f"Alert: {a.get('title') or a.get('rule')}", "detail": str(a.get("message") or ""),
                    "tone": TONES.get(str(a.get("severity")), "warn"), "link": hc._href("/alerts")})
    for e in (approvals or {}).get("items") or []:
        i = e.get("item") or {}
        out.append({"key": f"approval:{i.get('kind')}:{i.get('id')}", "what": f"Approve: {i.get('title')}", "detail": f"from {i.get('maker')}",
                    "tone": "warn" if e.get("dueSoon") else "muted", "link": hc._href("/approvals")})
    for r in loads or []:
        out.append({"key": f"load:{r.get('PACK')}/{r.get('KIND')}/{r.get('BUSINESS_DATE')}", "what": f"Load {r.get('STATE')}: {r.get('PACK')}/{r.get('KIND')}",
                    "detail": f"for {r.get('BUSINESS_DATE')}", "tone": "bad" if r.get("STATE") == "failed" else "warn", "link": hc._go("LOADS")})
    for r in exceptions or []:
        if r.get("SEVERITY") == "high":
            out.append({"key": f"exception:{r.get('SUBJECT')}|{r.get('MESSAGE')}", "what": str(r.get("MESSAGE")), "detail": str(r.get("SUBJECT") or ""),
                        "tone": "bad", "link": hc._go(str(r.get("COMMAND") or "")) if r.get("COMMAND") else None})
    out += list(more)
    out = [n for n in out if hc.awake(prefs, n["key"], now)]
    out.sort(key=lambda n: ("bad", "warn", "muted").index(n["tone"]) if n["tone"] in ("bad", "warn", "muted") else 3)
    return out[:8]


def _wake(until, now: datetime) -> bool:
    try:
        return not until or datetime.fromisoformat(str(until).replace("Z", "+00:00")) <= now
    except ValueError:
        return True


def _digest(events: list[dict], movers: list[dict], alerts, window: str | None, since: str) -> dict | None:
    """"Since your last visit" (or, on a first visit, since the Home's cut-off): what the server recorded since then (loads, limit moves, new
    variants and the like: ``home_digest``), the alerts fired since, and on a first visit the biggest movers. ``None`` when nothing happened."""
    lines = [{"at": str(e.get("AT") or ""), "what": str(e.get("TEXT") or "")} for e in events]
    if window:
        lines += [{"at": str(a.get("raisedAt") or ""), "what": f"Alert fired: {a.get('title') or a.get('rule')}"} for a in alerts or []
                  if str(a.get("raisedAt") or "") >= window]
    else:
        lines += [{"at": "", "what": f"{m.get('SUBJECT')} {m.get('MEASURE')}: {hc.fmt(m.get('THEN'))} to {hc.fmt(m.get('NOW'))}"} for m in movers]
    if not lines:
        return None
    lines.sort(key=lambda x: x["at"], reverse=True)
    title = ("Since your last visit, " + window.replace("T", " ").rstrip("Z") + " UTC") if window else (f"Since {since}" if since else "What changed")
    return {"id": "digest", "kind": "table", "title": title, "area": "digest", "actions": [],
            "data": {"columns": ["When", "What"], "numeric": [False, False], "search": False, "emptyLabel": "Nothing has changed.",
                     "rows": [{"cells": [hc._cell(x["at"].replace("T", " ")[5:16]), hc._cell(x["what"])], "highlight": False, "path": None} for x in lines[:8]]}}


async def compose(request: Request, now: datetime | None = None) -> dict:
    """The person's Home: ``{view, homes, layout, cards}``. Every read is as the person (their roles, row rules and masks apply on the server)."""
    me, backend = ident(request), request.app.state.backend
    now = now or datetime.now(timezone.utc)
    offered = await _quiet(request, "/homes", {})
    homes_info = (offered or {}).get("homes") or []
    start = (offered or {}).get("start")
    every_home = ([start] if start else []) + [h["home"] for h in homes_info if h.get("home") != start]
    try:
        settings = await backend.settings(me)
    except BackendError:
        settings = {}
    prefs = hc.clean(settings.get("homeCards"))
    chosen = hc.view_as(prefs, every_home)                 # "View as": one of the person's own Homes, or all of them
    homes = [chosen] if chosen else every_home
    common, declared = _common()
    ps = request.app.state.module_parts if getattr(request.app.state, "module_parts", None) else None
    parts, decls = (ps.role_cards(), ps.home_needs()) if ps else ([], [])
    every = hc.catalogue(parts, common)
    layout = hc.layout_for(homes[:1] or [], declared)
    mine = hc.ranked(every, homes, prefs)
    hidden = hc.hidden_cards(every, homes, prefs)
    # a table is asked of a Home that has its card (a module's tables are served to its own Homes only)
    asked: dict[str, list[str]] = {}
    for c in mine:
        if c.table and not c.table.startswith("@"):
            h = next((x for x in homes if x in c.rank), homes[0] if homes else "")
            if h and h not in asked.setdefault(c.table, []):
                asked[c.table].append(h)
    need_decls = [dict(d, _homes=[x for x in homes if x in (d.get("homes") or [])]) for p in decls for d in p["needs"]]
    need_decls = [d for d in need_decls if d["_homes"]]
    for d in need_decls:
        asked.setdefault(str(d["table"]), []).append(d["_homes"][0])
    window = hc.digest_window(prefs, now)
    base = ("home_meta", "home_loads", "home_exceptions", "home_changes")
    wanted = sorted(set(asked) | set(base))
    got = await asyncio.gather(*([_table(request, asked.get(t) or homes, t) for t in wanted] + [_table(request, homes, "home_digest", window)])) if homes \
        else [[] for _ in wanted + [""]]
    rows = dict(zip(wanted + ["home_digest"], got))
    alerts, approvals = await asyncio.gather(_quiet_call(backend.raised_alerts(me, "open")), _quiet_call(backend.approvals_list(me, "inbox")))
    rows["@pinned"] = [{"KIND": p.get("kind"), "ID": p.get("id")} for p in settings.get("pinned") or [] if isinstance(p, dict)]
    pinned = list(prefs["pins"])
    cards = []
    for i, c in enumerate(mine):
        cards.append((c, hc.panel(c, rows.get(c.table, []), pinned=c.id in pinned, first=i == 0, last=i == len(mine) - 1)))
    needs = _needs(alerts, approvals, rows.get("home_loads"), rows.get("home_exceptions"), prefs, now, _module_needs(need_decls, rows))
    meta = (rows.get("home_meta") or [{}])[0]
    late = [r for r in rows.get("home_loads") or [] if r.get("STATE") in ("late", "missing", "failed")]
    since = str(meta.get("SINCE") or "")
    digest = _digest(rows.get("home_digest") or [], rows.get("home_changes") or [], alerts, window, since)
    date = str(meta.get("BUSINESS_DATE") or "")
    strip = [{"label": "Business date", "text": date}] if date else []
    strip.append({"label": "Data", "text": (f"{len(late)} daily load{'s' if len(late) != 1 else ''} late or failed" if late else "every daily load is in"),
                  "tone": "warn" if late else "ok"})
    if window:
        strip.append({"label": "Last visit", "text": window.replace("T", " ").rstrip("Z") + " UTC"})
    title = next((h.get("title") for h in homes_info if h.get("home") == (chosen or start)), "") or "Your Home"
    name = getattr(me, "display", "") or getattr(me, "user", "")
    view = hc.build_view(title=hc.greeting(name, settings.get("clockZone"), now), role=title, layout=layout, strip=strip, needs=needs, digest=digest, cards=cards,
                         hidden=hidden, pins=pinned, business_date=date or None, stale=bool(late))
    options = [{"value": h.get("home"), "label": h.get("title") or h.get("home")} for h in homes_info]
    switch = {"value": chosen, "options": ([{"value": "", "label": "All"}] + options) if len(options) > 1 else []}
    return {"view": view, "layout": layout, "viewAs": switch, "homes": [h.get("home") for h in homes_info], "cards": [c.id for c, p in cards if p is not None], "visible": [c.id for c in mine]}


async def _quiet_call(coro):
    try:
        return await coro
    except BackendError:
        return None


@router.get("/home")
async def home(request: Request):
    """Your Home: the page is a shell; the first paint's ViewModel rides in it (one read, no flash of an empty page)."""
    got = await compose(request)
    return render(request, "home_cards.html", screen="home", home=got, view_json=json.dumps(got["view"]), layout=got["layout"])


@router.get("/api/home/cards")
async def cards(request: Request):
    return JSONResponse(await compose(request), headers={"Cache-Control": "no-store"})


@router.post("/api/home/cards/prefs")
async def prefs(request: Request):
    """One change to the person's own arrangement (pin, unpin, hide, show, up, down, snooze, reset, visited); answers the Home as it now is."""
    try:
        body = await json_body(request, limit=4096)
    except BodyError as e:
        return JSONResponse({"code": "DRS-5001", "detail": str(e)}, status_code=400)
    action, card = str(body.get("action") or ""), str(body.get("card") or "")
    keys = [str(k) for k in body.get("keys") or [] if isinstance(k, str)][:20]
    if action not in ACTIONS or (action in ("pin", "unpin", "hide", "show", "up", "down") and not card):
        return JSONResponse({"code": "DRS-5001", "detail": "action is one of " + ", ".join(ACTIONS) + "; a card action names its card"}, status_code=400)
    me, backend = ident(request), request.app.state.backend
    if action == "viewas" and card:
        offered = await _quiet(request, "/homes", {})
        if card not in [h.get("home") for h in (offered or {}).get("homes") or []]:      # only a Home the person really has
            return JSONResponse({"code": "DRS-5001", "detail": "you do not have the Home " + card}, status_code=400)
    try:
        current = hc.clean((await backend.settings(me)).get("homeCards"))
        before = await compose(request) if action in ("up", "down") else {"visible": []}
        now = datetime.now(timezone.utc)
        if action == "visited" and current.get("lastVisit") and (now - datetime.fromisoformat(current["lastVisit"].replace("Z", "+00:00"))).total_seconds() < 1800:
            return JSONResponse({"kept": True})                          # one visit counts per half hour, so a refresh does not erase "since your last visit"
        after = hc.apply(current, action, card, now, keys, before["visible"])
        await backend.patch_settings({"homeCards": after}, me)
    except BackendError as e:
        return JSONResponse({"code": e.code, "detail": e.detail}, status_code=e.status if 400 <= (e.status or 0) < 600 else 502)
    request.app.state.user_settings.forget(me.user, request)
    return JSONResponse(await compose(request, now), headers={"Cache-Control": "no-store"})
