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

"""Drishti Trading's Home (docs/architecture/EDITIONS.md section 14): who "my" is, and the figures of the Trader desk and Desk head
landings, worked out here from the servers' own answers, read as the person (their roles, masks, lens and date box), read-only.

**"My" scope.** A person maps to a trader (their books) or a desk: first ``trading.people`` (the console's own configuration, then the
edition file), then a trader record whose ``TRADER_ID`` is ``TRDR-<USER>`` or whose ``NAME`` is theirs (``trading.match-records``), then
their own "View as" choice, kept in their settings (``tradingView``: ``trader:<id>`` or ``desk:<id>``). An administrator may always pick,
and their choice wins. The scope only chooses what the landing shows: every read is still the person's own.

**The figures**, each from an existing answer, never invented (a figure the data cannot give is None, or the panel says why):

* P&L today: the MtM change of every trade valued on both the business date and the business day before (``TVAL``, the trade valuations,
  read on both dates), summed over the scope; no valuations the day before: None, said;
* VaR and limit use: the VaR engine's own answers (``/api/v1/var/scope`` of ``BOOK:`` and ``DESK:`` scopes, with their ``var-limit``),
  intraday when the engine applied updates (its ``intraday`` label);
* DV01: the valuations' ``DV01`` of the live trades, when any holds a number;
* today's trades: the trades (``TRD``) whose ``SOURCE_UPDATE_TIME`` is on the business date, new (``TRADE_ACTION`` NEW, or booked that
  day), amended or cancelled (``TRADE_STATUS``);
* the desk's limit use by book over the last business dates: each date's official VaR record (``/api/v1/var/official``); P&L against VaR:
  the desk's official history (``/api/v1/var/scope/history``, mode ``official``: the hypothetical P&L against the VaR before it);
* by trader: P&L and trades from the valuations' ``TRADER_ID`` (masked for a role without raw: then said), VaR as the sum of the trader's
  trades' component VaR in the desk (``/scope/contributions?by=trade``, which adds up to the desk's VaR);
* the new-trade check: one VaR what-if (``POST /api/v1/var/what-if``) and one pre-deal exposure check (``POST /api/v1/exposure/what-if``),
  each the engine's own, on a copy of a booked trade of the product, scaled to the notional (no new risk maths here).
"""
from __future__ import annotations

import asyncio
import math
import re
import time
from dataclasses import asdict, dataclass, field, replace
from datetime import date, timedelta
from typing import Any

from core import asof
from core.backend import BackendError

LIVE = ("NEW", "VALIDATED", "AMENDED")
VIEW = re.compile(r"^(trader|desk):([A-Za-z0-9][A-Za-z0-9._~-]{0,79})$")
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._~-]{0,79}$")
PAGE = 1000                                   # the server's most rows per search (SearchQuery.MAX_LIMIT)
#: the valuation fields read per trade (all of them columns of the trade-valuation store, so the search reads no document)
FIELDS = ("TRADE_ID", "PORTFOLIO", "DESK", "COUNTERPARTY", "PRODUCT_TYPE", "PRODUCT_GROUP", "TRADE_STATUS", "TRADER_ID", "BUY_SELL_CODE",
          "NOTIONAL", "DV01", "CURRENCY")
_CACHE: dict[tuple, tuple[float, Any]] = {}
_FLIGHT: dict[tuple, asyncio.Future] = {}
_TTL = 120.0


# -- configuration ----------------------------------------------------------------------------------------------------------------
def config(ed, settings=None) -> dict:
    """The edition's ``trading:`` block with the console's own ``trading.people`` over its people (a name in both: the console's)."""
    block = dict(ed._get("trading") or {}) if ed is not None and ed.active else {}
    people = {str(k).lower(): v for k, v in (block.get("people") or {}).items() if isinstance(v, dict)}
    site = settings.get("trading.people") if settings is not None else None
    if isinstance(site, dict):
        people.update({str(k).lower(): v for k, v in site.items() if isinstance(v, dict)})
    block["people"] = people
    return block


def _num(v: Any) -> float | None:
    if isinstance(v, bool) or v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def _share(cfg: dict, key: str, default: float) -> float:
    v = _num(cfg.get(key))
    return default if v is None or v <= 0 else v


def near_limit(cfg: dict) -> float:
    """``trading.near-limit``: the share of a limit from which a book is "near" it (0.8)."""
    return _share(cfg, "near-limit", 0.8)


def _count(cfg: dict, key: str, default: int, lo: int, hi: int) -> int:
    v = _num(cfg.get(key))
    return default if v is None else max(lo, min(int(v), hi))


def masked(v: Any) -> bool:
    return isinstance(v, str) and "•" in v


def _q(s: str) -> str:
    """A text value for the search grammar (single-quoted, a quote doubled)."""
    return "'" + str(s).replace("'", "''") + "'"


# -- "my" scope ---------------------------------------------------------------------------------------------------------------------
@dataclass
class Scope:
    kind: str = ""                   # trader | desk | "" (nobody yet: the View as picker)
    id: str = ""
    role: str = ""                   # trader | desk-head
    source: str = "none"             # config | record | choice | none
    name: str = ""
    desk: str = ""
    desk_name: str = ""
    head: str = ""
    books: list = field(default_factory=list)
    can_pick: bool = False
    problems: list = field(default_factory=list)

    def as_dict(self) -> dict:
        names = {"desk_name": "deskName", "can_pick": "canPick"}
        return {names.get(k, k): v for k, v in asdict(self).items()}


def parse_view(value: Any) -> tuple[str, str] | None:
    m = VIEW.match(str(value or "").strip())
    return (m.group(1), m.group(2)) if m else None


