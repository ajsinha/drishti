#!/usr/bin/env python3
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
"""The demo lake built once per hash of what makes it, reused by every test stack and check (docs/architecture/TEST_HARNESS.md, "The cached demo lake").

A lake lives in ``<cache>/<hash>/<name>/``: ``delta/`` the lake, ``data/`` and ``connectors/`` what the build wrote beside it. ``<hash>`` covers the
committed test data, its generators and the lake code (the working tree's edits included), so a change to any of them makes a new one and the old is
pruned. It also covers the loader that writes the lake: the pinned Lake version (``pom.xml``), the Lake wheel pin and the wheel file's digest, and the
Lake jars the loader runs from (the CRCs of the ``lake-*`` jars inside the server's exec jar, or ``LAKE_CLASSPATH``'s files), so a Lake re-pin, or a rebuilt
loader under the same ``-SNAPSHOT`` version, makes a new lake. Nothing else invalidates it. ``<cache>`` is ``$DRISHTI_LAKE_CACHE``, else ``<repo>/data/tmp/lake-cache``. A cached lake is read-only by
contract: a test that writes to a lake builds its own.

    lake_cache.py hash
    lake_cache.py build [--only DATASETS] [--as-of DATE]     build unless cached; prints the lake's path
    lake_cache.py prune [--keep N]

Standard library only.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ("data/testdata", "tools/testdata", "tools/pvgen", "tools/lake", "tools/drishti.py", "tools/build_demo_lakes.py", "tools/demonames.py", "config/demo",
           "config/packs", "config/lakes", "tools/packgen", "lake/drishti-lake-host/src/main")
DONE = "BUILT"
KEEP = 3


def cache_root(root: Path = ROOT) -> Path:
    return Path(os.environ.get("DRISHTI_LAKE_CACHE") or root / "data" / "tmp" / "lake-cache")


def source_hash(root: Path = ROOT, sources: tuple[str, ...] = SOURCES) -> str:
    """The hash of the sources: the index's blob ids plus the content of every file edited or added in the working tree."""
    def git(*a: str) -> str:
        return subprocess.run(["git", *a], cwd=root, capture_output=True, text=True, check=True).stdout
    h = hashlib.sha256(git("ls-files", "-s", "--", *sources).encode())
    h.update(loader_key(root).encode())
    for line in sorted(set(git("ls-files", "-m", "--others", "--exclude-standard", "--", *sources).splitlines())):
        p = root / line
        h.update(line.encode())
        h.update(p.read_bytes() if p.is_file() else b"deleted")
    return h.hexdigest()[:16]


def loader_key(root: Path = ROOT) -> str:
    """What identifies the loader that writes the lake: the pinned Lake version, the wheel pin and the pinned wheel's SHA-256 (``-`` when it is absent),
    and the Lake jars in use (name, size and CRC of the ``lake-*`` jars inside the server's exec jar in ``<root>/lib``, else the files of ``LAKE_CLASSPATH``)."""
    parts: list[str] = []
    pom = root / "pom.xml"
    m = re.search(r"<drishti\.lake\.version>([^<]+)</drishti\.lake\.version>", pom.read_text(encoding="utf-8")) if pom.is_file() else None
    parts.append("lake.version=" + (m.group(1) if m else "-"))
    pin = root / "lib" / "lake-wheel.pin"
    parts.append("pin=" + (hashlib.sha256(pin.read_bytes()).hexdigest() if pin.is_file() else "-"))
    version = re.search(r"^version=(\S+)", pin.read_text(encoding="utf-8"), re.M) if pin.is_file() else None
    wheel = root / "lib" / "wheels" / f"drishti_lake-{version.group(1)}-py3-none-any.whl" if version else None
    parts.append("wheel=" + (hashlib.sha256(wheel.read_bytes()).hexdigest() if wheel and wheel.is_file() else "-"))
    for jar in sorted((root / "lib").glob("drishti-server-*-exec.jar")):
        with zipfile.ZipFile(jar) as z:
            parts += [f"{i.filename}:{i.file_size}:{i.CRC}" for i in z.infolist() if re.match(r"BOOT-INF/lib/(lake|drishti-lake)-.*\.jar$", i.filename)]
    for entry in sorted(filter(None, os.environ.get("LAKE_CLASSPATH", "").split(os.pathsep))):
        f = Path(entry)
        files = sorted(f.rglob("*")) if f.is_dir() else [f]
        for x in files:
            if x.is_file():
                parts.append(f"cp:{x}:{x.stat().st_size}:{int(x.stat().st_mtime)}")
    return "\n".join(parts)


