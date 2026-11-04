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

"""Our own feed files, written and read by the load rules (docs/architecture/DATA_LOAD_RULES.md, section 10): the one place the
generators (tools/testdata, tools/packgen, tools/publicdata) write a table's dated files, and the demo lake builder, the tests and the
benchmarks read them back.

**Written** (``write_table``), into one folder per table, every earlier feed file of the folder removed first:

* one file per business date when every date has one file: ``<table>.<YYYY-MM-DD>.jsonl.gz``;
* else tar archives of ``<table>/<YYYY-MM-DD>/<file>.jsonl.gz`` folders (a date in several files, such as the P&L vectors' parts and the
  stress set's own file, or a table of many dates that one run writes, such as a desk's daily P&L over a year): ``tar="table"`` one
  archive ``<table>.tar`` for every date, ``tar="date"`` one archive per date ``<table>-<YYYY-MM-DD>.tar`` (a table whose single date is
  already large: every archive stays small enough to commit, and a date is never split across archives).

Everything is deterministic: gzip without a name or a time stamp, tar members in name order with no time, owner or mode of their own,
so the same rows write the same bytes.

**Read** (``read_table``, ``days``, ``lines``): a folder of such files, or one file, as ``{date: [Part]}``, the parts of each date in name
order; a tar is read through ``archives.plan(layout="feed")``, so a file these functions read is one ``lake build`` loads the same way."""
from __future__ import annotations

import gzip
import io
import pathlib
import re
import sys
import tarfile
from dataclasses import dataclass
from typing import Iterable, Iterator

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import filedate  # noqa: E402

SUFFIX = ".jsonl.gz"
TAR = ".tar"
_ISO = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def gz_bytes(data: str | bytes | Iterable[str]) -> bytes:
    """Deterministic gzip (no name, time 0, level 9) of text, bytes or lines (each written with a newline)."""
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
        if isinstance(data, bytes):
            gz.write(data)
        elif isinstance(data, str):
            gz.write(data.encode("utf-8"))
        else:
            for line in data:
                gz.write(line.encode("utf-8") + b"\n")
    return buf.getvalue()


def gz_parts(stem: str, lines: list[str], part_bytes: int) -> list[tuple[str, bytes]]:
    """One date's lines as (file name, deterministic gzip) parts of about ``part_bytes`` compressed each (about 3:1 on our JSON):
    ``<stem>.jsonl.gz``, or ``<stem>.part-NNN.jsonl.gz`` when it takes several; ``write_table`` writes them as the date's files."""
    parts: list[list[str]] = [[]]
    size = 0
    for ln in lines:
        if size > part_bytes * 3 and parts[-1]:
            parts.append([])
            size = 0
        parts[-1].append(ln)
        size += len(ln)
    return [(f"{stem}{SUFFIX}" if len(parts) == 1 else f"{stem}.part-{i:03d}{SUFFIX}", gz_bytes("\n".join(chunk) + "\n"))
            for i, chunk in enumerate(parts, 1)]


def plain_name(table: str, day: str) -> str:
    """``<table>.<YYYY-MM-DD>.jsonl.gz``: a file of one table and one business date."""
    _check(table, day)
    return f"{table}.{day}{SUFFIX}"


def tar_name(table: str, day: str | None = None) -> str:
    """``<table>.tar`` (every date of the table) or ``<table>-<YYYY-MM-DD>.tar`` (one date): the name labels the archive only; its
    tables and dates are its folders."""
    if day is not None:
        _check(table, day)
        return f"{table}-{day}{TAR}"
    _check(table, None)
    return f"{table}{TAR}"


def _check(table: str, day: str | None) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_-]+", table or ""):
        raise ValueError(f"{table!r}: a table name is letters, digits, _ and - (no '.')")
    if day is not None and not _ISO.match(day):
        raise ValueError(f"{day!r}: a business date is YYYY-MM-DD")


def is_feed_file(p: pathlib.Path) -> bool:
    """A file ``write_table`` writes (and so removes before writing again): a dated ``.jsonl.gz`` or a ``.tar``, or the older names
    (``snapshot-``, ``changes-``, ``cob-``, ``history-``, ``stress-``) that it replaces."""
    n = p.name.lower()
    return p.is_file() and (n.endswith(SUFFIX) or n.endswith(TAR))


