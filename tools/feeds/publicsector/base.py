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

"""The public-sector feeds' shared part (docs/guides/PUBLIC_SECTOR_GUIDE.md section 5): the feed YAML, the HTTP client with its
recorded-fixture and recording openers, the pull's state, and what every pull writes. Imported, never run; Python standard library and
PyYAML only.

    Spec          config/feeds/<feed>.yaml read, ${ENV} expanded (keys only from the environment, never written down)
    Fetch         GET and POST with a User-Agent, a pause between calls, retries with back-off (429, 5xx, time-outs)
    Fixtures      an opener answering from tests' recorded responses (index.json: request -> file); never the network
    Recorder      an opener that calls the network and saves every response as a fixture (--record)
    PublicFeed    one feed: pull(state) -> {kind: [documents]}; each source module (world_development.py ...) subclasses it
    write_outputs one JSONL.gz per kind and date with its .ready file (lake, pack, kind, connector, date, mode, key, sha256, rows)

The documents are the packs' kinds (config/packs/<pack>/contracts/<kind>.contract.yaml): a pull writes contract fields only.
"""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
SPECS = ROOT / "config" / "feeds"
UA = "Drishti-feeds/1.0 (Drishti data feeds)"
_ENV = re.compile(r"\$\{([A-Z0-9_]+)(?::([^}]*))?}")


class FeedError(Exception):
    """A pull that cannot go on, said in words (the command line prints it and exits 2)."""


def data_dir() -> Path:
    return Path(os.environ.get("DRISHTI_DATA_DIR") or ROOT / "data").expanduser().resolve()


def today() -> str:
    return date.today().isoformat()


def now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _expand(v, env):
    if isinstance(v, str):
        return _ENV.sub(lambda m: env.get(m.group(1), m.group(2) or ""), v)
    if isinstance(v, list):
        return [_expand(x, env) for x in v]
    if isinstance(v, dict):
        return {k: _expand(x, env) for k, x in v.items()}
    return v


def load_spec(name: str, specs: Path = SPECS, env: dict | None = None) -> dict:
    """The feed's YAML with ${ENV} and ${ENV:default} expanded from the environment."""
    f = Path(specs) / f"{name}.yaml"
    if not f.is_file():
        raise FeedError(f"no feed '{name}': {f} does not exist")
    return _expand(yaml.safe_load(f.read_text(encoding="utf-8")) or {}, os.environ if env is None else env)


def feed_names(specs: Path = SPECS) -> list[str]:
    """The public-sector feeds: every config/feeds/*.yaml whose module is publicsector."""
    out = []
    for f in sorted(Path(specs).glob("*.yaml")):
        y = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        if y.get("module") == "publicsector":
            out.append(f.stem)
    return out


# ---------------------------------------------------------------- HTTP: the network, recorded fixtures, recording

def request_key(method: str, url: str, body: bytes | None) -> str:
    """The fixture name of one request: readable, stable, short (the query's API key never in it)."""
    u = re.sub(r"([?&](api_key|apikey|key)=)[^&]*", r"\1KEY", url)
    h = hashlib.sha256((method + " " + u + " " + (body or b"").decode("utf-8", "replace")).encode()).hexdigest()[:12]
    stem = re.sub(r"[^A-Za-z0-9]+", "-", urllib.parse.urlsplit(u).path)[-60:].strip("-") or "root"
    return f"{stem}-{h}"


class Fixtures:
    """An opener that answers from recorded responses: <folder>/index.json maps a request key to a file (gzip when it ends .gz).
    A request with no recording fails the test in words: a pull under test never reaches the network."""

    def __init__(self, folder: Path):
        self.folder = Path(folder)
        self.index = json.loads((self.folder / "index.json").read_text(encoding="utf-8"))
        self.asked: list[str] = []

    def __call__(self, req: urllib.request.Request, timeout: float = 0):
        key = request_key(req.get_method(), req.full_url, req.data)
        self.asked.append(key)
        entry = self.index.get(key)
        if entry is None:
            raise FeedError(f"no recorded response for {req.get_method()} {req.full_url} (key {key}) in {self.folder}")
        if entry.get("status", 200) != 200:
            raise urllib.error.HTTPError(req.full_url, entry["status"], "recorded", {}, None)
        raw = (self.folder / entry["file"]).read_bytes()
        return io.BytesIO(gzip.decompress(raw) if entry["file"].endswith(".gz") else raw)


