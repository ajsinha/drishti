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

"""When lake maintenance runs: the one scheduler of ``lake maintain`` (its --daemon) and of Admin → Lakes (the console runs the same
code), so a schedule behaves the same wherever it is run.

A schedule is either a time, as five-field cron text (minute hour day-of-month month weekday: ``30 2 * * 2-6`` is Tue–Sat 02:30,
weekday 0 or 7 Sunday, names mon..sun and jan..dec accepted), or ``after-build``: after every build of one of its tables (a new
``lastBuild`` in the lake tool's build record). Times are in the schedule zone (``schedule.zone``, default America/New_York).

Missed runs: a timed schedule whose last fire time passed while nothing was running (the console or daemon was down) runs once at
the next start, and the history says it was a catch-up (the systemd ``Persistent=true`` behaviour); several missed times still make
one run."""
from __future__ import annotations

import json
import os
import pathlib
from datetime import datetime, timedelta

DAYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
AFTER_BUILD = "after-build"


class CronError(ValueError):
    pass


def _field(text: str, lo: int, hi: int, names: list[str] | None = None, base: int = 0) -> set[int]:
    out: set[int] = set()
    for part in text.lower().split(","):
        step = 1
        if "/" in part:
            part, s = part.split("/", 1)
            if not s.isdigit() or int(s) < 1:
                raise CronError(f"bad step '/{s}'")
            step = int(s)

        def num(x: str) -> int:
            if names and x[:3] in names:
                return names.index(x[:3]) + base
            if not x.isdigit():
                raise CronError(f"'{x}' is not a number" + (f" or one of {', '.join(names)}" if names else ""))
            return int(x)
        if part in ("*", ""):
            a, b = lo, hi
        elif "-" in part:
            a, b = (num(x) for x in part.split("-", 1))
        else:
            a = b = num(part)
            if step > 1:
                b = hi
        if a < lo or b > hi or a > b:
            raise CronError(f"'{part}' is outside {lo}-{hi}")
        out.update(range(a, b + 1, step))
    return out


class Cron:
    """Five-field cron (minute hour day-of-month month weekday); day-of-month and weekday combine as in cron (either matches when
    both are restricted)."""

    def __init__(self, text: str):
        f = str(text or "").split()
        if len(f) != 5:
            raise CronError(f"'{text}' needs five fields: minute hour day-of-month month weekday (30 2 * * 2-6)")
        self.text = " ".join(f)
        self.minutes = sorted(_field(f[0], 0, 59))
        self.hours = sorted(_field(f[1], 0, 23))
        self.dom = _field(f[2], 1, 31)
        self.months = _field(f[3], 1, 12, MONTHS, 1)
        dow = _field(f[4], 0, 7, DAYS)
        self.dow = {d % 7 for d in dow}
        self.dom_any, self.dow_any = f[2] == "*", f[4] == "*"

    def _day(self, d) -> bool:
        if d.month not in self.months:
            return False
        dom, dow = d.day in self.dom, (d.isoweekday() % 7) in self.dow
        if self.dom_any or self.dow_any:
            return dom and dow
        return dom or dow

    def next(self, after: datetime) -> datetime | None:
        """The first fire time strictly after ``after`` (same tzinfo)."""
        start = after.replace(second=0, microsecond=0)
        for k in range(0, 1500):
            d = (start + timedelta(days=k)).date() if k else start.date()
            if not self._day(d):
                continue
            for h in self.hours:
                for m in self.minutes:
                    t = start.replace(year=d.year, month=d.month, day=d.day, hour=h, minute=m)
                    if t > after:
                        return t
        return None

    def previous(self, before: datetime) -> datetime | None:
        """The last fire time at or before ``before``."""
        start = before.replace(second=0, microsecond=0)
        for k in range(0, 1500):
            d = (start - timedelta(days=k)).date()
            if not self._day(d):
                continue
            for h in reversed(self.hours):
                for m in reversed(self.minutes):
                    t = start.replace(year=d.year, month=d.month, day=d.day, hour=h, minute=m)
                    if t <= before:
                        return t
        return None


def friendly(cron: str) -> str:
    """'30 2 * * 2-6' as 'Tue–Sat 02:30'; anything a picker cannot say is returned as the cron text."""
    if cron == AFTER_BUILD:
        return "after every build of the table"
    try:
        c = Cron(cron)
    except CronError:
        return cron
    f = cron.split()
    if len(c.minutes) != 1 or len(c.hours) != 1 or f[2] != "*" or f[3] != "*":
        return "cron " + c.text
    at = f"{c.hours[0]:02d}:{c.minutes[0]:02d}"
    days = sorted(c.dow, key=lambda d: (d + 6) % 7)                   # Monday first
    if len(days) == 7:
        return "every day " + at
    if days == [1, 2, 3, 4, 5]:
        return "Mon–Fri " + at
    names = [DAYS[d].title() for d in days]
    order = [(d + 6) % 7 for d in days]
    if len(days) > 2 and order == list(range(order[0], order[0] + len(order))):
        return f"{names[0]}–{names[-1]} {at}"
    return ", ".join(names) + " " + at


