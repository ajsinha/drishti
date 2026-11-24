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

"""The three oracles of the first-run matrix (docs/plans/FIRST_RUN_ROBUSTNESS.md, section B1), shared by the tools/test_matrix_*.py files.

* **Write set**: :func:`snapshot` lists every file (path, size, hash) of the folders given; :func:`changed` is the difference.
* **Output**: :func:`output_problems`: no traceback; every ``[FAIL]`` line carries ``Fix:``; setup's last line one of the two verdicts.
* **Network**: :func:`oracle_env` gives a process tree a scratch home, the proxy variables and ``PIP_INDEX_URL`` pointing at a dead port,
  a ``sitecustomize`` that records and refuses every TCP connect that is not to this machine, and a **poisoned PATH**: shims named
  ``python``, ``python3``, ``pip``, ``pip3``, ``git``, ``mvn``, ``curl``, ``wget`` that log their caller and fail. :func:`calls` and
  :func:`net` read the two logs; both must be empty.

Scratch folders only; nothing here starts a server or a console."""
from __future__ import annotations

import hashlib
import os
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
SHIMS = ("python", "python3", "pip", "pip3", "git", "mvn", "curl", "wget")
DEAD = "http://127.0.0.1:9"
#: the owner's stack: never used by a test (FIRST_RUN_ROBUSTNESS.md, lane rules)
OWNER_PORTS = (18480, 17480, 18791, 18792)

_SITE = '''# the network oracle (tools/matrix_oracles.py): every TCP connect that is not to this machine is recorded and refused
import socket as _s
_LOG = {log!r}
_real = _s.socket.connect
_local = None


def _mine():
    global _local
    if _local is None:
        _local = {{"127.0.0.1", "::1", "0.0.0.0", "localhost"}}
        try:
            _local.update(i[4][0] for i in _s.getaddrinfo(_s.gethostname(), None))
        except OSError:
            pass
        try:
            u = _s.socket(_s.AF_INET, _s.SOCK_DGRAM)
            _real(u, ("10.255.255.255", 1))
            _local.add(u.getsockname()[0])
            u.close()
        except OSError:
            pass
    return _local


def _connect(self, address):
    if self.family in (_s.AF_INET, _s.AF_INET6) and self.type == _s.SOCK_STREAM:
        host = str(address[0])
        if not (host.startswith("127.") or host in _mine()):
            import os, sys, traceback
            with open(_LOG, "a", encoding="utf-8") as f:
                f.write(f"{{os.getpid()}} {{' '.join(sys.argv)[:200]}} -> {{address}}\\n")
            raise ConnectionRefusedError(111, "refused by the first-run network oracle")
    return _real(self, address)


_s.socket.connect = _connect
'''

_SHIM = """#!/bin/sh
echo "$(basename "$0") $* (caller $PPID)" >> {log}
exit 97
"""


def snapshot(*folders: Path, skip: tuple[str, ...] = ()) -> dict:
    """path -> (size, sha256) of every file under ``folders`` (the write-set oracle); a path holding any of ``skip`` is left out."""
    out = {}
    for folder in folders:
        if not folder.exists():
            continue
        for f in sorted(folder.rglob("*")):
            s = str(f)
            if f.is_file() and not f.is_symlink() and not any(k in s for k in skip):
                b = f.read_bytes()
                out[s] = (len(b), hashlib.sha256(b).hexdigest())
    return out


def changed(before: dict, after: dict) -> list[str]:
    """Every path made, changed or removed between two snapshots."""
    return sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))


def oracle_env(tmp: Path, home: Path | None = None, base: dict | None = None, keep_drishti: bool = False, **extra) -> dict:
    """An environment for a process tree under the three oracles (see the module's doc); ``home`` (default ``<tmp>/John Smith``) is HOME
    and USERPROFILE; DRISHTI_* of the test's own environment are dropped unless ``keep_drishti``; ``extra`` is added last."""
    base = dict(os.environ if base is None else base)
    env = {k: v for k, v in base.items() if keep_drishti or not k.startswith("DRISHTI_")}
    env.pop("PYTHONPATH", None)
    home = home or tmp / "John Smith"
    home.mkdir(parents=True, exist_ok=True)
    oracle = tmp / "oracle"
    shims = oracle / "shims"
    shims.mkdir(parents=True, exist_ok=True)
    site = oracle / "site"
    site.mkdir(exist_ok=True)
    (site / "sitecustomize.py").write_text(_SITE.format(log=str(oracle / "net.log")), encoding="utf-8")
    for name in SHIMS:
        f = shims / name
        f.write_text(_SHIM.format(log=_sh(str(oracle / "calls.log"))), encoding="utf-8")
        f.chmod(0o755)
    env.update(HOME=str(home), USERPROFILE=str(home), PATH=str(shims) + os.pathsep + env.get("PATH", ""), PYTHONPATH=str(site),
               PIP_INDEX_URL=DEAD + "/simple", PIP_EXTRA_INDEX_URL=DEAD + "/extra", PIP_REQUIRE_VIRTUALENV="true",
               HTTP_PROXY=DEAD, HTTPS_PROXY=DEAD, http_proxy=DEAD, https_proxy=DEAD, ALL_PROXY=DEAD, NO_PROXY="", no_proxy="",
               PYTHONDONTWRITEBYTECODE="1")
    env.update({k: str(v) for k, v in extra.items()})
    return env


