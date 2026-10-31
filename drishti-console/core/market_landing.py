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

"""Drishti Market's Home figures (docs/admin/EDITIONS.md › The design as built, section 13): the Market desk's tiles, USD curves and biggest moves, and the
Quality landing's checks, worked out here from the market-data pack's own answers (the server's search, raw documents, business date and
one field's history), read-only, with the person's identity, lens and date box. Nothing is invented: a figure the data does not hold is
None, a day-over-day change is given only when both business days hold real data (a history point whose data date is its date), and the
biggest moves say how they were measured (``sigma``, ``absolute``) or that the lake has no history (``none``).

The checks are the ones the data supports today: stale (data older than the business date by more than ``quality.stale-days`` business
days), gaps (a curve missing a tenor every other curve of its type has), broken discount factors and negative forwards, and the vol
surfaces' sanity (no negative vol; total variance rising with expiry where the underlying is the same). Impact on VaR and valuations, jumps
over n sigma and two sources disagreeing are not built (IMPLEMENTATION_PLAN.md, 2.9.8)."""
from __future__ import annotations

import asyncio
import math
import re
import statistics
import time
from datetime import date, timedelta
from typing import Any

from core.backend import BackendError

#: the market-data pack's kinds and their commands (config/packs/market-data/pack.yaml)
COMMANDS = {"ir-curve": "CRV", "repo-curve": "REPO", "fx-spot": "FX", "fx-forward-curve": "FXF", "fx-vol-surface": "FXV", "ir-vol-cube": "IRV",
            "cap-vol-surface": "CPV", "equity": "EQ", "equity-index": "EQX", "dividend-curve": "DIV", "equity-vol-surface": "EQV",
            "credit-curve": "CDS", "inflation-index": "INF", "inflation-curve": "INFC", "commodity": "CMD", "commodity-curve": "CMDC",
            "commodity-vol-surface": "CMDV", "rate-fixing": "FIX", "bond": "BND", "correlation-matrix": "CORR"}
#: kinds read whole for the checks (their points, grids and dates); the others are checked by their latest load (one document each)
CURVES = ("ir-curve", "repo-curve", "credit-curve", "fx-forward-curve", "inflation-curve")
SURFACES = {"fx-vol-surface": "ATM", "equity-vol-surface": "M_100", "commodity-vol-surface": None, "cap-vol-surface": None, "ir-vol-cube": None}
#: the biggest moves: each kind's headline field, and whether its move is in basis points (a rate or spread) or percent of the level
MOVERS = (("ir-curve", "TEN_Y", "bp"), ("credit-curve", "SPREAD_5Y", "spread"), ("fx-spot", "MID", "pct"), ("commodity", "FRONT", "pct"),
          ("equity-index", "LEVEL", "pct"))
SIGMA_MIN_DAYS = 10            # daily changes needed before a move is measured against its own history
TENOR = re.compile(r"^(\d+(?:\.\d+)?)\s*([DWMY])$", re.I)
_CACHE: dict[tuple, tuple[float, Any]] = {}
_TTL = 120.0


# -- business days ----------------------------------------------------------------------------------------------------------------
def _day(v: Any) -> date | None:
    try:
        return date.fromisoformat(str(v)[:10])
    except (TypeError, ValueError):
        return None


def is_business_day(d: date, holidays: set[str]) -> bool:
    return d.weekday() < 5 and d.isoformat() not in holidays


def previous_business_day(d: date, holidays: set[str]) -> date:
    d -= timedelta(days=1)
    while not is_business_day(d, holidays):
        d -= timedelta(days=1)
    return d


def business_days_between(earlier: date, later: date, holidays: set[str]) -> int:
    """Business days after ``earlier`` up to and including ``later`` (0 when ``earlier`` is not before it)."""
    n, d = 0, earlier
    while d < later and n < 400:
        d += timedelta(days=1)
        n += is_business_day(d, holidays)
    return n