def _mapping(entry: dict) -> tuple[str, str, str] | None:
    """A ``trading.people`` entry as (kind, id, role): ``{trader: TRDR-ASHAH}`` or ``{desk: DESK-COMM, role: desk-head}``."""
    role = str(entry.get("role") or "").strip().lower()
    for kind in ("trader", "desk"):
        v = str(entry.get(kind) or "").strip()
        if v and SAFE_ID.match(v):
            return kind, v, role or ("desk-head" if kind == "desk" else "trader")
    return None


def trading_role(ed, settings, me, user_settings: dict | None) -> str:
    """The person's trading role without reading the data (for which landing opens first): their mapping's, else what their View as
    choice names (a desk: desk-head), else ''. A person matched by a trader record is a trader: the default landing already."""
    if me is None or ed is None or not ed.active:
        return ""
    cfg = config(ed, settings)
    entry = cfg["people"].get(str(me.user).lower())
    mapped = _mapping(entry) if entry else None
    view = parse_view((user_settings or {}).get("tradingView"))
    if view and (mapped is None or me.is_admin):
        return "desk-head" if view[0] == "desk" else "trader"
    return mapped[2] if mapped else ""


async def _record_match(backend, me) -> str | None:
    """A trader record that is this person's: ``TRDR-<USER>`` (case and dots ignored), or the same name as their display name."""
    user = re.sub(r"[^A-Z0-9]", "", str(me.user).upper())
    display = str(getattr(me, "display", "") or "").strip().lower()
    try:
        found = await backend.search("TRDR limit 500", me)
    except BackendError:
        return None
    for r in (found or {}).get("rows") or []:
        rid = str((r.get("ref") or {}).get("id") or "")
        if rid and re.sub(r"[^A-Z0-9]", "", rid.upper()) in ("TRDR" + user, user):
            return rid
    for r in (found or {}).get("rows") or []:
        rid = str((r.get("ref") or {}).get("id") or "")
        name = str(r.get("title") or (r.get("values") or {}).get("$.NAME") or "").strip().lower()
        if rid and display and name == display:
            return rid
    return None


async def resolve(backend, me, ed, settings, user_settings: dict | None) -> Scope:
    """Who "my" is for this person (the module's docstring), with the trader's or desk's books read from its record as the person."""
    if me is None:
        return Scope()
    cfg = config(ed, settings)
    entry = cfg["people"].get(str(me.user).lower())
    mapped = _mapping(entry) if entry else None
    view = parse_view((user_settings or {}).get("tradingView"))
    key = ("scope", me.user, ed.name if ed is not None else "", str(mapped), str(view), asof.current())
    hit = _cached(key)
    if hit is not None:
        return replace(hit, books=list(hit.books), problems=list(hit.problems))
    chosen: tuple[str, str, str, str] | None = None
    if view and me.is_admin:
        chosen = (view[0], view[1], "desk-head" if view[0] == "desk" else "trader", "choice")
    elif mapped:
        chosen = (*mapped, "config")
    elif cfg.get("match-records", True) is not False and (rid := await _record_match(backend, me)):
        chosen = ("trader", rid, "trader", "record")
    elif view:
        chosen = (view[0], view[1], "desk-head" if view[0] == "desk" else "trader", "choice")
    s = Scope(can_pick=me.is_admin)
    if chosen is None:
        s.can_pick = True
        _put(key, replace(s))
        return s
    s.kind, s.id, s.role, s.source = chosen
    s.can_pick = me.is_admin or s.source == "choice"
    try:
        doc = ((await backend.raw("trader" if s.kind == "trader" else "desk", s.id, me)) or {}).get("data") or {}
    except BackendError as e:
        s.problems.append(f"{s.kind} {s.id}: {e.code or e.status} {e.detail}")
        doc = {}
    s.name = str(doc.get("NAME") or "") if not masked(doc.get("NAME")) else ""
    s.books = [str(b.get("ID") if isinstance(b, dict) else b) for b in doc.get("BOOKS") or [] if (b.get("ID") if isinstance(b, dict) else b)]
    s.books = [b for b in s.books if SAFE_ID.match(b)]
    if s.kind == "trader":
        s.desk = str(doc.get("DESK") or "")
        s.desk_name = str(doc.get("DESK_NAME") or "")
    else:
        s.desk, s.desk_name, s.head = s.id, s.name, str(doc.get("HEAD") or "")
    _put(key, replace(s, books=list(s.books), problems=list(s.problems)))
    return s


async def desk_of(backend, me, scope: Scope) -> Scope:
    """The Desk head landing of a trader: their desk (its record's books, read as the person); a desk stays itself."""
    if scope.kind != "trader" or not SAFE_ID.match(scope.desk or "-"):
        return scope
    out = replace(scope, kind="desk", id=scope.desk, books=[], problems=list(scope.problems))
    try:
        doc = ((await backend.raw("desk", scope.desk, me)) or {}).get("data") or {}
    except BackendError as e:
        out.problems.append(f"desk {scope.desk}: {e.code or e.status} {e.detail}")
        doc = {}
    out.books = [str(b.get("ID")) for b in doc.get("BOOKS") or [] if isinstance(b, dict) and SAFE_ID.match(str(b.get("ID") or "-"))]
    out.desk_name, out.head = str(doc.get("NAME") or scope.desk_name), str(doc.get("HEAD") or "")
    return out


async def options(backend, me) -> dict:
    """What the View as picker offers: the traders and desks this person may read (their search, as them)."""
    out: dict = {"traders": [], "desks": []}
    for word, kind in (("TRDR", "traders"), ("DESK", "desks")):
        try:
            a = await backend.search(f"{word} where NAME != null limit 200", me)   # NAME read: the picker shows names
        except BackendError:
            continue
        for r in (a or {}).get("rows") or []:
            rid = str((r.get("ref") or {}).get("id") or "")
            if SAFE_ID.match(rid):
                name = (r.get("values") or {}).get("$.NAME")
                out[kind].append({"id": rid, "name": str(name if name and not masked(name) else r.get("title") or rid)})
    return out


