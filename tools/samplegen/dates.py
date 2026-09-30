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

"""Business-day calendars, tenors, schedules and day-count fractions (ISDA 2006 conventions, simplified holidays)."""
from __future__ import annotations

import calendar as _cal
from dataclasses import dataclass
from datetime import date, timedelta


def _nth_weekday(y, m, weekday, n):
    d = date(y, m, 1)
    d += timedelta(days=(weekday - d.weekday()) % 7)
    return d + timedelta(weeks=n - 1)


def _last_weekday(y, m, weekday):
    d = date(y, m, _cal.monthrange(y, m)[1])
    return d - timedelta(days=(d.weekday() - weekday) % 7)


def _easter(y):
    a, b, c = y % 19, y // 100, y % 100
    d, e = b // 4, b % 4
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = c // 4, c % 4
    l_ = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * l_) // 451
    month = (h + l_ - 7 * m + 114) // 31
    return date(y, month, (h + l_ - 7 * m + 114) % 31 + 1)


def _observed(d):
    return d + timedelta(days=1) if d.weekday() == 6 else d - timedelta(days=1) if d.weekday() == 5 else d


def _holidays(center: str, y: int) -> set[date]:
    e = _easter(y)
    if center == "USNY":
        return {_observed(date(y, 1, 1)), _nth_weekday(y, 1, 0, 3), _nth_weekday(y, 2, 0, 3), _last_weekday(y, 5, 0),
                _observed(date(y, 6, 19)), _observed(date(y, 7, 4)), _nth_weekday(y, 9, 0, 1), _nth_weekday(y, 10, 0, 2),
                _observed(date(y, 11, 11)), _nth_weekday(y, 11, 3, 4), _observed(date(y, 12, 25))}
    if center == "GBLO":
        return {_observed(date(y, 1, 1)), e - timedelta(days=2), e + timedelta(days=1), _nth_weekday(y, 5, 0, 1),
                _last_weekday(y, 5, 0), _last_weekday(y, 8, 0), _observed(date(y, 12, 25)), _observed(date(y, 12, 26))}
    if center == "EUTA":  # TARGET2
        return {date(y, 1, 1), e - timedelta(days=2), e + timedelta(days=1), date(y, 5, 1), date(y, 12, 25), date(y, 12, 26)}
    if center == "JPTO":
        return {date(y, 1, 1), date(y, 1, 2), date(y, 1, 3), date(y, 2, 11), date(y, 4, 29), date(y, 5, 3), date(y, 5, 4),
                date(y, 5, 5), date(y, 11, 3), date(y, 11, 23), date(y, 12, 31)}
    return {date(y, 1, 1), date(y, 12, 25)}


@dataclass(frozen=True)
class Calendar:
    """One or more financial centres joined (a day is good only if it is good in all of them)."""
    centers: tuple[str, ...]

    @staticmethod
    def of(*centers: str) -> "Calendar":
        return Calendar(tuple(centers))

    @property
    def name(self) -> str:
        return "+".join(self.centers)

    def is_business_day(self, d: date) -> bool:
        return d.weekday() < 5 and not any(d in _holidays(c, d.year) for c in self.centers)

    def adjust(self, d: date, convention: str = "MODFOLLOWING") -> date:
        if convention == "NONE" or self.is_business_day(d):
            return d
        step = -1 if convention == "PRECEDING" else 1
        out = d
        while not self.is_business_day(out):
            out += timedelta(days=step)
        if convention == "MODFOLLOWING" and out.month != d.month:
            out = d
            while not self.is_business_day(out):
                out -= timedelta(days=1)
        return out

    def add_business_days(self, d: date, n: int) -> date:
        step = 1 if n >= 0 else -1
        out, left = d, abs(n)
        while left:
            out += timedelta(days=step)
            if self.is_business_day(out):
                left -= 1
        return out


def add_months(d: date, months: int, eom: bool = False) -> date:
    y, m = divmod(d.month - 1 + months, 12)
    y, m = d.year + y, m + 1
    last = _cal.monthrange(y, m)[1]
    return date(y, m, last if eom and d.day == _cal.monthrange(d.year, d.month)[1] else min(d.day, last))


def add_tenor(d: date, tenor: str) -> date:
    n, unit = int(tenor[:-1] or 0), tenor[-1].upper()
    if unit == "D":
        return d + timedelta(days=n)
    if unit == "W":
        return d + timedelta(weeks=n)
    if unit == "M":
        return add_months(d, n)
    if unit == "Y":
        return add_months(d, 12 * n)
    raise ValueError(tenor)


def tenor_years(tenor: str) -> float:
    if tenor in ("ON", "O/N"):
        return 1 / 365
    n, unit = int(tenor[:-1]), tenor[-1].upper()
    return {"D": n / 365, "W": 7 * n / 365, "M": n / 12, "Y": float(n)}[unit]


FREQ_MONTHS = {"Monthly": 1, "Quarterly": 3, "Semi-annual": 6, "Annual": 12}


def schedule(start: date, end: date, frequency: str, cal: Calendar, convention: str = "MODFOLLOWING") -> list[tuple[date, date]]:
    """Unadjusted periods rolled backwards from the end date (short front stub), then business-day adjusted."""
    step = FREQ_MONTHS[frequency]
    dates, k = [end], 1
    while True:
        d = add_months(end, -step * k)
        if d <= start + timedelta(days=7):
            break
        dates.append(d)
        k += 1
    dates.append(start)
    dates = sorted(dates)
    adj = [dates[0]] + [cal.adjust(d, convention) for d in dates[1:]]
    return list(zip(adj[:-1], adj[1:]))


def year_fraction(a: date, b: date, basis: str) -> float:
    if basis == "ACT/360":
        return (b - a).days / 360
    if basis in ("ACT/365F", "ACT/365"):
        return (b - a).days / 365
    if basis in ("30/360", "30E/360"):
        d1, d2 = min(a.day, 30), b.day
        if basis == "30/360" and d1 == 30:
            d2 = min(d2, 30)
        elif basis == "30E/360":
            d2 = min(d2, 30)
        return (360 * (b.year - a.year) + 30 * (b.month - a.month) + (d2 - d1)) / 360
    if basis == "ACT/ACT":
        total, cur = 0.0, a
        while cur < b:
            nxt = min(date(cur.year + 1, 1, 1), b)
            total += (nxt - cur).days / (366 if _cal.isleap(cur.year) else 365)
            cur = nxt
        return total
    raise ValueError(basis)


def iso(d: date) -> str:
    return d.isoformat()
