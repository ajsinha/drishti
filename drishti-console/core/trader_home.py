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

"""The trader home (docs/architecture/SHARED_SCREENS.md section 4, the product owner's option A with C's feed as a column and B one click from
a book; USER_GUIDE.md, "The trader home"): one answer, ``GET /api/home/trading/home``, of everything the page shows, put together from the
servers' own answers read as the person, in the trading domain (core/trading_landing.py reads them; this module only arranges them):

* **headline figures**: today's P&L (the server's pivot engine over the trade valuations), VaR against its limit and the limit use (the VaR
  engine's ``/scope`` of the trader's books together: never a sum of the books' VaRs), the biggest risk (the largest VaR contribution), breaches
  and near ones (each book's own VaR against its own limit), approvals waiting for the person (the one Approvals queue);
* **my books**: each book of the person's scope with its P&L, VaR, limit and limit use, a row per book, nothing else;
* **markets**: the market-data pack's headline prices and curves (core/market_landing.py's desk tiles, with their day's change);
* **what changed**: the day's new, amended and cancelled trades of the books, since the morning (the business date) or since the person's
  last visit (kept in their settings, ``traderHomeVisit``), the day's P&L and the biggest movers;
* **the live feed**: trades booked, limits approached or breached, approvals waiting and alerts raised for the person, newest first.

Every figure is the server's; the console counts rows and places text, it adds up no amount (ONE_GRAMMAR.md section 5). Whose books they are
is :func:`core.trading_landing.resolve`'s ("my" scope); every read is the person's own, so row rules and masks apply; what is cached is cached
per person by core/user_cache.py's keys (core/trading_landing.py, core/market_landing.py), never shared."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Any

from core import market_landing
from core import trading_landing as tl
from core.backend import BackendError

FEED_MAX = 40                     # the most items in the live feed
SINCE = ("morning", "visit")


async def _try(coro, problems: list, what: str):
    """An answer, or None with the reason noted (a part of the home that cannot be read says why; the others still show)."""
    try:
        return await coro
    except BackendError as e:
        problems.append(f"{what}: {e.code or e.status} {e.detail}")
    except Exception as e:  # noqa: BLE001 - one part failing never takes the home down
        problems.append(f"{what}: {e}")
    return None


def _num(v: Any) -> float | None:
    return tl._num(v)


def books(scope: tl.Scope, v: dict, r: dict) -> list[dict]:
    """A row per book of the person's scope (pure): its P&L and MtM from the valuations' answer, its VaR, limit, limit use and state from the
    VaR engine's; a book the answers do not name keeps its row with nothing in it (so a book never disappears from a trader's view)."""
    by_v = {b.get("book"): b for b in (v or {}).get("byBook") or []}
    by_r = {b.get("book"): b for b in (r or {}).get("books") or []}
    out = []
    for name in scope.books:
        a, b = by_v.get(name) or {}, by_r.get(name) or {}
        out.append({"book": name, "pnl": a.get("pnl"), "mtm": a.get("mtm"), "trades": a.get("trades"), "var": b.get("var"),
                    "limit": b.get("limit"), "limitUse": b.get("limitUse"), "state": b.get("state") or "none",
                    "scope": f"BOOK:{name}"})
    return out


def headline(v: dict, r: dict, approvals: dict | None) -> dict:
    """The five headline figures (pure), each the server's or None with nothing invented."""
    v, r = v or {}, r or {}
    pos = (r.get("positions") or [None])[0] if r.get("available") else None
    return {
        "pnl": v.get("pnl") if v.get("available") else None, "mtm": v.get("mtm"), "previousDate": v.get("previousDate"),
        "var": r.get("var"), "es": r.get("es"), "limit": r.get("limit"), "limitUse": r.get("limitUse"), "state": r.get("state") or "none",
        "confidence": r.get("confidence"), "currency": r.get("currency") or "USD", "scope": r.get("scope"), "intraday": r.get("intraday"),
        "biggest": None if not pos else {"label": pos.get("label"), "componentVar": pos.get("componentVar"), "pct": pos.get("pct"),
                                         "scope": pos.get("scope"), "book": pos.get("book")},
        "breaches": [b["book"] for b in r.get("breaches") or []], "near": [b["book"] for b in r.get("nearLimit") or []],
        "approvals": None if approvals is None else int(approvals.get("inbox") or 0),
        "approvalsDueSoon": None if approvals is None else int(approvals.get("dueSoon") or 0),
    }


def _after(at: Any, since: str | None) -> bool:
    """Whether an event's time is after ``since`` (both ISO; an event with no time of day is the day's: after a visit only when unknown)."""
    if not since:
        return True
    if not at:
        return False
    return str(at)[:19] > str(since)[:19]


def changed(v: dict, b: dict, since: str, last_visit: str | None) -> dict:
    """What changed (pure): the trades' events of the day, all of them (``morning``) or only those after the last visit (``visit``), counted;
    the day's P&L and the biggest movers (the valuations' answer). Since a visit the P&L is the day's still: the valuations are daily."""
    b = b or {}
    rows = [e for e in b.get("rows") or [] if since != "visit" or _after(e.get("at"), last_visit)]
    counts = {"new": 0, "amended": 0, "cancelled": 0}
    for e in rows:
        if e.get("event") in counts:
            counts[e["event"]] += 1
    return {"since": since, "from": last_visit if since == "visit" else (v or {}).get("dataDate") or (v or {}).get("businessDate"),
            "available": bool(b.get("available")), "reason": b.get("reason"), "counts": counts, "trades": rows[:12],
            "untimed": since == "visit" and b.get("how") == "valuations",
            "pnl": (v or {}).get("pnl"), "previousDate": (v or {}).get("previousDate"),
            "movers": [{"trade": m.get("trade"), "book": m.get("book"), "change": m.get("change"), "product": m.get("product")}
                       for m in ((v or {}).get("movers") or [])[:5]]}