def _sh(word: str) -> str:
    return "'" + word.replace("'", "'\\''") + "'"


def calls(tmp: Path) -> list[str]:
    """What the poisoned PATH caught: one line per call of a shim (must be empty)."""
    f = tmp / "oracle" / "calls.log"
    return f.read_text(encoding="utf-8").splitlines() if f.is_file() else []


def net(tmp: Path) -> list[str]:
    """What the network oracle refused: one line per TCP connect to another machine (must be empty)."""
    f = tmp / "oracle" / "net.log"
    return f.read_text(encoding="utf-8").splitlines() if f.is_file() else []


def output_problems(text: str, setup: bool = False) -> list[str]:
    """The output oracle: a traceback, a [FAIL] line with no ``Fix:``, or (``setup``) a last line that is not one of the two verdicts."""
    out = []
    if "Traceback (most recent call last)" in text:
        out.append("a traceback reached the screen")
    for line in text.splitlines():                 # a failure a person reads (setup's indented step table is its own status, S3)
        if line.startswith("[FAIL]") and "Fix:" not in line:
            out.append(f"a [FAIL] line without Fix: {line.strip()[:160]}")
    if setup:
        last = text.rstrip().splitlines()[-1] if text.strip() else ""
        if not (last == "Drishti is ready." or last.startswith("Drishti is NOT ready:")):
            out.append(f"setup's last line is not a verdict: {last[:120]}")
    return out


def system_python_state(python: str = "/usr/bin/python3") -> dict:
    """What the operating system's Python holds: ``pip list --format=freeze`` (when it has pip), and a hash of the listing (name, size,
    mtime) of its purelib and platlib and of the real user's ~/.local/lib/python3*/site-packages. Equal before and after = untouched."""
    import pwd
    out = {}
    if not Path(python).exists():
        return out
    clean = {k: v for k, v in os.environ.items() if not k.startswith(("PIP_", "PYTHON"))}
    r = subprocess.run([python, "-m", "pip", "list", "--format=freeze", "--disable-pip-version-check"], capture_output=True, text=True, env=clean)
    out["pip freeze"] = r.stdout if r.returncode == 0 else f"(no pip: {r.returncode})"
    r = subprocess.run([python, "-c", "import site, sysconfig, json; p = sysconfig.get_paths(); "
                        "print(json.dumps(sorted({p['purelib'], p['platlib'], *site.getsitepackages()})))"], capture_output=True, text=True, env=clean)
    import json
    folders = [Path(p) for p in json.loads(r.stdout)] if r.returncode == 0 else []
    folders += sorted((Path(pwd.getpwuid(os.getuid()).pw_dir) / ".local" / "lib").glob("python3*/site-packages"))
    for folder in folders:
        h = hashlib.sha256()
        if folder.is_dir():
            for f in sorted(folder.rglob("*")):
                try:
                    st = f.lstat()
                except OSError:
                    continue
                h.update(f"{f}|{st.st_size}|{st.st_mtime_ns}\n".encode())
        out[str(folder)] = h.hexdigest()
    return out


def drishti(*argv, env: dict, cwd: Path | None = None, python: str | None = None, home: Path | None = None, timeout: float = 600):
    """``tools/drishti.py`` of ``home`` (default this checkout) run with ``argv`` as a person runs it; the CompletedProcess."""
    tool = (home or ROOT) / "tools" / "drishti.py"
    return subprocess.run([python or sys.executable, str(tool), *map(str, argv)], capture_output=True, text=True, cwd=str(cwd or home or ROOT),
                          env=env, timeout=timeout, encoding="utf-8", errors="replace")
