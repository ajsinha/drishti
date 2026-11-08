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

"""drishti-feeds: the Feed base class, the feed file (config/feeds/<feed>.yaml) and one feed run (docs/guides/FEEDS_GUIDE.md).

A feed PULLS one external source in Python and writes what it pulled as JSON Lines, one gzipped file per dataset and business date,
under ``<data>/datafeeds/<feed>/<YYYY-MM-DD>/<table>.<YYYY-MM-DD>.jsonl.gz`` with a ``manifest.json`` (each file's rows and SHA-256). The
LOAD into a lake is a separate step that reuses what Drishti already has (tools/feeds/feedload.py: the parallel loader, ``lake build`` or
the data inbox). Writing a feed is one class:

    class WorldBank(Feed):
        def plan(self, run):                   # what a --dry-run would fetch (URLs or words)
            return [f"{BASE}/indicator/{c}" for c in run.option("indicators")]

        def pull(self, run):
            start, end = run.window("indicator")              # --since, else the day after the state's last date, else history
            for code in run.option("indicators"):
                doc = run.get_json(f"{BASE}/country/all/indicator/{code}?date={start.year}:{end.year}&format=json")
                run.emit("indicator", [row(x) for x in doc[1]])   # the run's business date; or business_date=...
            run.mark("indicator", end)                        # the state saved only when the whole run succeeds

The API a feed uses (stable; FEEDS_GUIDE.md "Writing a feed"):

    run.business_date      the run's business date (--date, else today)          run.since    --since, else None
    run.window(dataset)    (first, last) dates to pull: --since; else the day after the dataset's last date in the state; else
                           last - history.first-run-days
    run.option(name, d)    the feed file's options: value, over-ridden by --set name=value
    run.key(name)          a key of the feed file (only ever ${ENV}); FeedRefused when its variable is not set
    run.get_json / get_text / get_bytes(url, name=None, headers=None)
                           polite GETs (User-Agent, pause, retries with back-off, Retry-After) through the download cache
                           <data>/datafeeds/<feed>/cache, so a broken run resumes; None after the retries (the run then fails)
    run.emit(dataset, rows, business_date=None)   rows (dicts) of a dataset of the feed file, appended to that date's file
    run.state              the feed's state.json (a dict; saved only when the run succeeds)
    run.mark(dataset, last)                       records the last business date pulled for the dataset
    run.log(text)          one line to the run's log
"""
from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import os
import re
import shutil
import socket
import sys
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import pullkit as K  # noqa: E402  (the polite HTTP client and the download cache)

ROOT = K.ROOT
CONFIG = ROOT / "config" / "feeds"
SOURCES = HERE / "sources"
SCHEMA_VERSION = 1

# exit codes, for schedulers (docs/guides/FEEDS_GUIDE.md section "Exit codes")
OK, FAILED, REFUSED, LOCKED, PARTIAL = 0, 1, 2, 3, 4
EXIT_WORDS = {OK: "ok", FAILED: "failed", REFUSED: "refused", LOCKED: "locked", PARTIAL: "partial"}

ROUTES = ("java", "python", "inbox", "none")
REDISTRIBUTION = ("allowed", "attribution", "internal-only")
_ENV = re.compile(r"^\$\{([A-Z][A-Z0-9_]*)\}$")
_NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
_TABLE = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
_LAKE = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
_TIME = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
DAYS = ("daily", "weekdays")


class FeedError(Exception):
    """A feed run that failed (exit 1): the message says why in words."""


class FeedRefused(FeedError):
    """A feed that may not run as asked (exit 2): a bad feed file, a key not set, internal-only data into a committed folder."""


class FeedLocked(FeedError):
    """Another run of the feed holds its lock (exit 3)."""


def feeds_dir() -> Path:
    """<data>/datafeeds: every feed's files, states and the run ledger (DRISHTI_DATA_DIR, else ./data)."""
    return K.data_dir() / "datafeeds"


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().replace(microsecond=0).isoformat()


# ------------------------------------------------------------------------------------------------ the feed file

