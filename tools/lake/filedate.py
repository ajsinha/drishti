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

"""The business date a file's name carries (DATA_GUIDE.md section 2.8): the Python half of one rule (drishti-lakeload ``FileDates`` is
the Java half; both are tested against ``tools/lake/testdata/file-dates/names.json``).

The base name, its data and archive extensions stripped (``.jsonl .ndjson .json .gz .zst .zip .tar .tar.gz .tgz``, any case, any
number), must END with a separator (space, ``.``, ``_`` or ``-``) and a date, ``YYYYMMDD`` or ``YYYY-MM-DD``, that is a calendar date of
the years 1900 to 2999. An archive's member: its own name's date wins, else the archive's.

The loader applies such a date to every row of the file (``--date-from auto``, the default): an explicit ``--as-of`` wins over it, and
it wins over each row's own date field; ``--date-from field`` ignores names."""
from __future__ import annotations

import re
from datetime import date

EXTENSIONS = (".tar.gz", ".tgz", ".gz", ".zst", ".zip", ".tar", ".jsonl", ".ndjson", ".json")
_AT_END = re.compile(r"[ ._-](\d{8}|\d{4}-\d{2}-\d{2})$", re.ASCII)
_EIGHT = re.compile(r"(?<!\d)\d{8}(?!\d)|(?<!\d)\d{4}-\d{2}-\d{2}(?!\d)", re.ASCII)
FIRST_YEAR, LAST_YEAR = 1900, 2999
DATE_FROM = ("auto", "field")


def base_name(name: str) -> str:
    """The last part of a path (after '/', '\\' or an archive's '!/'), its data and archive extensions stripped."""
    s = str(name).replace("\\", "/").rsplit("/", 1)[-1]
    stripped = True
    while stripped:
        stripped = False
        lower = s.lower()
        for ext in EXTENSIONS:
            if lower.endswith(ext) and len(lower) > len(ext):
                s = s[: len(s) - len(ext)]
                stripped = True
                break
    return s


def find(name) -> tuple[date | None, str | None]:
    """(the date ``name`` carries, None) or (None, why it carries none)."""
    if name is None or not str(name).strip():
        return None, "no name"
    base = base_name(name)
    m = _AT_END.search(base)
    if not m:
        if _EIGHT.search(base):
            return None, f"{base}: the date is not at the end of the name after a space, '.', '_' or '-'"
        return None, f"{base}: no YYYYMMDD or YYYY-MM-DD at the end of the name"
    d = m[1].replace("-", "")
    try:
        day = date(int(d[:4]), int(d[4:6]), int(d[6:8]))
    except ValueError:
        return None, f"{base}: {m[1]} is not a calendar date"
    if not FIRST_YEAR <= day.year <= LAST_YEAR:
        return None, f"{base}: {m[1]} is outside the years {FIRST_YEAR} to {LAST_YEAR}"
    return day, None


def find_text(name) -> str:
    """The date's text at the end of ``name``'s base name (``20261103`` or ``2026-11-03``), '' when it carries no date."""
    if find(name)[0] is None:
        return ""
    return _AT_END.search(base_name(name))[1]


def of(name) -> date | None:
    """The date ``name`` (a file name or a path) carries, or None."""
    return find(name)[0]


def of_member(archive, member) -> date | None:
    """An archive member's date: its own name's, else the archive's; None when neither carries one."""
    d = of(member) if member is not None else None
    return d if d is not None else (of(archive) if archive is not None else None)


def of_input(f) -> date | None:
    """The date of one input of ``jsonstream.input_files``: a path, or an ``archives.Member`` (its name, else its archive's)."""
    import archives
    if isinstance(f, archives.Member):
        return of_member(f.archive.name, f.name)
    return of(getattr(f, "name", f))


def other_date_note(rows: int, label: str, field: str | None, day: date) -> str:
    """The words a load report gives rows whose own date disagreed with the date taken from their file's name (the Java loader's
    LoadReport words the same)."""
    return (f"{rows} row{'' if rows == 1 else 's'} of {label} carried another date in {field}; "
            f"loaded under {day.isoformat()} from the file name")
