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

"""The files of one kind and one business date, as the data inbox and ``lake build`` take them (docs/guides/BANK_DATA_GUIDE.md section 6.4):

  <kind>.<date>.jsonl            the records, plain JSON Lines (to read and to diff; left out with plain=False)
  <kind>.<date>.jsonl.gz         the same records, deterministic gzip: the file that is loaded
  <kind>.<date>.jsonl.gz.ready   its marker: pack, kind, mode, rows, sha256 (and lake when named)

The name is the routing: the inbox reads the table and the business date from ``<table>.<YYYY-MM-DD>.<extension>``. A tar is written only
for a date split into parts (``rows_per_part``): ``<kind>-<date>.tar`` of ``<kind>/<date>/part-NNNN.jsonl.gz``, which loads completely or not
at all. ``mr-<date>.sha256`` lists every loadable file of the day's folder."""
from __future__ import annotations

import gzip
import json
from pathlib import Path

from loaders.mrloader.common import PvfError, dumps, make_tar, sha256_of

#: the folder of one business date's files, shared by every kind
FOLDER = "mr-{date}"


def day_folder(out: Path, business_date: str) -> Path:
    """``<out>/mr-<date>/``: where every kind of the date is written."""
    return Path(out) / FOLDER.format(date=business_date)


def write_marker(path: Path, kind: str, pack: str, rows: int, lake: str | None = None, mode: str = "replace",
                 tar: bool = False) -> Path:
    """The ``.ready`` marker beside ``path``: only what the name cannot say (pack and kind, or a tar's folders), the mode and the checks
    (rows, sha256), and the lake when named. Never a business-date or table: the name says both."""
    ready = {"pack": pack, "kind": kind} if not tar else {"folders": {kind: pack}}
    ready.update({"mode": mode, "rows": rows, "sha256": sha256_of(path)})
    if lake:
        ready["lake"] = lake
    marker = Path(f"{path}.ready")
    marker.write_text("".join(f"{k}: {json.dumps(v)}\n" for k, v in ready.items()), encoding="utf-8")
    return marker


class KindWriter:
    """Streams the records of one kind and date into ``<kind>.<date>.jsonl`` (optional) and ``<kind>.<date>.jsonl.gz``, or, with
    ``rows_per_part``, into parts packed as one tar. ``finish`` closes them and writes the marker; the same records give the same bytes.

    ``name`` is the file name's table part (the kind by default; another only where one kind has two files of a date, such as a stress
    scenario set, whose marker then routes it by pack and kind)."""

    def __init__(self, folder: Path, kind: str, business_date: str, pack: str, plain: bool = True, rows_per_part: int | None = None,
                 name: str | None = None):
        if rows_per_part is not None and rows_per_part < 1:
            raise PvfError("rows per part must be 1 or more")
        self.folder, self.kind, self.date, self.pack = Path(folder), kind, business_date, pack
        self.name = name or kind
        self.per = rows_per_part
        self.rows = 0
        self.folder.mkdir(parents=True, exist_ok=True)
        self.plain_path = self.folder / f"{self.name}.{self.date}.jsonl" if plain else None
        self.gz_path = self.folder / f"{self.name}.{self.date}.jsonl.gz"
        self.tar_path = self.folder / f"{self.name}-{self.date}.tar"
        self.parts: list[Path] = []
        for old in (self.gz_path, Path(f"{self.gz_path}.ready"), self.tar_path, Path(f"{self.tar_path}.ready")):
            if old.exists():
                old.unlink()                                   # a re-run replaces the date's files, never mixes with them
        part_dir = self.folder / self.kind / self.date
        for old in sorted(part_dir.glob("part-*.jsonl.gz")) if part_dir.is_dir() else []:
            old.unlink()
        self._plain = open(self.plain_path, "w", encoding="utf-8") if self.plain_path else None
        self._raw = self._gz = None
        self._in_part = 0
        if self.per is None:
            self._open(self.gz_path)

    def _open(self, path: Path) -> None:
        self._close_gz()
        path.parent.mkdir(parents=True, exist_ok=True)
        self._raw = open(path, "wb")
        self._gz = gzip.GzipFile(filename="", mode="wb", fileobj=self._raw, mtime=0, compresslevel=6)
        self._in_part = 0

    def _close_gz(self) -> None:
        if self._gz is not None:
            self._gz.close()
            self._raw.close()
            self._gz = self._raw = None

    def write(self, record: dict) -> None:
        line = dumps(record) + "\n"
        if self.per is not None and (self._gz is None or self._in_part >= self.per):
            p = self.folder / self.kind / self.date / f"part-{len(self.parts) + 1:04d}.jsonl.gz"
            self.parts.append(p)
            self._open(p)
        self._gz.write(line.encode("utf-8"))
        if self._plain:
            self._plain.write(line)
        self._in_part += 1
        self.rows += 1

    def close(self) -> None:
        """Close the files without a marker (a run that is refused after writing leaves no .ready behind)."""
        self._close_gz()
        if self._plain:
            self._plain.close()
            self._plain = None

    def finish(self, lake: str | None = None, mode: str = "replace") -> Path:
        """Close the files and write the marker; the loadable file's path (the .jsonl.gz, or the tar of the parts)."""
        self.close()
        if self.per is None:
            write_marker(self.gz_path, self.kind, self.pack, self.rows, lake, mode)
            return self.gz_path
        tar = make_tar(self.tar_path, self.folder, self.parts)
        write_marker(tar, self.kind, self.pack, self.rows, lake, mode, tar=True)
        return tar

    def discard(self) -> None:
        """Close and remove what was written (a refused kind leaves nothing loadable behind)."""
        self.close()
        for p in [self.gz_path, self.tar_path, *self.parts, *([self.plain_path] if self.plain_path else [])]:
            if p.exists():
                p.unlink()


def write_records(folder: Path, kind: str, business_date: str, pack: str, records, lake: str | None = None, mode: str = "replace",
                  plain: bool = True, name: str | None = None) -> Path:
    """One kind of one date written whole (a small kind: a scenario set, the FX, a master); the loadable file's path."""
    w = KindWriter(folder, kind, business_date, pack, plain, name=name)
    try:
        for r in records:
            w.write(r)
    except BaseException:
        w.discard()
        raise
    return w.finish(lake, mode)


def loadable_files(folder: Path) -> list[Path]:
    """The loadable files of a day's folder (each ``.jsonl.gz`` or ``.tar`` that has its marker), by name."""
    folder = Path(folder)
    return sorted(p for p in folder.iterdir() if p.is_file() and (p.name.endswith(".jsonl.gz") or p.name.endswith(".tar"))
                  and Path(f"{p}.ready").is_file()) if folder.is_dir() else []


def write_sha256(folder: Path, business_date: str) -> Path:
    """``mr-<date>.sha256`` beside the files: the SHA-256 of every loadable file of the folder (``sha256sum -c`` reads it)."""
    sha = Path(folder) / f"{FOLDER.format(date=business_date)}.sha256"
    sha.write_text("".join(f"{sha256_of(p)}  {p.name}\n" for p in loadable_files(folder)), encoding="utf-8")
    return sha


def read_marker(path: Path) -> dict:
    """A marker's keys and values (the format write_marker writes: one ``key: JSON`` a line)."""
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            out[k.strip()] = json.loads(v.strip())
    return out