# -- caching ------------------------------------------------------------------------------------------------------------------------
def _cached(key: tuple) -> Any:
    hit = _CACHE.get(key)
    if hit and time.monotonic() - hit[0] < _TTL:
        return hit[1]
    return None


def _put(key: tuple, value: Any) -> Any:
    if len(_CACHE) > 64:                     # a scope's valuations may be large: few kept, two minutes each
        _CACHE.clear()
    _CACHE[key] = (time.monotonic(), value)
    return value


async def _once(key: tuple, make):
    """One read per key at a time (two panels asking for the same valuations wait for one answer), kept for two minutes."""
    hit = _cached(key)
    if hit is not None:
        return hit
    running = _FLIGHT.get(key)
    if running is not None:
        return await asyncio.shield(running)
    fut = asyncio.get_running_loop().create_future()
    _FLIGHT[key] = fut
    try:
        value = await make()
        _put(key, value)
        fut.set_result(value)
        return value
    except BaseException as e:
        fut.set_exception(e)
        fut.exception()                       # retrieved: a failure nobody else waited for is not logged as unhandled
        raise
    finally:
        _FLIGHT.pop(key, None)


def forget() -> None:
    """Drops what was kept (tests)."""
    _CACHE.clear()


# -- dates --------------------------------------------------------------------------------------------------------------------------
def _day(v: Any) -> date | None:
    try:
        return date.fromisoformat(str(v)[:10])
    except (TypeError, ValueError):
        return None


def previous_business_day(d: date, holidays: set[str]) -> date:
    d -= timedelta(days=1)
    while d.weekday() >= 5 or d.isoformat() in holidays:
        d -= timedelta(days=1)
    return d


def business_days(end: date, n: int, holidays: set[str]) -> list[date]:
    """The last ``n`` business days up to ``end`` (itself first when it is one), oldest first."""
    out, d = [], end
    while len(out) < n:
        if d.weekday() < 5 and d.isoformat() not in holidays:
            out.append(d)
        d -= timedelta(days=1)
    return out[::-1]


async def dates(backend, me) -> tuple[date | None, set[str]]:
    """The business date the person looks at (their date box, else the current) and the server's holidays."""
    try:
        bd = await backend.business_date(me)
    except BackendError:
        return None, set()
    return _day(bd.get("selected") or bd.get("current")), {str(h) for h in bd.get("holidays") or []}


async def on_date(day: date, make):
    """``make()`` as of another business date: the request's date box set in a task of its own (the request's own is untouched)."""
    async def run():
        asof.set_current(day.isoformat())
        return await make()
    return await asyncio.create_task(run())


# -- the trade valuations of a scope on one date --------------------------------------------------------------------------------------
def scope_condition(scope: Scope) -> str | None:
    """The search condition of a scope: its desk (``DESK``), or a trader's books (``PORTFOLIO``)."""
    if scope.kind == "desk":
        return f"DESK = {_q(scope.id)}"
    if scope.books:
        return "(" + " or ".join(f"PORTFOLIO = {_q(b)}" for b in scope.books) + ")"
    return None


async def read_valuations(backend, me, condition: str, max_rows: int) -> dict:
    """Every trade valuation matching ``condition`` on the request's business date, a page of the server's most at a time (by MTM,
    largest first; each next page from the last MTM down, ids already read skipped): ``{rows: {trade id: fields}, matched, read, partial,
    noData}``. A role that sees MTM masked gets rows without it."""
    names = " or ".join(f"{f} != null" for f in FIELDS)
    base = f"TVAL where {condition} and ({names})"
    rows: dict[str, dict] = {}
    matched, partial, floor, no_data = None, False, None, None
    while True:
        q = base + (f" and MTM <= {floor!r}" if floor is not None else "") + f" order by MTM desc limit {PAGE}"
        a = await backend.search(q, me)
        if matched is None:
            matched = int(a.get("matched") or 0)
            no_data = a.get("noData")
        partial = partial or bool(a.get("partial"))
        new, last = 0, None
        for r in a.get("rows") or []:
            vals = r.get("values") or {}
            tid = str(vals.get("$.TRADE_ID") or (r.get("ref") or {}).get("id") or "")
            if not tid or tid in rows:
                continue
            rec = {f: vals.get("$." + f) for f in FIELDS}
            rec["MTM"] = vals.get("$.MTM")
            rec["VALUATION_ID"] = str((r.get("ref") or {}).get("id") or "")
            rows[tid] = rec
            new += 1
            if _num(rec["MTM"]) is not None:
                last = _num(rec["MTM"])
        if len(rows) >= matched or len(a.get("rows") or []) < PAGE or new == 0 or last is None:
            break
        if len(rows) >= max_rows:
            partial = True
            break
        floor = last
    return {"rows": rows, "matched": matched or 0, "read": len(rows), "partial": partial or len(rows) < (matched or 0), "noData": no_data}


async def valuations(backend, me, scope: Scope, cfg: dict) -> dict:
    """The scope's trade valuations on the business date and the business day before, and what they give (the P&L tiles, by book and
    trader, overnight movers, DV01, the largest counterparty share), kept two minutes per person, date and scope."""
    cond = scope_condition(scope)
    business, holidays = await dates(backend, me)
    if cond is None or business is None:
        return {"available": False, "reason": "no books in your scope" if cond is None else "the server gave no business date"}
    max_rows = _count(cfg, "max-rows", 20000, PAGE, 200000)
    key = ("tval", me.user, scope.kind, scope.id, ",".join(scope.books), business.isoformat())

    async def make():
        now = await read_valuations(backend, me, cond, max_rows)
        # the day the valuations are really of (a lake not loaded since serves its latest day on later dates): its day before is compared
        data_day = await data_date(backend, me, now) or business
        before = previous_business_day(data_day, holidays)
        was: Any = None
        try:
            was = await on_date(before, lambda: read_valuations(backend, me, cond, max_rows))
            was_day = await on_date(before, lambda: data_date(backend, me, was))
            if was_day is not None and was_day != before:          # carried from an earlier day: no valuations of that day
                was = {**was, "rows": {}, "noData": {"businessDate": before.isoformat(), "latestDate": was_day.isoformat()}}
        except BackendError as e:
            was = e
        out = summarise(now, None if isinstance(was, BaseException) else was, data_day, before, tuple(cfg.get("live-statuses") or LIVE),
                        str(was) if isinstance(was, BackendError) else None)
        out["businessDate"], out["dataDate"] = business.isoformat(), data_day.isoformat()
        out["__rows"] = now["rows"]                   # kept for the other panels (the blotter's notionals, the check, by trader): never sent
        if out["hasPrevious"]:
            out["__changes"] = changes(now["rows"], was["rows"], data_day)
        return out

    try:
        return await _once(key, make)
    except BackendError as e:
        return {"available": False, "reason": f"{e.code or e.status} {e.detail}"}


