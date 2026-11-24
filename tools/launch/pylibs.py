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

"""The Python half of ``drishti.py setup``: the libraries of drishti-console/requirements.txt (the pinned Lake wheel among them) CHECKED in
one interpreter, the one you run Drishti with, name by name and version by version, and the one ``drishti.python`` line of the settings
file that tells the server and ``start console`` which interpreter that is (written when absent; a value naming a missing file is
replaced, any other is kept).

Drishti never installs a Python library and never makes a virtual environment (owner rule, 2026-11-23: an automatic install can corrupt an
environment). When something is missing, setup prints the one command the owner may run himself (:func:`install_commands`): offline from
the wheel pack when one is found (:func:`find_pack`), else, as the second option, from the package index. Nothing here runs pip."""
from __future__ import annotations

import contextlib
import hashlib
import json
import os
import platform
import re
import shutil
import sys
import subprocess
import tempfile
from pathlib import Path

import procs
from procs import LaunchError, say

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


def verify(py: str, home: Path, run=subprocess.run, lock: bool = True) -> list[str]:
    """What is missing or wrong in the interpreter ``py`` against drishti-console/requirements.txt (and python.lock, the server's compute
    pool's, unless ``lock`` is False: the console needs only the requirements) and the Lake pin; empty: all is in order."""
    reqs = all_requirements(home) if lock else [r for r in all_requirements(home) if r[2] != LOCK.as_posix()]
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


# ---- the wheel pack: where it is found without help (FIRST_RUN_ROBUSTNESS.md S4, rows 21 to 29) ------------------------------------------

WHEELS_ENV = "DRISHTI_WHEELS"
WHEELS_DIR = "drishti-wheels"
#: the name make_wheels.py gives a pack: drishti-platform-<release>-wheels-<system>-<arch>-py<XY>.zip
PACK_ZIP = re.compile(r"^drishti-platform-(?P<release>.+?)-wheels-(?P<system>windows|linux|macos)-(?P<arch>[A-Za-z0-9_]+)-py(?P<py>\d{2,3})\.zip$", re.I)


def user_home(env: dict | None = None, windows: bool | None = None) -> Path:
    """The person's home folder: %USERPROFILE% on Windows, $HOME elsewhere (read from ``env``, so a scratch home is honoured)."""
    env = os.environ if env is None else env
    win = procs.windows() if windows is None else windows
    given = (env.get("USERPROFILE") if win else env.get("HOME")) or env.get("HOME") or env.get("USERPROFILE")
    return Path(given) if given else Path.home()


def wheels_home(env: dict | None = None) -> Path:
    """~/drishti-wheels (Windows %USERPROFILE%\\drishti-wheels): where START_HERE puts the pack, and where the person unzips a pack zip."""
    return user_home(env) / WHEELS_DIR


def machine(windows: bool | None = None, platform_machine: str | None = None, version=None) -> tuple[str, str, str]:
    """(system, arch, python) of this machine as a pack's name spells them, e.g. ``("linux", "x64", "313")``."""
    win = procs.windows() if windows is None else windows
    system = "windows" if win else "macos" if sys.platform == "darwin" else "linux"
    m = (platform_machine if platform_machine is not None else platform.machine()).lower()
    arch = "x64" if m in ("amd64", "x86_64", "x64") else "arm64" if m in ("arm64", "aarch64") else m
    v = version or sys.version_info
    return system, arch, f"{v[0]}{v[1]}"


def pack_zip_name(release: str, system: str, arch: str, py: str) -> str:
    return f"drishti-platform-{release}-wheels-{system}-{arch}-py{py}.zip"


def checkout_release(home: Path | None = None) -> str:
    """This checkout's release as a pack names it (``i7g`` of ``2.9.11.i7g``); "" when it cannot be read (then it is not checked)."""
    try:
        import javacmd
        v = javacmd._version_of(home) if home is not None else javacmd._version_of()
    except Exception:                                   # noqa: BLE001 - no pom or no javacmd: the release is not checked
        return ""
    return (v or "").rsplit(".", 1)[-1]


def _py_words(py: str) -> str:
    return f"Python {py[0]}.{py[1:]}"


def zip_problem(z: Path, me: tuple[str, str, str], release: str = "") -> str:
    """Why the pack zip ``z`` does not fit this machine, naming both sides; "" when it fits (or is not named like a pack)."""
    m = PACK_ZIP.match(z.name)
    if not m:
        return ""
    system, arch, py = me
    if (m["system"].lower(), m["arch"].lower(), m["py"]) != (system, arch, py):
        return (f"the wheel pack {z.name} is for {m['system'].lower()}-{m['arch'].lower()}, {_py_words(m['py'])}; "
                f"this machine is {system}-{arch}, {_py_words(py)}")
    if release and m["release"].lower() != release.lower():
        return f"the wheel pack {z.name} is of release {m['release']}; this checkout is {release}"
    return ""