def tenor_years(t: Any) -> float | None:
    """``1M`` → 1/12, ``2Y`` → 2, ``1W`` → 7/365; None for a label that is not a tenor (a futures month)."""
    m = TENOR.match(str(t or "").strip())
    if not m:
        return None
    n, u = float(m.group(1)), m.group(2).upper()
    return n * {"D": 1 / 365, "W": 7 / 365, "M": 1 / 12, "Y": 1.0}[u]


def _num(v: Any) -> float | None:
    if isinstance(v, bool) or v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


# -- reading -----------------------------------------------------------------------------------------------------------------------
class Reader:
    """The pack's answers for one person, at most ``limit`` calls at once; an answer refused or missing is None and noted."""

    def __init__(self, backend, me, limit: int = 8):
        self.backend, self.me = backend, me
        self.gate = asyncio.Semaphore(limit)
        self.problems: list[str] = []

    async def _call(self, what: str, fn, *args):
        async with self.gate:
            try:
                return await fn(*args, self.me)
            except BackendError as e:
                if len(self.problems) < 20:
                    self.problems.append(f"{what}: {e.code or e.status} {e.detail}")
                return None

    async def raw(self, kind: str, id_: str) -> dict | None:
        return await self._call(f"{kind}/{id_}", self.backend.raw, kind, id_)

    async def search(self, q: str) -> dict | None:
        return await self._call(q, self.backend.search, q)

    async def series(self, kind: str, id_: str, path: str, days: int) -> dict | None:
        return await self._call(f"{kind}/{id_} {path}", self.backend.series, kind, id_, path, days)

    async def ids(self, kind: str, limit: int = 500) -> list[str]:
        a = await self.search(f"{COMMANDS[kind]} limit {limit}")
        return [str((r.get("ref") or {}).get("id") or r.get("title")) for r in (a or {}).get("rows") or [] if (r.get("ref") or {}).get("id") or r.get("title")]


def real_points(series: dict | None) -> list[tuple[date, Any]]:
    """A field's history as (day, value), oldest first, only the days that hold that day's own data (not a day carried from before)."""
    out = []
    for p in (series or {}).get("points") or []:
        d, dd = _day(p.get("date")), _day(p.get("dataDate"))
        if d is not None and dd == d and p.get("value") is not None:
            out.append((d, p.get("value")))
    return out


def day_change(series: dict | None, holidays: set[str]) -> dict | None:
    """The latest real day's value against the business day before it, when both hold real data; else None (no change is made up)."""
    pts = [(d, _num(v)) for d, v in real_points(series)]
    pts = [(d, v) for d, v in pts if v is not None]
    if len(pts) < 2:
        return None
    (d0, v0), (d1, v1) = pts[-2], pts[-1]
    if d0 != previous_business_day(d1, holidays):
        return None
    return {"date": d1.isoformat(), "since": d0.isoformat(), "from": v0, "to": v1, "delta": v1 - v0}


def _cached(key: tuple):
    hit = _CACHE.get(key)
    return hit[1] if hit and time.monotonic() - hit[0] < _TTL else None


def _keep(key: tuple, value):
    if len(_CACHE) > 200:
        _CACHE.clear()
    _CACHE[key] = (time.monotonic(), value)
    return value


async def _business(reader: Reader) -> tuple[date | None, set[str]]:
    try:
        bd = await reader.backend.business_date(reader.me)
    except BackendError as e:
        reader.problems.append(f"business date: {e.detail}")
        return None, set()
    return _day(bd.get("selected") or bd.get("current")), {str(h) for h in bd.get("holidays") or []}


# -- A · Market desk ---------------------------------------------------------------------------------------------------------------
def _curve_points(doc: dict | None) -> list[dict]:
    out = []
    for p in ((doc or {}).get("data") or {}).get("POINTS") or []:
        y, r = tenor_years(p.get("TENOR")), _num(p.get("ZERO_RATE"))
        if y is not None and r is not None:
            out.append({"tenor": str(p.get("TENOR")), "years": round(y, 4), "rate": r})
    return out