async def data_date(backend, me, read: dict) -> date | None:
    """The day a read's valuations are of: one of them read whole, its provenance's business date (None when there is none to read)."""
    rows = (read or {}).get("rows") or {}
    first = next(iter(rows.values()), None)
    if not first or not first.get("VALUATION_ID"):
        return None
    try:
        doc = await backend.raw("trade-valuation", first["VALUATION_ID"], me)
    except BackendError:
        return None
    return _day(((doc or {}).get("provenance") or {}).get("businessDate"))


def summarise(now: dict, was: dict | None, business: date, before: date, live: tuple, was_problem: str | None = None) -> dict:
    """The figures of two dates' valuations (pure: tested on its own)."""
    rows, old = now["rows"], (was or {}).get("rows") or {}
    had_before = bool(was) and not (was or {}).get("noData") and bool(old)
    live = tuple(str(s).upper() for s in live)
    pnl, by_book, by_trader, movers = 0.0, {}, {}, []
    trader_masked = any(masked(r.get("TRADER_ID")) for r in rows.values())
    mtm_masked = any(masked(r.get("MTM")) for r in rows.values())
    both = 0
    for tid, r in rows.items():
        m1, p = _num(r.get("MTM")), old.get(tid)
        m0 = _num(p.get("MTM")) if p else None
        book, trader = str(r.get("PORTFOLIO") or ""), r.get("TRADER_ID")
        live_now = str(r.get("TRADE_STATUS") or "").upper() in live
        b = by_book.setdefault(book, {"book": book, "pnl": 0.0, "trades": 0, "mtm": 0.0})
        t = by_trader.setdefault(str(trader), {"trader": str(trader), "pnl": 0.0, "trades": 0}) if trader and not masked(trader) else None
        if live_now:
            b["trades"] += 1
            if t is not None:
                t["trades"] += 1
        if m1 is not None:
            b["mtm"] += m1
        if m1 is not None and m0 is not None:
            d = m1 - m0
            both += 1
            pnl += d
            b["pnl"] += d
            if t is not None:
                t["pnl"] += d
            if d:
                movers.append({"trade": tid, "book": book, "product": r.get("PRODUCT_TYPE"), "counterparty": r.get("COUNTERPARTY"),
                               "change": round(d), "mtm": round(m1), "valuation": r.get("VALUATION_ID")})
    movers.sort(key=lambda x: -abs(x["change"]))
    live_rows = [r for r in rows.values() if str(r.get("TRADE_STATUS") or "").upper() in live]
    dv01s = [_num(r.get("DV01")) for r in live_rows if _num(r.get("DV01")) is not None]
    positive: dict[str, float] = {}
    for r in live_rows:
        m = _num(r.get("MTM"))
        cp = r.get("COUNTERPARTY")
        if m is not None and m > 0 and cp and not masked(cp):
            positive[str(cp)] = positive.get(str(cp), 0.0) + m
    total_pos = sum(positive.values())
    products: dict[str, int] = {}
    cps: dict[str, int] = {}
    for r in live_rows:
        if r.get("PRODUCT_TYPE") and _num(r.get("NOTIONAL")):
            products[str(r["PRODUCT_TYPE"])] = products.get(str(r["PRODUCT_TYPE"]), 0) + 1
        if r.get("COUNTERPARTY") and not masked(r.get("COUNTERPARTY")):
            cps[str(r["COUNTERPARTY"])] = cps.get(str(r["COUNTERPARTY"]), 0) + 1
    top_cp = max(positive.items(), key=lambda kv: kv[1]) if positive else None
    return {
        "available": True, "businessDate": business.isoformat(), "previousDate": before.isoformat(), "hasPrevious": had_before,
        "previousProblem": was_problem, "trades": len(rows), "liveTrades": len(live_rows), "valuedBoth": both,
        "matched": now["matched"], "partial": bool(now["partial"] or (was or {}).get("partial")),
        "pnl": round(pnl) if had_before and both and not mtm_masked else None,
        "mtm": round(sum(_num(r.get("MTM")) or 0 for r in rows.values())) if not mtm_masked else None,
        "mtmMasked": mtm_masked, "traderMasked": trader_masked,
        "dv01": round(sum(dv01s)) if dv01s else None, "dv01Trades": len(dv01s),
        "byBook": sorted(({**b, "pnl": round(b["pnl"]) if had_before else None, "mtm": round(b["mtm"])} for b in by_book.values() if b["book"]),
                         key=lambda b: b["book"]),
        "byTrader": sorted(({**t, "pnl": round(t["pnl"]) if had_before else None} for t in by_trader.values()), key=lambda t: t["trader"]),
        "movers": movers[:10] if had_before else [],
        "products": [k for k, _ in sorted(products.items(), key=lambda kv: (-kv[1], kv[0]))][:40],
        "counterparties": [k for k, _ in sorted(cps.items(), key=lambda kv: (-kv[1], kv[0]))][:60],
        "largestCounterparty": ({"counterparty": top_cp[0], "share": top_cp[1] / total_pos, "positiveMtm": round(top_cp[1]),
                                 "totalPositiveMtm": round(total_pos)} if top_cp and total_pos > 0 else None),
    }


