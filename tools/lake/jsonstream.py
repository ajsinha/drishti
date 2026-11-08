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

"""Documents from JSON files of any size, one at a time, so memory stays flat however large the input is.

A file is read as a stream of documents:

* a JSON array (``[ {...}, {...} ]``, pretty-printed or on one line): its elements, decoded one by one from a window of the
  text (never the whole file); a nested array inside a document stays inside that document;
* JSON Lines: one document per line (blank lines skipped);
* either of them gzip-compressed (``.gz``, or any file starting with the gzip magic bytes);
* a folder: its ``*.json``, ``*.jsonl``, ``*.ndjson`` and ``*.gz`` files and its archives, sorted by name (``recursive`` for subfolders);
* an archive (``.zip``, ``.tar.gz``, ``.tgz``, ``.tar``): its JSON Lines members in name order, by the archive rule of
  docs/guides/DATA_GUIDE.md section 2.7 (``archives.py``); one table is read, so its folders, if any, must all be that table's.

The format is decided from the content, not the name: the first character that is not blank is ``[`` for an array. A single JSON
object spread over several lines (one document, pretty-printed) is read as one document.

Each document comes with where it was found (``file:line`` or ``file[n]``), so a rejected one can be named. An element that is not
an object, or a line that is not JSON, is a reject (``Bad``), never an error that stops the run; text that cannot be decoded as
JSON inside an array stops that file (nothing after it can be trusted) and is reported as one reject.

Only the standard library is used (``json.JSONDecoder.raw_decode`` on a sliding window)."""
from __future__ import annotations

import gzip
import io
import json
import pathlib
import sys
from dataclasses import dataclass
from typing import Iterator

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import archives  # noqa: E402

SUFFIXES = (".json", ".jsonl", ".ndjson", ".gz", ".zip", ".tgz", ".tar")
CHUNK = 1 << 20                     # characters read at a time
_WS = " \t\r\n"


@dataclass(frozen=True)
class Bad:
    """A reject: where it was and why."""
    where: str
    reason: str


def _key(f) -> str:
    return f"{f.archive.absolute()}!/{f.name}" if isinstance(f, archives.Member) else str(f.absolute())


def input_files(inputs, recursive: bool = False, table: str | None = None, limits: archives.Limits | None = None,
                tar_table: str | None = None, known=None) -> list:
    """The files named by ``inputs`` (files or folders), in one deterministic order: sorted by absolute path (as text), whatever the
    order of the inputs, so "the last one read" means the same in the Python path and the Java loader (CLI_REFERENCE.md section 4.13). An
    archive gives its JSON Lines members (``archives.Member``, its members after the archive's path, in name order); every archive is
    checked before any is read, as a loader's feed (DATA_GUIDE.md section 2.8): a zip is one table, loose files; a tar holds
    <table>/<YYYY-MM-DD>/ folders (``known``: its tables must be among them) and gives the members of ``tar_table`` only (none given: a
    tar holding a table other than ``table`` is refused)."""
    out: list = []
    for i in inputs if isinstance(inputs, (list, tuple)) else [inputs]:
        p = pathlib.Path(i)
        if p.is_dir():
            pattern = "**/*" if recursive else "*"
            out += [f for f in p.glob(pattern) if f.is_file() and f.name.lower().endswith(SUFFIXES)]
        elif p.is_file():
            out.append(p)
        else:
            raise FileNotFoundError(str(p))
    expanded: list = []
    for f in out:
        if not archives.is_archive(f.name):
            expanded.append(f)
            continue
        plan = archives.plan(f, limits=limits, layout="feed", configured=known)
        if plan.kind != "zip":
            if tar_table is None:
                if table:
                    plan.require([table])
                expanded += plan.members()
            else:
                expanded += plan.datasets.get(tar_table, [])
            continue
        expanded += plan.members()
    return sorted(expanded, key=_key)


class _Counted(io.RawIOBase):
    """Counts the bytes read from the file underneath (the compressed bytes for a .gz), for a progress bar."""

    def __init__(self, raw):
        self.raw, self.count = raw, 0

    def readable(self):
        return True

    def readinto(self, b):
        n = self.raw.readinto(b)
        self.count += n or 0
        return n

    def close(self):
        self.raw.close()
        super().close()


class _Closing(io.RawIOBase):
    """A member's stream that closes its archive with it."""

    def __init__(self, raw, archive):
        self.raw, self.archive = raw, archive

    def readable(self):
        return True

    def readinto(self, b):
        return self.raw.readinto(b)

    def close(self):
        try:
            self.raw.close()
            self.archive.__exit__(None, None, None)
        finally:
            super().close()


