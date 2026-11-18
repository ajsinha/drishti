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

"""What the pvf scripts share: logging, the UPPER_SNAKE key rule, dates, JSONL readers and the deterministic .jsonl.gz and .tar writers."""
from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import io
import json
import logging
import re
import tarfile
from pathlib import Path
from typing import Any, Iterable, Iterator

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "modules" / "banking" / "packs" / "market-risk" / "contracts"
SCENARIO_PV_CONTRACT = CONTRACTS / "scenario-pv.contract.yaml"
SCENARIO_SET_CONTRACT = CONTRACTS / "scenario-set.contract.yaml"
DEFAULT_MAPPING = Path(__file__).resolve().parent / "mapping.yaml"

log = logging.getLogger("pvf")
_UPPER = re.compile(r"^[A-Z][A-Z0-9_]*$")


class PvfError(Exception):
    """A refusal: the run stops, says why in one line, exit 1."""


def setup_logging(verbose: bool = False) -> None:
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                        datefmt="%Y-%m-%dT%H:%M:%S")


def peak_memory_mb() -> float | None:
    """The process's peak resident memory in MB (None where the platform cannot say, e.g. Windows)."""
    try:
        import resource
        import sys
        kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return round(kb / (1024 * 1024 if sys.platform == "darwin" else 1024), 1)
    except (ImportError, OSError):
        return None


def sha256_of(path: Path) -> str:
    """The SHA-256 of a file, read in 1 MB blocks."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class Timer:
    """Seconds since it started; ``lap(what)`` logs a step's time."""

    def __init__(self) -> None:
        import time
        self._now = time.monotonic
        self.start = self.last = self._now()

    def lap(self, what: str) -> None:
        t = self._now()
        log.info("%s (%.1fs)", what, t - self.last)
        self.last = t

    @property
    def seconds(self) -> float:
        return round(self._now() - self.start, 1)


# ---- the UPPER_SNAKE rule: every attribute name the tools write ------------------------------------------------------------------
def upper_name(name: str) -> str:
    """A source column name in UPPER_SNAKE: ``src_position_id`` -> ``SRC_POSITION_ID``, ``Fo_portfolio_nm`` -> ``FO_PORTFOLIO_NM``."""
    out = re.sub(r"[^0-9A-Za-z]+", "_", name.strip()).strip("_").upper()
    if not out:
        raise PvfError(f"the column name {name!r} has no letters or digits")
    return out if out[0].isalpha() else "C_" + out


def upper_keys(obj: Any) -> Any:
    """The object with every key, at every depth, in UPPER_SNAKE; values unchanged. Two keys that become one are refused."""
    if isinstance(obj, dict):
        out: dict = {}
        for k, v in obj.items():
            u = upper_name(str(k))
            if u in out:
                raise PvfError(f"the keys {k!r} and another both become {u!r}")
            out[u] = upper_keys(v)
        return out
    if isinstance(obj, list):
        return [upper_keys(v) for v in obj]
    return obj


def lower_keys(obj: Any, path: str = "") -> list[str]:
    """The keys (dotted paths) of ``obj`` that are not UPPER_SNAKE: empty when the rule holds."""
    bad: list[str] = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            p = f"{path}.{k}" if path else str(k)
            if not _UPPER.match(str(k)):
                bad.append(p)
            bad += lower_keys(v, p)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            bad += lower_keys(v, f"{path}[{i}]")
    return bad


# ---- dates ---------------------------------------------------------------------------------------------------------------------------
def iso_date(text: str) -> str:
    """YYYY-MM-DD from YYYY-MM-DD, YYYYMMDD or a timestamp that starts with a date; refused otherwise."""
    s = str(text).strip()
    for fmt, n in (("%Y-%m-%d", 10), ("%Y%m%d", 8)):
        try:
            return dt.datetime.strptime(s[:n], fmt).date().isoformat()
        except ValueError:
            pass
    raise PvfError(f"{text!r} is not a date (YYYY-MM-DD or YYYYMMDD)")


def date_in_name(path: Path) -> str | None:
    """The business date a file name carries (``pvf_path_20261029.csv`` -> 2026-10-29), or None."""
    m = re.search(r"(?<!\d)(\d{4})-?(\d{2})-?(\d{2})(?!\d)", Path(path).name)
    if not m:
        return None
    try:
        return dt.date(int(m[1]), int(m[2]), int(m[3])).isoformat()
    except ValueError:
        return None