# -- today's trades -----------------------------------------------------------------------------------------------------------------
async def blotter(backend, me, scope: Scope, cfg: dict) -> dict:
    """The scope's trades touched on the day its valuations are of (``SOURCE_UPDATE_TIME``; the business date unless the lake's latest
    day is earlier), newest first, each new, amended or cancelled."""
    business, _h = await dates(backend, me)
    books = scope.books
    if not books or business is None:
        return {"available": False, "reason": "no books in your scope" if not books else "the server gave no business date"}
    tv = await valuations(backend, me, scope, cfg)              # the same read as the tiles (one at a time): the day, each notional
    business = _day(tv.get("dataDate")) or business
    cond = "(" + " or ".join(f"PORTFOLIO = {_q(b)}" for b in books) + ")"
    q = (f"TRD where {cond} and SOURCE_UPDATE_TIME startswith {_q(business.isoformat())} and (TRADE_ACTION != null or TRADE_VERSION != null "
         f"or COUNTERPARTY != null or PRODUCT_TYPE != null or TRADE_STATUS != null or TRADE_DATE != null) order by SOURCE_UPDATE_TIME desc "
         f"limit {PAGE}")
    key = ("blotter", me.user, scope.kind, scope.id, ",".join(books), business.isoformat())

    async def make():
        try:
            return {**events(await backend.search(q, me), business), "how": "trades"}
        except BackendError as e:              # the trades' events read every trade document: too slow on a large lake (kept two minutes)
            changed = tv.get("__changes")
            if changed is None:
                return {"available": False, "reason": f"{e.code or e.status} {e.detail}"}
            return {**changed, "how": "valuations", "note": f"from the valuations' status against {tv.get('previousDate')} (the trades "
                                                            f"answered {e.code or e.status}): no time of day"}

    out = await _once(key, make)
    if not out.get("available"):
        return out
    out = {**out, "rows": [dict(e) for e in out["rows"]]}
    known = tv.get("__rows") or {}
    for e in out["rows"]:
        r = known.get(e["trade"])
        e["notional"] = _num(r.get("NOTIONAL")) if r else None
    return out


def changes(rows: dict, old: dict, day: date) -> dict:
    """The day's events from two days' valuations (pure), when the trades cannot say: ``new`` (valued that day, not the day before),
    ``cancelled`` (its status became CANCELLED), ``amended`` (its status became AMENDED); no time of day."""
    out, counts = [], {"new": 0, "amended": 0, "cancelled": 0}
    for tid, r in rows.items():
        was = old.get(tid)
        status = str(r.get("TRADE_STATUS") or "").upper()
        before = str((was or {}).get("TRADE_STATUS") or "").upper()
        what = "new" if was is None else "cancelled" if status == "CANCELLED" != before else "amended" if status == "AMENDED" != before else None
        if what:
            counts[what] += 1
            out.append({"trade": tid, "event": what, "at": None, "time": "", "book": r.get("PORTFOLIO"), "counterparty": r.get("COUNTERPARTY"),
                        "product": r.get("PRODUCT_TYPE"), "version": None, "status": r.get("TRADE_STATUS")})
    out.sort(key=lambda e: -abs(_num(rows[e["trade"]].get("NOTIONAL")) or 0))
    return {"available": True, "businessDate": day.isoformat(), "counts": counts, "total": len(out), "matched": len(out), "partial": False,
            "rows": out[:50]}


def events(answer: dict, business: date) -> dict:
    """The day's trade events (pure): ``cancelled`` (status CANCELLED), ``new`` (TRADE_ACTION NEW, or booked on the day), else
    ``amended``; with counts, and ``partial`` when the server matched more than it returned."""
    rows, counts = [], {"new": 0, "amended": 0, "cancelled": 0}
    for r in answer.get("rows") or []:
        v = r.get("values") or {}
        status, action = str(v.get("$.TRADE_STATUS") or "").upper(), str(v.get("$.TRADE_ACTION") or "").upper()
        what = "cancelled" if status == "CANCELLED" else "new" if action == "NEW" or str(v.get("$.TRADE_DATE") or "")[:10] == business.isoformat() \
            else "amended"
        counts[what] += 1
        when = str(v.get("$.SOURCE_UPDATE_TIME") or "")
        rows.append({"trade": str((r.get("ref") or {}).get("id") or ""), "event": what, "at": when, "time": when[11:16],
                     "book": v.get("$.PORTFOLIO"), "counterparty": v.get("$.COUNTERPARTY"), "product": v.get("$.PRODUCT_TYPE"),
                     "version": v.get("$.TRADE_VERSION"), "status": v.get("$.TRADE_STATUS")})
    matched = int(answer.get("matched") or len(rows))
    return {"available": True, "businessDate": business.isoformat(), "counts": counts, "total": len(rows), "matched": matched,
            "partial": matched > len(rows) or bool(answer.get("partial")), "rows": rows[:50]}


# -- VaR and limits -----------------------------------------------------------------------------------------------------------------
def limit_state(use: float | None, near: float) -> str:
    """``breach`` above the limit, ``near`` at or above the near-limit share, ``ok`` below; ``none`` without a limit."""
    if use is None:
        return "none"
    return "breach" if use > 1 else "near" if use >= near else "ok"


async def _ask(fn, *args, **params) -> tuple[Any, str | None, str | None]:
    """An engine's answer as the person: (answer, None, None), or (None, why, the error code)."""
    try:
        return await fn(*args, **params), None, None
    except BackendError as e:
        return None, f"{e.code or e.status} {e.detail}", e.code