class Reader:
    """One file's documents. ``bytes_read`` and ``size`` (on disk) give the progress while iterating."""

    def __init__(self, path, chunk: int = CHUNK):
        self.member = path if isinstance(path, archives.Member) else None
        self.path, self.chunk = (path.archive if self.member else pathlib.Path(path)), chunk
        self.size = self.member.size if self.member else self.path.stat().st_size
        self.label = self.member.label if self.member else self.path.name
        self._counted: _Counted | None = None

    @property
    def bytes_read(self) -> int:
        return self._counted.count if self._counted else 0

    def _open(self):
        if self.member:                                          # an archive's member: the archive rule's reader, held to its caps
            plan = archives.Plan(self.member.archive, {self.member.dataset: [self.member]}, False, size=self.path.stat().st_size,
                                 kind=archives._kind(self.member.archive))
            arch = archives.Archive(plan).__enter__()
            self._counted = _Counted(_Closing(arch.open(self.member), arch))
            return io.TextIOWrapper(io.BufferedReader(self._counted, 1 << 16), encoding="utf-8-sig", errors="strict", newline="")
        raw = open(self.path, "rb")
        gz = raw.read(2) == b"\x1f\x8b"
        raw.seek(0)
        self._counted = _Counted(raw)
        buffered = io.BufferedReader(self._counted, 1 << 16)
        binary = gzip.GzipFile(fileobj=buffered, mode="rb") if gz else buffered
        return io.TextIOWrapper(binary, encoding="utf-8-sig", errors="strict", newline="")

    def __iter__(self) -> Iterator[tuple[object, str]]:
        """Yields (document, where) or (Bad, where)."""
        with self._open() as fh:
            head = fh.read(self.chunk)
            stripped = head.lstrip(_WS)
            if stripped.startswith("["):
                yield from self._array(fh, head)
            else:
                yield from self._lines(fh, head)

    # --------------------------------------------------------------------------------------------- JSON Lines

    def _lines(self, fh, head: str):
        name = self.label
        pending = head
        n = 0
        while True:
            more = fh.read(self.chunk)
            pending += more
            lines = pending.split("\n")
            pending = lines.pop() if more else ""
            for line in lines:
                n += 1
                text = line.strip()
                if not text:
                    continue
                try:
                    doc = json.loads(text)
                except ValueError as e:
                    if n == 1 and text.startswith("{"):          # one pretty-printed object: the rest of the file is that document
                        yield from self._single(fh, line + "\n" + "\n".join(lines[1:]) + ("\n" + pending if pending else ""))
                        return
                    yield Bad(f"{name}:{n}", f"not JSON ({e.msg})"), f"{name}:{n}"
                    continue
                yield (doc if isinstance(doc, dict) else Bad(f"{name}:{n}", "not a JSON object")), f"{name}:{n}"
            if not more:
                return

    def _single(self, fh, start: str):
        text = start + fh.read()
        where = f"{self.label}:1"
        try:
            doc = json.loads(text)
        except ValueError as e:
            yield Bad(where, f"not JSON ({e.msg} at line {e.lineno})"), where
            return
        yield (doc if isinstance(doc, dict) else Bad(where, "not a JSON object")), where

    # --------------------------------------------------------------------------------------------- JSON array

    def _array(self, fh, head: str):
        dec = json.JSONDecoder()
        name = self.label
        buf = head
        pos = buf.index("[") + 1
        eof = False
        n = 0
        want = self.chunk
        while True:
            # skip blanks and the comma between elements
            while True:
                while pos < len(buf) and buf[pos] in _WS:
                    pos += 1
                if pos < len(buf) and buf[pos] == "," and n:
                    pos += 1
                    continue
                break
            if pos >= len(buf):
                more = "" if eof else fh.read(want)
                if not more:
                    yield Bad(name, "the array is not closed (the file ends early)"), name
                    return
                buf, pos = more, 0
                continue
            if buf[pos] == "]":
                return
            try:
                doc, end = dec.raw_decode(buf, pos)
                if end == len(buf) and not eof and not isinstance(doc, (dict, list)):
                    raise ValueError("a value cut at the window's edge")   # 12 of 123: read on before deciding
            except ValueError as e:
                if not eof:
                    more = fh.read(want)
                    if more:
                        buf, pos = buf[pos:] + more, 0
                        want = min(want * 2, 1 << 28)            # a document larger than the window: read more at a time
                    else:
                        eof = True
                    continue
                where = f"{name}[{n + 1}]"
                yield Bad(where, f"not JSON ({getattr(e, 'msg', e)}); the rest of the file is skipped"), where
                return
            n += 1
            want = self.chunk
            where = f"{name}[{n}]"
            yield (doc if isinstance(doc, dict) else Bad(where, "not a JSON object")), where
            pos = end
            if pos > self.chunk:                                 # drop what was decoded, keep the window small
                buf, pos = buf[pos:], 0


def documents(inputs, recursive: bool = False, on_file=None) -> Iterator[tuple[object, str]]:
    """Every document of every file: (document or Bad, where). ``on_file(reader)`` is called as each file starts."""
    for f in input_files(inputs, recursive):
        r = Reader(f)
        if on_file:
            on_file(r)
        yield from r
