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

"""The Python half of ``drishti.py setup``: the libraries of drishti-console/requirements.txt (the pinned Lake wheel first) installed into one
interpreter (the console's .venv, or the current one with ``setup libs`` / ``--system``), verified name by name and version by version, and
the one ``drishti.python`` line of the settings file that tells the server which interpreter that is (written once, never over a value).

No network is needed to *check*; pip is the only thing that touches one, and on Windows it is told to use the Windows certificate store
(pip's truststore), so a company proxy that re-signs TLS works as it does for Maven."""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import procs
from procs import LaunchError, say

#: pip with truststore on by default (the Windows certificate store); earlier ones need --use-feature=truststore, and before 23.1 cannot
TRUSTSTORE_DEFAULT = (24, 2)
TRUSTSTORE_MIN = (23, 1)
SETTING = "drishti.python"
_REQ = re.compile(r"^([A-Za-z0-9][A-Za-z0-9_.\-]*)\s*(\[[^\]]*\])?\s*([^;#]*)")


def norm(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def requirements(home: Path) -> list[tuple[str, str]]:
    """(name, version spec) of every requirement of drishti-console/requirements.txt; its options (--find-links ...) are not requirements."""
    out = []
    f = home / "drishti-console" / "requirements.txt"
    for raw in f.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "-")):
            continue
        m = _REQ.match(line)
        if m:
            out.append((m.group(1), m.group(3).strip()))
    return out


def lake_pin(home: Path) -> dict[str, str]:
    """The key=value lines of lib/lake-wheel.pin (ref, version, sha256)."""
    out = {}
    f = home / "lib" / "lake-wheel.pin"
    if f.is_file():
        for ln in f.read_text(encoding="utf-8").splitlines():
            if "=" in ln and not ln.lstrip().startswith("#"):
                k, v = ln.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def lake_wheel(home: Path) -> Path | None:
    """The pinned Lake wheel in lib/wheels, or None."""
    v = lake_pin(home).get("version", "")
    found = sorted((home / "lib" / "wheels").glob(f"drishti_lake-{v}-*.whl")) if v else []
    return found[0] if found else None


def _ver(text: str) -> tuple[int, ...]:
    parts = []
    for seg in text.split("."):
        m = re.match(r"\d+", seg)
        if not m:
            break
        parts.append(int(m.group()))
    return tuple(parts)


def _pad(a: tuple[int, ...], b: tuple[int, ...]):
    n = max(len(a), len(b))
    return a + (0,) * (n - len(a)), b + (0,) * (n - len(b))


def satisfies(version: str, spec: str) -> bool:
    """Whether ``version`` meets a spec such as ``>=0.141``, ``==0.9.1``, ``~=1.4`` or ``>=1,<3`` (an empty spec: any)."""
    v = _ver(version)
    for part in filter(None, (p.strip() for p in spec.split(","))):
        m = re.match(r"(==|!=|>=|<=|~=|>|<)\s*(\S+)", part)
        if not m:
            continue
        op, want = m.groups()
        if op in ("==", "!=") and want.endswith(".*"):
            w = _ver(want[:-2])
            ok = v[:len(w)] == w
            if ok == (op == "!="):
                return False
            continue
        a, b = _pad(v, _ver(want))
        if op == "~=":
            w = _ver(want)
            a, b = _pad(v, w)
            ok = a >= b and v[:max(len(w) - 1, 1)] == w[:max(len(w) - 1, 1)]
        else:
            ok = {"==": a == b, "!=": a != b, ">=": a >= b, "<=": a <= b, ">": a > b, "<": a < b}[op]
        if not ok:
            return False
    return True


_PROBE = "import importlib.metadata as m, json, sys\nout = {}\nfor n in sys.argv[1:]:\n    try:\n        out[n] = m.version(n)\n    except Exception:\n        out[n] = None\nprint(json.dumps(out))"


def installed(py: str, names: list[str], run=subprocess.run) -> dict[str, str | None]:
    """The version each distribution has in the interpreter ``py`` (None: not installed)."""
    r = run([py, "-c", _PROBE, *names], capture_output=True, text=True, cwd=tempfile.gettempdir())
    if r.returncode:
        raise LaunchError(f"{py} could not be asked what it has installed: {(r.stderr or r.stdout).strip()[-300:]}")
    return json.loads(r.stdout.strip().splitlines()[-1])


