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
``TempFiles.configuredBase`` computes it: ``DRISHTI_TMP_DIR``, else ``<DRISHTI_DATA_DIR or <cwd>/data>/tmp/server``. The server removes
stale native copies from that base at start. The folder is made if absent (Java warns and the libraries fail on a missing one).
A ``-Djava.io.tmpdir=`` already in the arguments wins. Standard library only: the console tests load it through ``java_launch.py``.

tests/test_java_launch_guard.py (drishti-console) fails when a file under drishti-console/tests or tools starts java without it."""
from __future__ import annotations

import os
from pathlib import Path

FLAG = "-Djava.io.tmpdir="


def server_tmp(cwd=None, env=None) -> Path:
    """The server's temp base for a server started in ``cwd`` with ``env``: DRISHTI_TMP_DIR, else <data dir>/tmp/server."""
    env = os.environ if env is None else env
    base = Path(cwd) if cwd else Path.cwd()
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
# a server or loader with many threads then exhausts -XX:MaxDirectMemorySize (OPERATIONS.md, Direct memory). 256 KB.
CACHE_FLAG = "-Djdk.nio.maxCachedBufferSize="
CACHE_LIMIT = CACHE_FLAG + "262144"


def java_cmd(java, args, tmp=None, cwd=None, env=None) -> list:
    """``[java, -Djava.io.tmpdir=<folder>, -Djdk.nio.maxCachedBufferSize=262144, *args]``; ``cwd`` and ``env`` are the ones the JVM
    will run with (they decide the default folder). A flag already in the arguments wins."""
    args = [str(a) for a in args]
    cache = [] if any(a.startswith(CACHE_FLAG) for a in args) else [CACHE_LIMIT]
    if any(a.startswith(FLAG) for a in args):
        return [str(java), *cache, *args]
    return [str(java), tmp_flag(tmp, cwd, env), *cache, *args]
