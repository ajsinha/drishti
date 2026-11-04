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

"""Archives as loader input: ``.zip``, ``.tar.gz``, ``.tgz`` and ``.tar``, read in place (nothing is unpacked to disk).

The rule is one for every loader, Python and Java (docs/guides/DATA_GUIDE.md, section 2.7 "Archives"; the Java side is
drishti-lakeload's ``Archives``). Both test suites run the same fixtures (``tools/lake/testdata/archives``, ``expected.json``):

* **Members.** A member named ``*.jsonl``, ``*.ndjson`` or ``*.json``, plain or compressed (``.gz``, ``.zst``), is JSON Lines; the
  members of a dataset are read in name order. Any other file is skipped with a note, and so are hidden files (a name part that starts
  with ``.``) and ``__MACOSX/``. Folder entries and a leading ``./`` are ignored.
* **Datasets.** No folders: the archive is one dataset named after the file (``trade.tar.gz``, ``trade.tgz``, ``trade.zip``,
  ``trade.tar`` -> ``trade``), unless the loader is given another name (``--dataset``). Folders: exactly one level deep, each folder a
  dataset named after it, its JSON Lines that dataset's. JSON Lines both loose and in folders: refused. A file deeper than one folder:
  refused. A folder name that is not a table name (letters, digits, ``.``, ``_``, ``-``), or that matches no dataset the loader has
  configured: refused, every such folder listed. No JSON Lines member at all: refused.
* **Guards.** A member name that is absolute, has a drive letter or a ``..`` part, a link or a device member, an encrypted member, and an
  archive inside the archive (archives are read one level deep): refused. Caps (``Limits``): at most ``max_members`` entries; the
  entries' declared sizes together at most ``max_mb`` MB and at most ``max_ratio`` times the archive's size (at least 1 MB is always
  allowed); and while reading, the bytes the archive expands to and, apart, the JSON Lines text of its compressed members, each held to
  the same two caps: reading stops the moment one is passed.

**The loaders' feeds** (``plan(..., layout="feed")``: ``lake build``, the parallel loader, the data inbox; DATA_GUIDE.md section 2.8)
are stricter: a tar (``.tar``, ``.tgz``, ``.tar.gz``, ``.tar.zip``) holds ``<table>/<YYYY-MM-DD>/<file>.jsonl`` and nothing else
(``filedate.tar_layout``: every problem listed, the whole archive refused), each member dated by its folder; a ``.zip`` is one table and
one date, loose JSON Lines files only (folders refused). The rule above (``layout="rows"``) stays for ``load_rows.py``.

Every check that needs no reading is made by ``plan`` before a row is read. ``DRISHTI_ARCHIVE_MAX_MB``, ``DRISHTI_ARCHIVE_MAX_RATIO``
and ``DRISHTI_ARCHIVE_MAX_MEMBERS`` change the caps' defaults."""
from __future__ import annotations

import gzip
import io
import os
import pathlib
import re
import stat
import tarfile
import types
import zipfile
from dataclasses import dataclass, field
from typing import Iterable, Iterator

ARCHIVE_SUFFIXES = (".tar.gz", ".tar.zip", ".tgz", ".tar", ".zip")
JSONL_SUFFIXES = (".jsonl", ".ndjson", ".json")
MEMBER_COMPRESSION = ("", ".gz", ".zst")
NESTED_SUFFIXES = (".zip", ".tar", ".tgz", ".tar.gz", ".tar.zst", ".tar.bz2", ".tar.xz", ".7z", ".rar")
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
FLOOR = 1 << 20                     # the ratio cap always allows this many bytes
DEFAULT_MAX_MB = 102400
DEFAULT_MAX_RATIO = 200.0
DEFAULT_MAX_MEMBERS = 100000


class ArchiveError(ValueError):
    """An archive the rule refuses: the message says which member and why."""


