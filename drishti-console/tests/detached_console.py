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

"""The detached console (docs/architecture/THIN_CONSOLE_DESIGN.md 5, step S0; 5.2): a copy of ``drishti-console/`` alone, in a folder
that holds nothing of the server's (no ``config/``, ``modules/``, ``docs/contracts``, ``tools/``), started as a real console process in
front of a real server, with an audit hook (Python's ``sys.addaudithook``) in every process of it that records each file it opens or
folder it lists outside its own folder, its data folder and Python's own: the remaining readers, each named by the console file and line
that asked. That list is the checklist of the thin console's steps; a reader not on the known list fails the test.

What the hook sees: ``open``, ``os.listdir``, ``os.scandir`` (``glob``, ``rglob``, ``iterdir``). Not seen: ``stat`` alone
(``Path.exists``, ``is_file``); a reader that only asks whether a file exists is found by the pages it then draws wrongly.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

CONSOLE = Path(__file__).resolve().parents[1]
REPO = CONSOLE.parent

#: the audit hook, written as sitecustomize.py into a folder on PYTHONPATH: every process of the console (each worker) loads it
HOOK = r'''
import os, sys

_LOG = os.environ.get("DRISHTI_AUDIT_LOG")
if _LOG:
    _OWN = [os.path.realpath(p) for p in os.environ.get("DRISHTI_AUDIT_ALLOW", "").split(os.pathsep) if p]
    _WATCH = [os.path.realpath(p) for p in os.environ.get("DRISHTI_AUDIT_WATCH", "").split(os.pathsep) if p]
    _PY = [os.path.realpath(p) for p in {sys.prefix, sys.base_prefix, sys.exec_prefix, *[x for x in sys.path if x]}]
    _CODE = os.path.realpath(os.environ.get("DRISHTI_AUDIT_CODE", ""))
    _BUSY = [False]

    def _under(path, roots):
        return any(path == r or path.startswith(r + os.sep) for r in roots)

    def _hook(event, args):
        if _BUSY[0] or event not in ("open", "os.listdir", "os.scandir") or not args:
            return
        target = args[0]
        if not isinstance(target, (str, bytes, os.PathLike)):
            return
        _BUSY[0] = True
        try:
            p = os.path.realpath(os.fsdecode(target))
            if not _under(p, _WATCH) or _under(p, _OWN) or (_under(p, _PY) and not _under(p, [os.path.dirname(_CODE)])):
                return
            where, f = "?", sys._getframe(1)
            while f is not None:
                fn = os.path.realpath(f.f_code.co_filename)
                if fn.startswith(_CODE + os.sep):
                    where = os.path.relpath(fn, _CODE).replace(os.sep, "/") + ":" + str(f.f_lineno)
                    break
                f = f.f_back
            with open(_LOG, "a", encoding="utf-8") as out:
                out.write(event + "\t" + p + "\t" + where + "\n")
        except Exception:
            pass
        finally:
            _BUSY[0] = False

    sys.addaudithook(_hook)
'''


@dataclass
class Detached:
    url: str
    folder: Path            # the copy's parent: holds drishti-console/ and data/ only
    log: Path               # the audit log

    def readers(self) -> list[dict]:
        """Every read outside the copy's own folders: ``{event, path, where}``, each once."""
        if not self.log.is_file():
            return []
        seen, out = set(), []
        for line in self.log.read_text(encoding="utf-8").splitlines():
            event, path, where = (line.split("\t") + ["", "", ""])[:3]
            if (path, where) not in seen:
                seen.add((path, where))
                out.append({"event": event, "path": path, "where": where})
        return out


def copy_console(dst: Path) -> Path:
    """``drishti-console/`` copied alone into ``dst`` (its tests, caches and virtual environment left out: the copy runs on this
    interpreter, and a checkout's ``.venv`` is often a link to another's); the copy's folder."""
    target = dst / "drishti-console"
    shutil.copytree(CONSOLE, target, ignore=shutil.ignore_patterns("tests", "__pycache__", "*.pyc", "data", "run-configs", ".venv", "venv"))
    return target


def free_port() -> int:
    import socket
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start(tmp: Path, server: str, extra: dict | None = None, seconds: float = 120):
    """A detached console in front of ``server``: yields a :class:`Detached`, stops the process after. ``extra``: more console arguments
    (``--auth.enabled=true``)."""
    copy = copy_console(tmp)
    data = tmp / "data"
    (data / "tmp").mkdir(parents=True)
    hook = tmp / "audit"
    hook.mkdir()
    (hook / "sitecustomize.py").write_text(HOOK, encoding="utf-8")
    log = tmp / "audit.log"
    port = free_port()
    env = {"PATH": os.environ.get("PATH", ""), "HOME": str(tmp), "PYTHONPATH": str(hook), "PYTHONDONTWRITEBYTECODE": "1",
           "DRISHTI_DATA_DIR": str(data), "DRISHTI_CONSOLE_TMP_DIR": str(data / "tmp"),
           "DRISHTI_AUDIT_LOG": str(log), "DRISHTI_AUDIT_CODE": str(copy), "DRISHTI_AUDIT_ALLOW": os.pathsep.join([str(copy), str(data), str(hook)]),
           "DRISHTI_AUDIT_WATCH": os.pathsep.join([str(tmp), str(REPO)])}
    args = [sys.executable, str(copy / "run_drishti_web.py"), f"--server.port={port}", "--server.host=127.0.0.1", f"--backend.url={server}",
            *[f"--{k}={v}" for k, v in (extra or {}).items()]]
    out = (tmp / "console.log").open("w")
    proc = subprocess.Popen(args, cwd=copy, env=env, stdout=out, stderr=subprocess.STDOUT)
    url = f"http://127.0.0.1:{port}"
    try:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError("the detached console exited: " + (tmp / "console.log").read_text()[-2000:])
            try:
                with urllib.request.urlopen(url + "/healthz/backend", timeout=2) as r:
                    if json.load(r).get("reachable", True):
                        break
            except (OSError, ValueError, urllib.error.URLError):
                pass
            time.sleep(0.5)   # wait-ok: polling a console that is still starting
        else:
            raise RuntimeError("the detached console did not answer: " + (tmp / "console.log").read_text()[-2000:])
        yield Detached(url, tmp, log)
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(10)
        out.close()