def holds_wheels(folder: Path, depth: int = 2) -> bool:
    """``folder`` holds .whl files, directly or up to ``depth - 1`` folders deep (Explorer's "Extract All" adds one)."""
    if not folder.is_dir():
        return False
    if any(folder.glob("*.whl")):
        return True
    return depth > 1 and any(holds_wheels(d, depth - 1) for d in sorted(folder.iterdir()) if d.is_dir())


def pack_zips_in(folder: Path, depth: int = 0) -> list[Path]:
    """The pack-named zips in ``folder`` (and ``depth`` folders below it)."""
    if not folder.is_dir():
        return []
    out = [z for z in sorted(folder.glob("*.zip")) if PACK_ZIP.match(z.name)]
    if depth > 0:
        for d in sorted(folder.iterdir()):
            if d.is_dir():
                out += pack_zips_in(d, depth - 1)
    return out


class Pack:
    """What discovery found: ``path`` (a folder or a pack zip; None: none), ``how`` it was found, the places ``looked`` in, a ``problem``
    (one sentence: a pack is there but for another system, arch, Python or release: pip must not run) and the zip name ``wanted``."""

    def __init__(self, path: Path | None, how: str, looked: list[Path], problem: str = "", wanted: str = ""):
        self.path, self.how, self.looked, self.problem, self.wanted = path, how, looked, problem, wanted

    def fail(self, fix: str) -> str:
        """The one [FAIL] line for a pack that does not fit, or for none found."""
        if self.problem:
            return procs.fail("wheel pack", self.problem, f"fetch {self.wanted} and run {fix}")
        return procs.fail("libraries", "no wheel pack found (looked in " + ", ".join(map(str, self.looked)) + ")",
                          f"put {self.wanted} in {self.looked[0]} or your Downloads folder, then {fix}")

    def __repr__(self) -> str:
        return f"Pack({self.path!r}, {self.how!r}, problem={self.problem!r})"


def find_pack(wheels: str | None = None, env: dict | None = None, places: list[Path] | None = None,
              me: tuple[str, str, str] | None = None, release: str | None = None) -> Pack:
    """The offline wheel pack, found without help: ``--wheels`` (a folder or a zip), else DRISHTI_WHEELS, else ~/drishti-wheels (unzipped
    flat, one folder deep, or the pack zip in it or one folder below), else the pack zip for this system, arch and Python in ``places``
    (default: the platform folder that holds this checkout, then ~/Downloads). Of several fitting zips the one of this release is taken;
    a zip for another system, arch, Python or release is never taken, and when only such zips are there ``problem`` names both sides."""
    env = os.environ if env is None else env
    me = me or machine()
    release = checkout_release() if release is None else release
    wanted = pack_zip_name(release or "<release>", *me)
    named, how = (wheels or "").strip() or None, "--wheels"
    if not named and (env.get(WHEELS_ENV) or "").strip():
        named, how = env[WHEELS_ENV].strip(), WHEELS_ENV
    if named:
        p = Path(named).expanduser()
        if not p.exists():
            return Pack(None, how, [p], f"{how} names {p}, which is not there", wanted)
        problem = zip_problem(p, me, release) if p.is_file() else ""
        return Pack(None if problem else p, how, [p], problem, wanted)
    home = wheels_home(env)
    places = [procs.ROOT.parent, user_home(env) / "Downloads"] if places is None else places
    looked = [home, *places]
    if holds_wheels(home):
        return Pack(home, WHEELS_DIR, looked, "", wanted)
    zips = pack_zips_in(home, 1) + [z for place in places for z in pack_zips_in(place, 0)]
    fits = [z for z in zips if not zip_problem(z, me, release)]
    if fits:
        return Pack(fits[0], "found", looked, "", wanted)
    if zips:
        right_machine = [z for z in zips if not zip_problem(z, me, "")]
        return Pack(None, "found", looked, zip_problem((right_machine or zips)[0], me, release), wanted)
    return Pack(None, "", looked, "", wanted)


def sums_problem(folder: Path) -> str:
    """An unzipped pack checked against its SHA256SUMS: one sentence naming the first file missing or different; "" when all agree (or
    the pack has no SHA256SUMS)."""
    sums = folder / "SHA256SUMS"
    if not sums.is_file():
        return ""
    for line in sums.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        digest, name = parts[0].lower(), parts[-1].lstrip("*")
        f = folder / name
        if not f.is_file():
            return f"the wheel pack {folder} lacks {name} (named in its SHA256SUMS)"
        if hashlib.sha256(f.read_bytes()).hexdigest() != digest:
            return f"the wheel pack {folder} has a damaged {name} (its SHA-256 differs from SHA256SUMS)"
    return ""