def var_scope(scope: Scope) -> str | None:
    """The VaR engine's scope of "mine": the desk's node, or the union of the trader's books."""
    if scope.kind == "desk":
        return f"DESK:{scope.id}"
    return " + ".join(f"BOOK:{b}" for b in scope.books) or None


async def risk(backend, me, scope: Scope, cfg: dict) -> dict:
    """The scope's VaR (intraday when the engine applied updates), each book's VaR against its limit, the worst book, breaches and books
    near their limit, and the largest positions by VaR contribution: the VaR engine's own answers."""
    text = var_scope(scope)
    if text is None:
        return {"available": False, "reason": "no books in your scope"}
    near = _share(cfg, "near-limit", 0.8)
    head_t = asyncio.create_task(_ask(backend.var, "GET", "/scope", me, scope=text))
    book_ts = [asyncio.create_task(_ask(backend.var, "GET", "/scope", me, scope=f"BOOK:{b}")) for b in scope.books]
    parts_t = asyncio.create_task(_ask(backend.var, "GET", "/scope/contributions", me, scope=text, by="position"))
    head, why, _c = await head_t
    books, problems = [], []
    for b, t in zip(scope.books, book_ts):
        a, w, _c2 = await t
        if a is None:
            problems.append(f"BOOK:{b}: {w}")
            books.append({"book": b, "var": None, "limit": None, "limitUse": None, "state": "none"})
            continue
        use = _num(a.get("limitUse"))
        books.append({"book": b, "var": _num(a.get("var")), "limit": _num(a.get("limit")), "limitUse": use, "state": limit_state(use, near),
                      "trades": a.get("trades")})
    parts, pwhy, _c3 = await parts_t
    if head is None:
        return {"available": False, "reason": why, "books": books, "problems": problems}
    limited = [b for b in books if b["limitUse"] is not None]
    worst = max(limited, key=lambda b: b["limitUse"]) if limited else None
    positions = []
    for r in ((parts or {}).get("rows") or [])[:6]:
        positions.append({"label": r.get("label") or r.get("key"), "scope": r.get("scope"), "componentVar": _num(r.get("componentVar")),
                          "marketValue": _num(r.get("marketValue")), "pct": _num(r.get("pct")), "book": r.get("book"),
                          "productType": r.get("productType")})
    intraday = head.get("intraday") if isinstance(head.get("intraday"), dict) else None
    use = _num(head.get("limitUse"))
    return {"available": True, "scope": text, "businessDate": head.get("businessDate"), "var": _num(head.get("var")), "es": _num(head.get("es")),
            "confidence": head.get("confidence"), "currency": head.get("currency") or "USD", "trades": head.get("trades"),
            "limit": _num(head.get("limit")), "limitUse": use, "state": limit_state(use, near), "near": near,
            "intraday": {"trades": intraday.get("trades"), "lastAt": intraday.get("lastAt"), "updates": intraday.get("updates")} if intraday else None,
            "books": books, "worst": worst, "breaches": [b for b in books if b["state"] == "breach"],
            "nearLimit": [b for b in books if b["state"] == "near"], "positions": positions, "positionsProblem": pwhy, "problems": problems}


def _node(nodes: dict, book: str) -> dict | None:
    for nid in (f"MGMT~{book}", f"MGMT~BOOK-{book}"):
        if isinstance(nodes.get(nid), dict):
            return nodes[nid]
    return None


async def limits(backend, me, scope: Scope, cfg: dict) -> dict:
    """Each book's VaR ÷ limit over the last business dates, from each date's official VaR record (the numbers of record)."""
    business, holidays = await dates(backend, me)
    if business is None or not scope.books:
        return {"available": False, "reason": "no books in your scope" if business else "the server gave no business date"}
    days = business_days(business, _count(cfg, "limit-days", 5, 1, 20), holidays)
    answers = await asyncio.gather(*(_ask(backend.var, "GET", "/official", me, asOf=d.isoformat()) for d in days))
    if all(a is None for a, _w, _c in answers):
        return {"available": False, "reason": answers[-1][1] or "no official record"}
    near = _share(cfg, "near-limit", 0.8)
    grid = []
    for b in scope.books:
        cells = []
        for d, (a, _w, _c) in zip(days, answers):
            n = _node((a or {}).get("nodes") or {}, b) if a else None
            use = _num(n.get("limitUse")) if n else None
            cells.append({"date": d.isoformat(), "var": _num(n.get("var")) if n else None, "limit": _num(n.get("limit")) if n else None,
                          "limitUse": use, "state": limit_state(use, near) if n else "missing",
                          "why": None if n else ("no official record of this date" if a is None else "the book is not a node of the record")})
        grid.append({"book": b, "cells": cells})
    return {"available": True, "dates": [d.isoformat() for d in days], "near": near, "books": grid,
            "missingDates": [d.isoformat() for d, (a, _w, _c) in zip(days, answers) if a is None]}


async def pnl_vs_var(backend, me, scope: Scope, cfg: dict) -> dict:
    """The desk's daily P&L against its VaR, from its official history (each day's hypothetical P&L against the VaR before it). A
    replayed history is scenario P&L, not a day's: then the card is left out and says why."""
    text = var_scope(scope) if scope.kind == "desk" else None
    if text is None:
        return {"available": False, "reason": "a desk's history: pick a desk"}
    h, why, _c = await _ask(backend.var, "GET", "/scope/history", me, scope=text)
    if h is None:
        return {"available": False, "reason": why}
    if str(h.get("mode") or "") != "official":
        return {"available": False, "reason": "the desk has no official end-of-day history on more than one day: a replay's scenario P&L "
                                               "is not a day's P&L, so there is nothing honest to draw"}
    n = _count(cfg, "pnl-var-days", 60, 5, 250)
    rows = [{"date": r.get("date"), "var": _num(r.get("var")), "pnl": _num(r.get("pnl")), "exception": bool(r.get("exception"))}
            for r in (h.get("rows") or [])[-n:] if r.get("date")]
    rows = [r for r in rows if r["var"] is not None and r["pnl"] is not None]
    if not rows:
        return {"available": False, "reason": "the official history holds no day with both a P&L and a VaR"}
    return {"available": True, "scope": text, "rows": rows, "days": len(rows), "exceptions": sum(r["exception"] for r in rows),
            "backtest": h.get("backtest"), "note": h.get("note")}


