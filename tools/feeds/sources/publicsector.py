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

"""What the public-sector module's six feeds share (docs/guides/PUBLIC_SECTOR_GUIDE.md section 5), on the drishti-feeds Feed base
class (tools/feeds/feedbase.py): each source's licence and terms (the feed file's `options.sources`, one entry a source, since a feed may
read several sources under different terms), the PROVENANCE every document carries, the kept history of a time series in the feed's
folder, the values as published, and the recorded fixtures the tests answer from (never the network). Imported, never run.

    sources(run)              options.sources: [{id, name, title, url, terms, licence, redistributable, internal-only, attribution, ...}]
    provenance(run, ...)      {SOURCE, DATASET, RETRIEVED_AT, LICENCE, ATTRIBUTION, URL, INTERNAL_ONLY} for a document
    History                   <data>/feeds/<feed>/history/<name>.json: a series' kept points, merged with what a pull brought
    Fixtures, Recorder        an opener answering from recorded responses (tests), and one recording them (new fixtures)
"""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from feedbase import FeedError, FeedRefused


def sources(run) -> list[dict]:
    """The feed's sources with their licences (options.sources); a feed file without them is refused."""
    out = run.option("sources") or []
    if not out:
        raise FeedRefused(f"feed {run.spec.name}: options.sources names no source and its licence")
    return out


def source(run, sid: str | None = None) -> dict:
    srcs = sources(run)
    if sid is None:
        return srcs[0]
    for s in srcs:
        if s.get("id") == sid:
            return s
    raise FeedRefused(f"feed {run.spec.name}: no source '{sid}' in options.sources")


def provenance(run, sid: str | None = None, dataset: str | None = None, url: str | None = None) -> dict:
    s = source(run, sid)
    return {"SOURCE": s.get("title") or s.get("name"), "DATASET": dataset or s.get("name"), "RETRIEVED_AT": run.business_date.isoformat(),
            "LICENCE": s.get("licence"), "ATTRIBUTION": s.get("attribution"), "URL": url or s.get("url"),
            "INTERNAL_ONLY": bool(s.get("internal-only"))}


def need(doc, what: str):
    """A source's answer, or the run fails in words (feedbase answers None after its retries)."""
    if doc is None:
        raise FeedError(f"{what}: no answer after the retries")
    return doc


def tolerate(run, url: str) -> None:
    """A failed request the feed carries on without (one publisher down, a day GDELT skipped): taken off the run's failures, which
    would otherwise fail the whole run; the feed says so in its own log line."""
    run.http.failed[:] = [u for u in run.http.failed if u != url]


class History:
    """A time series' kept points under the feed's folder (history/<name>.json), merged with what a pull brought: a revised point
    replaces the old one, the oldest beyond `keep` (points, or a floor key) are dropped. Written at once: a failed run merges again."""

    def __init__(self, run, name: str):
        self.file = Path(run.folder) / "history" / f"{name}.json"

    def merge(self, points: dict, keep: int | None = None, floor: str | None = None, key: str = "DATE") -> list[dict]:
        try:
            kept = {h[key]: h["VALUE"] for h in json.loads(self.file.read_text(encoding="utf-8"))}
        except (OSError, ValueError):
            kept = {}
        kept.update({k: v for k, v in points.items() if v is not None})
        hist = [{key: k, "VALUE": v} for k, v in sorted(kept.items()) if floor is None or k >= floor]
        if keep:
            hist = hist[-keep:]
        self.file.parent.mkdir(parents=True, exist_ok=True)
        self.file.write_text(json.dumps(hist, separators=(",", ":")) + "\n", encoding="utf-8")
        return hist


def number(v):
    """A published value as a float, None for a missing one ('', '.', 'NA', None)."""
    if v is None:
        return None
    if isinstance(v, (int, float)) and not isinstance(v, bool):
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
    """An id part: upper case, letters, digits and underscores only (NY.GDP.PCAP.CD -> NY_GDP_PCAP_CD)."""
    return re.sub(r"[^A-Z0-9]+", "_", str(s).upper()).strip("_")


# ---------------------------------------------------------------- recorded fixtures (tests) and recording them

_KEYQ = re.compile(r"([?&](api_key|apikey|key)=)[^&]*")


def request_key(method: str, url: str, body: bytes | None) -> str:
    """The fixture name of one request: readable, stable, short; an API key never in it."""
    u = _KEYQ.sub(r"\1KEY", url)
    h = hashlib.sha256((method + " " + u + " " + (body or b"").decode("utf-8", "replace")).encode()).hexdigest()[:12]
    stem = re.sub(r"[^A-Za-z0-9]+", "-", urllib.parse.urlsplit(u).path)[-60:].strip("-") or "root"
    return f"{stem}-{h}"


class Fixtures:
    """An opener (for pullkit.Http) answering from recorded responses: <folder>/index.json maps a request key to a file (gzip when it
    ends .gz). A request with no recording raises: a pull under test never reaches the network."""

    def __init__(self, folder: Path):
        self.folder = Path(folder)
        self.index = json.loads((self.folder / "index.json").read_text(encoding="utf-8"))
        self.asked: list[str] = []

    def __call__(self, req: urllib.request.Request, timeout: float = 0):
        key = request_key(req.get_method(), req.full_url, req.data)
        self.asked.append(key)
        entry = self.index.get(key)
        if entry is None:
            raise AssertionError(f"no recorded response for {req.get_method()} {_KEYQ.sub(r'\1KEY', req.full_url)} (key {key}) in {self.folder}")
        raw = (self.folder / entry["file"]).read_bytes()
        return io.BytesIO(gzip.decompress(raw) if entry["file"].endswith(".gz") else raw)


class Recorder:
    """An opener that calls the network and keeps each response under <folder> with index.json: new fixtures for the tests."""

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
        self.index[key] = {"file": name, "url": _KEYQ.sub(r"\1KEY", req.full_url)}
        (self.folder / "index.json").write_text(json.dumps(self.index, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        return io.BytesIO(body)
