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

"""The one way Drishti's tools and tests start a JVM: ``java_cmd(java, [args...])`` puts ``-Djava.io.tmpdir=<a Drishti folder>`` first.

Since JDK 17 ``Files.createTempFile`` reads ``java.io.tmpdir`` once, at JVM start, so only the command line moves it. DuckDB (60 MB),
RocksDB and CatBoost unpack their native code that way; without the flag every JVM killed without a clean exit leaves its copy in the
system /tmp, which has a per-user quota (a full one crashes Chromium and the browser tests).

The folder: ``tmp`` when given (a test stack passes a folder under its own tmp_path), else the server's own temp base, as
``TempFiles.configuredBase`` computes it: ``DRISHTI_TMP_DIR``, else ``<DRISHTI_DATA_DIR or <Drishti folder>/data>/tmp/server``. The server removes
stale native copies from that base at start. The folder is made if absent (Java warns and the libraries fail on a missing one).
A ``-Djava.io.tmpdir=`` already in the arguments wins. Standard library only: the console tests load it through ``java_launch.py``.

The ``sutra`` command line (``cli_tmp``) is the exception: it writes nothing under the data folder, so its JVM gets ``DRISHTI_TMP_DIR``
when set, else a fresh folder in the system temp folder, removed when the command ends.

tests/test_java_launch_guard.py (drishti-console) fails when a file under drishti-console/tests or tools starts java without it."""
from __future__ import annotations

import contextlib
import os
import shutil
import tempfile
from pathlib import Path

FLAG = "-Djava.io.tmpdir="


def server_tmp(cwd=None, env=None) -> Path:
    """The server's temp base for a server started in ``cwd`` with ``env``: DRISHTI_TMP_DIR, else <data dir>/tmp/server."""
    env = os.environ if env is None else env
    base = Path(cwd) if cwd else REPO
    given = (env.get("DRISHTI_TMP_DIR") or "").strip()
    if given:
        p = Path(given)
    else:
        p = Path((env.get("DRISHTI_DATA_DIR") or "").strip() or "data") / "tmp" / "server"
    return (p if p.is_absolute() else base / p).resolve()


def tmp_flag(tmp=None, cwd=None, env=None) -> str:
    """``-Djava.io.tmpdir=<folder>``, the folder made."""
    folder = Path(tmp) if tmp else server_tmp(cwd, env)
    folder.mkdir(parents=True, exist_ok=True)
    return FLAG + str(folder)


# The JDK caches a temporary direct buffer per thread for every channel read into a heap buffer, as large as the read, unless capped:
# a server or loader with many threads then exhausts -XX:MaxDirectMemorySize (OPERATIONS_GUIDE.md, Direct memory). 256 KB.
CACHE_FLAG = "-Djdk.nio.maxCachedBufferSize="
CACHE_LIMIT = CACHE_FLAG + "262144"


# 2.9.7 (CLUSTER_GUIDE.md 5.4, D5): compact object headers (about 10% less heap) whenever the JVM is 25 or later. A product flag
# there (JEP 519); Java 21 refuses to start with it, and 24 is not supported, so it is added only when the version says 25+.
COMPACT = "-XX:+UseCompactObjectHeaders"
_VERSIONS: dict = {}


def java_version(java) -> int:
    """The feature version of a ``java`` binary (21, 25, ...), asked once per binary; 21 when it cannot say."""
    key = str(java)
    if key not in _VERSIONS:
        import subprocess
        try:
            out = subprocess.run([key, "-XshowSettings:properties", "-version"], capture_output=True, text=True, timeout=30).stderr
            line = next((x for x in out.splitlines() if "java.specification.version" in x), "")
            _VERSIONS[key] = int(line.split("=")[1].strip().split(".")[0]) if line else 21
        except (OSError, ValueError, IndexError, subprocess.SubprocessError):
            _VERSIONS[key] = 21
    return _VERSIONS[key]