@dataclass(frozen=True)
class Limits:
    """The zip-bomb caps (DATA_GUIDE.md section 2.7)."""
    max_mb: float = DEFAULT_MAX_MB
    max_ratio: float = DEFAULT_MAX_RATIO
    max_members: int = DEFAULT_MAX_MEMBERS

    @classmethod
    def from_env(cls, env=None, **overrides) -> "Limits":
        """The defaults, then ``DRISHTI_ARCHIVE_MAX_*``, then ``overrides`` (keys max_mb, max_ratio, max_members; None is no override)."""
        e = os.environ if env is None else env
        kw = {"max_mb": float(e.get("DRISHTI_ARCHIVE_MAX_MB") or DEFAULT_MAX_MB),
              "max_ratio": float(e.get("DRISHTI_ARCHIVE_MAX_RATIO") or DEFAULT_MAX_RATIO),
              "max_members": int(e.get("DRISHTI_ARCHIVE_MAX_MEMBERS") or DEFAULT_MAX_MEMBERS)}
        kw.update({k: v for k, v in overrides.items() if v is not None})
        if kw["max_mb"] <= 0 or kw["max_ratio"] <= 0 or kw["max_members"] <= 0:
            raise ValueError("the archive caps (max-mb, max-ratio, max-members) must be more than 0")
        return cls(float(kw["max_mb"]), float(kw["max_ratio"]), int(kw["max_members"]))

    def cap(self, archive_bytes: int) -> int:
        """The most bytes one stage may expand to: max_mb, and max_ratio times the archive's size (at least FLOOR)."""
        return int(min(self.max_mb * 1048576, self.max_ratio * max(archive_bytes, FLOOR)))


def is_archive(name: str) -> bool:
    return str(name).lower().endswith(ARCHIVE_SUFFIXES)


def archive_stem(name: str) -> str:
    """``trade.tar.gz`` -> ``trade`` (the dataset of an archive without folders)."""
    base = pathlib.PurePath(str(name)).name
    low = base.lower()
    for s in ARCHIVE_SUFFIXES:
        if low.endswith(s):
            return base[: len(base) - len(s)]
    return base


def valid_name(name: str) -> bool:
    """A dataset name that can be a table's folder: letters, digits, '.', '_' and '-', not starting with '.', '_' or '-'."""
    return bool(NAME.match(name or "")) and name not in (".", "..")


def is_jsonl(name: str) -> bool:
    low = name.lower()
    return any(low.endswith(j + c) for j in JSONL_SUFFIXES for c in MEMBER_COMPRESSION)


def is_nested(name: str) -> bool:
    return name.lower().endswith(NESTED_SUFFIXES)


def safe_parts(raw: str) -> list[str]:
    """The member's path as parts ('\\' read as '/', '.' parts and a leading './' dropped); raises for an unsafe name."""
    n = raw.replace("\\", "/")
    if "\x00" in n or n.startswith("/") or re.match(r"^[A-Za-z]:", n):
        raise ArchiveError(f"member {raw!r}: an absolute path (unsafe; nothing was read)")
    parts = [p for p in n.split("/") if p not in ("", ".")]
    if ".." in parts:
        raise ArchiveError(f"member {raw!r}: a '..' in its path (unsafe; nothing was read)")
    return parts


@dataclass
class Member:
    """One JSON Lines member: its archive, its name in the archive (normalised), its dataset and its declared size."""
    archive: pathlib.Path
    name: str
    dataset: str
    size: int
    raw: str = ""
    tally: tuple = field(default=None, repr=False, compare=False)   # (expanded, text): the archive's counts, shared by its members
    date: object = None                                             # a tar feed's member: the business date of its folder

    def __str__(self) -> str:
        return f"{self.archive}!/{self.name}"

    @property
    def label(self) -> str:
        """How a reject names it: ``trade.zip!/trade/a.jsonl``."""
        return f"{self.archive.name}!/{self.name}"

    def stat(self):
        """As a file's: ``st_size`` is the member's declared (uncompressed) size."""
        return types.SimpleNamespace(st_size=self.size)


@dataclass
class Plan:
    """What an archive holds: its datasets (name -> members in name order), whether they came from folders, and the notes."""
    archive: pathlib.Path
    datasets: dict[str, list[Member]]
    folders: bool
    notes: list[str] = field(default_factory=list)
    size: int = 0
    kind: str = ""

    def require(self, configured: Iterable[str]) -> None:
        """Refuses every dataset that is not one of ``configured`` (folders, or the one named after the archive), listing them all."""
        known = list(configured)
        unknown = [d for d in self.datasets if d not in known]
        if unknown:
            what = "folder" if self.folders else "dataset"
            raise ArchiveError(f"{self.archive.name}: {what}{'s' if len(unknown) > 1 else ''} {', '.join(sorted(unknown))} "
                               f"match{'' if len(unknown) > 1 else 'es'} no configured dataset ({', '.join(known) or 'none'}); nothing was loaded")

    def members(self) -> list[Member]:
        return [m for d in sorted(self.datasets) for m in self.datasets[d]]