def _week_ago(series: dict | None, latest: date | None, holidays: set[str]) -> dict | None:
    """The curve five business days before its latest day, from its history, when that day holds real data."""
    if latest is None:
        return None
    want = latest
    for _ in range(5):
        want = previous_business_day(want, holidays)
    for d, v in real_points(series):
        if d == want and isinstance(v, list):
            pts = _curve_points({"data": {"POINTS": v}})
            if pts:
                return {"date": d.isoformat(), "points": pts}
    return None


def _tile(key: str, label: str, value, unit: str, cmd: str, sub: str, change: dict | None = None, scale: float = 1.0) -> dict:
    return {"id": key, "label": label, "value": value, "unit": unit, "cmd": cmd, "sub": sub,
            "change": None if change is None else {"delta": round(change["delta"] * scale, 4), "since": change["since"]}}


async def desk(backend, me, key: tuple = ()) -> dict:
    """A's tiles (USD OIS 10Y, USD 2s10s, EUR/USD 1M ATM vol, the CDS 5Y median) and its USD curves (OIS today, a week ago when the
    history has it, and projection)."""
    hit = _cached(("desk",) + key)
    if hit is not None:
        return hit
    r = Reader(backend, me)
    business, holidays = await _business(r)
    ois, proj, fxv, cds, crv_n, ois_10y, proj_slope, fxv_atm, ois_pts = await asyncio.gather(
        r.raw("ir-curve", "CRV-USD-OIS"), r.raw("ir-curve", "CRV-USD-PROJ"), r.raw("fx-vol-surface", "FXV-EURUSD"),
        r.search("CDS where SPREAD_5Y > 0 order by SPREAD_5Y limit 500"), r.search("CRV limit 1"),
        r.series("ir-curve", "CRV-USD-OIS", "$.TEN_Y", 4), r.series("ir-curve", "CRV-USD-PROJ", "$.SLOPE_2S_10S", 4),
        r.series("fx-vol-surface", "FXV-EURUSD", "$.ATM_1M", 4), r.series("ir-curve", "CRV-USD-OIS", "$.POINTS", 8))
    data_day = _day(((ois or {}).get("provenance") or {}).get("businessDate"))
    tiles = []
    o = (ois or {}).get("data") or {}
    ten = _num(o.get("TEN_Y"))
    tiles.append(_tile("ois10y", "USD OIS 10Y", None if ten is None else round(ten * 100, 4), "%", "CRV CRV-USD-OIS",
                       "CRV CRV-USD-OIS" + (f" · {o.get('INDEX')}" if o.get("INDEX") else ""), day_change(ois_10y, holidays), 10000))
    p = (proj or {}).get("data") or {}
    tiles.append(_tile("slope", "USD 2s10s", _num(p.get("SLOPE_2S_10S")), "bp", "CRV CRV-USD-PROJ", "slope, CRV-USD-PROJ",
                       day_change(proj_slope, holidays)))
    f = (fxv or {}).get("data") or {}
    tiles.append(_tile("fxvol", "EUR/USD 1M ATM vol", _num(f.get("ATM_1M")), "%", "FXV FXV-EURUSD", "FXV FXV-EURUSD",
                       day_change(fxv_atm, holidays)))
    rows = (cds or {}).get("rows") or []
    spreads = sorted(s for s in (_num((x.get("values") or {}).get("$.SPREAD_5Y")) for x in rows) if s is not None)
    median, change = (statistics.median(spreads) if spreads else None), None
    if spreads:
        ids = [str((x.get("ref") or {}).get("id")) for x in rows][:60]
        moves = [day_change(s, holidays) for s in await asyncio.gather(*(r.series("credit-curve", i, "$.SPREAD_5Y", 4) for i in ids))]
        if moves and all(m is not None for m in moves) and len({m["since"] for m in moves}) == 1:
            change = {"delta": statistics.median([m["to"] for m in moves]) - statistics.median([m["from"] for m in moves]), "since": moves[0]["since"]}
    tiles.append(_tile("cds", "CDS 5Y, median", median, "bp", "CDS where SPREAD_5Y > 0 order by SPREAD_5Y desc",
                       f"CDS · {len(spreads)} names" if spreads else "CDS", change))
    today = _curve_points(ois)
    out = {"businessDate": business.isoformat() if business else None, "dataDate": data_day.isoformat() if data_day else None,
           "curves": (crv_n or {}).get("matched"), "kinds": len(COMMANDS), "tiles": tiles,
           "curve": {"ois": today, "projection": _curve_points(proj), "weekAgo": _week_ago(ois_pts, data_day, holidays) if today else None},
           "problems": r.problems}
    return _keep(("desk",) + key, out)