def verify(py: str, home: Path, run=subprocess.run) -> list[str]:
    """What is missing or wrong in the interpreter ``py`` against drishti-console/requirements.txt and the Lake pin; empty: all is in order."""
    reqs = requirements(home)
    have = installed(py, [n for n, _ in reqs], run)
    pin = lake_pin(home).get("version", "")
    bad = []
    for name, spec in reqs:
        v = have.get(name)
        if v is None:
            bad.append(f"{name}{spec} is not installed")
        elif not satisfies(v, spec):
            bad.append(f"{name} {v} is installed, the requirement is {name}{spec}")
        if norm(name) == "drishti-lake" and pin and v is not None and v != pin:
            bad.append(f"{name} {v} is installed, lib/lake-wheel.pin pins {pin}")
    return bad


def pip_version(py: str, run=subprocess.run) -> tuple[int, ...]:
    r = run([py, "-m", "pip", "--version"], capture_output=True, text=True)
    m = re.match(r"pip\s+(\d+(?:\.\d+)*)", r.stdout or "")
    return _ver(m.group(1)) if m else ()


def pip_extra(py: str, windows: bool | None = None, run=subprocess.run) -> list[str]:
    """pip's options for the machine: on Windows the certificate store (truststore) is on, by the flag for a pip before 24.2 (default after)."""
    if not (procs.windows() if windows is None else windows):
        return []
    v = pip_version(py, run)
    if v and v < TRUSTSTORE_MIN:
        say(f"  note: pip {'.'.join(map(str, v))} cannot use the Windows certificate store; upgrade it (python -m pip install --upgrade pip) behind a company proxy")
        return []
    return ["--use-feature=truststore"] if v and v < TRUSTSTORE_DEFAULT else []


def install(py: str, home: Path, windows: bool | None = None, run=subprocess.run) -> None:
    """The Lake wheel first (the requirements name it and no index has it), then drishti-console/requirements.txt, into the interpreter ``py``."""
    win = procs.windows() if windows is None else windows
    wheel = lake_wheel(home)
    if wheel is None:
        raise LaunchError(f"the pinned Lake wheel is not in {home / 'lib' / 'wheels'}: build it as lib/lake-wheel.pin says (version {lake_pin(home).get('version', '?')}), then run setup again")
    extra = pip_extra(py, win, run)
    req = home / "drishti-console" / "requirements.txt"
    for args in (["install", "-q", *extra, str(wheel)], ["install", "-q", *extra, "-r", str(req)]):
        say(f"  pip {' '.join(args[:2] + args[-1:])} ...")
        r = run([py, "-m", "pip", *args])
        if r.returncode:
            raise LaunchError(f"pip {' '.join(args[:3])} ... failed in {py}: read pip's message above (behind a proxy: set pip's index and certificate, or use Python's Windows store)")


def ensure_setting(ini: Path, py: str, check: bool = False) -> tuple[str, str]:
    """``drishti.python=<py>`` into the settings file when absent; (state, detail) with state ok, made or todo. An existing value is never changed."""
    text = ini.read_text(encoding="utf-8") if ini.is_file() else ""
    m = re.search(rf"^[ \t]*{re.escape(SETTING)}[ \t]*=[ \t]*(\S.*)$", text, flags=re.M)
    if m:
        v = m.group(1).strip()
        return "ok", f"{v}  (kept as it is; setup used {py})" if v != Path(py).as_posix() else v
    if check or not ini.is_file():
        return "todo", f"{SETTING} is not in {ini}: setup writes {Path(py).as_posix()}"
    line = f"{SETTING}={Path(py).as_posix()}"
    for pat in (rf"^#\s*{re.escape(SETTING)}\s*=.*$", rf"^[ \t]*{re.escape(SETTING)}[ \t]*=[ \t]*$"):
        if re.search(pat, text, flags=re.M):
            ini.write_text(re.sub(pat, lambda _: line, text, count=1, flags=re.M), encoding="utf-8")
            break
    else:
        sec = re.search(r"(?m)^\[", text)
        at = sec.start() if sec else len(text)
        pre = text[:at] + ("" if not text[:at] or text[:at].endswith("\n") else "\n")
        ini.write_text(pre + line + "\n" + text[at:], encoding="utf-8")
    return "made", f"{line}  (the server's Python where its own setting is blank; DRISHTI_PYTHON still overrides)"