def version_flags(java, args=()) -> list:
    """The flags this JVM's version allows that Drishti wants: compact object headers on 25 and later (none on 21)."""
    return [COMPACT] if java_version(java) >= 25 and not any(str(a).startswith("-XX:+UseCompactObjectHeaders") or
                                                              str(a).startswith("-XX:-UseCompactObjectHeaders") for a in args) else []


#: the repository this file is in: its modules/ are the modules a server started from here serves
REPO = Path(__file__).resolve().parent.parent


def _named(key: str, envs: tuple, args: list, env) -> bool:
    """Whether a setting is given: as a -D or --flag in the arguments, or as one of its environment variables."""
    env = os.environ if env is None else env
    return any(a.startswith(f"-D{key}=") or a.startswith(f"--{key}=") for a in args) or any((env.get(e) or "").strip() for e in envs)


def root_flags(args: list, cwd=None, env=None) -> list:
    """The launch roots of a Drishti JVM (a server or the command line, ``-jar`` a Drishti jar or a Drishti main class), as absolute paths, each only when not
    already given (docs/admin/CONFIGURATION.md, "Launch roots"): ``drishti.home``, the folder it runs in (``cwd``; this repository when
    none); ``drishti.data.dir``, ``<home>/data``; ``drishti.modules.home``, this repository's ``modules/``. The server never takes a root
    from its process's folder, so a launcher names them."""
    jar = "-jar" in args and any(Path(a).name.startswith("drishti") and a.endswith(".jar") for a in args)
    main = any(a.startswith("com.ash.drishti.") for a in args)        # a Drishti main class on a class path (DRISHTI_LAKE_CLASSPATH)
    if not jar and not main:
        return []
    home = (Path(cwd) if cwd else REPO).resolve()
    out = []
    if not _named("drishti.home", ("DRISHTI_HOME",), args, env):
        out.append(f"-Ddrishti.home={home}")
    if not _named("drishti.data.dir", ("DRISHTI_DATA_DIR",), args, env):
        out.append(f"-Ddrishti.data.dir={home / 'data'}")
    if not _named("drishti.modules.home", ("DRISHTI_MODULES_HOME", "DRISHTI_MODULE_MANIFESTS_DIR"), args, env) \
            and not _named("drishti.module-manifests-dir", (), args, env):
        out.append(f"-Ddrishti.modules.home={REPO / 'modules'}")
    return out


def java_cmd(java, args, tmp=None, cwd=None, env=None) -> list:
    """``[java, -Djava.io.tmpdir=<folder>, -Djdk.nio.maxCachedBufferSize=262144, <launch roots>, *args]``; ``cwd`` and ``env`` are the
    ones the JVM will run with (they decide the default folder and the launch roots: :func:`root_flags`). A flag already in the
    arguments wins. On Java 25 and later ``-XX:+UseCompactObjectHeaders`` too (2.9.7)."""
    args = [str(a) for a in args]
    cache = [] if any(a.startswith(CACHE_FLAG) for a in args) else [CACHE_LIMIT]
    compact = version_flags(java, args)
    roots = root_flags(args, cwd, env)
    if any(a.startswith(FLAG) for a in args):
        return [str(java), *compact, *cache, *roots, *args]
    return [str(java), tmp_flag(tmp, cwd, env), *compact, *cache, *roots, *args]


@contextlib.contextmanager
def cli_tmp(env=None):
    """The JVM temp folder of one ``sutra`` command: ``DRISHTI_TMP_DIR`` when set, else a fresh folder in the system temp folder,
    removed afterwards. Never ``<data dir>/tmp/server``: the command line writes nothing under the site's data folder."""
    env = os.environ if env is None else env
    given = (env.get("DRISHTI_TMP_DIR") or "").strip()
    if given:
        Path(given).mkdir(parents=True, exist_ok=True)
        yield Path(given)
        return
    folder = Path(tempfile.mkdtemp(prefix="drishti-sutra-"))
    try:
        yield folder
    finally:
        shutil.rmtree(folder, ignore_errors=True)