def _move_value(kind_unit: str, d0: float, d1: float) -> tuple[float, str]:
    """A move in the units a desk reads it: basis points for a rate held as a fraction (TEN_Y 0.0392), basis points for a spread
    already in basis points, percent of the level otherwise."""
    if kind_unit == "bp":
        return (d1 - d0) * 10000, "bp"
    if kind_unit == "spread":
        return d1 - d0, "bp"
    return ((d1 - d0) / d0 * 100 if d0 else 0.0), "%"


async def moves(backend, me, key: tuple = (), days: int = 21, top: int = 6) -> dict:
    """The biggest moves of the latest day: each item's change against the business day before, sized in standard deviations of its own
    daily changes when the lake holds ``SIGMA_MIN_DAYS`` of them (``sigma``), else the largest moves in percent of the level
    (``absolute``), else none (``none``: the lake keeps no history per date)."""
    hit = _cached(("moves",) + key)
    if hit is not None:
        return hit
    r = Reader(backend, me, limit=6)
    _business_day, holidays = await _business(r)
    plan = []
    for kind, field, unit in MOVERS:
        for i in (await r.ids(kind, 200))[:60]:
            plan.append((kind, i, field, unit))
    answers = await asyncio.gather(*(r.series(k, i, f"$.{f}", days) for k, i, f, _u in plan))
    items = []
    for (kind, i, field, unit), s in zip(plan, answers):
        pts = [(d, _num(v)) for d, v in real_points(s)]
        pts = [(d, v) for d, v in pts if v is not None]
        diffs = [(pts[n][0], pts[n - 1][1], pts[n][1]) for n in range(1, len(pts)) if pts[n - 1][0] == previous_business_day(pts[n][0], holidays)]
        if not diffs or diffs[-1][0] != pts[-1][0]:
            continue
        day, v0, v1 = diffs[-1]
        move, shown = _move_value(unit, v0, v1)
        hist = [_move_value(unit, a, b)[0] for _d, a, b in diffs[:-1]]
        sd = statistics.pstdev(hist) if len(hist) >= SIGMA_MIN_DAYS else 0.0
        items.append({"kind": kind, "id": i, "cmd": f"{COMMANDS[kind]} {i}", "field": field, "date": day.isoformat(),
                      "since": previous_business_day(day, holidays).isoformat(), "move": round(move, 4), "unit": shown,
                      "sigma": round(abs(move) / sd, 2) if sd > 1e-12 else None, "days": len(hist)})
    sized = [x for x in items if x["sigma"] is not None]
    if len(sized) >= 3:
        mode, ranked = "sigma", sorted(sized, key=lambda x: -x["sigma"])
    elif items:
        mode, ranked = "absolute", sorted(items, key=lambda x: -abs(x["move"] if x["unit"] == "%" else x["move"] / 100))
    else:
        mode, ranked = "none", []
    out = {"mode": mode, "items": ranked[:top], "measured": len(items), "asked": len(plan), "problems": r.problems}
    return _keep(("moves",) + key, out)


# -- C · Quality -------------------------------------------------------------------------------------------------------------------
def _exc(kind: str, id_: str, check: str, detail: str, severity: str, since: str | None = None) -> dict:
    return {"kind": kind, "id": id_, "cmd": f"{COMMANDS.get(kind, kind)} {id_}", "check": check, "detail": detail, "severity": severity,
            "since": since}


