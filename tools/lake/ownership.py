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

"""Which tables of a lake Drishti's lake tool built, so maintenance touches only those (the ownership rule).

``drishti.py lake build`` records every table it commits to in ``<lake>/_drishti-lake/tables-built.json``
({"<domain>/<table>": {pack, kind, firstBuild, lastBuild, run}}), under the lake's lock. Builds made before that file existed
are found once in their run summaries (``<lake>/_drishti-lake/runs/*.json``) and added to it. A table with no such record was
published by another system: it is listed, never compacted or vacuumed by Drishti (``lake maintain --force-foreign`` overrides,
with a warning).

Object-storage lakes (s3://...) are never built by the lake tool, so their tables are foreign unless forced."""
from __future__ import annotations

import json
import os
import pathlib

STATE = "_drishti-lake"
REGISTRY = "tables-built.json"
FOREIGN = "maintained by its publisher: not built by drishti.py lake build (no build record in _drishti-lake)"


def _key(lake: pathlib.Path, table: str | pathlib.Path) -> str | None:
    t = pathlib.Path(table)
    try:
        rel = t.resolve().relative_to(lake.resolve())
    except ValueError:
        return None
    parts = rel.parts
    return "/".join(parts) if len(parts) == 2 else None


def _read(path: pathlib.Path) -> dict:
    try:
        got = json.loads(path.read_text(encoding="utf-8"))
        return got if isinstance(got, dict) else {}
    except (OSError, ValueError):
        return {}


def _write(path: pathlib.Path, reg: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.writing.{os.getpid()}")
    tmp.write_text(json.dumps(reg, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, path)


def record(lake: str | pathlib.Path, summary: dict) -> None:
    """Called by a build that committed (status ok or partial), while it holds the lake's lock."""
    lake = pathlib.Path(lake)
    key = _key(lake, summary.get("table") or "")
    if not key:
        return
    path = lake / STATE / REGISTRY
    with _RegistryLock(lake):                           # builds of other tables may record at the same moment
        reg = _read(path)
        at = summary.get("finishedAt") or summary.get("startedAt")
        old = reg.get(key) or {}
        reg[key] = {"pack": summary.get("pack") or old.get("pack"), "kind": summary.get("kind") or old.get("kind"),
                    "firstBuild": old.get("firstBuild") or at, "lastBuild": at, "run": summary.get("run")}
        _write(path, reg)


class _RegistryLock:
    """A short, waiting lock around the registry's read and write (POSIX; on Windows builds never run side by side)."""

    def __init__(self, lake: pathlib.Path):
        self.path = lake / STATE / "registry.lock"
        self.fh = None

    def __enter__(self):
        if os.name == "nt":
            return self
        import fcntl
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.fh = open(self.path, "a+", encoding="utf-8")
        fcntl.flock(self.fh.fileno(), fcntl.LOCK_EX)
        return self

    def __exit__(self, *exc):
        if self.fh:
            self.fh.close()
        return False


def built(lake: str | pathlib.Path) -> dict:
    """{"<domain>/<table>": record} for every table the lake tool built under this lake root (local lakes only)."""
    root = str(lake)
    if "://" in root and not root.startswith("file://"):
        return {}
    lake = pathlib.Path(root.removeprefix("file://"))
    state = lake / STATE
    reg = _read(state / REGISTRY)
    runs = state / "runs"
    if runs.is_dir():                                   # builds older than the registry: their summaries say what they wrote
        added = False
        for f in sorted(runs.glob("*.json")):
            s = _read(f)
            key = _key(lake, s.get("table") or "") if s.get("status") in ("ok", "partial") and not s.get("dryRun") else None
            if key and key not in reg:
                reg[key] = {"pack": s.get("pack"), "kind": s.get("kind"), "firstBuild": s.get("finishedAt"), "lastBuild": s.get("finishedAt"),
                            "run": s.get("run")}
                added = True
        if added and state.is_dir():
            try:
                _write(state / REGISTRY, reg)
            except OSError:
                pass                                    # a read-only lake still answers
    return reg


def owner(lake: str | pathlib.Path, table: str) -> dict | None:
    """The build record of ``<domain>/<table>`` under ``lake``, or None when another system published it."""
    return built(lake).get(table)