def _safe(name: str) -> str:
    return "".join(c if c.isalnum() or c in "-_." else "_" for c in name)[:80] + "-" + hashlib.sha1(name.encode()).hexdigest()[:8]


def cached(name: str, build: Callable[[Path], None], root: Path = ROOT) -> Path:
    """The folder of ``name`` for the current sources: ``build(folder)`` runs at most once per hash, under a lock held across processes."""
    base = cache_root(root) / source_hash(root)
    base.mkdir(parents=True, exist_ok=True)
    folder = base / _safe(name)
    with open(base / f"{folder.name}.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not (folder / DONE).exists():
            shutil.rmtree(folder, ignore_errors=True)
            scratch = base / f"{folder.name}.building"
            shutil.rmtree(scratch, ignore_errors=True)
            scratch.mkdir()
            try:
                build(scratch)
                (scratch / DONE).write_text(time.strftime("%FT%T\n"), encoding="utf-8")
                scratch.rename(folder)
            finally:
                shutil.rmtree(scratch, ignore_errors=True)
    os.utime(base)                                  # the newest used hashes survive a prune
    return folder


def demo_lake(only: str, as_of: str, extra: tuple[str, ...] = (), root: Path = ROOT, python: str = sys.executable) -> Path:
    """The folder of a demo lake of ``only`` ending ``as_of`` (``delta/`` inside), built once per hash by the default fast path (lake demo's parallel Java loader, several tables at a time). ``only``
    empty: every dataset of the saved test data (the full demo lake the sweeps run on, about 3 GB, minutes to build)."""
    def build(work: Path) -> None:
        r = subprocess.run([python, str(root / "tools" / "drishti.py"), "lake", "demo", "--lake", str(work / "delta"), "--data-dir", str(work / "data"),
                            "--connectors-dir", str(work / "connectors"), "--as-of", as_of, *(("--only", only) if only else ()), "--no-signal", "-q", *extra], capture_output=True, text=True, cwd=root, timeout=1500 if only else 3600)
        if r.returncode != 0:
            raise RuntimeError("lake demo failed: " + (r.stderr or r.stdout)[-800:])
        point_connectors(work / "connectors", work.with_name(work.name.removesuffix(".building")) / "delta")
    return cached("|".join((only, as_of, *extra)), build, root)


def point_connectors(folder: Path, lake: Path) -> int:
    """Points the connector files a lake build wrote at ``lake``, where the lake is now: the build writes them while the lake is still in
    its ``<name>.building`` folder, which ``cached`` then renames, so they named a folder that no longer exists (drill of 2026-11-15).
    Every ``<...>.building/delta`` path in them becomes ``lake``. Returns the number of files changed."""
    changed = 0
    for f in sorted(folder.glob("*.yaml")) if folder.is_dir() else []:
        text = f.read_text(encoding="utf-8")
        new = re.sub(r"[^\s;:]+\.building/delta", lambda _m: str(lake), text)
        if new != text:
            f.write_text(new, encoding="utf-8")
            changed += 1
    return changed


def prune(keep: int = KEEP, root: Path = ROOT) -> list[str]:
    """Removes every hash folder but the ``keep`` newest; returns what it removed."""
    base = cache_root(root)
    if not base.is_dir():
        return []
    dirs = sorted((d for d in base.iterdir() if d.is_dir()), key=lambda d: d.stat().st_mtime, reverse=True)
    gone = []
    for d in dirs[keep:]:
        shutil.rmtree(d, ignore_errors=True)
        gone.append(d.name)
    return gone


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("cmd", choices=("hash", "build", "prune"))
    ap.add_argument("--only", default="market-risk")
    ap.add_argument("--as-of", default="today")
    ap.add_argument("--keep", type=int, default=KEEP)
    a = ap.parse_args(argv)
    if a.cmd == "hash":
        print(source_hash())
    elif a.cmd == "prune":
        print("\n".join(prune(a.keep)))
    else:
        print(demo_lake(a.only, a.as_of) / "delta")
    return 0


if __name__ == "__main__":
    sys.exit(main())