def check_stale(kind: str, id_: str, doc: dict, business: date, holidays: set[str], stale_days: int) -> dict | None:
    """Stale: the document's own AS_OF (a curve's) or, without one, the date its source holds it for, more than ``stale_days`` business
    days before the business date."""
    data = doc.get("data") or {}
    seen = _day(data.get("AS_OF")) or _day((doc.get("provenance") or {}).get("businessDate"))
    if seen is None:
        return None
    lag = business_days_between(seen, business, holidays)
    if lag <= stale_days:
        return None
    return _exc(kind, id_, "stale", f"as of {seen.isoformat()}, {lag} business days old", "warning", seen.isoformat())


def check_gaps(group: list[tuple[str, str, dict]]) -> list[dict]:
    """Gaps: a curve missing a tenor that every other curve of its type has (a type of three curves or more)."""
    if len(group) < 3:
        return []
    tenors = {i: {str(p.get("TENOR")) for p in (d.get("data") or {}).get("POINTS") or [] if p.get("TENOR")} for _k, i, d in group}
    out = []
    for kind, i, _d in group:
        others = [t for j, t in tenors.items() if j != i and t]
        if not others:
            continue
        missing = set.intersection(*others) - tenors[i]
        if missing:
            order = sorted(missing, key=lambda t: tenor_years(t) or 0)
            out.append(_exc(kind, i, "gap", f"{', '.join(order)} missing (the other curves of its type have {'it' if len(order) == 1 else 'them'})",
                            "warning"))
    return out


def check_forwards(kind: str, id_: str, doc: dict) -> list[dict]:
    """Broken discount factors (not falling with maturity, or outside (0, 1]) and negative forward rates."""
    data, out = doc.get("data") or {}, []
    pts = [(str(p.get("TENOR")), _num(p.get("DF"))) for p in data.get("POINTS") or []]
    pts = [(t, df) for t, df in pts if df is not None]
    bad = [t for t, df in pts if df <= 0 or df > 1]
    rises = [pts[n][0] for n in range(1, len(pts)) if pts[n][1] >= pts[n - 1][1]]
    if bad:
        out.append(_exc(kind, id_, "discount factor", f"outside (0, 1] at {', '.join(bad)}", "critical"))
    if rises:
        out.append(_exc(kind, id_, "discount factor", f"does not fall at {', '.join(rises)}", "critical"))
    neg = [(str(f.get("TENOR")), _num(f.get("RATE"))) for f in data.get("FORWARDS") or []]
    neg = [(t, v) for t, v in neg if v is not None and v < 0]
    if neg:
        out.append(_exc(kind, id_, "negative forward", ", ".join(f"{t} {v:.2f}%" for t, v in neg), "warning"))
    return out


def check_surface(kind: str, id_: str, doc: dict, atm: str | None) -> list[dict]:
    """A vol surface's sanity: no vol at or below zero (risk reversals and butterflies may be negative) and, where every expiry has the
    same underlying (FX, equity), ATM total variance (vol² × T) rising with expiry."""
    out, grid = [], (doc.get("data") or {}).get("GRID") or []
    neg = []
    for row in grid:
        label = row.get("EXPIRY") or row.get("MATURITY") or row.get("MONTH")
        for k, v in row.items():
            n = _num(v)
            if n is not None and n <= 0 and not k.startswith(("RR", "BF")):
                neg.append(f"{label} {k} {n:g}")
    if neg:
        out.append(_exc(kind, id_, "negative vol", ", ".join(neg[:4]) + (" …" if len(neg) > 4 else ""), "critical"))
    if atm:
        tv = [(str(row.get("EXPIRY")), tenor_years(row.get("EXPIRY")), _num(row.get(atm))) for row in grid]
        tv = [(e, t * v * v) for e, t, v in tv if t is not None and v is not None and v > 0]
        falls = [f"{tv[n - 1][0]} to {tv[n][0]}" for n in range(1, len(tv)) if tv[n][1] < tv[n - 1][1]]
        if falls:
            out.append(_exc(kind, id_, "calendar spread", "total variance falls " + ", ".join(falls), "critical"))
    return out


