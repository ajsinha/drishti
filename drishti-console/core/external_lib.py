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
"""The external library folder, console side.

``<base>/python`` holds modules and packages (folders, or ``.whl`` / ``.zip`` / ``.egg`` files of pure Python) that the
console makes importable at start; ``<base>/java`` is the server's (jars on its class path). The base is the
``external_lib`` setting, then ``DRISHTI_EXTERNAL_LIB``, then ``external_lib`` in the repository root. Python items in
the sandbox see these paths too, but may import a package only when it is on the allow-list (``bi.python.packages``).
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

_REPO = Path(__file__).resolve().parents[2]
_paths: list[str] = []
_base: Path | None = None


def base(setting: str = "") -> Path:
    """The configured base folder (not checked for existence)."""
    for candidate in (setting, os.environ.get("DRISHTI_EXTERNAL_LIB", "")):
        if candidate:
            return Path(candidate).expanduser().resolve()
    return _REPO / "external_lib"


def python_paths(root: Path) -> list[str]:
    """``<root>/python`` and every importable archive directly inside it, in name order."""
    folder = root / "python"
    if not folder.is_dir():
        return []
    archives = sorted(str(p) for p in folder.iterdir() if p.is_file() and p.suffix in (".whl", ".zip", ".egg"))
    return [str(folder)] + archives


def activate(setting: str = "") -> list[str]:
    """Put the folder's Python paths on ``sys.path`` (after the console's own folder, so console modules win)."""
    global _base, _paths
    _base = base(setting)
    _paths = python_paths(_base)
    for p in _paths:
        if p not in sys.path:
            sys.path.insert(1, p)
    return list(_paths)


def paths() -> list[str]:
    """The paths activated at start (for the sandboxed Python workers)."""
    return list(_paths)


def info() -> dict:
    """What Health shows: the base, the Python folder and what is in it."""
    root = _base or base()
    folder = root / "python"
    items = sorted(p.name for p in folder.iterdir() if not p.name.startswith(".") and p.name != "__pycache__") if folder.is_dir() else []
    return {"base": str(root), "python": str(folder), "items": items}
