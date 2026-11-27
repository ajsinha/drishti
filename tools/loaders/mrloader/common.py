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

"""What the mrloader scripts share: logging, the UPPER_SNAKE key rule, dates, JSONL readers and the deterministic .jsonl.gz and .tar writers
(the files of a kind and date and their markers: package.py; the inbox and the load: hooks.py)."""
from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import io
import json
import logging
import re
import subprocess
import sys
import tarfile
from pathlib import Path
from typing import Any, Iterable, Iterator

ROOT = Path(__file__).resolve().parents[3]
CONTRACTS = ROOT / "modules" / "banking" / "packs" / "market-risk" / "contracts"
SCENARIO_PV_CONTRACT = CONTRACTS / "scenario-pv.contract.yaml"
SCENARIO_SET_CONTRACT = CONTRACTS / "scenario-set.contract.yaml"
FX_SCENARIO_CONTRACT = CONTRACTS / "fx-scenario.contract.yaml"
PACK = "market-risk"
DEFAULT_INBOX = ROOT / "data" / "dataload" / "inbox"
DEFAULT_OUT = ROOT / "data" / "pvf"
DEFAULT_MAPPING = Path(__file__).resolve().parent / "kinds" / "scenario-pv.pvf.yaml"

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


# ---- dates files and lake delete: shared by the export, the set, the FX and the driver ------------------------------------------
def strict_date(text: str) -> str:
    """exactly YYYY-MM-DD, a real day (an argparse type)."""
    import argparse
    try:
        if len(text) == 10 and iso_date(text) == text:
            return text
    except PvfError:
        pass
    raise argparse.ArgumentTypeError(f"{text!r} is not a date YYYY-MM-DD")


def read_dates_file(path: Path, order: str = "oldest-first") -> list[str]:
    """The days of a dates file (one YYYY-MM-DD a line; blank lines ignored), returned oldest first. ``order`` says how the file is
    ordered (``oldest-first`` or ``newest-first``): a file that is not in that order, or has a repeated day, is refused."""
    if order not in ("oldest-first", "newest-first"):
        raise PvfError("--order is oldest-first or newest-first")
    try:
        days = [iso_date(x) for x in Path(path).read_text(encoding="utf-8").split()]
    except OSError as e:
        raise PvfError(f"cannot read the dates file {path}: {e}") from None
    if not days:
        raise PvfError(f"{path} has no dates")
    if len(set(days)) != len(days):
        raise PvfError(f"{path}: a day appears more than once")
    if days != sorted(days, reverse=order == "newest-first"):
        raise PvfError(f"{path}: the dates are not {order} (--order says how the file is ordered)")
    return days[::-1] if order == "newest-first" else days


def lake_delete(kind: str, business_date: str, lake: str, server: str | None = None, token_file: str | None = None,
                settings: str | None = None, dry_run: bool = False, yes: bool = False, no_signal: bool = False) -> int:
    """Remove one business date of one kind: Drishti's own ``tools/drishti.py lake delete`` (never a second deleter). Whole dates only;
    returns its exit code."""
    cmd = [sys.executable, str(ROOT / "tools" / "drishti.py")]
    if settings:
        cmd += ["--settings", settings]
    cmd += ["lake", "delete", "--lake", lake, "--kind", kind, "--date", business_date]
    if server:
        cmd += ["--server", server]
    if token_file:
        cmd += ["--token-file", token_file]
    cmd += [f for f, on in (("--dry-run", dry_run), ("--yes", yes), ("--no-signal", no_signal)) if on]
    log.info("running: %s", " ".join(cmd[1:]))
    return subprocess.run(cmd, check=False).returncode


def add_remove_arguments(p) -> None:
    """The options of a --remove run, shared by the set and the FX scripts."""
    g = p.add_argument_group("remove (with --remove: the date's rows leave the lake; whole dates only)")
    g.add_argument("--lake", help="the lake by its name (risklake) or its folder; required to remove; with add it is named in the .ready")
    g.add_argument("--settings", help="the settings file drishti.py takes before its command (--settings FILE)")
    g.add_argument("--server", help="the server to tell (default DRISHTI_SERVER, then http://localhost:18480)")
    g.add_argument("--token-file", help="a file holding the API token (loads:write); never printed")
    g.add_argument("--dry-run", action="store_true", help="say what would be done (rows per date for a remove), change nothing")
    g.add_argument("--yes", action="store_true", help="remove without asking you to type the table's name (scripts)")
    g.add_argument("--no-signal", action="store_true", help="tell no server (a running server notices within its version check)")
