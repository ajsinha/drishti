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

import contextlib
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
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


LOCK = Path("config") / "compute" / "locks" / "python.lock"
REQ_FILE = Path("drishti-console") / "requirements.txt"


def lock_requirements(home: Path) -> list[tuple[str, str]]:
    """(name, ==version) of every pin of config/compute/locks/python.lock (the compute pool's environment); its --hash lines are not requirements."""
    f = home / LOCK
    out = []
    if f.is_file():
        for raw in f.read_text(encoding="utf-8").splitlines():
            line = raw.strip().rstrip("\\").strip()
            if not line or line.startswith(("#", "-")):
                continue
            m = _REQ.match(line)
            if m:
                out.append((m.group(1), m.group(3).strip()))
    return out


def all_requirements(home: Path) -> list[tuple[str, str, str]]:
    """(name, spec, file) of drishti-console/requirements.txt, then python.lock. The two must agree: a lock pin that breaks the requirement of
    the same library (or a second, different pin) stops setup in words naming both files."""
    reqs = [(n, sp, REQ_FILE.as_posix()) for n, sp in requirements(home)]
    seen = {norm(n): (sp, f) for n, sp, f in reqs}
    for n, sp in lock_requirements(home):
        k = norm(n)
        if k in seen:
            osp, of = seen[k]
            v = sp.lstrip("=").strip()
            if osp and not satisfies(v, osp):
                raise LaunchError(f"{REQ_FILE.as_posix()} and {LOCK.as_posix()} disagree on {n}: {n}{osp} against {n}{sp}; make the two files name one version")
        seen.setdefault(k, (sp, LOCK.as_posix()))
        reqs.append((n, sp, LOCK.as_posix()))
    return reqs


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
    reqs = all_requirements(home)
    have = installed(py, [n for n, _, _ in reqs], run)
    pin = lake_pin(home).get("version", "")
    bad = []
    for name, spec, src in reqs:
        v = have.get(name)
        at = f"  [{src}]"
        if v is None:
            bad.append(f"{name}{spec} is not installed{at}")
        elif not satisfies(v, spec):
            bad.append(f"{name} {v} is installed, the requirement is {name}{spec}{at}")
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


def missing(py: str, home: Path, run=subprocess.run) -> list[tuple[str, str, str]]:
    """(name, spec, file) of every requirement ``py`` lacks or has at the wrong version (the Lake wheel's pin counts); empty: nothing to install."""
    reqs = all_requirements(home)
    have = installed(py, [n for n, _, _ in reqs], run)
    pin = lake_pin(home).get("version", "")
    out = []
    for name, spec, src in reqs:
        v = have.get(name)
        lake = norm(name) == "drishti-lake"
        if v is None or not satisfies(v, spec) or (lake and pin and v != pin):
            out.append((name, f"=={pin}" if lake and pin else spec, src))
    return list({norm(n): (n, sp, f) for n, sp, f in out}.values())     # one entry a library (the lock's exact pin wins)


def _q(t: str) -> str:
    return f'"{t}"' if re.search(r"[<>|&\s]", t) else t


def pip_line(py: str, items: list[str]) -> str:
    """One ready-to-copy ``<python> -m pip install name==ver ...`` line (a spec with < or > is quoted; Windows: ``py``)."""
    return f"{'py' if procs.windows() else _q(py)} -m pip install " + " ".join(_q(i) for i in items)


def explain_pip_failure(output: str) -> str:
    """One plain sentence for the commonest pip failures (the index refused the login, could not be reached, lacks a package); '' when unknown."""
    lines = output.splitlines()
    hot = [l for l in lines if re.search(r"\b(401|403)\b|Max retries|HTTPSConnectionPool|host=", l)] or lines
    m = re.search(r"https?://(?:[^/@\s]*@)?([^/\s:'\"]+)", "\n".join(hot))
    host = m.group(1) if m else "the configured package index"
    if re.search(r"\b(401|403)\b", output):
        return f"your package index ({host}) rejected the login: fix its credentials in pip.ini, or install offline with --wheels <folder>"
    if re.search(r"CERTIFICATE_VERIFY_FAILED|SSLError|certificate verify", output, re.I):
        return f"your package index ({host}) could not be reached: its certificate was not trusted; fix the certificate in pip.ini, or install offline with --wheels <folder>"
    if re.search(r"Max retries exceeded|Failed to establish|Name or service not known|getaddrinfo|ConnectionError|ProxyError|Connection (?:timed out|refused|reset)|Temporary failure|ReadTimeout|ConnectTimeout", output, re.I):
        return f"your package index ({host}) could not be reached: check the network, proxy and pip.ini, or install offline with --wheels <folder>"
    m = re.search(r"No matching distribution found for (\S+)|Could not find a version that satisfies the requirement (\S+)", output)
    if m:
        return f"your package index lacks {(m.group(1) or m.group(2)).strip()}: use an index that has it, or install offline with --wheels <folder>"
    return ""


