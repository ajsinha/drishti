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

"""The shared parts of the migration evidence tools (docs/plans: I7B_PRODUCTS_PLAN section 3.3, increment C0): canonical JSON, the
normal form of a recorded answer (what may differ between two runs of the same build is removed, nothing else), a plain HTTP client
with timing, and the list of differences between two documents."""
from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

#: Keys whose value is a clock reading or a measurement of the run, not of the build (dropped wherever they occur in an answer).
VOLATILE = frozenset({"timings", "fetchedAt", "updatedAt", "timestamp", "elapsedMs", "generatedAt", "servedBy", "ms", "providerMs", "computeMs",
                      "requestId", "computedAt"})
#: Text that carries a clock reading or a run's own id, wherever it occurs inside a string (replaced by a fixed word): an ISO instant, a compute request id, a provenance id's hash.
_RUN_TEXT = ((re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z"), "<instant>"), (re.compile(r"req-[a-z0-9]+-[0-9a-f]+"), "<request>"),
             (re.compile(r"(pv:\d{4}-\d\d-\d\d:)[0-9a-f]+"), r"\1<hash>"))


def canonical(value) -> str:
    """The one text of a JSON value: keys sorted, no white space, non-ASCII kept."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def normalise(value):
    """An answer without what varies by run: the VOLATILE keys are removed at every depth."""
    if isinstance(value, dict):
        return {k: normalise(v) for k, v in value.items() if k not in VOLATILE}
    if isinstance(value, list):
        return [normalise(v) for v in value]
    if isinstance(value, str):
        for rx, word in _RUN_TEXT:
            value = rx.sub(word, value)
    return value


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def get(url: str, timeout: float = 60.0, headers: dict | None = None):
    """(status, body bytes, seconds): an HTTP error status is an answer, a refused connection is raised."""
    req = urllib.request.Request(url, headers=headers or {})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), time.perf_counter() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read(), time.perf_counter() - t0


def get_json(url: str, timeout: float = 60.0):
    status, body, _ = get(url, timeout, {"Accept": "application/json, application/schema+json"})
    try:
        return status, json.loads(body) if body else None
    except ValueError:
        return status, {"unparsed": hashlib.sha256(body).hexdigest(), "bytes": len(body)}


def diff(a, b, path: str = "") -> list[str]:
    """The paths at which two JSON documents differ, each as 'path: before -> after' (long values cut)."""
    def cut(v):
        t = canonical(v)
        return t if len(t) <= 80 else t[:77] + "..."
    if type(a) is not type(b):
        return [f"{path or '/'}: {cut(a)} -> {cut(b)}"]
    if isinstance(a, dict):
        out = []
        for k in sorted(set(a) | set(b)):
            if k not in a:
                out.append(f"{path}/{k}: (absent) -> {cut(b[k])}")
            elif k not in b:
                out.append(f"{path}/{k}: {cut(a[k])} -> (absent)")
            else:
                out += diff(a[k], b[k], f"{path}/{k}")
        return out
    if isinstance(a, list):
        if len(a) != len(b):
            return [f"{path or '/'}: {len(a)} items -> {len(b)} items"] + [d for x, y in zip(a, b) for d in diff(x, y, path + "[]")][:20]
        return [d for i, (x, y) in enumerate(zip(a, b)) for d in diff(x, y, f"{path}[{i}]")]
    return [] if a == b else [f"{path or '/'}: {cut(a)} -> {cut(b)}"]


def pack(folder: Path, target: Path) -> None:
    """A capture folder as one .tar.gz that is the same bytes for the same files (sorted, no times, no owners)."""
    import gzip
    import tarfile
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as gz, tarfile.open(fileobj=gz, mode="w", format=tarfile.PAX_FORMAT) as tar:
        for p in sorted(folder.rglob("*")):
            if p.is_file():
                ti = tar.gettarinfo(str(p), arcname=p.relative_to(folder).as_posix())
                ti.mtime, ti.uid, ti.gid, ti.uname, ti.gname, ti.mode = 0, 0, 0, "", "", 0o644
                with open(p, "rb") as f:
                    tar.addfile(ti, f)


def unpack(archive: Path, folder: Path) -> Path:
    import tarfile
    with tarfile.open(archive) as tar:
        tar.extractall(folder, filter="data")
    return folder