def still_zipped(z: Path, env: dict | None = None) -> str:
    """The one [FAIL] line for a pack that was found only as a zip: setup never unzips it (it writes nothing outside the checkout), the
    person does, into ~/drishti-wheels (%USERPROFILE%\\drishti-wheels on Windows)."""
    return procs.fail("wheel pack", f"{z} is still zipped", f"unzip it into {wheels_home(env)}, then run setup again")


@contextlib.contextmanager
def wheel_folder(spec: str | None, env: dict | None = None):
    """The folder ``--wheels`` names, as it is; a pack that carries SHA256SUMS is checked against it first (a missing or damaged wheel:
    one [FAIL], pip never runs). A pack .zip is never unzipped: one [FAIL] (:func:`still_zipped`). None stays None. Writes nothing."""
    if not spec:
        yield None
        return
    path = Path(spec).expanduser()
    if path.is_dir():
        folder = path
    elif path.is_file() and path.suffix.lower() == ".zip":
        problem = zip_problem(path, machine())
        if problem:
            raise LaunchError(procs.fail("wheel pack", problem, f"fetch {pack_zip_name(PACK_ZIP.match(path.name)['release'], *machine())}"))
        raise LaunchError(still_zipped(path, env))
    else:
        raise LaunchError(f"--wheels {path}: no such folder or .zip file (unzip the wheel pack into a folder, e.g. ~/drishti-wheels, and name it)")
    for d in wheel_dirs(folder):
        said = sums_problem(d)
        if said:
            raise LaunchError(procs.fail("wheel pack", said, "fetch the pack again"))
    yield folder


def wheel_names(folder: Path) -> dict[str, Path]:
    """normalised distribution name -> wheel, for every .whl under ``folder``."""
    return {norm(w.name.split("-")[0]): w for w in sorted(folder.rglob("*.whl"))}


def wheel_dirs(folder: Path) -> list[Path]:
    """The folder(s) pip must be given: ``--find-links`` does not look in sub-folders, so a pack unzipped one folder deep (Explorer's
    "Extract All" makes a folder named after the zip) needs the folder that holds the .whl files. ``folder`` itself when it holds any."""
    if any(folder.glob("*.whl")):
        return [folder]
    return sorted({w.parent for w in folder.rglob("*.whl")}) or [folder]


_WHEEL_PY = re.compile(r"^cp(\d)(\d+)$")


def pack_pythons(folder: Path) -> set[tuple[int, int]]:
    """The (major, minor) of every binary wheel (cpNN tag, not abi3, not pure Python) of the pack."""
    out = set()
    for w in folder.rglob("*.whl"):
        parts = w.name[:-4].split("-")
        if len(parts) >= 5:
            m = _WHEEL_PY.match(parts[-3])
            if m and parts[-2] not in ("abi3", "none"):
                out.add((int(m.group(1)), int(m.group(2))))
    return out


def python_version(py: str, run=subprocess.run) -> tuple[int, int] | None:
    """(major, minor) of the interpreter ``py``; None when it cannot be asked."""
    r = run([py, "-c", "import sys; print('PYVER %d.%d' % sys.version_info[:2])"], capture_output=True, text=True, cwd=tempfile.gettempdir())
    m = re.search(r"PYVER (\d+)\.(\d+)", r.stdout or "")
    return (int(m.group(1)), int(m.group(2))) if m and not r.returncode else None


def pack_mismatch(py: str, folder: Path, run=subprocess.run, windows: bool | None = None) -> str:
    """One sentence when the pack's cpNN wheels are for another Python than ``py`` (naming both versions); "" when they fit."""
    packs = pack_pythons(folder)
    have = python_version(py, run) if packs else None
    if not packs or have is None or have in packs:
        return ""
    want = "/".join(f"{a}.{b}" for a, b in sorted(packs))
    first = f"{sorted(packs)[0][0]}.{sorted(packs)[0][1]}"
    how = (f"`py -{first} tools\\drishti.py setup`" if (procs.windows() if windows is None else windows)
           else f"a Python {first} you prepared for Drishti (a dedicated /opt/python{first}, or your own virtual environment is fine)")
    return f"the wheel pack is for Python {want}; this Python is {have[0]}.{have[1]}: run setup with {how}, or use the pack for Python {have[0]}.{have[1]}"