def run_checks(docs: dict[str, dict[str, dict]], latest: dict[str, tuple[str, dict]], business: date, holidays: set[str],
               stale_days: int) -> dict:
    """Every check over the documents read (``docs``: kind → id → raw answer) and each other kind's latest document (``latest``: kind →
    (id, raw answer)). Pure: the tests give it data with known stale, gap, forward and surface problems."""
    found: list[dict] = []
    checks = {c: {"id": c, "items": 0, "flagged": 0} for c in ("stale", "gap", "forwards", "surface")}
    items: set[tuple[str, str]] = set()

    def note(check: str, n_items: int, ex: list[dict]):
        checks[check]["items"] += n_items
        checks[check]["flagged"] += len({(e["kind"], e["id"]) for e in ex})
        found.extend(ex)

    for kind, by_id in docs.items():
        for i, d in by_id.items():
            items.add((kind, i))
            s = check_stale(kind, i, d, business, holidays, stale_days)
            note("stale", 1, [s] if s else [])
    for kind, (i, d) in latest.items():
        items.add((kind, f"{i}"))
        s = check_stale(kind, i, d, business, holidays, stale_days)
        if s:
            s["detail"] += f" (the latest {COMMANDS.get(kind, kind)} loaded)"
        note("stale", 1, [s] if s else [])
    groups: dict[tuple[str, str], list] = {}
    for kind in CURVES:
        for i, d in (docs.get(kind) or {}).items():
            ctype = str((d.get("data") or {}).get("CURVE_TYPE") or "") if kind == "ir-curve" else ""
            groups.setdefault((kind, ctype), []).append((kind, i, d))
    for g in groups.values():
        note("gap", len(g) if len(g) >= 3 else 0, check_gaps(g))
    for i, d in (docs.get("ir-curve") or {}).items():
        note("forwards", 1, check_forwards("ir-curve", i, d))
    for kind, atm in SURFACES.items():
        for i, d in (docs.get(kind) or {}).items():
            note("surface", 1, check_surface(kind, i, d, atm))
    flagged = {(e["kind"], e["id"]) for e in found}
    rank = {"critical": 0, "warning": 1}
    found.sort(key=lambda e: (rank.get(e["severity"], 2), e["check"], e["kind"], e["id"]))
    n = len(items)
    return {"checked": n, "clean": n - len(flagged & items), "cleanPct": round((n - len(flagged & items)) / n * 100, 1) if n else None,
            "critical": [e for e in found if e["severity"] == "critical"], "warnings": [e for e in found if e["severity"] == "warning"],
            "checks": list(checks.values())}


async def quality(backend, me, stale_days: int, key: tuple = ()) -> dict:
    """C's checks over the pack's own answers: the curves and surfaces read whole, every other kind by its latest load."""
    hit = _cached(("quality", stale_days) + key)
    if hit is not None:
        return hit
    r = Reader(backend, me)
    business, holidays = await _business(r)
    whole = list(CURVES) + list(SURFACES)
    rest = [k for k in COMMANDS if k not in whole]
    id_lists = await asyncio.gather(*(r.ids(k) for k in whole), *(r.ids(k, 1) for k in rest))
    wanted = [(k, i) for k, ids in zip(whole, id_lists[:len(whole)]) for i in ids[:200]]
    wanted += [(k, ids[0]) for k, ids in zip(rest, id_lists[len(whole):]) if ids]
    answers = await asyncio.gather(*(r.raw(k, i) for k, i in wanted))
    docs: dict[str, dict[str, dict]] = {}
    latest: dict[str, tuple[str, dict]] = {}
    for (k, i), a in zip(wanted, answers):
        if not isinstance(a, dict):
            continue
        if k in whole:
            docs.setdefault(k, {})[i] = a
        else:
            latest[k] = (i, a)
    if business is None:
        out = {"businessDate": None, "staleDays": stale_days, "checked": 0, "clean": 0, "cleanPct": None, "critical": [], "warnings": [],
               "checks": [], "problems": r.problems or ["no business date"]}
        return out
    out = {"businessDate": business.isoformat(), "staleDays": stale_days, **run_checks(docs, latest, business, holidays, stale_days),
           "problems": r.problems}
    return _keep(("quality", stale_days) + key, out)


def forget() -> None:
    """Drops the cached answers (tests)."""
    _CACHE.clear()