def cron_of(days: list[str], at: str) -> str:
    """The picker's choice (weekday names, HH:MM) as cron text."""
    hh, mm = (int(x) for x in str(at).split(":"))
    if not (0 <= hh <= 23 and 0 <= mm <= 59):
        raise CronError(f"'{at}' is not a time of day (HH:MM)")
    picked = sorted({DAYS.index(d[:3].lower()) for d in days if d and d[:3].lower() in DAYS})
    if not picked:
        raise CronError("pick at least one weekday")
    return f"{mm} {hh} * * " + ("*" if len(picked) == 7 else ",".join(map(str, picked)))


def check(when: str) -> str:
    """The schedule's ``when`` as stored (cron text or after-build); raises CronError with what is accepted."""
    w = " ".join(str(when or "").split())
    if w == AFTER_BUILD:
        return w
    Cron(w)
    return w


def due(schedule: dict, state: dict, now: datetime, built: dict[str, str] | None = None, started: bool = False) -> str | None:
    """Why ``schedule`` should run now, or None. ``state`` is what was kept for it ({lastRun, lastFire, builds}); ``built`` the
    tables' lastBuild stamps ({"<root>|<domain>/<table>": iso}); ``started`` is true on the first tick after a start, when a fire
    time missed while down is caught up once."""
    if schedule.get("paused"):
        return None
    when = schedule.get("when") or ""
    if when == AFTER_BUILD:
        seen = state.get("builds") or {}
        new = [t for t in schedule.get("tables") or [] if (built or {}).get(t) and (built or {}).get(t) != seen.get(t)]
        if new:
            return "after the build of " + ", ".join(new)
        return None
    try:
        cron = Cron(when)
    except CronError:
        return None
    fire = cron.previous(now)
    if fire is None:
        return None
    last = state.get("lastFire")
    if last is None:
        created = state.get("created")
        if created and fire.isoformat() > created:
            return "scheduled " + fire.strftime("%a %Y-%m-%d %H:%M")
        return None
    if fire.isoformat() <= last:
        return None
    if started and (now - fire) > timedelta(minutes=2):
        return "catch-up: the run due " + fire.strftime("%a %Y-%m-%d %H:%M") + " was missed while nothing was running; run once at start"
    return "scheduled " + fire.strftime("%a %Y-%m-%d %H:%M")


class Scheduler:
    """The schedules' state between ticks ({"<root>|<name>": {created, lastFire, lastRun, builds}}), kept in a JSON file beside the
    history so a restart knows what ran (and catches up what was missed). Used by ``lake maintain --daemon`` and the console."""

    def __init__(self, path):
        self.path = pathlib.Path(path)
        try:
            self.state = json.loads(self.path.read_text(encoding="utf-8")) if self.path.is_file() else {}
        except ValueError:
            self.state = {}
        self.started = True

    @staticmethod
    def key(s: dict) -> str:
        return f"{s.get('root')}|{s.get('name')}"

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + ".writing")
        tmp.write_text(json.dumps(self.state, indent=2, sort_keys=True), encoding="utf-8")
        os.replace(tmp, self.path)

    def due(self, schedules: list[dict], now: datetime, built: dict[str, dict[str, str]]) -> list[tuple[dict, str]]:
        """The schedules to run now with the reason; ``built`` is {root: {domain/table: lastBuild}}. A new schedule is remembered
        (created now; an after-build one remembers the current builds) and runs from its next time."""
        out, dirty = [], False
        live = {self.key(s) for s in schedules}
        for k in [k for k in self.state if k not in live]:
            del self.state[k]
            dirty = True
        for s in schedules:
            st = self.state.get(self.key(s))
            stamps = {t: v for t, v in (built.get(str(s.get("root"))) or {}).items() if t in (s.get("tables") or [])}
            if st is None:
                self.state[self.key(s)] = {"created": now.isoformat(), "builds": stamps}
                dirty = True
                continue
            why = due(s, st, now, built.get(str(s.get("root"))) or {}, self.started)
            if why:
                out.append((s, why))
        self.started = False
        if dirty:
            self.save()
        return out

    def ran(self, s: dict, now: datetime, built: dict[str, dict[str, str]]) -> None:
        st = self.state.setdefault(self.key(s), {"created": now.isoformat()})
        st["lastRun"] = now.isoformat()
        if s.get("when") != AFTER_BUILD:
            try:
                fire = Cron(s.get("when") or "").previous(now)
                st["lastFire"] = fire.isoformat() if fire else st.get("lastFire")
            except CronError:
                pass
        st["builds"] = {t: v for t, v in (built.get(str(s.get("root"))) or {}).items() if t in (s.get("tables") or [])}
        self.save()

    def view(self, s: dict) -> dict:
        return self.state.get(self.key(s)) or {}