@dataclass
class Dataset:
    """One dataset of a feed: where its rows go (pack, kind, table) and how its files are dated."""
    name: str
    pack: str
    kind: str
    table: str
    key: str = ""
    dated: str = "run"            # run: every row under the run's business date; source: the dates the feed gives (emit business_date=)
    description: str = ""


@dataclass
class Licence:
    name: str
    url: str
    redistribution: str           # allowed | attribution | internal-only
    internal_only: bool
    attribution: str = ""
    notes: str = ""


@dataclass
class FeedSpec:
    """config/feeds/<feed>.yaml, checked. Every key is in docs/guides/FEEDS_GUIDE.md section "The feed file"."""
    name: str
    title: str
    impl: str                     # <module>.<Class> under tools/feeds/sources/
    lake: str
    datasets: dict[str, Dataset]
    licence: Licence
    provider: str = ""
    url: str = ""
    description: str = ""
    load: str = "java"            # the default load route
    enabled: bool = True
    schedule_at: str = "06:30"
    schedule_days: str = "daily"
    pause: float = 0.5            # seconds between requests (the rate limit)
    retries: int = 3
    timeout: float = 90.0
    cache_hours: float = 12.0     # a download younger than this is reused (a broken run resumes)
    first_run_days: int = 30
    keys: dict[str, str] = field(default_factory=dict)        # name -> ENV variable
    optional_keys: set = field(default_factory=set)
    options: dict[str, Any] = field(default_factory=dict)
    snapshot: dict[str, Any] = field(default_factory=dict)    # --snapshot: {testdata: <path>, note: ...}
    path: Path | None = None

    @property
    def internal_only(self) -> bool:
        return self.licence.internal_only

    def dataset_for_kind(self, kind: str) -> Dataset | None:
        return next((d for d in self.datasets.values() if d.kind == kind), None)


def _need(doc: dict, key: str, where: str, kind=str):
    v = doc.get(key)
    if v is None or (kind is str and not str(v).strip()):
        raise FeedRefused(f"{where}: '{key}' is required")
    if not isinstance(v, kind):
        raise FeedRefused(f"{where}: '{key}' must be a {kind.__name__}")
    return v