class Recorder:
    """An opener that calls the network and saves each response under <folder> with index.json, for new fixtures (--record)."""

    def __init__(self, folder: Path, opener=urllib.request.urlopen):
        self.folder, self.opener = Path(folder), opener
        self.folder.mkdir(parents=True, exist_ok=True)
        p = self.folder / "index.json"
        self.index = json.loads(p.read_text(encoding="utf-8")) if p.is_file() else {}

    def __call__(self, req: urllib.request.Request, timeout: float = 90):
        with self.opener(req, timeout=timeout) as r:
            body = r.read()
        key = request_key(req.get_method(), req.full_url, req.data)
        name = key + (".gz" if len(body) > 4096 else "")
        (self.folder / name).write_bytes(gzip.compress(body, mtime=0) if name.endswith(".gz") else body)
        self.index[key] = {"file": name, "url": re.sub(r"([?&](api_key|apikey|key)=)[^&]*", r"\1KEY", req.full_url)}
        (self.folder / "index.json").write_text(json.dumps(self.index, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        return io.BytesIO(body)


class Fetch:
    """Polite requests: a User-Agent (DRISHTI_FEEDS_USER_AGENT, else Drishti's), at least `pause` seconds between calls, retries
    after 2, 4, 8 s on 429, 5xx and network errors (the server's Retry-After when it gives one)."""
    RETRY = (2, 4, 8)

    def __init__(self, opener=urllib.request.urlopen, pause: float = 0.5, timeout: float = 90, sleep=time.sleep, log=print):
        self.opener, self.pause, self.timeout, self.sleep, self.log = opener, pause, timeout, sleep, log
        self._last = 0.0
        self.calls = 0
        self.recorded = isinstance(opener, Fixtures)

    def _wait(self) -> None:
        if self.recorded:
            return
        gap = self._last + self.pause - time.monotonic()
        if gap > 0:
            self.sleep(gap)
        self._last = time.monotonic()

    def raw(self, url: str, body: bytes | None = None, headers: dict | None = None) -> bytes:
        hdrs = {"User-Agent": os.environ.get("DRISHTI_FEEDS_USER_AGENT") or UA, "Accept": "*/*", **(headers or {})}
        if body is not None:
            hdrs.setdefault("Content-Type", "application/json")
        why = ""
        for attempt in range(len(self.RETRY) + 1):
            self._wait()
            req = urllib.request.Request(url, data=body, headers=hdrs, method="POST" if body is not None else "GET")
            try:
                with self.opener(req, timeout=self.timeout) as r:
                    self.calls += 1
                    return r.read()
            except urllib.error.HTTPError as e:
                why = f"HTTP {e.code}"
                if e.code not in (429, 500, 502, 503, 504) or self.recorded:
                    break
                wait = e.headers.get("Retry-After") if e.headers else None
                delay = float(wait) if wait and str(wait).isdigit() else None
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                why, delay = str(e), None
                if self.recorded:
                    break
            if attempt < len(self.RETRY):
                self.log(f"  retry {attempt + 1} {safe_url(url)}: {why}")
                self.sleep(delay if delay is not None else self.RETRY[attempt])
        raise FeedError(f"{safe_url(url)}: {why}")

    def json(self, url: str, body=None, headers: dict | None = None):
        data = None if body is None else json.dumps(body).encode()
        return json.loads(self.raw(url, data, {"Accept": "application/json", **(headers or {})}))


def safe_url(url: str) -> str:
    """The URL as logs and errors show it: an API key never printed."""
    return re.sub(r"([?&](api_key|apikey|key)=)[^&]*", r"\1***", url)


# ---------------------------------------------------------------- the feed

class PublicFeed:
    """One public-sector feed. A subclass sets `name` and implements pull(); the YAML (config/feeds/<name>.yaml) holds the source,
    the lake, the tables (kind -> connector, key), the schedule, the licences and the selection."""
    name = ""

    def __init__(self, spec: dict, fetch: Fetch, as_of: str | None = None, log=print):
        self.spec, self.fetch, self.as_of, self.log = spec, fetch, as_of or today(), log
        self.selection = spec.get("selection") or {}

    # what a subclass implements
    def pull(self, state: dict) -> dict[str, list[dict]]:
        """Fetch what is new since `state` (changed in place), and return every kind's documents for the business date."""
        raise NotImplementedError

    # helpers every pull uses
    def provenance(self, source: str | None = None, dataset: str | None = None, url: str | None = None) -> dict:
        s = self.source(source)
        return {"SOURCE": s.get("title") or s.get("name"), "DATASET": dataset or s.get("dataset") or s.get("name"),
                "RETRIEVED_AT": self.as_of, "LICENCE": s.get("licence"), "ATTRIBUTION": s.get("attribution"),
                "URL": url or s.get("url"), "INTERNAL_ONLY": bool(s.get("internal-only"))}

    def source(self, name: str | None = None) -> dict:
        sources = self.spec.get("sources") or []
        if name is None:
            return sources[0] if sources else {}
        for s in sources:
            if s.get("id") == name or s.get("name") == name:
                return s
        raise FeedError(f"feed {self.name}: no source '{name}' in its YAML")

    def table(self, kind: str) -> dict:
        for t in self.spec.get("tables") or []:
            if t.get("kind") == kind:
                return t
        raise FeedError(f"feed {self.name}: no table for kind '{kind}' in its YAML")


def number(v):
    """A published value as a float, None for a missing one ('', '.', 'NA', None)."""
    if v is None:
        return None
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).strip()
    if s in ("", ".", "NA", "NaN", "..", "n/a"):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def change(latest, previous):
    return None if latest is None or previous is None else round(latest - previous, 10)


def ident(s: str) -> str:
    """An id part: upper case, letters, digits and dashes only (NY.GDP.PCAP.CD -> NY_GDP_PCAP_CD)."""
    return re.sub(r"[^A-Z0-9]+", "_", str(s).upper()).strip("_")


# ---------------------------------------------------------------- writing

def write_outputs(spec: dict, docs: dict[str, list[dict]], out: Path, as_of: str) -> list[dict]:
    """One <kind>.<date>.jsonl.gz per kind with its .ready file (DATA_LAKE_GUIDE.md section 4): the lake, the pack, the kind, the
    connector, the business date, mode replace (the date replaced: a rerun is idempotent), the key, the checksum and the row count.
    Returns what was written, for the run's manifest."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for t in spec.get("tables") or []:
        kind = t["kind"]
        rows = docs.get(kind)
        if rows is None:
            continue
        rows = sorted(rows, key=lambda d: str(d.get(t["key"])))
        f = out / f"{kind}.{as_of}.jsonl.gz"
        buf = io.BytesIO()
        with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
            for d in rows:
                gz.write((json.dumps(d, ensure_ascii=False, sort_keys=False, separators=(",", ":")) + "\n").encode("utf-8"))
        f.write_bytes(buf.getvalue())
        sha = hashlib.sha256(buf.getvalue()).hexdigest()
        ready = {"lake": spec["lake"], "pack": spec["pack"], "kind": kind, "connector": t["connector"],
                 "business-date": as_of, "mode": "replace", "key": t["key"], "sha256": sha, "rows": len(rows)}
        Path(str(f) + ".ready").write_text(yaml.safe_dump(ready, sort_keys=False), encoding="utf-8")
        written.append({"kind": kind, "file": str(f), "rows": len(rows), "sha256": sha, "ready": ready})
    return written


def read_jsonl_gz(path: Path) -> list[dict]:
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]
