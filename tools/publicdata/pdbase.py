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

"""The shared library of the public-data refresh scripts (one script per pack: world_economy.py, news_and_markets.py, us_equities.py,
official_market_data.py). It is imported, never run. Python standard library only (PyYAML where a pack's own tools need it).

    Source      one public source: its licence and use, then fetch -> normalise -> write
    Http        GET with a User-Agent, a polite pause between calls, retries with back-off (429, 5xx, time-outs)
    Cache       the download cache under <data>/publicdata/cache/<pack>: raw files as served plus cache-index.json
                (url, fetched time, sha256, bytes); a file younger than --max-age is reused, so a broken run resumes
    Manifest    <data>/publicdata/<pack>/manifest.json (and a copy in --out outside the repository): what ran, when,
                from where, under which licence, the files written with their sha256 and row counts
    guard_out   the internal-only rule: a source whose terms do not allow redistribution is never written into a
                committed folder of the repository unless --internal-ok, and the refusal says why in words
    cli         the common command line: status | refresh [SOURCE ...] --years --since --scope --out --dry-run --internal-ok

<data> is DRISHTI_DATA_DIR, else ./data of the repository (git-ignored, apart from the committed data/testdata).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UA = "drishti-publicdata/1.0 (public-data refresh scripts; contact ajsinha@gmail.com)"
COMMITTABLE, INTERNAL = "committable", "internal only"


def data_dir() -> Path:
    """The configurable data folder: DRISHTI_DATA_DIR, else <repository>/data."""
    return Path(os.environ.get("DRISHTI_DATA_DIR") or ROOT / "data").expanduser().resolve()


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n} B"


def is_committed(path: Path) -> bool:
    """True for a folder of the repository that git tracks: inside the repository and not under the git-ignored data
    folder, or under data/testdata (the one committed part of data/)."""
    p, root = Path(path).expanduser().resolve(), ROOT.resolve()
    if not p.is_relative_to(root):
        return False
    data = root / "data"
    if p.is_relative_to(data / "testdata"):
        return True
    return not p.is_relative_to(data)


class Refusal(Exception):
    """A refusal explained in words (the scripts print it and exit 3)."""


# ---------------------------------------------------------------- the source plug-in

class Source:
    """One public source. Subclasses set the class attributes and implement fetch and normalise; write is optional (a pack
    whose sources share one dataset writes once, in its Pack.write)."""
    name = ""            # the id used on the command line
    title = ""
    pack = ""
    url = ""             # the provider's page
    licence = ""
    licence_url = ""
    use = COMMITTABLE    # or INTERNAL
    why_internal = ""    # in words: why it may not be redistributed
    scope_help = ""      # what --scope widens for this source ("" = nothing)
    cache_prefixes: tuple = ()   # the cache paths this source owns (for the size in status)

    def cache_size(self, cache_dir: Path) -> int:
        total = 0
        for pre in self.cache_prefixes:
            for f in Path(cache_dir).glob(pre + "*"):
                total += sum(x.stat().st_size for x in f.rglob("*") if x.is_file()) if f.is_dir() else f.stat().st_size
        return total

    def last_fetched(self, cache: "Cache") -> str:
        return max((cache.last(p) for p in self.cache_prefixes), default="")

    def plan(self, run: "Run") -> list[str]:
        """The URLs (or a description of them) a refresh would fetch; for --dry-run."""
        return []

    def fetch(self, run: "Run") -> None:
        raise NotImplementedError

    def normalise(self, run: "Run") -> dict:
        """Parse what fetch cached; return {"rows": n, ...} for the manifest."""
        return {}

    def write(self, run: "Run") -> list[Path]:
        return []

    def describe(self) -> dict:
        return {"source": self.name, "title": self.title, "url": self.url, "licence": self.licence, "licenceUrl": self.licence_url,
                "use": self.use}


def guard_out(sources: list[Source], out: Path, internal_ok: bool) -> None:
    """Refuse to write internal-only data into a committed folder unless the caller said --internal-ok."""
    internal = [s for s in sources if s.use == INTERNAL]
    if internal and is_committed(out) and not internal_ok:
        lines = [f"refused: {out} is a committed folder of the repository, and these sources may not be redistributed:"]
        lines += [f"  - {s.title}: {s.why_internal or s.licence}" for s in internal]
        lines += ["Write them to the data folder instead (leave out --out, or point it outside the repository), or pass --internal-ok",
                  "if the result stays inside your organisation (internal demos only)."]
        raise Refusal("\n".join(lines))


# ---------------------------------------------------------------- HTTP and the download cache

class Cache:
    """Raw downloads as served, under one folder, with cache-index.json: {relative path: {url, fetched, sha256, bytes}}."""

    def __init__(self, folder: Path):
        self.folder = Path(folder)
        self.index_path = self.folder / "cache-index.json"
        self._lock = threading.Lock()
        try:
            self.index = json.loads(self.index_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.index = {}

    def rel(self, dest: Path) -> str:
        return Path(dest).resolve().relative_to(self.folder.resolve()).as_posix()

    def fresh(self, dest: Path, max_age_h: float, url: str | None = None) -> bool:
        """The cached file is young enough to reuse (its index entry, else its modified time) and came from the same URL
        (a wider --years asks another URL for the same file)."""
        if not dest.is_file():
            return False
        e = self.index.get(self.rel(dest))
        if e and url and e.get("url") != url:
            return False
        when = datetime.fromisoformat(e["fetched"].replace("Z", "+00:00")).timestamp() if e else dest.stat().st_mtime
        return time.time() - when < max_age_h * 3600

    def record(self, dest: Path, url: str, body: bytes) -> None:
        with self._lock:
            self.index[self.rel(dest)] = {"url": url, "fetched": now(), "sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)}

    def save(self) -> None:
        with self._lock:
            self.folder.mkdir(parents=True, exist_ok=True)
            tmp = self.index_path.with_name("cache-index.json.part")
            tmp.write_text(json.dumps(self.index, indent=1, sort_keys=True) + "\n", encoding="utf-8")
            tmp.replace(self.index_path)

    def size(self, prefix: str = "") -> int:
        return sum(e.get("bytes", 0) for k, e in self.index.items() if k.startswith(prefix))

    def last(self, prefix: str = "") -> str:
        """The latest fetch under prefix: from the index, else the newest file's modified time."""
        when = max((e["fetched"] for k, e in self.index.items() if k.startswith(prefix)), default="")
        if not when:
            times = [f.stat().st_mtime for f in self.folder.glob(prefix + "*")]
            if times:
                when = datetime.fromtimestamp(max(times), timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z") + " (file time)"
        return when


class Http:
    """Polite GETs: a User-Agent, at least `pause` seconds between calls, retries after 2, 4, 8 s (or the server's Retry-After)
    on 429, 5xx and network errors. get() reuses a cached file younger than max_age_h, writes atomically, and records it."""
    RETRY = (2, 4, 8)

    def __init__(self, cache: Cache, pause: float = 0.4, max_age_h: float = 24.0, offline: bool = False, log=print,
                 opener=urllib.request.urlopen, sleep=time.sleep):
        self.cache, self.pause, self.max_age_h, self.offline, self.log = cache, pause, max_age_h, offline, log
        self.opener, self.sleep = opener, sleep
        self._lock, self._last = threading.Lock(), 0.0
        self.downloaded, self.reused, self.failed = 0, 0, []

    def _wait(self) -> None:
        with self._lock:
            gap = self._last + self.pause - time.monotonic()
            if gap > 0:
                self.sleep(gap)
            self._last = time.monotonic()

    def get(self, url: str, dest: Path, accept: str = "application/json, text/csv, */*") -> bytes | None:
        dest = Path(dest)
        if dest.is_file() and (self.offline or self.cache.fresh(dest, self.max_age_h, url)):
            self.reused += 1
            return dest.read_bytes()
        if self.offline:
            self.log(f"  offline: not in the cache: {url}")
            self.failed.append(url)
            return None
        why = ""
        for attempt in range(len(self.RETRY) + 1):
            self._wait()
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
                with self.opener(req, timeout=90) as r:
                    body = r.read()
                dest.parent.mkdir(parents=True, exist_ok=True)
                part = dest.with_name(dest.name + ".part")
                part.write_bytes(body)
                part.replace(dest)
                self.cache.record(dest, url, body)
                self.downloaded += 1
                if self.downloaded % 25 == 0:
                    self.cache.save()                  # a run that breaks resumes from here
                return body
            except urllib.error.HTTPError as e:
                why = f"HTTP {e.code}"
                if e.code not in (429, 500, 502, 503, 504):
                    break
                wait = e.headers.get("Retry-After") if e.headers else None
                delay = float(wait) if wait and wait.isdigit() else None
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                why, delay = str(e), None
            if attempt < len(self.RETRY):
                self.log(f"  retry {attempt + 1} {url}: {why}")
                self.sleep(delay if delay is not None else self.RETRY[attempt])
        self.log(f"  FAILED {url}: {why}")
        self.failed.append(url)
        return None


# ---------------------------------------------------------------- the manifest

class Manifest:
    """<data>/publicdata/<pack>/manifest.json: the last refresh of each source and the files written."""

    def __init__(self, pack: str):
        self.pack = pack
        self.path = data_dir() / "publicdata" / pack / "manifest.json"
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = {"pack": pack, "sources": {}, "runs": []}

    def source(self, name: str) -> dict:
        return self.data.get("sources", {}).get(name, {})

    def record(self, src: Source, urls: list[str], counts: dict, out: Path) -> None:
        self.data.setdefault("sources", {})[src.name] = {**src.describe(), "urls": urls[:20], "urlCount": len(urls),
                                                         "fetched": now(), "out": str(out), **counts}

    def finish(self, out: Path, files: list[Path], argv: list[str], ok: bool) -> None:
        written = [{"path": str(f), "sha256": sha256(f), "bytes": f.stat().st_size} for f in files if Path(f).is_file()]
        self.data.setdefault("runs", []).append({"at": now(), "argv": argv, "out": str(out), "ok": ok, "files": len(written)})
        self.data["runs"] = self.data["runs"][-20:]
        self.data["files"] = written
        self.write(self.path)
        if not is_committed(out) and Path(out).resolve() != self.path.parent.resolve():
            self.write(Path(out) / "manifest.json")

    def write(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.data, indent=1, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- the run and the command line

class Run:
    """Everything one refresh needs: the parsed options, the cache, the HTTP client, the output folder."""

    def __init__(self, pack: str, a: argparse.Namespace, out: Path, log=print):
        self.pack, self.a, self.out, self.log = pack, a, Path(out), log
        self.cache = Cache(Path(a.cache) if getattr(a, "cache", None) else data_dir() / "publicdata" / "cache" / pack)
        self.http = Http(self.cache, a.pause, a.max_age, a.offline, log)
        self.scope = [s.strip() for s in (a.scope or "").split(",") if s.strip()]
        self.written: list[Path] = []

    def first_year(self, default: int, last: int) -> int:
        if self.a.since:
            return int(self.a.since)
        if self.a.years:
            return last - int(self.a.years) + 1
        return default


# Each script's pack, which names its wrappers: tools/publicdata/<pack>.sh and tools/windows/publicdata-<pack>.ps1.
WRAPPERS = {"world_economy.py": "world-economy", "news_and_markets.py": "news-and-markets",
            "official_market_data.py": "market-data", "us_equities.py": "us-equities", "gleif.py": "gleif", "funds.py": "funds"}


def examples(description: str, prog: str) -> str:
    """The worked examples of a script's docstring (its indented `python3 tools/publicdata/...` lines and their notes) and the
    shell and Windows wrappers that run it, shown at the end of every subcommand's --help."""
    lines, keep = [], False
    for line in (description or "").splitlines():
        if line.startswith("    python3 tools/publicdata/"):
            keep = True
        elif not line.startswith("        "):
            keep = False
        if keep:
            lines.append(line)
    pack = WRAPPERS.get(prog)
    if pack:
        lines += ["", f"    tools/publicdata/{pack}.sh <the same arguments>                 Linux and macOS",
                  f"    .\\tools\\windows\\publicdata-{pack}.ps1 <the same arguments>        Windows (PowerShell)"]
    return ("examples:\n" + "\n".join(lines) + "\n\nguide: docs/guides/DATA_GUIDE.md (Help → Data → External data)") if lines else ""


def parser(prog: str, description: str, sources: list[Source], extra=None) -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog=prog, description=description, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    shown = examples(description, prog)
    for name, what in (("status", "each source: licence, use, last refreshed, cached size"), ("list", "the same as status")):
        s = sub.add_parser(name, help=what, description=what + " (offline: reads the manifest and the cache only)",
                           formatter_class=argparse.RawDescriptionHelpFormatter, epilog=shown)
        s.set_defaults(command="status")
        s.add_argument("--cache", help="the download cache (default <data>/publicdata/cache/<pack>)")
    r = sub.add_parser("refresh", help="fetch, normalise and write; then the pack's own check",
                       formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="sources: " + ", ".join(s.name for s in sources) + "\n" +
                              "\n".join(f"--scope for {s.name}: {s.scope_help}" for s in sources if s.scope_help) +
                              ("\n\n" + shown if shown else ""))
    r.add_argument("sources", nargs="*", metavar="SOURCE", help="one or more of: " + ", ".join(s.name for s in sources) + " (default: all of them)")
    r.add_argument("--years", type=int, help="this many years of history, ending with the latest")
    r.add_argument("--since", type=int, metavar="YEAR", help="history from this year")
    r.add_argument("--scope", help="widen the source (comma separated; see below)")
    r.add_argument("--out", help="where to write (default: the pack's committed location, or <data>/publicdata/<pack> for internal-only data)")
    r.add_argument("--cache", help="the download cache (default <data>/publicdata/cache/<pack>)")
    r.add_argument("--max-age", type=float, default=24.0, metavar="HOURS", help="reuse a cached download younger than this (default 24)")
    r.add_argument("--offline", action="store_true", help="use the cache only; fetch nothing")
    r.add_argument("--pause", type=float, default=0.4, help="seconds between requests (default 0.4)")
    r.add_argument("--dry-run", action="store_true", help="say what would be fetched and written; change nothing")
    r.add_argument("--internal-ok", action="store_true", help="allow internal-only data into a committed folder (internal demos only)")
    r.add_argument("--no-check", action="store_true", help="skip the pack's check after writing")
    if extra:
        extra(r)
    return ap


def pick(sources: list[Source], names: list[str]) -> list[Source]:
    if not names:
        return list(sources)
    by = {s.name: s for s in sources}
    unknown = [n for n in names if n not in by]
    if unknown:
        raise Refusal(f"unknown source {', '.join(unknown)}; this pack has: {', '.join(by)}")
    return [by[n] for n in names]


def status(pack: str, sources: list[Source], cache_dir: Path, out_hint: str, log=print) -> int:
    """The status table: source, licence, use, last refreshed (the manifest, else the cache), cached size."""
    cache, man = Cache(cache_dir), Manifest(pack)
    rows = [("SOURCE", "LICENCE", "USE", "LAST REFRESHED", "CACHED")]
    for s in sources:
        when = man.source(s.name).get("fetched") or s.last_fetched(cache) or "never"
        size = s.cache_size(cache_dir)
        rows.append((s.name, s.licence[:48], s.use, when[:19], human(size) if size else "-"))
    w = [max(len(r[i]) for r in rows) for i in range(5)]
    for r in rows:
        log("  ".join(c.ljust(w[i]) for i, c in enumerate(r)).rstrip())
    log(f"\npack {pack}: writes {out_hint}")
    log(f"cache {cache_dir}\nmanifest {man.path}")
    for s in sources:
        if s.use == INTERNAL:
            log(f"internal only: {s.name}: {s.why_internal}")
    return 0
