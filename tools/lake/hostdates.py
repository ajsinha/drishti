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

"""Drishti's own half of the business-date rule: the calendar day of a moment (``local_day``) and the date of one input of
``jsonstream.input_files`` (``of_input``). Everything else of the rule (a file name's table and date, a tar's layout, the business today,
``decide``) is Drishti Lake's: ``from drishti_lake import filedate``."""
from __future__ import annotations

from datetime import date

from drishti_lake import filedate


def local_day(stamp: str | float | None = None) -> date:
    """The calendar day of a moment in the business calendar: local time, as ``business_today`` and the "not in the future" check read
    it. ``stamp``: an ISO timestamp (``...Z`` or with an offset; one without an offset is taken as UTC, as the download caches write
    them), seconds since the epoch (a file time), or None for now. A download made at 23:00 local, already the next day in UTC, is dated
    today, never tomorrow."""
    from datetime import datetime, timezone
    if stamp is None:
        return date.today()
    if isinstance(stamp, (int, float)):
        return datetime.fromtimestamp(stamp).date()
    text = str(stamp).strip()
    if len(text) == 10:                                  # a calendar date already: kept as it is
        return date.fromisoformat(text)
    t = datetime.fromisoformat(text.replace("Z", "+00:00"))
    return (t if t.tzinfo else t.replace(tzinfo=timezone.utc)).astimezone().date()


def of_input(f, today: date | None = None) -> date | None:
    """The date of one input of ``jsonstream.input_files``: a tar member's folder date, a zip member's archive's name's, else the
    path's name's."""
    import archives
    if isinstance(f, archives.Member):
        if getattr(f, "date", None) is not None:
            return f.date
        return filedate.of(f.archive.name, today)
    return filedate.of(getattr(f, "name", f), today)
