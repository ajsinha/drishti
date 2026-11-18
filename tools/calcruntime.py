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

"""``drishti.py calc install``: installs the Python runtime of Calc (USER_GUIDE.md): Pyodide, CPython compiled to WebAssembly, which the
console serves from its own origin (never a CDN). Downloads the pinned release once, checks its SHA-256, and unpacks only the core runtime
and numpy, pandas, scipy, statsmodels and matplotlib with what they depend on into drishti-console/web/static/vendor/pyodide/
(git-ignored). Run it again and it does nothing unless the pin changed.

    python tools/drishti.py calc install                         download (cached in ~/.cache/drishti), verify, unpack
    python tools/drishti.py calc install --tarball FILE          an already downloaded pyodide-<version>.tar.bz2 (offline machines)
    python tools/drishti.py calc install --dest DIR --force      somewhere else; unpack again even if installed

Standard library only, so it also runs as ``python tools/calcruntime.py [the same options]`` where nothing else of Drishti is installed
(the console's Docker image runs it so at build time: a running console needs no internet)."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

VERSION = "314.0.7"
SHA256 = "192b5864e6e6d30ab074861af800cb8b4acb0998ef0f9342c3367448aeb86645"
URL = f"https://github.com/pyodide/pyodide/releases/download/{VERSION}/pyodide-{VERSION}.tar.bz2"
PACKAGES = ("numpy", "pandas", "scipy", "statsmodels", "matplotlib")
CORE = {"pyodide.mjs", "pyodide.asm.mjs", "pyodide.asm.wasm", "python_stdlib.zip", "package.json"}
STAMP = "drishti-pyodide.txt"

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "drishti-console" / "web" / "static" / "vendor" / "pyodide"


class InstallError(RuntimeError):
    """The archive is refused or incomplete: said in words, exit 1."""


def default_cache(env=None) -> Path:
    """``$XDG_CACHE_HOME/drishti``, else ``~/.cache/drishti``."""
    env = os.environ if env is None else env
    base = (env.get("XDG_CACHE_HOME") or "").strip()
    return Path(base) / "drishti" if base else Path.home() / ".cache" / "drishti"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def installed(dest: Path) -> bool:
    """Whether ``dest`` holds this pin (its stamp names this version and hash)."""
    try:
        lines = set((dest / STAMP).read_text(encoding="utf-8").splitlines())
    except OSError:
        return False
    return f"version={VERSION}" in lines and f"sha256={SHA256}" in lines


def _size(dest: Path) -> tuple[int, int]:
    files = [p for p in dest.rglob("*") if p.is_file()]
    return sum(p.stat().st_size for p in files), len(files)


def download(cache: Path, say=print) -> Path:
    """The pinned archive in ``cache``, downloaded once (again when the cached copy's hash is wrong)."""
    tarball = cache / f"pyodide-{VERSION}.tar.bz2"
    if tarball.is_file() and sha256(tarball) == SHA256:
        return tarball
    cache.mkdir(parents=True, exist_ok=True)
    say(f"downloading {URL} (about 340 MB, once)")
    part = tarball.with_name(tarball.name + ".part")
    with urllib.request.urlopen(URL, timeout=120) as r, open(part, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    part.replace(tarball)
    return tarball


def unpack(tarball: Path, out: Path, wanted=PACKAGES) -> list[str]:
    """Unpacks the core runtime and the closure of the wanted packages into ``out`` (made), and writes a lock file listing only those, so
    Pyodide never looks for anything that is not here (an import of anything else fails at once, with no request); the packages kept."""
    def members():
        with tarfile.open(tarball, "r|bz2") as tar:      # streamed: the archive is read front to back
            for m in tar:
                yield tar, m

    lock = None
    for tar, m in members():                             # pass 1: the lock file, to know what the packages need
        if m.name == "pyodide/pyodide-lock.json":
            lock = json.load(tar.extractfile(m))
            break
    if lock is None:
        raise InstallError("no pyodide-lock.json in the archive")
    packages = lock["packages"]
    keep, todo = set(), list(wanted)
    while todo:
        name = todo.pop()
        if name in keep:
            continue
        if name not in packages:
            raise InstallError(f"the release has no package {name}")
        keep.add(name)
        todo.extend(packages[name].get("depends", []))
    files = {packages[n]["file_name"] for n in keep} | CORE
    out.mkdir(parents=True)
    found = set()
    for tar, m in members():                             # pass 2: only those files
        name = m.name.removeprefix("pyodide/")
        if m.isfile() and name in files and "/" not in name:
            (out / name).write_bytes(tar.extractfile(m).read())
            found.add(name)
    missing = files - found
    if missing:
        raise InstallError(f"missing from the archive: {sorted(missing)}")
    lock["packages"] = {n: p for n, p in packages.items() if n in keep}
    (out / "pyodide-lock.json").write_text(json.dumps(lock, indent=1), encoding="utf-8")
    return sorted(keep)


def install(dest: Path = DEST, tarball: Path | None = None, cache: Path | None = None, force: bool = False, say=print) -> int:
    """Installs the pinned runtime into ``dest``; 0 done (or already there), 1 refused."""
    dest = Path(dest)
    if not force and installed(dest):
        size, _ = _size(dest)
        say(f"Pyodide {VERSION} is already installed in {dest} ({size / 1e6:.0f} MB); --force unpacks it again")
        return 0
    try:
        tarball = Path(tarball) if tarball else download(cache or default_cache(), say)
        got = sha256(tarball)
        if got != SHA256:
            raise InstallError(f"{tarball} has SHA-256 {got}, expected {SHA256}: refusing it")
        say(f"verified {tarball} (SHA-256 {SHA256})")
        with tempfile.TemporaryDirectory(prefix="drishti-pyodide.") as work:
            out = Path(work) / "pyodide"
            kept = unpack(tarball, out)
            say("packages: " + ", ".join(kept))
            (out / STAMP).write_text(f"version={VERSION}\nsha256={SHA256}\npackages={' '.join(PACKAGES)}\nsource={URL}\n"
                                     f"installed={datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}\n", encoding="utf-8")
            dest.parent.mkdir(parents=True, exist_ok=True)
            old = dest.with_name(dest.name + ".old")
            shutil.rmtree(old, ignore_errors=True)
            if dest.exists():
                dest.rename(old)
            shutil.move(str(out), str(dest))
            shutil.rmtree(old, ignore_errors=True)
    except (InstallError, OSError) as e:
        say(f"calc install: {e}", file=sys.stderr)
        return 1
    size, n = _size(dest)
    say(f"installed Pyodide {VERSION} in {dest}: {size / 1e6:.0f} MB on disk, {n} files")
    return 0


def add_arguments(p: argparse.ArgumentParser) -> None:
    p.add_argument("--dest", type=Path, default=DEST, help=f"where to unpack (default {DEST.relative_to(ROOT)})")
    p.add_argument("--tarball", type=Path, help=f"an already downloaded pyodide-{VERSION}.tar.bz2 (offline machines; its hash is still checked)")
    p.add_argument("--cache", type=Path, help="where the download is kept (default $XDG_CACHE_HOME/drishti, else ~/.cache/drishti)")
    p.add_argument("--force", action="store_true", help="unpack again even if this version is installed")


def register(groups) -> None:
    """Adds the ``calc`` group: ``calc install``."""
    g = groups.add_parser("calc", help="calc install: the Python runtime of Calc (Pyodide), pinned and verified, served by the console itself",
                          description=__doc__.split("\n\n")[0])
    sub = g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    p = sub.add_parser("install", help="calc install: download (once), verify and unpack Pyodide for Calc",
                       description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n")[1], formatter_class=argparse.RawDescriptionHelpFormatter)
    add_arguments(p)
    p.set_defaults(func=lambda a, extra=None: install(a.dest, a.tarball, a.cache, a.force))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="calcruntime.py", description=__doc__.split("\n\n")[0])
    add_arguments(ap)
    a = ap.parse_args(argv)
    return install(a.dest, a.tarball, a.cache, a.force)


if __name__ == "__main__":
    sys.exit(main())
