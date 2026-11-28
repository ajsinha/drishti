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

MERGED = ("home_kpis", "home_exceptions", "home_changes")      # a person with several roles sees every Home's rows of these
ACTIONS = ("pin", "unpin", "hide", "show", "up", "down", "snooze", "reset", "visited")
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


async def _table(request: Request, homes: list[str], table: str) -> list[dict]:
    names = homes if table in MERGED else homes[:1]
    got = await asyncio.gather(*[_quiet(request, f"/homes/{quote(h)}/tables/{table}", {}) for h in names])
    rows, seen = [], set()
    for g in got:
        for r in (g or {}).get("rows") or []:
            k = json.dumps(r, sort_keys=True, default=str)
            if k not in seen:
                seen.add(k)
                rows.append(r)
    return rows


def _needs(alerts, approvals, loads, exceptions, prefs: dict, now: datetime) -> list[dict]:
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
    out = [n for n in out if hc.awake(prefs, n["key"], now)]
    out.sort(key=lambda n: ("bad", "warn", "muted").index(n["tone"]) if n["tone"] in ("bad", "warn", "muted") else 3)
    return out[:8]


def _wake(until, now: datetime) -> bool:
    try:
        return not until or datetime.fromisoformat(str(until).replace("Z", "+00:00")) <= now
    except ValueError:
        return True


async def compose(request: Request, now: datetime | None = None) -> dict:
    """The person's Home: ``{view, homes, layout, cards}``. Every read is as the person (their roles, row rules and masks apply on the server)."""
    me, backend = ident(request), request.app.state.backend
    now = now or datetime.now(timezone.utc)
    offered = await _quiet(request, "/homes", {})
    homes_info = (offered or {}).get("homes") or []
    start = (offered or {}).get("start")
    homes = ([start] if start else []) + [h["home"] for h in homes_info if h.get("home") != start]
    try:
        settings = await backend.settings(me)
    except BackendError:
        settings = {}
    prefs = hc.clean(settings.get("homeCards"))
    common, declared = _common()
    parts = request.app.state.module_parts.role_cards() if getattr(request.app.state, "module_parts", None) else []
    every = hc.catalogue(parts, common)
    layout = hc.layout_for(homes[:1] or [], declared)
    mine = hc.ranked(every, homes, prefs)
    hidden = hc.hidden_cards(every, homes, prefs)
    tables = sorted({c.table for c in mine if c.table and not c.table.startswith("@")} | {"home_meta", "home_loads", "home_exceptions", "home_changes"})
    rows = dict(zip(tables, await asyncio.gather(*[_table(request, homes, t) for t in tables]))) if homes else {}
    alerts, approvals = await asyncio.gather(_quiet_call(backend.raised_alerts(me, "open")), _quiet_call(backend.approvals_list(me, "inbox")))
    rows["@pinned"] = [{"KIND": p.get("kind"), "ID": p.get("id")} for p in settings.get("pinned") or [] if isinstance(p, dict)]
    pinned = list(prefs["pins"])
    cards = []
    for i, c in enumerate(mine):
        cards.append((c, hc.panel(c, rows.get(c.table, []), pinned=c.id in pinned, first=i == 0, last=i == len(mine) - 1)))
    needs = _needs(alerts, approvals, rows.get("home_loads"), rows.get("home_exceptions"), prefs, now)
    meta = (rows.get("home_meta") or [{}])[0]
    late = [r for r in rows.get("home_loads") or [] if r.get("STATE") in ("late", "missing", "failed")]
    since = str(meta.get("SINCE") or "")
    movers = rows.get("home_changes") or []
    digest = None
    if movers:
        digest = hc.panel(hc.Card(id="digest", title=f"Since {since}" if since else "What changed", module="", kind="table", table="home_changes",
                                  columns=({"label": "Subject", "col": "SUBJECT", "fmt": "", "link": ""}, {"label": "Measure", "col": "MEASURE", "fmt": "", "link": ""},
                                           {"label": "Then", "col": "THEN", "fmt": "", "link": ""}, {"label": "Now", "col": "NOW", "fmt": "", "link": ""},
                                           {"label": "Change", "col": "CHANGE", "fmt": "", "link": ""}), limit_rows=8, always=True),
                          movers, pinned=False, first=True, last=True)
        digest["actions"] = []
    date = str(meta.get("BUSINESS_DATE") or "")
    strip = [{"label": "Business date", "text": date}] if date else []
    strip.append({"label": "Data", "text": (f"{len(late)} daily load{'s' if len(late) != 1 else ''} late or failed" if late else "every daily load is in"),
                  "tone": "warn" if late else "ok"})
    if prefs.get("lastVisit"):
        strip.append({"label": "Last visit", "text": prefs["lastVisit"].replace("T", " ").rstrip("Z") + " UTC"})
    title = next((h.get("title") for h in homes_info if h.get("home") == start), "") or "Your Home"
    name = getattr(me, "display", "") or getattr(me, "user", "")
    view = hc.build_view(title=hc.greeting(name, settings.get("clockZone"), now), role=title, layout=layout, strip=strip, needs=needs, digest=digest, cards=cards,
                         hidden=hidden, pins=pinned, business_date=date or None, stale=bool(late))
    return {"view": view, "layout": layout, "homes": [h.get("home") for h in homes_info], "cards": [c.id for c, p in cards if p is not None], "visible": [c.id for c in mine]}


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