def parse_spec(doc: dict, path: Path | None = None) -> FeedSpec:
    """A feed file's document, checked; FeedRefused naming the key that is wrong."""
    where = str(path.relative_to(ROOT) if path and path.is_relative_to(ROOT) else path or "the feed file")
    if not isinstance(doc, dict):
        raise FeedRefused(f"{where}: not a YAML mapping")
    known = {"feed", "title", "description", "class", "source", "lake", "load", "enabled", "schedule", "licence", "keys", "datasets",
             "options", "history", "snapshot", "schema"}
    unknown = sorted(set(doc) - known)
    if unknown:
        raise FeedRefused(f"{where}: unknown key(s) {', '.join(unknown)}; the keys are {', '.join(sorted(known))}")
    name = _need(doc, "feed", where)
    if not _NAME.match(name):
        raise FeedRefused(f"{where}: feed '{name}' must be lower case letters, digits and dashes")
    if path is not None and path.stem != name:
        raise FeedRefused(f"{where}: the file must be named {name}.yaml")
    lake = _need(doc, "lake", where)
    if not _LAKE.match(lake):
        raise FeedRefused(f"{where}: lake '{lake}' is not a lake name")
    load = doc.get("load", "java")
    if load not in ROUTES:
        raise FeedRefused(f"{where}: load '{load}' must be one of {', '.join(ROUTES)}")
    src = doc.get("source") or {}
    if not isinstance(src, dict):
        raise FeedRefused(f"{where}: 'source' must be a mapping")
    sched = doc.get("schedule") or {}
    at, days = str(sched.get("at", "06:30")), str(sched.get("days", "daily"))
    if not _TIME.match(at):
        raise FeedRefused(f"{where}: schedule.at '{at}' must be HH:MM (24 hours, local time)")
    if days not in DAYS:
        raise FeedRefused(f"{where}: schedule.days '{days}' must be one of {', '.join(DAYS)}")
    lic = _need(doc, "licence", where, dict)
    redis = _need(lic, "redistribution", where + " licence")
    if redis not in REDISTRIBUTION:
        raise FeedRefused(f"{where}: licence.redistribution '{redis}' must be one of {', '.join(REDISTRIBUTION)}")
    internal = bool(lic.get("internal-only", redis == "internal-only"))
    if redis == "internal-only" and not internal:
        raise FeedRefused(f"{where}: licence.redistribution internal-only needs internal-only: true")
    licence = Licence(_need(lic, "name", where + " licence"), _need(lic, "url", where + " licence"), redis, internal,
                      str(lic.get("attribution") or ""), str(lic.get("notes") or ""))
    keys, optional = {}, set()
    for k, v in (doc.get("keys") or {}).items():
        spec = v if isinstance(v, dict) else {"env": v}
        m = _ENV.match(str(spec.get("env") or ""))
        if not m:
            raise FeedRefused(f"{where}: keys.{k} must be an environment variable as ${{NAME}} (a key is never written in a file)")
        keys[k] = m.group(1)
        if spec.get("required") is False:
            optional.add(k)
    ds_doc = _need(doc, "datasets", where, dict)
    datasets = {}
    for dn, d in ds_doc.items():
        w = f"{where} datasets.{dn}"
        if not isinstance(d, dict):
            raise FeedRefused(f"{w}: must be a mapping (pack, kind, table)")
        kind = str(d.get("kind") or "")
        table = str(d.get("table") or kind or dn)               # default: the kind (the pack's table of it, as the demo lake has)
        if not _TABLE.match(table):
            raise FeedRefused(f"{w}: table '{table}' must be lower case letters, digits, '_' and '-'")
        dated = str(d.get("dated", "run"))
        if dated not in ("run", "source"):
            raise FeedRefused(f"{w}: dated '{dated}' must be run or source")
        datasets[dn] = Dataset(dn, _need(d, "pack", w), _need(d, "kind", w), table, str(d.get("key") or ""), dated,
                               str(d.get("description") or ""))
    if not datasets:
        raise FeedRefused(f"{where}: datasets: at least one dataset")
    tables = [d.table for d in datasets.values()]
    if len(set(tables)) != len(tables):
        raise FeedRefused(f"{where}: two datasets write the same table")
    hist = doc.get("history") or {}
    return FeedSpec(name=name, title=_need(doc, "title", where), impl=_need(doc, "class", where), lake=lake, datasets=datasets,
                    licence=licence, provider=str(src.get("provider") or ""), url=str(src.get("url") or ""),
                    description=str(doc.get("description") or ""), load=load, enabled=bool(doc.get("enabled", True)),
                    schedule_at=at, schedule_days=days, pause=float(src.get("pause", 0.5)), retries=int(src.get("retries", 3)),
                    timeout=float(src.get("timeout", 90)), cache_hours=float(src.get("cache-hours", 12)),
                    first_run_days=int(hist.get("first-run-days", 30)), keys=keys, optional_keys=optional,
                    options=dict(doc.get("options") or {}), snapshot=dict(doc.get("snapshot") or {}), path=path)


def load_spec(name: str, config: Path | None = None) -> FeedSpec:
    import yaml
    folder = Path(config) if config else CONFIG
    p = folder / f"{name}.yaml"
    if not p.is_file():
        names = ", ".join(s.name for s in all_specs(folder)) or "none"
        raise FeedRefused(f"no feed '{name}' (no {p}); the feeds are: {names}")
    return parse_spec(yaml.safe_load(p.read_text(encoding="utf-8")), p)


def all_specs(config: Path | None = None) -> list[FeedSpec]:
    import yaml
    folder = Path(config) if config else CONFIG
    return [parse_spec(yaml.safe_load(p.read_text(encoding="utf-8")), p) for p in sorted(folder.glob("*.yaml"))]