async def traders(backend, me, scope: Scope, cfg: dict) -> dict:
    """The desk by trader: each trader's P&L and live trades from the valuations' TRADER_ID, and their VaR as the sum of their trades'
    component VaR in the desk (it adds up to the desk's VaR). A role that sees TRADER_ID masked gets the trader records' counts only."""
    if scope.kind != "desk":
        return {"available": False, "reason": "by trader is a desk's: pick a desk"}
    listed, why, _c = await _ask(backend.search, f"TRDR where DESK = {_q(scope.id)} and (NAME != null or TRADE_COUNT != null) limit 200", me)
    tv_t = asyncio.create_task(valuations(backend, me, scope, cfg))
    parts_t = asyncio.create_task(_ask(backend.var, "GET", "/scope/contributions", me, scope=f"DESK:{scope.id}", by="trade"))
    tv = await tv_t
    parts, pwhy, _c2 = await parts_t
    rows: dict[str, dict] = {}
    for r in (listed or {}).get("rows") or []:
        tid = str((r.get("ref") or {}).get("id") or "")
        v = r.get("values") or {}
        if tid:
            rows[tid] = {"trader": tid, "name": str(v.get("$.NAME") or r.get("title") or tid), "pnl": None, "var": None, "trades": None,
                         "recordTrades": _num(v.get("$.TRADE_COUNT"))}
    masked_ids = bool(tv.get("traderMasked")) if tv.get("available") else False
    for t in tv.get("byTrader") or []:
        row = rows.setdefault(t["trader"], {"trader": t["trader"], "name": t["trader"], "var": None, "recordTrades": None})
        row.update({"pnl": t["pnl"], "trades": t["trades"]})
    var_note = pwhy
    if parts and not masked_ids:
        who = {tid: str(r.get("TRADER_ID") or "") for tid, r in (tv.get("__rows") or {}).items()}
        sums: dict[str, float] = {}
        for r in parts.get("rows") or []:
            key = str(r.get("key") or "").removeprefix("TRD:")
            t = who.get(key)
            if t and _num(r.get("componentVar")) is not None:
                sums[t] = sums.get(t, 0.0) + _num(r.get("componentVar"))
        for t, v in sums.items():
            rows.setdefault(t, {"trader": t, "name": t, "pnl": None, "trades": None, "recordTrades": None})["var"] = round(v)
        if parts.get("truncated"):
            var_note = f"the desk has {parts.get('total')} trades; VaR by trader counts the {len(parts.get('rows') or [])} largest"
    out = sorted(rows.values(), key=lambda r: (-(abs(r["pnl"]) if r.get("pnl") is not None else -1), r["trader"]))
    return {"available": bool(out), "reason": why if not out else None, "rows": out, "traderMasked": masked_ids,
            "hasPrevious": tv.get("hasPrevious"), "varNote": var_note,
            "note": "trader ids are masked for your role: P&L, VaR and trades by trader need a role that sees TRADER_ID" if masked_ids else None}


# -- the new-trade check -----------------------------------------------------------------------------------------------------------
def parse_amount(v: Any) -> float | None:
    """``250m``, ``1.5bn``, ``400k``, ``250,000,000`` or a number; None otherwise."""
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return float(v) if math.isfinite(v) else None
    m = re.fullmatch(r"\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(k|m|mm|bn|b)?\s*", str(v or "").lower())
    if not m:
        return None
    n = float(m.group(1).replace(",", ""))
    return n * {"k": 1e3, "m": 1e6, "mm": 1e6, "bn": 1e9, "b": 1e9}.get(m.group(2) or "", 1)


def template(rows: dict, product: str, counterparty: str, book: str | None, live: tuple) -> tuple[str, dict] | None:
    """The booked trade the check copies: a live trade of the product with a notional, the counterparty's first, then the book's, then
    the largest notional (pure)."""
    live = tuple(str(s).upper() for s in live)
    cands = [(tid, r) for tid, r in rows.items() if str(r.get("PRODUCT_TYPE") or "") == product and _num(r.get("NOTIONAL"))
             and str(r.get("TRADE_STATUS") or "").upper() in live]
    if not cands:
        return None
    cands.sort(key=lambda x: (str(x[1].get("COUNTERPARTY") or "").upper() != counterparty,
                              bool(book) and str(x[1].get("PORTFOLIO") or "") != book, -abs(_num(x[1].get("NOTIONAL")) or 0), x[0]))
    return cands[0]