# ---- JSONL in ------------------------------------------------------------------------------------------------------------------------
def _lines(stream: io.TextIOBase, where: str) -> Iterator[dict]:
    for n, line in enumerate(stream, 1):
        if line.strip():
            try:
                yield json.loads(line)
            except json.JSONDecodeError as e:
                raise PvfError(f"{where} line {n}: not JSON ({e.msg})") from None


def read_jsonl(path: Path) -> Iterator[dict]:
    """The records of a .jsonl or .jsonl.gz file, one at a time."""
    path = Path(path)
    opener = gzip.open if path.name.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as f:
        yield from _lines(f, str(path))


def read_any(path: Path) -> Iterator[tuple[str, dict]]:
    """(where, record) of a .jsonl[.gz] file, a folder of them (recursively, sorted) or a .tar of them; ``where`` names the member."""
    path = Path(path)
    if path.is_dir():
        for f in sorted(p for p in path.rglob("*") if p.is_file() and (p.name.endswith(".jsonl") or p.name.endswith(".jsonl.gz"))):
            for r in read_jsonl(f):
                yield str(f.relative_to(path)), r
    elif path.name.endswith(".tar"):
        with tarfile.open(path) as t:
            for m in sorted((m for m in t.getmembers() if m.isfile()), key=lambda m: m.name):
                raw = t.extractfile(m)
                data = gzip.GzipFile(fileobj=raw) if m.name.endswith(".gz") else raw
                for r in _lines(io.TextIOWrapper(data, encoding="utf-8"), f"{path.name}:{m.name}"):
                    yield m.name, r
    else:
        for r in read_jsonl(path):
            yield path.name, r


# ---- deterministic writers -----------------------------------------------------------------------------------------------------------
def dumps(record: dict) -> str:
    """One JSONL line: compact, UTF-8 as is, NaN refused (a NaN is not JSON and the engine never takes one)."""
    return json.dumps(record, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


class GzParts:
    """``<folder>/<stem>.part-NNN.jsonl.gz``, a new part every ``rows_per_part`` records; the same bytes on every run (gzip mtime 0)."""

    def __init__(self, folder: Path, stem: str = "cob", rows_per_part: int = 20_000):
        if rows_per_part < 1:
            raise PvfError("rows per part must be 1 or more")
        self.folder, self.stem, self.per = Path(folder), stem, rows_per_part
        self.folder.mkdir(parents=True, exist_ok=True)
        self.paths: list[Path] = []
        self.rows = 0
        self._f = None
        self._in_part = 0

    def write(self, record: dict) -> None:
        if self._f is None or self._in_part >= self.per:
            self._open()
        self._f.write((dumps(record) + "\n").encode("utf-8"))
        self._in_part += 1
        self.rows += 1

    def _open(self) -> None:
        self.close()
        p = self.folder / f"{self.stem}.part-{len(self.paths) + 1:03d}.jsonl.gz"
        self._raw = open(p, "wb")
        self._f = gzip.GzipFile(filename="", mode="wb", fileobj=self._raw, mtime=0, compresslevel=6)
        self.paths.append(p)
        self._in_part = 0

    def close(self) -> None:
        if self._f is not None:
            self._f.close()
            self._raw.close()
            self._f = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def write_gz(path: Path, records: Iterable[dict]) -> int:
    """One .jsonl.gz file of ``records``, deterministic; the count written."""
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with open(path, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as f:
        for r in records:
            f.write((dumps(r) + "\n").encode("utf-8"))
            n += 1
    return n


def make_tar(tar_path: Path, root: Path, files: Iterable[Path]) -> Path:
    """A plain .tar of ``files`` named relative to ``root`` (``scenario-pv/2026-10-29/cob.part-001.jsonl.gz``): sorted, mtime 0,
    owner 0, so the same inputs give the same bytes."""
    tar_path.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(tar_path, "w", format=tarfile.PAX_FORMAT) as t:
        for f in sorted(Path(x) for x in files):
            info = t.gettarinfo(str(f), arcname=f.relative_to(root).as_posix())
            info.mtime, info.uid, info.gid, info.uname, info.gname, info.mode = 0, 0, 0, "", "", 0o644
            with open(f, "rb") as fh:
                t.addfile(info, fh)
    return tar_path