def _kind(path: pathlib.Path) -> str:
    low = path.name.lower()
    if low.endswith(".tar.zip"):
        return "tarzip"
    if low.endswith(".zip"):
        return "zip"
    if low.endswith((".tar.gz", ".tgz")):
        return "tgz"
    return "tar"


def _tar_in_zip(z: zipfile.ZipFile, name: str) -> zipfile.ZipInfo:
    """The one .tar member of a .tar.zip."""
    tars = [i for i in z.infolist() if not i.is_dir() and i.filename.lower().endswith(".tar")]
    if len(tars) != 1 or len([i for i in z.infolist() if not i.is_dir()]) != 1:
        raise ArchiveError(f"{name}: a .tar.zip holds exactly one .tar file")
    return tars[0]


def _open_tar(path: pathlib.Path, kind: str):
    """(the tar, the zip holding it or None)."""
    if kind == "tarzip":
        z = zipfile.ZipFile(path)
        try:
            return tarfile.open(fileobj=z.open(_tar_in_zip(z, path.name)), mode="r:"), z
        except BaseException:
            z.close()
            raise
    return tarfile.open(path, "r:gz" if kind == "tgz" else "r:"), None


def _entries(path: pathlib.Path, kind: str, limits: Limits) -> list[tuple[str, int, str]]:
    """(raw name, declared size, type) of every entry: type file, dir, or a reason it is refused."""
    out = []
    if kind == "zip":
        with zipfile.ZipFile(path) as z:
            infos = z.infolist()
            if len(infos) > limits.max_members:
                raise ArchiveError(f"{path.name}: {len(infos):,} entries, more than the cap of {limits.max_members:,} (max-members)")
            for i in infos:
                mode = (i.external_attr >> 16) & 0xFFFF
                if i.is_dir():
                    t = "dir"
                elif stat.S_ISLNK(mode):
                    t = "a link"
                elif i.flag_bits & 0x1:
                    t = "encrypted"
                else:
                    t = "file"
                out.append((i.filename, i.file_size, t))
        return out
    t, z = _open_tar(path, kind)
    try:
        n = 0
        for i in t:
            n += 1
            if n > limits.max_members:
                raise ArchiveError(f"{path.name}: more than the cap of {limits.max_members:,} entries (max-members)")
            kind_ = "dir" if i.isdir() else "file" if i.isreg() else "a link" if (i.issym() or i.islnk()) else "a device or special file"
            out.append((i.name, i.size if i.isreg() else 0, kind_))
    finally:
        t.close()
        if z:
            z.close()
    return out