def feed(b: dict, bk: list[dict], approvals: dict | None, alerts: list | None, business: str | None) -> list[dict]:
    """The live feed (pure), newest first: the day's trades of the books, limits breached or approached (each book's own), the approvals
    waiting for the person and the alerts raised for them. Each item has an id, so the page tells a new item from one it has drawn."""
    out = []
    for e in (b or {}).get("rows") or []:
        out.append({"id": f"trade:{e.get('trade')}:{e.get('event')}:{e.get('version') or ''}", "kind": "trade", "at": e.get("at") or "",
                    "time": e.get("time") or "", "event": e.get("event"), "trade": e.get("trade"), "book": e.get("book"),
                    "text": f"{e.get('trade')} {e.get('event')} · {e.get('book') or ''}" + (f" · {e['counterparty']}" if e.get("counterparty") else ""),
                    "href": f"/go?q={e.get('trade')}%20%3CGO%3E" if e.get("trade") else None})
    for row in bk:
        if row["state"] in ("breach", "near"):
            out.append({"id": f"limit:{row['book']}:{row['state']}", "kind": "limit", "at": f"{business or ''}", "time": "", "book": row["book"],
                        "state": row["state"], "limitUse": row["limitUse"],
                        "text": f"{row['book']} {'over' if row['state'] == 'breach' else 'near'} its VaR limit", "href": f"/var?scope=BOOK:{row['book']}"})
    for it in ((approvals or {}).get("items") or [])[:10]:
        i = it.get("item") or {}
        out.append({"id": f"approval:{i.get('kind')}:{i.get('id')}", "kind": "approval", "at": str(i.get("at") or i.get("created") or ""),
                    "time": str(i.get("at") or i.get("created") or "")[11:16], "text": f"{it.get('label') or i.get('kind')} waiting for you"
                    + (f": {i.get('title')}" if i.get("title") else ""), "href": f"/approvals/{i.get('kind')}/{i.get('id')}", "due": bool(it.get("dueSoon"))})
    for a in (alerts or [])[:10]:
        if not isinstance(a, dict):
            continue
        out.append({"id": f"alert:{a.get('id')}", "kind": "alert", "at": str(a.get("raisedAt") or ""), "time": str(a.get("raisedAt") or "")[11:16],
                    "severity": a.get("severity"), "text": str(a.get("message") or a.get("title") or "an alert"), "href": a.get("link") or "/alerts"})
    out.sort(key=lambda x: str(x.get("at") or ""), reverse=True)
    return out[:FEED_MAX]


async def home(backend, me, scope: tl.Scope, cfg: dict, *, since: str = "morning", last_visit: str | None = None,
               market_key: tuple | None = None, markets_on: bool = True, fresh: bool = False) -> dict:
    """The whole home for one person (the module's docstring). ``fresh``: the page heard the server's tick, so what was kept for this
    person (and only for them) is read again."""
    if fresh:
        tl.forget_person(me)
        market_landing.forget_person(me)
    if not scope.kind:
        return {"available": False, "reason": "pick a trader or a desk to view as", "scope": scope.as_dict()}
    since = since if since in SINCE else "morning"
    problems: list[str] = []
    v, r, appr_n, appr, alerts, mk = await asyncio.gather(
        _try(tl.valuations(backend, me, scope, cfg), problems, "P&L"),
        _try(tl.risk(backend, me, scope, cfg), problems, "VaR"),
        _try(backend.approvals_count(me), problems, "approvals"),
        _try(backend.approvals_list(me, "inbox"), problems, "approvals"),
        _try(backend.raised_alerts(me, "open"), problems, "alerts"),
        _try(market_landing.desk(backend, me, market_key), problems, "markets") if markets_on else asyncio.sleep(0, None))
    b = await _try(tl.blotter(backend, me, scope, cfg), problems, "trades")
    v, r = v or {}, r or {}
    if v and not v.get("available") and v.get("reason"):
        problems.append(f"P&L: {v['reason']}")
    if r and not r.get("available") and r.get("reason"):
        problems.append(f"VaR: {r['reason']}")
    rows = books(scope, v, r)
    business = v.get("businessDate") or r.get("businessDate")
    out = {
        "available": True, "scope": scope.as_dict(), "businessDate": business, "dataDate": v.get("dataDate"), "near": tl.near_limit(cfg),
        "headline": headline(v, r, appr_n), "books": rows,
        "markets": None if mk is None else {"tiles": mk.get("tiles") or [], "dataDate": mk.get("dataDate"), "problems": mk.get("problems") or []},
        "changes": changed(v, b or {}, since, last_visit), "lastVisit": last_visit,
        "feed": feed(b or {}, rows, appr, alerts if isinstance(alerts, list) else None, business),
        "problems": problems, "at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    return tl.public(out)