def feed_class(spec: FeedSpec, sources: Path | None = None):
    """The Feed class a feed file names (``class: <module>.<Class>``): the module under tools/feeds/sources/, else beside the feed
    file (a site's own feed kept with its feed file)."""
    mod_name, _, cls_name = spec.impl.rpartition(".")
    folders = [Path(sources)] if sources else [SOURCES] + ([spec.path.parent] if spec.path else [])
    f = next((d / f"{mod_name}.py" for d in folders if (d / f"{mod_name}.py").is_file()), folders[0] / f"{mod_name}.py")
    folder = f.parent
    if not mod_name or not f.is_file():
        raise FeedRefused(f"feed {spec.name}: class '{spec.impl}' needs {f}")
    key = f"drishti_feed_{mod_name}"
    mod = sys.modules.get(key)
    plain = sys.modules.get(mod_name)                       # already imported by its own name (a source's tests): the same module
    if mod is None and plain is not None and Path(getattr(plain, "__file__", "") or "").resolve() == f.resolve():
        mod = sys.modules[key] = plain
    if mod is None:
        for p in (str(folder), str(HERE)):
            if p not in sys.path:
                sys.path.insert(0, p)
        s = importlib.util.spec_from_file_location(key, f)
        mod = importlib.util.module_from_spec(s)
        sys.modules[key] = mod
        s.loader.exec_module(mod)
    cls = getattr(mod, cls_name, None)
    if not (isinstance(cls, type) and issubclass(cls, Feed)):
        raise FeedRefused(f"feed {spec.name}: {f.name} has no Feed class {cls_name}")
    return cls


# ------------------------------------------------------------------------------------------------ the Feed

class Feed:
    """One external source. Subclasses implement ``pull`` (and ``plan`` for --dry-run); ``snapshot`` when the feed also refreshes a
    committed demo snapshot (--snapshot); ``check`` for the feed's own checks of what it wrote."""

    def __init__(self, spec: FeedSpec):
        self.spec = spec

    def plan(self, run: "FeedRun") -> list[str]:
        """What a --dry-run says the run would fetch: URLs, or words."""
        return []

    def pull(self, run: "FeedRun") -> None:
        raise NotImplementedError

    def snapshot(self, run: "FeedRun") -> int:
        """--snapshot: refresh the committed demo snapshot where the feed keeps one (data/testdata); an exit code."""
        raise FeedRefused(f"feed {self.spec.name} keeps no committed snapshot (--snapshot)")

    def check(self, run: "FeedRun") -> list[str]:
        """Problems with what this run wrote, in words (an empty list: fine)."""
        return []


# ------------------------------------------------------------------------------------------------ one run

class _Writer:
    """One dataset's file of one business date, written as it is emitted (gzip without a name or a time: same rows, same bytes)."""

    def __init__(self, path: Path):
        self.path, self.rows = path, 0
        path.parent.mkdir(parents=True, exist_ok=True)
        self._raw = open(path, "wb")
        self._gz = gzip.GzipFile(filename="", mode="wb", fileobj=self._raw, mtime=0)

    def write(self, row: dict) -> None:
        self._gz.write((json.dumps(row, ensure_ascii=False, sort_keys=True, default=str) + "\n").encode("utf-8"))
        self.rows += 1

    def close(self) -> None:
        self._gz.close()
        self._raw.close()