def plan(path, dataset: str | None = None, configured: Iterable[str] | None = None, limits: Limits | None = None, layout: str = "rows",
         today=None) -> Plan:
    """The archive's datasets by the rule (module doc), every check that needs no reading made. ``dataset`` names the dataset of an
    archive without folders (refused for one with folders); ``configured`` (when given) are the datasets the loader knows. ``layout``:
    ``rows`` (the rule above) or ``feed`` (a loader's feed: a tar of <table>/<YYYY-MM-DD>/, a zip of one table and one date)."""
    path = pathlib.Path(path)
    limits = limits or Limits.from_env()
    if not is_archive(path.name):
        raise ArchiveError(f"{path.name}: not an archive (.zip, .tar.gz, .tgz, .tar)")
    size = path.stat().st_size
    kind = _kind(path)
    try:
        entries = _entries(path, kind, limits)
    except (zipfile.BadZipFile, tarfile.TarError, EOFError, gzip.BadGzipFile, OSError) as e:
        raise ArchiveError(f"{path.name}: not a readable {kind} archive ({e})") from None
    declared = sum(s for _, s, _ in entries)
    cap = limits.cap(size)
    if declared > cap:
        why = (f"more than max-mb {limits.max_mb:g}" if declared > limits.max_mb * 1048576
               else f"more than max-ratio {limits.max_ratio:g} times the archive's {size:,} bytes")
        raise ArchiveError(f"{path.name}: its entries expand to {declared:,} bytes, {why} (a zip bomb?); nothing was read")
    if layout == "feed" and kind != "zip":
        return _tar_feed(path, kind, entries, size, configured, today)
    notes: list[str] = []
    loose: list[Member] = []
    foldered: dict[str, list[Member]] = {}
    other_folders: set[str] = set()
    for raw, sz, t in entries:
        parts = safe_parts(raw)
        if t == "dir" or not parts:
            continue
        if t != "file":
            raise ArchiveError(f"{path.name}: member {raw!r} is {t} (unsafe; nothing was read)")
        if any(p.startswith(".") or p == "__MACOSX" for p in parts):
            notes.append(f"{raw}: skipped (hidden)")
            continue
        if len(parts) > 2:
            raise ArchiveError(f"{path.name}: member {raw!r} is {len(parts) - 1} folders deep; folders are datasets, exactly one level deep")
        name = "/".join(parts)
        if is_nested(name):
            raise ArchiveError(f"{path.name}: member {raw!r} is an archive inside the archive; archives are read one level deep (unpack it first)")
        if not is_jsonl(name):
            notes.append(f"{name}: skipped (not JSON Lines)")
            if len(parts) == 2:
                other_folders.add(parts[0])
            continue
        if len(parts) == 1:
            loose.append(Member(path, name, "", sz, raw))
        else:
            foldered.setdefault(parts[0], []).append(Member(path, name, parts[0], sz, raw))
    if loose and foldered:
        raise ArchiveError(f"{path.name}: JSON Lines both loose ({', '.join(sorted(m.name for m in loose)[:3])}) and in folders "
                           f"({', '.join(sorted(foldered))}): a mixed archive is refused; put every file in a folder (one per dataset) or none")
    if not loose and not foldered:
        raise ArchiveError(f"{path.name}: no JSON Lines member (*.jsonl, *.ndjson, *.json, plain or .gz/.zst)")
    for f in sorted(other_folders - set(foldered)):
        notes.append(f"{f}/: holds no JSON Lines member, not a dataset")
    if foldered and layout == "feed":
        raise ArchiveError(f"{path.name}: a .zip is one table and one business date, its JSON Lines files loose; this one has folders "
                           f"({', '.join(sorted(foldered))}): several tables or dates go in a .tar of <table>/<YYYY-MM-DD>/ folders")
    if foldered:
        if dataset:
            raise ArchiveError(f"{path.name}: --dataset names the one dataset of an archive without folders; this one has folders "
                               f"({', '.join(sorted(foldered))}), each a dataset")
        bad = sorted(f for f in foldered if not valid_name(f))
        if bad:
            raise ArchiveError(f"{path.name}: folder{'s' if len(bad) > 1 else ''} {', '.join(repr(b) for b in bad)} cannot name a table "
                               "(letters, digits, '.', '_' and '-')")
        datasets = {d: sorted(ms, key=lambda m: m.name) for d, ms in sorted(foldered.items())}
    else:
        name = dataset or archive_stem(path.name)
        datasets = {name: sorted((Member(path, m.name, name, m.size, m.raw) for m in loose), key=lambda m: m.name)}
    tally = ([0], [0])
    for ms in datasets.values():
        for m in ms:
            m.tally = tally
    p = Plan(path, datasets, bool(foldered), notes, size, kind)
    if configured is not None:
        p.require(configured)
    return p


def _tar_feed(path: pathlib.Path, kind: str, entries, size: int, configured, today) -> Plan:
    """A tar feed's plan: one dataset per table, each member dated by its folder (filedate.tar_layout); any problem refuses the archive."""
    import filedate
    for raw, _sz, t in entries:                       # the guards first: an unsafe member refuses the archive before anything else
        safe_parts(raw)
    lay = filedate.tar_layout([(raw, t) for raw, _sz, t in entries], today, None if configured is None else list(configured))
    if lay.errors:
        raise ArchiveError(f"{path.name}: refused, nothing was loaded ({len(lay.errors)} problem{'s' if len(lay.errors) > 1 else ''}): "
                           + "; ".join(lay.errors))
    by_name = {"/".join(safe_parts(raw)): (raw, sz) for raw, sz, t in entries if t == "file"}
    datasets: dict[str, list[Member]] = {}
    for table, days in sorted(lay.tables.items()):
        ms = []
        for day, names in sorted(days.items()):
            for n in names:
                raw, sz = by_name[n]
                if is_nested(n):
                    raise ArchiveError(f"{path.name}: member {raw!r} is an archive inside the archive; archives are read one level deep")
                ms.append(Member(path, n, table, sz, raw, date=day))
        datasets[table] = ms
    tally = ([0], [0])
    for ms in datasets.values():
        for m in ms:
            m.tally = tally
    return Plan(path, datasets, True, [f"{r}: skipped (litter)" for r in lay.ignored], size, kind)