def write_table(folder: pathlib.Path, table: str, days: dict[str, list[tuple[str, bytes]]], tar: str | None = None,
                clear: bool = True) -> list[pathlib.Path]:
    """Writes one table's dates into ``folder`` (module doc) and returns the files written, in name order. ``days``: date -> the date's
    files as (member file name, gzip bytes), in order; ``tar``: None (one file a date; a date with several files makes it "table"),
    "table" or "date". ``clear``: the folder's earlier feed files removed first."""
    folder.mkdir(parents=True, exist_ok=True)
    if clear:
        for old in [f for f in folder.iterdir() if is_feed_file(f)]:
            old.unlink()
    days = {d: list(v) for d, v in sorted(days.items()) if v}
    if tar is None and any(len(v) > 1 for v in days.values()):
        tar = "table"
    if tar not in (None, "table", "date"):
        raise ValueError(f"tar={tar!r}: None, 'table' or 'date'")
    out: list[pathlib.Path] = []
    if tar is None:
        for d, ((_member, data),) in days.items():
            p = folder / plain_name(table, d)
            p.write_bytes(data)
            out.append(p)
        return out
    groups = {None: days} if tar == "table" else {d: {d: v} for d, v in days.items()}
    for label_day, group in groups.items():
        p = folder / tar_name(table, label_day)
        _write_tar(p, table, group)
        out.append(p)
    return sorted(out)


def _write_tar(path: pathlib.Path, table: str, days: dict[str, list[tuple[str, bytes]]]) -> None:
    path.write_bytes(tar_bytes(table, days))


def tar_bytes(table: str, days: dict[str, list[tuple[str, bytes]]]) -> bytes:
    """A deterministic tar of ``<table>/<date>/<member>`` files (no folder entries, no time, owner or mode of their own)."""
    members = []
    for d, files in days.items():
        seen = set()
        for name, data in files:
            if "/" in name or not filedate._JSONL_MEMBER.search(name):
                raise ValueError(f"{name!r}: a member is a JSON Lines file name (.jsonl, .jsonl.gz), no folders")
            if name in seen:
                raise ValueError(f"{table}/{d}/{name}: twice")
            seen.add(name)
            members.append((f"{table}/{d}/{name}", data))
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as t:
        for name, data in sorted(members):
            info = tarfile.TarInfo(name)
            info.size, info.mtime, info.mode, info.uid, info.gid, info.uname, info.gname = len(data), 0, 0o644, 0, 0, "", ""
            t.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def pack_tar(path: pathlib.Path, table: str, days: dict[str, list[pathlib.Path]], remove: bool = True) -> pathlib.Path:
    """A deterministic tar of files already on disk (``days``: date -> the date's .jsonl.gz files), streamed (a large date never held in
    memory): ``<table>/<date>/<file name>``; ``remove``: the files deleted once packed."""
    _check(table, None)
    with open(path, "wb") as raw, tarfile.open(fileobj=raw, mode="w", format=tarfile.USTAR_FORMAT) as t:
        for d in sorted(days):
            _check(table, d)
            for f in sorted(days[d], key=lambda x: x.name):
                info = tarfile.TarInfo(f"{table}/{d}/{f.name}")
                info.size, info.mtime, info.mode, info.uid, info.gid, info.uname, info.gname = f.stat().st_size, 0, 0o644, 0, 0, "", ""
                with open(f, "rb") as fh:
                    t.addfile(info, fh)
    if remove:
        for fs in days.values():
            for f in fs:
                f.unlink()
    return path


# ---------------------------------------------------------------------------------------------------------------------------- reading
@dataclass(frozen=True)
class Part:
    """One file of a table's date: a plain dated file, or a tar's member (``member`` its name in the archive)."""
    path: pathlib.Path
    table: str
    date: str
    member: str | None = None

    @property
    def label(self) -> str:
        return f"{self.path.name}!/{self.member}" if self.member else self.path.name

    def open(self):
        """The part's text, decompressed (a binary stream of JSON Lines)."""
        if self.member is None:
            return gzip.open(self.path, "rb") if self.path.name.lower().endswith(".gz") else open(self.path, "rb")
        t = tarfile.open(self.path, "r:")
        data = t.extractfile(self.member).read()
        t.close()
        return io.BytesIO(gzip.decompress(data) if self.member.lower().endswith(".gz") else data)

    def lines(self) -> Iterator[str]:
        """The part's non-blank lines, newline off."""
        with self.open() as fh:
            for raw in fh:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.strip():
                    yield line


