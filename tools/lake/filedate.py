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

"""The table and the business date a file's name carries, and a tar archive's layout (DATA_GUIDE.md section 2.8): the Python half of
one rule. drishti-lakeload ``FileDates`` is the Java half; both are tested against ``tools/lake/testdata/file-dates/names.json``.

**Names.** ``<table>.<date>.<extensions>`` or ``<table>.<extensions>``: the table is everything before the first ``.`` (letters, digits,
``_`` and ``-``); the date, when there is one, is the second segment, ``YYYY-MM-DD`` (or ``YYYYMMDD``), a calendar date from 1900, not
in the future; the extensions are a format (``jsonl``, ``ndjson``, ``json``) and/or a compression (``gz``, ``zip``, ``zst``), in that
order, read without case: ``abc.jsonl``, ``abc.2026-11-03.jsonl.gz``, ``abc.2026-11-03.zip``. Anything else (``abc.v2.jsonl``) is
refused with the reason: never guessed. Where the table comes from elsewhere (``--kind``, a rule, a ``.ready`` file), a name that is
refused only carries no date.

**Tar archives** (``.tar``, ``.tgz``, ``.tar.gz``, ``.tar.zip``) name their tables and dates in their folders: every member is
``<table>/<YYYY-MM-DD>/<file>.jsonl[.gz|.zst]``; OS litter (``__MACOSX/``, hidden names such as ``._x`` and ``.DS_Store``,
``Thumbs.db``) is ignored; anything else refuses the whole archive, every problem listed. An archive with nothing in it is empty.

**The business date of a plain file** (``decide``): the date given for the load (``--as-of``, a ``.ready`` file's ``business-date:``),
else the name's date, else the business today (``business_today``: Saturday and Sunday are the Friday before); ``date-from: name``
refuses a name without a date instead. Every row of the file goes under it: a row's own date field never decides; it is only counted
when it differs (``other_date_note``)."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, timedelta

FORMATS = ("jsonl", "ndjson", "json")
COMPRESSIONS = ("gz", "zip", "zst")
TAR_SUFFIXES = (".tar.gz", ".tar.zip", ".tgz", ".tar")
DATE_FROM = ("auto", "name")
LITTER = ("__MACOSX", "Thumbs.db")
FIRST_YEAR = 1900
EXTENSIONS_TEXT = "(.jsonl, .ndjson, .json, .gz, .zip, .zst; an archive .tar, .tgz, .tar.gz, .tar.zip)"
_DATEISH = re.compile(r"^(\d{8}|\d{4}-\d{2}-\d{2})$", re.ASCII)
_TABLE = re.compile(r"^[A-Za-z0-9_-]+$", re.ASCII)
_JSONL_MEMBER = re.compile(r"\.(jsonl|ndjson|json)(\.gz|\.zst)?$", re.IGNORECASE)


@dataclass(frozen=True)
class Name:
    """What a file's name says: its table (None: none), its date (None: none), whether it is a tar archive, or why it is refused."""
    table: str | None
    date: date | None
    tar: bool = False
    error: str | None = None


def business_today(today: date | None = None) -> date:
    """The business today: today, or the Friday before on a Saturday or a Sunday (the server rolls back the same way)."""
    d = today or date.today()
    return d - timedelta(days=max(0, d.weekday() - 4))


def base_name(name) -> str:
    """The last part of a path (after '/', '\\' or an archive's '!/')."""
    return str(name).replace("\\", "/").rsplit("/", 1)[-1]


def _date(text: str, today: date | None) -> tuple[date | None, str | None]:
    """(the date, None) or (None, why it is not one)."""
    d = text.replace("-", "")
    try:
        day = date(int(d[:4]), int(d[4:6]), int(d[6:8]))
    except ValueError:
        return None, f"{text} is not a calendar date"
    if day.year < FIRST_YEAR:
        return None, f"{text} is before {FIRST_YEAR}"
    if day > (today or date.today()):
        return None, f"{text} is in the future"
    return day, None


def is_tar(name) -> bool:
    return base_name(name).lower().endswith(TAR_SUFFIXES)


def parse(name, today: date | None = None) -> Name:
    """The table and date ``name`` carries (module doc), or why it is refused."""
    base = base_name(name)
    low = base.lower()
    for s in TAR_SUFFIXES:
        if low.endswith(s):
            label = base[: len(base) - len(s)]
            if not _TABLE.match(label):
                return Name(None, None, True, f"{base}: a .tar archive names its tables and dates in its folders "
                                              "(<table>/<YYYY-MM-DD>/), never in its file name (letters, digits, _ and - before .tar)")
            return Name(label.lower(), None, True)
    tokens = base.split(".")
    table, rest = tokens[0], tokens[1:]
    if not _TABLE.match(table):
        return Name(None, None, False, f"{base}: '{table}' is not a table name (letters, digits, _ and -, before the first '.')")
    if not rest:
        return Name(None, None, False, f"{base}: no extension {EXTENSIONS_TEXT}")
    day = None
    if _DATEISH.match(rest[0]):
        day, why = _date(rest[0], today)
        if why:
            return Name(None, None, False, f"{base}: {why}")
        rest = rest[1:]
    shape = ""
    for t in rest:
        lt = t.lower()
        if lt in FORMATS:
            shape += "F"
        elif lt in COMPRESSIONS:
            shape += "C"
        else:
            return Name(None, None, False, f"{base}: '{t}' is neither the date (YYYY-MM-DD, right after the table name) nor an extension "
                                           + EXTENSIONS_TEXT)
    if shape not in ("F", "FC", "C"):
        return Name(None, None, False, f"{base}: not <table>[.<YYYY-MM-DD>].<format>[.<compression>] {EXTENSIONS_TEXT}")
    return Name(table.lower(), day, False)