class _Capped(io.RawIOBase):
    """Counts what a stream gives into a shared tally and stops it the moment the tally passes its cap."""

    def __init__(self, raw, tally: list[int], cap: int, what: str):
        self.raw, self.tally, self.cap, self.what = raw, tally, cap, what

    def readable(self):
        return True

    def readinto(self, b):
        n = self.raw.readinto(b) if hasattr(self.raw, "readinto") else self._copy(b)
        self.tally[0] += n or 0
        if self.tally[0] > self.cap:
            raise ArchiveError(f"{self.what} expands past its cap of {self.cap:,} bytes (max-mb, max-ratio): a zip bomb? Reading stopped")
        return n

    def _copy(self, b):
        data = self.raw.read(len(b))
        b[: len(data)] = data
        return len(data)

    def close(self):
        try:
            self.raw.close()
        finally:
            super().close()


class Archive:
    """An archive open for reading its members in turn (one handle, so a .tar.gz is not decompressed once per member)."""

    def __init__(self, plan_: Plan, limits: Limits | None = None):
        self.plan, self.limits = plan_, limits or Limits.from_env()
        self.cap = self.limits.cap(plan_.size)
        self.expanded, self.text = [0], [0]
        self._z = self._t = self._outer = None

    def __enter__(self):
        if self.plan.kind == "zip":
            self._z = zipfile.ZipFile(self.plan.archive)
        else:
            self._t, self._outer = _open_tar(self.plan.archive, self.plan.kind)
        return self

    def __exit__(self, *exc):
        (self._z or self._t).close()
        if self._outer:
            self._outer.close()

    def open(self, m: Member):
        """The member's bytes as JSON Lines (decompressed when it is .gz or .zst), held to the caps."""
        where = m.label
        try:
            raw = self._z.open(m.raw) if self._z else self._t.extractfile(m.raw)
        except (KeyError, RuntimeError, NotImplementedError) as e:
            raise ArchiveError(f"{where}: cannot be read ({e})") from None
        expanded, text = m.tally or (self.expanded, self.text)
        outer = io.BufferedReader(_Capped(raw, expanded, self.cap, f"{self.plan.archive.name}"), 1 << 16)
        low = m.name.lower()
        if low.endswith(".gz"):
            inner = gzip.GzipFile(fileobj=outer, mode="rb")
        elif low.endswith(".zst"):
            try:
                import zstandard
            except ImportError:
                raise ArchiveError(f"{where}: a .zst member needs the zstandard package (pip install zstandard), "
                                   "or load it with the Java loader") from None
            inner = zstandard.ZstdDecompressor().stream_reader(outer)
        else:
            return outer
        return io.BufferedReader(_Capped(inner, text, self.cap, f"{where}'s JSON Lines"), 1 << 16)

    def lines(self, m: Member) -> Iterator[str]:
        """The member's lines as text (UTF-8, a byte-order mark dropped), newline kept off."""
        with io.TextIOWrapper(self.open(m), encoding="utf-8-sig", newline="") as fh:
            for line in fh:
                yield line.rstrip("\r\n")


def read_lines(plan_: Plan, limits: Limits | None = None) -> Iterator[tuple[str, Member, int, str]]:
    """Every line of every dataset, datasets by name and members in name order: (dataset, member, line number, text)."""
    with Archive(plan_, limits) as a:
        for d in sorted(plan_.datasets):
            for m in plan_.datasets[d]:
                for n, line in enumerate(a.lines(m), 1):
                    yield d, m, n, line


def row_counts(plan_: Plan, limits: Limits | None = None) -> dict[str, int]:
    """Non-blank lines per dataset (what a loader reads as rows)."""
    out = {d: 0 for d in plan_.datasets}
    for d, _m, _n, line in read_lines(plan_, limits):
        if line.strip():
            out[d] += 1
    return out