class FeedRun:
    """Everything one run of one feed needs, and what it wrote."""

    def __init__(self, spec: FeedSpec, business_date: date | None = None, since: date | None = None, dry_run: bool = False,
                 sets: dict | None = None, log=print, root: Path | None = None, opener=None, sleep=time.sleep,
                 offline: bool = False, extra_args: list[str] | None = None, internal_ok: bool = False):
        self.spec, self.log, self.dry_run = spec, log, dry_run
        self.business_date = business_date or date.today()
        self.since = since
        self.sets = dict(sets or {})
        self.extra_args = list(extra_args or [])
        self.internal_ok = internal_ok
        self.offline = offline
        self.root = Path(root) if root else feeds_dir()
        self.folder = self.root / spec.name
        self.run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + f"-{socket.gethostname().split('.')[0]}-{os.getpid()}"
        self.work = self.folder / "work" / self.run_id
        self.cache = K.Cache(self.folder / "cache")
        self.http = K.Http(self.cache, pause=spec.pause, max_age_h=spec.cache_hours, offline=offline, log=log,
                           **({"opener": opener} if opener else {}), sleep=sleep)
        self.http.RETRY = tuple(min(2 ** (i + 1), 60) for i in range(max(0, spec.retries)))
        self.state_path = self.folder / "state.json"
        try:
            self.state: dict = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.state = {}
        self.state.setdefault("datasets", {})
        self._writers: dict[tuple[str, str], _Writer] = {}
        self.files: list[dict] = []           # after finish(): one entry per file written
        self.dates: list[str] = []

    # ---- what a feed reads
    def option(self, name: str, default=None):
        if name in self.sets:
            return self.sets[name]
        return self.spec.options.get(name, default)

    def key(self, name: str) -> str:
        env = self.spec.keys.get(name)
        if env is None:
            raise FeedRefused(f"feed {self.spec.name}: no key '{name}' in its feed file")
        v = os.environ.get(env, "")
        if not v and name not in self.spec.optional_keys:
            raise FeedRefused(f"feed {self.spec.name}: set the environment variable {env} (keys.{name}); keys are never written in files")
        return v

    def window(self, dataset: str | None = None) -> tuple[date, date]:
        last = self.business_date
        if self.since:
            return self.since, last
        done = (self.state["datasets"].get(dataset or "*") or {}).get("last")
        if done:
            start = date.fromisoformat(done) + timedelta(days=1)
            return min(start, last), last
        return last - timedelta(days=self.spec.first_run_days), last

    # ---- HTTP through the cache
    def _dest(self, url: str, name: str | None) -> Path:
        n = name or hashlib.sha1(url.encode("utf-8")).hexdigest()[:20]
        return self.cache.folder / n

    def get_bytes(self, url: str, name: str | None = None, headers: dict | None = None, accept: str = "*/*") -> bytes | None:
        if headers:
            self.http.headers = dict(headers)
        try:
            return self.http.get(url, self._dest(url, name), accept=accept)
        finally:
            self.http.headers = {}

    def get_text(self, url: str, name: str | None = None, headers: dict | None = None) -> str | None:
        b = self.get_bytes(url, name, headers, accept="text/plain, text/csv, application/xml, */*")
        return None if b is None else b.decode("utf-8", errors="replace")

    def get_json(self, url: str, name: str | None = None, headers: dict | None = None):
        b = self.get_bytes(url, name, headers, accept="application/json")
        if b is None:
            return None
        try:
            return json.loads(b.decode("utf-8"))
        except ValueError as e:
            raise FeedError(f"{url}: not JSON ({e})")

    # ---- what a feed writes
    def emit(self, dataset: str, rows: Iterable[dict], business_date: date | str | None = None) -> int:
        ds = self.spec.datasets.get(dataset)
        if ds is None:
            raise FeedError(f"feed {self.spec.name}: emit to '{dataset}', not a dataset of its feed file ({', '.join(self.spec.datasets)})")
        day = self.business_date if ds.dated == "run" or business_date is None else business_date
        day = day.isoformat() if isinstance(day, date) else str(day)
        if not re.match(r"^\d{4}-\d{2}-\d{2}$", day):
            raise FeedError(f"feed {self.spec.name}: business date '{day}' is not YYYY-MM-DD")
        w = self._writers.get((dataset, day))
        if w is None:
            w = self._writers[(dataset, day)] = _Writer(self.work / day / f"{ds.table}.{day}.jsonl.gz")
        n = 0
        for row in rows:
            if not isinstance(row, dict):
                raise FeedError(f"feed {self.spec.name}: dataset {dataset}: a row is not an object")
            w.write(row)
            n += 1
        return n

    def mark(self, dataset: str, last: date | str) -> None:
        self.state["datasets"].setdefault(dataset, {})["last"] = last.isoformat() if isinstance(last, date) else str(last)

    # ---- the end of a run
    def finish(self) -> list[dict]:
        """Closes the files, moves each date's folder into place (an earlier pull of that date replaced: idempotent), writes each
        date's manifest.json; the files written, with rows and SHA-256."""
        for w in self._writers.values():
            w.close()
        by_day: dict[str, list[dict]] = {}
        for (dataset, day), w in sorted(self._writers.items(), key=lambda kv: (kv[0][1], kv[0][0])):
            ds = self.spec.datasets[dataset]
            by_day.setdefault(day, []).append({"dataset": dataset, "pack": ds.pack, "kind": ds.kind, "table": ds.table, "key": ds.key,
                                               "file": w.path.name, "rows": w.rows, "bytes": w.path.stat().st_size,
                                               "sha256": K.sha256(w.path)})
        for day, entries in by_day.items():
            dest = self.folder / day
            man = {"feed": self.spec.name, "businessDate": day, "run": self.run_id, "pulled": now_iso(), "lake": self.spec.lake,
                   "licence": licence_doc(self.spec), "files": entries}
            (self.work / day / "manifest.json").write_text(json.dumps(man, indent=1, sort_keys=True) + "\n", encoding="utf-8")
            if dest.exists():
                old = self.folder / "work" / f"{self.run_id}-replaced-{day}"
                dest.replace(old)
                shutil.rmtree(old, ignore_errors=True)
            (self.work / day).replace(dest)
            for e in entries:
                self.files.append({**e, "path": str(dest / e["file"]), "businessDate": day})
        self.dates = sorted(by_day)
        self.cache.save()
        shutil.rmtree(self.work, ignore_errors=True)
        try:
            (self.folder / "work").rmdir()
        except OSError:
            pass
        return self.files

    def abandon(self) -> None:
        for w in self._writers.values():
            try:
                w.close()
            except OSError:
                pass
        self.cache.save()
        shutil.rmtree(self.work, ignore_errors=True)

    def save_state(self) -> None:
        self.state["schema"] = SCHEMA_VERSION
        self.state["lastRun"] = self.run_id
        self.state["lastSuccess"] = now_iso()
        self.state["lastBusinessDate"] = self.business_date.isoformat()
        self.folder.mkdir(parents=True, exist_ok=True)
        tmp = self.state_path.with_name("state.json.part")
        tmp.write_text(json.dumps(self.state, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        tmp.replace(self.state_path)


def licence_doc(spec: FeedSpec) -> dict:
    lic = spec.licence
    return {"name": lic.name, "url": lic.url, "redistribution": lic.redistribution, "internalOnly": lic.internal_only,
            **({"attribution": lic.attribution} if lic.attribution else {})}


# ------------------------------------------------------------------------------------------------ the lock

class Lock:
    """<data>/datafeeds/<feed>/run.lock: one run of a feed at a time; a lock whose process is gone (same host) is taken over."""

    def __init__(self, folder: Path):
        self.path = Path(folder) / "run.lock"
        self.held = False

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        host = socket.gethostname()
        for _ in range(2):
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                with os.fdopen(fd, "w") as f:
                    json.dump({"pid": os.getpid(), "host": host, "since": now_iso()}, f)
                self.held = True
                return self
            except FileExistsError:
                try:
                    info = json.loads(self.path.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    info = {}
                if info.get("host") == host and info.get("pid") and not _alive(int(info["pid"])):
                    self.path.unlink(missing_ok=True)
                    continue
                raise FeedLocked(f"another run holds {self.path} (pid {info.get('pid')} on {info.get('host')} since {info.get('since')})")
        raise FeedLocked(f"could not take {self.path}")

    def __exit__(self, *exc):
        if self.held:
            self.path.unlink(missing_ok=True)


def _alive(pid: int) -> bool:
    if os.name == "nt":                          # os.kill would end the process on Windows: ask the kernel instead
        import ctypes
        k = ctypes.windll.kernel32
        h = k.OpenProcess(0x1000, False, pid)    # PROCESS_QUERY_LIMITED_INFORMATION
        if not h:
            return False
        code = ctypes.c_ulong()
        try:
            return bool(k.GetExitCodeProcess(h, ctypes.byref(code))) and code.value == 259   # STILL_ACTIVE
        finally:
            k.CloseHandle(h)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