_MANAGED = ("import os, sys, sysconfig; print('MANAGED', int(sys.prefix == sys.base_prefix and "
            "os.path.isfile(os.path.join(sysconfig.get_path('stdlib'), 'EXTERNALLY-MANAGED'))))")
#: what setup says for an operating system's own Python (PEP 668): never --break-system-packages; Drishti makes no environment itself
MANAGED = ("this Python is managed by the operating system: run setup with a Python you prepared for Drishti (a dedicated Python 3.13, "
           "e.g. /opt/python3.13, see OPERATIONS_GUIDE.md; your own virtual environment is fine)")


def externally_managed(py: str, run=subprocess.run) -> bool:
    """``py`` is an operating system's Python that refuses pip (PEP 668: EXTERNALLY-MANAGED in its standard library, its own prefix)."""
    r = run([py, "-c", _MANAGED], capture_output=True, text=True, cwd=tempfile.gettempdir())
    return "MANAGED 1" in (r.stdout or "") and not r.returncode


# ---- Drishti never installs a Python library (owner rule, 2026-11-23): it checks, and prints the one command the owner may run -----------

def _word(text: str, win: bool) -> str:
    """One word of a printed command, quoted when it holds a space or a shell character."""
    return procs._q(text, win)


def interpreter_words(py: str, win: bool | None = None, version=None) -> str:
    """How the owner starts the Python setup checked: ``py -3.13`` on Windows, else that interpreter's own path (quoted when needed)."""
    win = procs.windows() if win is None else win
    if win:
        v = version or python_version(py) or sys.version_info[:2]
        return f"py -{v[0]}.{v[1]}"
    return _word(py, False)


def install_commands(py: str, home: Path, pack: Path | None = None, win: bool | None = None, version=None,
                     what: tuple[str, ...] = ("requirements.txt", "../config/compute/locks/python.lock")) -> list[str]:
    """The command(s) the owner may run himself to put the libraries into ``py`` (Drishti never runs them), in the form START_HERE teaches:
    from ``drishti-console`` (so requirements.txt's own ``--find-links ../lib/wheels`` finds the Lake wheel). With a wheel ``pack``: offline
    (``--no-index --find-links <pack>``); always also the form that uses your package index, as the second option. ``what``: the
    requirements files relative to drishti-console, one pip run EACH: python.lock carries --hash lines, and pip in hash mode refuses the
    unpinned lines of requirements.txt in the same run (checked: "all requirements must have their versions pinned with ==")."""
    win = procs.windows() if win is None else win
    sep = "\\" if win else "/"
    folder = str(home / "drishti-console") if not win else str(home).rstrip("\\/") + "\\drishti-console"
    files = (what,) if isinstance(what, str) else tuple(what)
    cd = f"cd {_word(folder, win)}"
    pip = f"{interpreter_words(py, win, version)} -m pip install"
    out = []
    if pack is not None:
        dirs = wheel_dirs(pack) if pack.is_dir() else [pack]
        links = " ".join(f"--find-links {_word(str(d), win)}" for d in dirs)
        out.append(f"{cd}   then   " + "   then   ".join(f"{pip} --no-index {links} -r {f.replace('/', sep)}" for f in files))
    out.append(f"{cd}   then   " + "   then   ".join(f"{pip} -r {f.replace('/', sep)}" for f in files) + "   (uses your package index)")
    return out


def stale_python(py: str) -> bool:
    """A drishti.python setup replaces: a file that is not there."""
    return not (Path(py).is_file() or shutil.which(py))


def ensure_setting(ini: Path, py: str, check: bool = False) -> tuple[str, str]:
    """``drishti.python=<py>`` into the settings file when absent or stale (:func:`stale_python`); (state, detail) with state ok, made or todo.
    Any other existing value is kept."""
    text = ini.read_text(encoding="utf-8") if ini.is_file() else ""
    m = re.search(rf"^[ \t]*{re.escape(SETTING)}[ \t]*=[ \t]*(\S.*)$", text, flags=re.M)
    if m:
        v = m.group(1).strip()
        if v == Path(py).as_posix():
            return "ok", v
        if not stale_python(v):
            return "ok", f"{v}  (kept as it is; setup used {py})"
        if check:
            return "todo", f"{v} is not there: setup writes {Path(py).as_posix()}"
        line = f"{SETTING}={Path(py).as_posix()}"
        ini.write_text(text[:m.start()] + line + text[m.end():], encoding="utf-8")
        return "made", f"{line}  (was {v}: no such Python)"
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
    return "made", f"{line}  (the one Python: start console, the cluster, the feeds and the server's Python work; DRISHTI_PYTHON overrides)"