@contextlib.contextmanager
def wheel_folder(spec: str | None):
    """The folder ``--wheels`` names (a folder, or the wheel-pack .zip itself, unzipped to a temporary folder that is removed afterwards); None stays None."""
    if not spec:
        yield None
        return
    path = Path(spec).expanduser()
    if path.is_dir():
        yield path
    elif path.is_file() and path.suffix.lower() == ".zip":
        tmp = tempfile.mkdtemp(prefix="drishti-wheels-")
        try:
            try:
                with zipfile.ZipFile(path) as z:
                    z.extractall(tmp)
            except zipfile.BadZipFile:
                raise LaunchError(f"--wheels {path} is not a readable .zip file") from None
            yield Path(tmp)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    else:
        raise LaunchError(f"--wheels {path}: no such folder or .zip file (unzip the wheel pack into a folder, e.g. ~/drishti-wheels, and name it)")


def wheel_names(folder: Path) -> dict[str, Path]:
    """normalised distribution name -> wheel, for every .whl under ``folder``."""
    return {norm(w.name.split("-")[0]): w for w in sorted(folder.rglob("*.whl"))}


def install(py: str, home: Path, windows: bool | None = None, run=subprocess.run, wheels: Path | None = None) -> None:
    """Installs only what ``py`` is missing: the Lake wheel first (the requirements name it and no index has it), then
    drishti-console/requirements.txt. Everything satisfied: says so and never calls pip. ``wheels`` (a folder): only from there
    (``--no-index --find-links``), the Lake wheel from it or from lib/wheels; a wheel the folder lacks is named and nothing is run."""
    win = procs.windows() if windows is None else windows
    need = missing(py, home, run)
    if not need:
        say(f"  every requirement is already installed in {py}: pip is not run")
        return
    others = [(n, sp) for n, sp, _ in need if norm(n) != "drishti-lake"]
    from_lock = [(n, sp) for n, sp, f in need if f == LOCK.as_posix() and norm(n) != "drishti-lake"]
    from_req = [x for x in others if x not in from_lock]
    wheel = None
    if len(others) != len(need):
        found = wheel_names(wheels) if wheels else {}
        wheel = found.get("drishti-lake") or lake_wheel(home)
        if wheel is None:
            raise LaunchError(f"the pinned Lake wheel is not in {wheels if wheels else home / 'lib' / 'wheels'}: build it as lib/lake-wheel.pin says (version {lake_pin(home).get('version', '?')}), then run setup again")
    if wheels:
        found = wheel_names(wheels)
        lacking = [n for n, _ in others if norm(n) not in found]
        if lacking:
            raise LaunchError(f"the wheel folder {wheels} has no wheel for {', '.join(lacking)}: add it (on a machine with a working index: pip download {' '.join(n + sp for n, sp in others)} -d <folder>) and run setup again")
    extra = [] if wheels else pip_extra(py, win, run)
    off = ["--no-index", "--find-links", str(wheels)] if wheels else []
    req = home / "drishti-console" / "requirements.txt"
    steps = ([["install", "-q", *extra, *off, str(wheel)]] if wheel else []) + ([["install", "-q", *extra, *off, "-r", str(req)]] if from_req else []) \
        + ([["install", "-q", *extra, *off, "-r", str(home / LOCK)]] if from_lock else [])
    for args in steps:
        say(f"  pip {' '.join(args[:2] + args[-1:])} ...")
        r = run([py, "-m", "pip", *args], capture_output=True, text=True)
        out = (r.stdout or "") + (r.stderr or "")
        if out.strip():
            say(out.rstrip())                      # pip's own output is always shown
        if r.returncode:
            why = explain_pip_failure(out) or f"pip failed in {py}: read its message above"
            by_hand = ([str(wheel)] if wheel else []) + [n + sp for n, sp in others]
            raise LaunchError(why + ("" if wheels else "\nthen install what is missing by hand:  " + pip_line(py, by_hand)))


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