async def check(backend, me, scope: Scope, cfg: dict, body: dict) -> dict:
    """Does a new trade fit? One VaR what-if on the book and one pre-deal exposure check on the counterparty, each the engine's own,
    on a copy of a booked trade of the product scaled to the notional (factor = notional ÷ its notional, negative for the other side)."""
    product = str(body.get("product") or "").strip()
    cp = str(body.get("counterparty") or "").strip().upper()
    notional = parse_amount(body.get("notional"))
    side = str(body.get("side") or "").strip().upper()[:1]
    if not product or not notional or notional <= 0 or not SAFE_ID.match(cp or "-"):
        return {"ok": False, "reason": "name the product, a notional (250m, 1.5bn) and the counterparty (CP-…)"}
    tv = await valuations(backend, me, scope, cfg)
    if not tv.get("available"):
        return {"ok": False, "reason": tv.get("reason") or "no valuations in your scope"}
    book = str(body.get("book") or "").strip() or None
    if book and book not in scope.books:
        book = None
    pick = template(tv.get("__rows") or {}, product, cp, book, tuple(cfg.get("live-statuses") or LIVE))
    if pick is None:
        return {"ok": False, "reason": f"no live trade of {product} with a notional in your books to price from: the check copies a booked trade"}
    tid, t = pick
    base_notional = abs(_num(t.get("NOTIONAL")) or 0)
    own = str(t.get("BUY_SELL_CODE") or "").upper()[:1]
    sign = -1.0 if side in ("B", "S") and own in ("B", "S") and side != own else 1.0
    factor = sign * notional / base_notional
    book = book or (str(t.get("PORTFOLIO")) if str(t.get("PORTFOLIO")) in scope.books else (scope.books or [None])[0])
    near = _share(cfg, "near-limit", 0.8)
    label = f"{product} {notional:,.0f} with {cp}"
    var_t = asyncio.create_task(_var_check(backend, me, book, tid, factor, label, near))
    exp_t = asyncio.create_task(_exposure_check(backend, me, cp, tid, t, factor, notional, sign, label, near))
    v = await var_t
    x = await exp_t
    return {"ok": True, "template": {"trade": tid, "product": product, "notional": base_notional, "book": t.get("PORTFOLIO"),
                                     "counterparty": t.get("COUNTERPARTY"), "side": t.get("BUY_SELL_CODE")},
            "factor": factor, "notional": notional, "counterparty": cp, "book": book, "var": v, "exposure": x}


async def _var_check(backend, me, book: str | None, tid: str, factor: float, label: str, near: float) -> dict:
    """The VaR what-if on the book (``POST /var/what-if``), against the book's limit (its ``/var/scope`` answer)."""
    if not book:
        return {"state": "unavailable", "reason": "no book in your scope"}
    scope = f"BOOK:{book}"
    head_t = asyncio.create_task(_ask(backend.var, "GET", "/scope", me, scope=scope))
    w, why, code = await _ask(backend.var, "POST", "/what-if", me, body={"scope": scope, "items": [{"copyOf": tid, "factor": factor, "label": label}]})
    head, _hw, _hc = await head_t
    if w is None:
        return {"state": "unavailable", "scope": scope, "reason": why, "code": code}
    before, after = _num((w.get("before") or {}).get("var")), _num((w.get("after") or {}).get("var"))
    limit = _num((head or {}).get("limit"))
    ub = before / limit if limit and before is not None else None
    ua = after / limit if limit and after is not None else None
    return {"state": limit_state(ua, near), "scope": scope, "before": before, "after": after, "change": _num((w.get("change") or {}).get("var")),
            "limit": limit, "useBefore": ub, "useAfter": ua, "confidence": w.get("confidence"), "currency": w.get("currency"),
            "engine": w.get("engine"), "note": w.get("note")}


async def _exposure_check(backend, me, cp: str, tid: str, t: dict, factor: float, notional: float, sign: float, label: str,
                          near: float) -> dict:
    """The pre-deal exposure check on the counterparty (``POST /exposure/what-if``): exact (``paths``, the copy's paths into the
    counterparty's largest netting set) when the engine runs, else the engine's own conservative proxy (``addon``), said."""
    scope = f"CPTY:{cp}"
    head, why, code = await _ask(backend.exposure, "GET", "/scope", me, scope=scope)
    if head is None:
        return {"state": "unavailable", "scope": scope, "reason": why, "code": code}
    lim = head.get("limit") if isinstance(head.get("limit"), dict) else None
    sets = sorted(head.get("byNettingSet") or [], key=lambda s: -(_num(s.get("peakPfe")) or 0))
    maturity = None
    try:
        doc = ((await backend.raw("trade-valuation", str(t.get("VALUATION_ID") or f"TVAL-{tid}"), me)) or {}).get("data") or {}
        maturity = doc.get("MATURITY_DATE")
    except BackendError:
        pass
    cand = {"tradeId": "CANDIDATE-1", "label": label, "counterparty": cp, "assetClass": t.get("PRODUCT_GROUP") or "", "notional": notional,
            "maturityDate": maturity, "mtm": (_num(t.get("MTM")) or 0) * factor, "delta": sign}
    body: dict = {"scope": scope, "method": "paths", "candidates": [cand]}
    if lim and _num(lim.get("confidence")):
        body["q"] = lim.get("confidence")
    w, fell_back = None, None
    if sets:
        body["candidates"] = [{**cand, "nettingSet": sets[0].get("nettingSet"), "paths": {"copyOf": tid, "factor": factor}}]
        w, fell_back, code = await _ask(backend.exposure, "POST", "/what-if", me, body=body)
    if w is None:                                  # no netting set to deal into, or the paths engine is not running: the engine's own proxy
        body = {**body, "method": "addon", "candidates": [cand]}
        w, why, code = await _ask(backend.exposure, "POST", "/what-if", me, body=body)
        if w is None:
            return {"state": "unavailable", "scope": scope, "reason": why, "code": code}
    before, after = _num((w.get("base") or {}).get("peakPfe")), _num((w.get("with") or {}).get("peakPfe"))
    limit = _num(lim.get("limit")) if lim else None
    ub = before / limit if limit and before is not None else None
    ua = after / limit if limit and after is not None else None
    return {"state": limit_state(ua, near), "scope": scope, "method": w.get("method") or body["method"], "proxy": body["method"] == "addon",
            "fallback": fell_back if body["method"] == "addon" else None, "nettingSet": sets[0].get("nettingSet") if sets else None,
            "before": before, "after": after, "limit": limit, "useBefore": ub, "useAfter": ua,
            "confidence": body.get("q"), "flags": w.get("flags") or [], "note": w.get("note")}


def public(d: Any) -> Any:
    """An answer without what is kept for the other panels (keys starting with two underscores)."""
    return {k: v for k, v in d.items() if not str(k).startswith("__")} if isinstance(d, dict) else d
