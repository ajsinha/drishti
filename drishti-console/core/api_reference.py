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

"""The built API references Help -> Developer serves (IMPLEMENTATION_PLAN row 2g; tools/api_docs.py writes them).

The folder (``help.api_docs_dir``, default ``web/apidocs``) holds one folder per version, each with ``manifest.json``, ``index.html``,
``java/<module>/`` (Javadoc) and ``python/`` (pdoc). Nothing in it is committed; a console without it says how to build it.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

VERSION = re.compile(r"\d+\.\d+\.\d+(?:[-.][A-Za-z0-9.]+)?")
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript", ".json": "application/json", ".svg": "image/svg+xml",
         ".png": "image/png", ".gif": "image/gif", ".txt": "text/plain; charset=utf-8", ".md": "text/plain; charset=utf-8", ".woff2": "font/woff2", ".woff": "font/woff", "": "text/plain; charset=utf-8"}


def _key(v: str) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def versions(folder: Path) -> list[dict]:
    """The built versions, newest first: each one's manifest (version, built, java, python)."""
    if not folder.is_dir():
        return []
    out = []
    for d in folder.iterdir():
        m = d / "manifest.json"
        if d.is_dir() and VERSION.fullmatch(d.name) and m.is_file():
            try:
                out.append(json.loads(m.read_text(encoding="utf-8")))
            except (OSError, ValueError):
                continue
    return sorted(out, key=lambda x: _key(str(x.get("version", ""))), reverse=True)


def file(folder: Path, path: str) -> tuple[bytes, str] | None:
    """A file of a built version (``<version>/...``) and its media type; None for anything outside the folder or of another type.
    A folder's address answers its ``index.html``; ``latest/...`` is the newest version built."""
    root = folder.resolve()
    first, _, rest = path.partition("/")
    if first == "latest":                            # /help/reference/latest/...: the newest version built (the guides link to it)
        built = versions(folder)
        if not built:
            return None
        path = f"{built[0]['version']}/{rest}"
    target = (root / path).resolve()
    if root not in target.parents:
        return None
    if target.is_dir():
        target = target / "index.html"
    if not target.is_file() or target.suffix.lower() not in TYPES or not VERSION.fullmatch(target.relative_to(root).parts[0]):
        return None
    return target.read_bytes(), TYPES[target.suffix.lower()]