def read_table(path: pathlib.Path, table: str | None = None) -> dict[str, list[Part]]:
    """``{date: [Part]}`` of a folder of feed files (or one file), dates and parts in order; ``table``: only that table (a tar's other
    folders, a file of another table, are left out). A name the rules refuse raises ValueError."""
    path = pathlib.Path(path)
    files = sorted(f for f in path.iterdir() if f.is_file()) if path.is_dir() else [path]
    out: dict[str, list[Part]] = {}
    for f in files:
        low = f.name.lower()
        if filedate.is_tar(f.name):
            import archives
            plan = archives.plan(f, layout="feed")
            for t, members in plan.datasets.items():
                if table is None or t == table.lower():
                    for m in members:
                        out.setdefault(m.date.isoformat(), []).append(Part(f, t, m.date.isoformat(), m.name))
        elif low.endswith((".jsonl", ".jsonl.gz", ".ndjson", ".ndjson.gz")):
            n = filedate.parse(f.name)
            if n.error:
                raise ValueError(n.error)
            if n.date is None:
                raise ValueError(f"{f.name}: a feed file of ours carries its business date (<table>.<YYYY-MM-DD>.jsonl.gz)")
            if table is None or n.table == table.lower():
                out.setdefault(n.date.isoformat(), []).append(Part(f, n.table, n.date.isoformat()))
    return {d: sorted(v, key=lambda p: (p.path.name, p.member or "")) for d, v in sorted(out.items())}


def days(path: pathlib.Path, table: str | None = None) -> list[str]:
    """The business dates a folder (or file) holds, in order."""
    return list(read_table(path, table))


def lines(path: pathlib.Path, day: str | None = None, table: str | None = None) -> Iterator[tuple[str, str]]:
    """(date, line) of every non-blank line, dates in order (``day``: only that date)."""
    for d, parts in read_table(path, table).items():
        if day is None or d == day:
            for p in parts:
                for line in p.lines():
                    yield d, line


def files(path: pathlib.Path) -> list[pathlib.Path]:
    """The feed files of a folder (plain dated files and tars), in name order."""
    path = pathlib.Path(path)
    return sorted(f for f in path.iterdir() if is_feed_file(f)) if path.is_dir() else [path]


def contents(path: pathlib.Path) -> dict[str, bytes]:
    """``{"<date>/<file>": decompressed bytes}`` of every part: what a --check compares (the same rows, whatever zlib wrote)."""
    out = {}
    for d, parts in read_table(path).items():
        for p in parts:
            with p.open() as fh:
                out[f"{d}/{p.member.rsplit('/', 1)[-1] if p.member else p.path.name}"] = fh.read()
    return out


def write_documents(folder: pathlib.Path, table: str, docs: Iterable[dict], date_of=None) -> list[pathlib.Path]:
    """A producer's documents of one table as feed files in ``folder`` (nothing removed first): with ``date_of`` (the producer's own
    reading of a document's business date, such as the date field the author of a pack names: an ISO date, a time stamp or YYYYMMDD,
    or None) one file per business date, ``<table>.<YYYY-MM-DD>.jsonl.gz``; the documents it dates nothing for, and every document
    without ``date_of``, in ``<table>.jsonl.gz``, which the loaders date by the rules (--as-of, the .ready file, the business today).
    Pack make and schema to pack write their samples so: the loader never reads a row's date."""
    import json
    by_day: dict[str, list[str]] = {}
    undated: list[str] = []
    for d in docs:
        line = json.dumps(d, ensure_ascii=False)
        day = _iso(date_of(d)) if date_of else None
        (by_day.setdefault(day, []) if day else undated).append(line)
    out = write_table(folder, table, {d: [(f"{table}{SUFFIX}", gz_bytes(v))] for d, v in by_day.items()}, clear=False) if by_day else []
    if undated:
        _check(table, None)
        p = pathlib.Path(folder) / f"{table}{SUFFIX}"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(gz_bytes(undated))
        out.append(p)
    return sorted(out)


def _iso(value) -> str | None:
    """An ISO date (YYYY-MM-DD) from a producer's date value (ISO, an ISO time stamp, or YYYYMMDD), None when it is none."""
    if value is None:
        return None
    s = str(value).strip()
    if len(s) >= 8 and s[:8].isdigit():
        s = f"{s[:4]}-{s[4:6]}-{s[6:8]}"
    s = s[:10]
    if not _ISO.match(s):
        return None
    try:
        import datetime
        datetime.date.fromisoformat(s)
    except ValueError:
        return None
    return s
