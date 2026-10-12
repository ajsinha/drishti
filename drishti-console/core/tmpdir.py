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
"""The console's one temp folder.

The console keeps a per-process folder ``drishti-console-<pid>`` under the configured base (``tmp_dir`` /
``DRISHTI_CONSOLE_TMP_DIR``, then ``DRISHTI_TMP_DIR``, then the system temp folder). :func:`start` makes it, removes the folders of
dead processes a crash left behind, points ``tempfile.tempdir`` at it (so uploads, exports, the static-zip cache and the Python
runner's scratch all land in it) and removes it on a clean exit. Every temporary file or folder of the console is made through this
module: a test forbids ``tempfile.*`` calls anywhere else in console code.
"""
from __future__ import annotations

import atexit
import contextlib
import os
import shutil
import tempfile
from pathlib import Path

_PREFIX = "drishti-console-"
_MARKER = ".drishti-console-tmp"
_root: Path | None = None
_base: Path | None = None
_before: str | None = None            # tempfile.tempdir before start(), put back by clean_up()


def configured_base(setting: str = "") -> str:
    """The base: the ``tmp_dir`` setting, ``DRISHTI_CONSOLE_TMP_DIR``, ``DRISHTI_TMP_DIR``, then the system temp folder."""
    for candidate in (setting, os.environ.get("DRISHTI_CONSOLE_TMP_DIR", ""), os.environ.get("DRISHTI_TMP_DIR", "")):
        if candidate and str(candidate).strip():
            return str(candidate).strip()
    return tempfile.gettempdir() if _root is None else str(_base)


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def remove_stale(base: Path) -> int:
    """Removes ``drishti-console-<pid>`` folders (with our marker) whose process is gone. Returns how many."""
    removed = 0
    try:
        entries = list(base.iterdir())
    except OSError:
        return 0
    for p in entries:
        n = p.name
        if not n.startswith(_PREFIX) or not n[len(_PREFIX):].isdigit() or not (p / _MARKER).is_file():
            continue
        pid = int(n[len(_PREFIX):])
        if pid != os.getpid() and not _alive(pid):
            shutil.rmtree(p, ignore_errors=True)
            removed += 1
    return removed


def start(setting: str = "") -> Path:
    """Makes the process folder (once), removes stale ones, sets ``tempfile.tempdir`` and registers the clean-up."""
    global _root, _base, _before
    if _root is not None:
        return _root
    base = Path(configured_base(setting)).expanduser().resolve()
    base.mkdir(parents=True, exist_ok=True)
    remove_stale(base)
    root = base / f"{_PREFIX}{os.getpid()}"
    root.mkdir(exist_ok=True)
    (root / _MARKER).write_text(f"pid {os.getpid()}\n", encoding="utf-8")
    _root, _base, _before = root, base, tempfile.tempdir
    tempfile.tempdir = str(root)
    atexit.register(clean_up)
    return root


def root() -> Path:
    """The process folder; started with the defaults if nothing started it."""
    return _root if _root is not None else start()


def clean_up() -> None:
    """Removes the process folder (registered to run at exit)."""
    global _root
    if _root is not None:
        shutil.rmtree(_root, ignore_errors=True)
        _root = None
        tempfile.tempdir = _before


def mkdtemp(prefix: str = "tmp-", dir: str | os.PathLike | None = None) -> str:
    """A new folder in ``dir`` (default the process folder)."""
    return tempfile.mkdtemp(prefix=prefix, dir=str(dir) if dir else str(root()))


@contextlib.contextmanager
def temp_dir(prefix: str = "tmp-"):
    """A new folder in the process folder, removed when the block ends."""
    path = mkdtemp(prefix)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def sub_dir(name: str) -> Path:
    """A named subfolder of the process folder (made if absent), for a cache that lives as long as the process."""
    p = root() / name
    p.mkdir(parents=True, exist_ok=True)
    return p


def info() -> dict:
    """Where the folder is and how much it holds, for Admin > Health."""
    if _root is None:
        return {"base": configured_base(), "path": "", "bytes": 0, "files": 0}
    size = files = 0
    for dirpath, _dirs, names in os.walk(_root):
        for n in names:
            try:
                size += os.path.getsize(os.path.join(dirpath, n))
                files += 1
            except OSError:
                pass
    return {"base": str(_base), "path": str(_root), "bytes": size, "files": files}