def of(name, today: date | None = None) -> date | None:
    """The date ``name`` carries; None when it carries none, is refused or is a tar (where the table comes from elsewhere, a name only
    adds a date)."""
    n = parse(name, today)
    return None if n.error or n.tar else n.date


def of_input(f, today: date | None = None) -> date | None:
    """The date of one input of ``jsonstream.input_files``: a tar member's folder date, a zip member's archive's name's, else the
    path's name's."""
    import archives
    if isinstance(f, archives.Member):
        if getattr(f, "date", None) is not None:
            return f.date
        return of(f.archive.name, today)
    return of(getattr(f, "name", f), today)


@dataclass(frozen=True)
class Decided:
    """A file's business date and where it comes from (given, name, folder, today), or why it is refused."""
    date: date | None
    source: str
    error: str | None = None


def decide(given: date | None, name_date: date | None, date_from: str = "auto", today: date | None = None, name: str = "") -> Decided:
    """The business date of one plain file (module doc)."""
    if given is not None:
        return Decided(given, "given")
    if name_date is not None:
        return Decided(name_date, "name")
    if date_from == "name":
        return Decided(None, "name", f"{base_name(name)}: date-from name, but the name carries no date (YYYY-MM-DD)")
    return Decided(business_today(today), "today")


def words(d: Decided, given_by: str = "the command line") -> str:
    """Where a file's date comes from, in words (the Java loader's words are the same)."""
    if d.source == "given":
        return f"{d.date.isoformat()} from {given_by}"
    if d.source == "name":
        return f"{d.date.isoformat()} from the file name"
    if d.source == "folder":
        return f"{d.date.isoformat()} from the archive's folder"
    if d.source == "ready":
        return f"{d.date.isoformat()} from the .ready file"
    return f"{d.date.isoformat()} (today: no date in the name)"


def other_date_note(rows: int, label: str, field_name: str | None, day: date) -> str:
    """The words a load report gives rows whose own date field differs from their file's date (the Java loader's words are the same)."""
    return (f"{rows} row{'' if rows == 1 else 's'} of {label} carried another date in {field_name}; "
            f"loaded under {day.isoformat()}")


@dataclass
class Layout:
    """A tar archive's layout: table -> date -> member names (in name order), the problems (any refuses the archive), the ignored litter."""
    tables: dict = field(default_factory=dict)
    errors: list = field(default_factory=list)
    ignored: list = field(default_factory=list)


def tar_layout(entries, today: date | None = None, known=None) -> Layout:
    """The layout of a tar archive's entries ``[(raw name, type)]`` (type ``file``, ``dir`` or what else it is). ``known``: the tables
    the load knows (a name compared without case: the known spelling is kept), None: any table name."""
    out = Layout()
    canon = {k.lower(): k for k in known} if known is not None else None
    unknown = []
    for raw, typ in entries:
        n = str(raw).replace("\\", "/")
        if n.startswith("/") or re.match(r"^[A-Za-z]:", n) or "\x00" in n:
            out.errors.append(f"{raw}: an absolute path")
            continue
        parts = [p for p in n.split("/") if p not in ("", ".")]
        if ".." in parts:
            out.errors.append(f"{raw}: a '..' in its path")
            continue
        if not parts or typ == "dir":
            continue
        if any(p.startswith(".") or p in LITTER for p in parts):
            out.ignored.append(raw)
            continue
        if typ != "file":
            out.errors.append(f"{raw}: {typ}, not a file")
            continue
        if len(parts) != 3:
            where = "at the top" if len(parts) == 1 else f"{len(parts) - 1} folder{'s' if len(parts) > 2 else ''} deep"
            out.errors.append(f"{raw}: {where}; every file is <table>/<YYYY-MM-DD>/<file>.jsonl")
            continue
        table, folder, file = parts
        if not _JSONL_MEMBER.search(file):
            out.errors.append(f"{raw}: not JSON Lines (.jsonl, .ndjson, .json; plain, .gz or .zst)")
            continue
        if not _DATEISH.match(folder):
            out.errors.append(f"{raw}: the folder {folder} is not a date (YYYY-MM-DD)")
            continue
        day, why = _date(folder, today)
        if why:
            out.errors.append(f"{raw}: {why}")
            continue
        if not _TABLE.match(table):
            out.errors.append(f"{raw}: the folder {table} is not a table name (letters, digits, _ and -)")
            continue
        if canon is not None:
            if table.lower() not in canon:
                if table not in unknown:
                    unknown.append(table)
                continue
            table = canon[table.lower()]
        else:
            table = table.lower()
        out.tables.setdefault(table, {}).setdefault(day, []).append("/".join(parts))
    for t in unknown:
        out.errors.append(f"{t}/: no such table (known: {', '.join(sorted(known, key=str.lower)) or 'none'})")
    for t in out.tables.values():
        for d in t:
            t[d].sort()
    return out
