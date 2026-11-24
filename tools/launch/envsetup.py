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

"""``drishti.py setup`` (prepare this machine); the Maven every build runs (:func:`maven_command`; the builds: modulesbuild.py).

``setup drishti``: checks Python and Java 21+ (says which java and why), shows the install folder (DRISHTI_HOME, read only), the data folder
(DRISHTI_DATA_DIR, writable) and the modules folder with where each came from, makes the data folder's layout and the settings folder
(~/drishti-envs; Windows %USERPROFILE%\\drishti-envs) with a copy of the example dev.ini (its paths filled in), writes the JVM arguments
files and checks the server jar and the modules. A file setup writes in the settings folder that is already there and differs (a dev.ini
naming another folder, a JVM arguments file of another data folder) is MOVED into a dated folder there (``replaced-YYYY-MM-DD-HHMM``), never
overwritten or edited, and a fresh one is written; a dev.ini naming this checkout is kept as it is. Nothing is made outside this checkout,
the settings folder and an explicit ``--data-dir``; a ``DRISHTI_*`` folder variable naming another folder is reported and never used.
``setup libs``: the libraries (the pinned Lake wheel first, then drishti-console/requirements.txt) of the Python that runs setup,
checked name by name, and what is missing installed into THAT Python. ``setup`` alone = drishti, then libs, on every system. Drishti never
makes a virtual environment: the Python is yours (the system's, a dedicated one, or a virtual environment you made yourself and run
setup with); ``--system`` is accepted and changes nothing. The libraries come from the offline wheel pack: ``--wheels <folder or .zip>``,
else DRISHTI_WHEELS, else ~/drishti-wheels (Windows %USERPROFILE%\\drishti-wheels) when it holds .whl files (said in one line); nothing
is fetched from a package index unless ``--index``. A missing library is a [FAIL] line with the command that fixes it; an operating
system's own Python (PEP 668, EXTERNALLY-MANAGED) and a pack for another Python version are one [FAIL] line each and pip never runs
(never --break-system-packages). The Python setup ran with is written as drishti.python into the settings file (~/drishti-envs/dev.ini,
or the file --settings names): the Python of ``start console``, the cluster, the feed schedules and the server's Python work;
DRISHTI_PYTHON overrides. ``start`` never makes an environment and never installs anything. The next steps never say ``start`` while a
[FAIL] or [todo] remains: the fixes come first.
On Windows pip uses the Windows certificate store (a company proxy). Idempotent, on Linux, macOS and Windows alike; lists the environment
variables it sees and prints the next steps. ``--check`` changes nothing and only reports, the libraries name by name (exit 1 when something
needs doing)."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import procs
from procs import LaunchError, say
import javacmd  # noqa: E402 - tools/ is on the path through procs
import settingsfile  # noqa: E402
import pylibs

#: the folders a data folder holds (docs/DEPLOYMENT_AND_QA_GUIDE.md, "Prepare your environment")
DATA_LAYOUT = ("run", "logs", "tmp", "dumps", "delta", "dataload/inbox", "dataload/inprocess", "dataload/outbox", "dataload/failed", "dataload/ledger")
#: the variables setup reports (a secret's value is never shown)
ENV_VARS = ("DRISHTI_HOME", "DRISHTI_DATA_DIR", "DRISHTI_MODULES_HOME", "DRISHTI_JAVA_HOME", "JAVA_HOME", "DRISHTI_PYTHON", "DRISHTI_PORT",
            "DRISHTI_CONSOLE_PORT", "DRISHTI_SERVER_URL", "DRISHTI_LIB_PATH", "DRISHTI_TMP_DIR", "DRISHTI_DISTRIBUTION", "DRISHTI_PACKS",
            "DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET", "DRISHTI_NET_SECRET")
MIN_PYTHON = (3, 11)
PLACEHOLDER = "/path/to/drishti"


def copy_readmes(home: Path, data: Path) -> None:
    """The data inbox folders' README.md files (kept in the clone's data/dataload/) into a data folder elsewhere, when missing."""
    src = home / "data" / "dataload"
    if src.resolve() == (data / "dataload").resolve() or not src.is_dir():
        return
    for rel in ("", "inbox", "inprocess", "outbox", "failed", "ledger"):
        f, to = src / rel / "README.md", data / "dataload" / rel / "README.md"
        if f.is_file() and not to.exists():
            to.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, to)


def envs_dir(env=None) -> Path:
    """The settings folder: ~/drishti-envs (Windows %USERPROFILE%\\drishti-envs)."""
    env = os.environ if env is None else env
    base = (env.get("USERPROFILE") if procs.windows() else None) or str(Path.home())
    return Path(base) / "drishti-envs"


def secret(name: str) -> bool:
    return any(w in name for w in ("SECRET", "TOKEN", "PASSWORD"))


def dev_ini_text(home: Path, data: Path, modules: Path, java: Path | None = None) -> str:
    """config/settings.examples/dev.ini with its placeholder paths replaced by this machine's (forward slashes, as the file asks)."""
    text = (home / "config" / "settings.examples" / "dev.ini").read_text(encoding="utf-8")
    text = (text.replace(f"{PLACEHOLDER}/data", data.as_posix()).replace(f"{PLACEHOLDER}/modules", modules.as_posix())
            .replace(PLACEHOLDER, home.as_posix()))
    if java is not None:   # the JDK 21+ setup found: the key written live (the example line stays commented otherwise)
        text = re.sub(r"^# drishti\.java\.home=.*$", lambda m: f"drishti.java.home={java.as_posix()}", text, count=1, flags=re.M)
    return text


REEXEC_ENV = "DRISHTI_SETUP_REEXEC"
def ini_collision(ini: Path, home, explicit_data=None, flavour=None) -> list[str]:
    """Why an existing dev.ini is not this checkout's, so setup moves it: its ``drishti.home`` names another folder; or it names no
    ``drishti.home`` and a root outside ``home`` (``drishti.data.dir``, ``drishti.modules.home``, ``drishti.lib.path``); or its
    ``drishti.data.dir`` is not the ``--data-dir`` of this run; or it cannot be read. A file whose ``drishti.home`` is this checkout is
    this checkout's, whatever its other roots (a data folder elsewhere is the user's choice): empty, it is kept as it is."""
    try:
        e = settingsfile.read_all(str(ini))
    except (settingsfile.SettingsError, OSError) as x:
        return [f"it cannot be read ({x})"]

    def same(x, y) -> bool:
        return settingsfile.inside(x, y, flavour) and settingsfile.inside(y, x, flavour)
    out = []
    named = (e.get("drishti.home") or "").strip()
    if named and not same(named, home):
        out.append(f"drishti.home={named}")
    for key in ("drishti.data.dir", "drishti.modules.home", "drishti.lib.path"):
        v = settingsfile.home_value((e.get(key) or "").strip(), home) if (e.get(key) or "").strip() else ""
        if key == "drishti.data.dir" and explicit_data is not None:
            if not same(v or f"{home}/data", explicit_data):
                out.append(f"{key}={v or '<home>/data'}, not the --data-dir {explicit_data}")
        elif v and not named and not settingsfile.inside(v, home, flavour):
            out.append(f"{key}={v}")
    return out


def ini_outside(ini: Path, home, flavour=None) -> list[str]:
    """The roots a kept dev.ini (one naming this checkout) puts outside it, ``key=value`` each: reported, never made by setup."""
    try:
        e = settingsfile.read_all(str(ini))
    except (settingsfile.SettingsError, OSError):
        return []
    out = []
    for key in ("drishti.data.dir", "drishti.modules.home", "drishti.lib.path"):
        v = settingsfile.home_value((e.get(key) or "").strip(), home) if (e.get(key) or "").strip() else ""
        if v and not settingsfile.inside(v, home, flavour):
            out.append(f"{key}={v}")
    return out


def have_yaml() -> bool:
    """PyYAML importable in this interpreter (the parts of setup that read the modules and the launcher files need it)."""
    import importlib.util
    return importlib.util.find_spec("yaml") is not None


def bootstrap(a, home: Path, env: dict, argv: list | None = None, run=subprocess.run, yaml_here=have_yaml, wheels: Path | None = None) -> int | None:
    """Setup on a Python that has only the standard library and an install source (the wheel pack, or --index): installs the libraries
    into THIS Python first, then runs the same command again in a fresh process that sees them (a user-site folder pip made is not on this
    process's path). None: nothing to do (PyYAML is there, --check, setup drishti, or no install source: setup goes on and the library
    step reports what is missing). Never a virtual environment."""
    part = getattr(a, "part", None)
    if part == "drishti" or a.check or yaml_here() or not (wheels or getattr(a, "index", False)):
        return None                                      # no install source: setup goes on and the library step says what is missing
    if env.get(REEXEC_ENV):
        raise LaunchError("PyYAML is still not importable after the libraries were installed: run  " + procs.command("setup", "--check"))
    target = sys.executable
    say(f"installing the Lake wheel and drishti-console/requirements.txt into {target}" + (f" from {wheels}" if wheels else " from your package index (--index)")
        + ": setup reads PyYAML (and Drishti needs the rest) and this Python has not got them")
    pylibs.install(target, home, run=run, wheels=wheels)
    argv = list(sys.argv if argv is None else argv)
    say(f"running setup again with {target} ...")
    return run([target, str(Path(argv[0]).resolve()), *argv[1:]], env={**env, REEXEC_ENV: "1"}).returncode


def default_wheels() -> Path:
    """Where START_HERE puts the offline wheel pack: ~/drishti-wheels (Windows %USERPROFILE%\\drishti-wheels)."""
    return Path.home() / "drishti-wheels"


def system_tag() -> str:
    """The system part of a wheel pack's name: windows, linux or macos."""
    return "windows" if procs.windows() else "macos" if sys.platform == "darwin" else "linux"


def pack_zips(folder: Path) -> list[Path]:
    """The wheel-pack zips in ``folder`` for this system and this Python (``drishti-platform-<release>-wheels-<system>-<arch>-py<XY>.zip``),
    this release's first, then the newest."""
    if not folder.is_dir():
        return []
    py = f"py{sys.version_info[0]}{sys.version_info[1]}"
    zips = [z for z in folder.glob("*wheels*.zip") if system_tag() in z.name.lower() and py in z.name.lower()]
    release = javacmd._version_of().rsplit(".", 1)[-1] if hasattr(javacmd, "_version_of") else ""
    return sorted(zips, key=lambda z: (release not in z.name, -z.stat().st_mtime))


def pack_places() -> list[Path]:
    """Where a downloaded pack zip is looked for besides ~/drishti-wheels: the platform folder (the one holding drishti/) and Downloads."""
    return [javacmd.REPO.parent, Path.home() / "Downloads"]


def find_pack() -> tuple[str | None, str]:
    """The offline wheel pack when none is named: ~/drishti-wheels (unzipped, any depth, or a pack zip in it), then a pack zip for this
    system and Python in the platform folder or the Downloads folder (FIRST_RUN_ROBUSTNESS.md S4). (its path, the places looked in)."""
    home = default_wheels()
    if home.is_dir() and any(home.rglob("*.whl")):
        return str(home), str(home)
    places = [home, *pack_places()]
    for place in places:
        zips = pack_zips(place)
        if zips:
            return str(zips[0]), ", ".join(map(str, places))
    return None, ", ".join(map(str, places))


def pack_name() -> str:
    """The pack zip this machine needs, as the release publishes it."""
    arch = "x64" if __import__("platform").machine().lower() in ("amd64", "x86_64") else __import__("platform").machine().lower()
    return f"drishti-platform-<release>-wheels-{system_tag()}-{arch}-py{sys.version_info[0]}{sys.version_info[1]}.zip"


def fix_libs(settings: str | None = None) -> str:
    """The fix when libraries are missing and no pack was found (no network unless --index)."""
    return (procs.command("setup", "--wheels", "<folder or zip>", settings=settings) + f"   (the offline wheel pack, {pack_name()}: put it in "
            f"{default_wheels()} or your Downloads folder and plain setup finds it; or --index to use your package index)")


def setup(a, env: dict | None = None, argv: list | None = None, run=subprocess.run) -> int:
    """setup; the libraries come from ``--wheels <folder or .zip>``, else DRISHTI_WHEELS, else ~/drishti-wheels when it holds wheels
    (offline); ``--index`` alone lets pip use the configured package index. Neither: missing libraries are reported, nothing installed."""
    real = env is None
    env = dict(os.environ if env is None else env)
    spec = (getattr(a, "wheels", None) or env.get("DRISHTI_WHEELS") or "").strip() or None
    if spec and getattr(a, "check", False):
        spec = None                                  # --check changes nothing and installs nothing
    if not spec and not getattr(a, "check", False) and a.part != "drishti":
        found, _ = find_pack()
        if found:
            spec = found
            say(f"using the wheel pack {found} (no --wheels given, DRISHTI_WHEELS unset): the libraries are installed offline from it")
    with pylibs.wheel_folder(spec) as wheels:
        return _setup(a, env, argv, run, wheels, real)


def _setup(a, env: dict, argv: list | None, run, wheels: Path | None, real: bool = False) -> int:
    check = a.check
    part = getattr(a, "part", None)
    do_drishti = part in (None, "drishti")
    do_libs = part in (None, "libs")
    index = getattr(a, "index", False)
    todo: list[str] = []
    problems: list[str] = []

    def line(state: str, what: str, detail: str) -> None:
        say(f"  [{state:>4}] {what:<22} {detail}")

    say("Drishti setup" + (f" {part}" if part else "") + (" (--check: nothing is changed)" if check else "") + f" on {sys.platform}")
    # Python
    v = sys.version_info
    ok = (v.major, v.minor) >= MIN_PYTHON
    line("ok" if ok else "FAIL", "python", f"{sys.executable} {v.major}.{v.minor}.{v.micro}" + ("" if ok else f": Drishti needs Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]} or newer"))
    if not ok:
        problems.append(f"install Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]}+ (python.org; Windows: winget install Python.Python.3.13)")
    # a DRISHTI_* folder variable naming another folder (an old setx): reported, never used (the settings file is the one place for roots)
    original = dict(env)
    here = javacmd.REPO.resolve()
    foreign = dict(settingsfile.foreign_vars(env, here) + [(n, v) for n, v in settingsfile.REPLACED.items() if not settingsfile.inside(v, here)])
    where = "in Environment Variables" if procs.windows() else "from your shell profile"
    for n, v in foreign.items():
        line("WARN", n, f"{v}: names a folder outside this checkout; ignored: your settings file wins; you may delete it {where}")
        env.pop(n, None)
        if real:
            os.environ.pop(n, None)                  # this process only: nothing setup runs reads it (the user's environment is not changed)
    keys = javacmd.settings()[1]
    home = javacmd.find_home(None, env, keys)[0]
    edir = Path(a.envs_dir).expanduser() if a.envs_dir else envs_dir(env)
    ini = edir / "dev.ini"
    # --settings naming another file (a production /etc/drishti/prod.ini): that file is the one setup reads and writes drishti.python into;
    # it is never moved, and no developer dev.ini (nor the IDE launchers' JVM options) is made in the settings folder
    named = (javacmd.settings()[0] or "").split(",")[0].strip()
    custom = bool(named) and Path(named).expanduser().resolve() != ini.expanduser().resolve()
    if custom:
        ini = Path(named).expanduser()
    explicit = Path(a.data_dir).expanduser().resolve() if a.data_dir else None
    j = {"ok": False, "java": "", "problem": ""}
    rc = bootstrap(a, home, original, argv, run, wheels=wheels)
    if rc is not None:
        return rc
    # the settings file: this checkout's (kept as it is), else moved to a dated folder and a fresh one written (never edited in place)
    stale = ini_collision(ini, home, explicit) if do_drishti and ini.is_file() and not custom else []
    fresh = {"drishti.home": home.as_posix(), "drishti.data.dir": (explicit or home / "data").as_posix(),
             "drishti.modules.home": (home / "modules").as_posix()}
    use_fresh = do_drishti and not custom and bool(stale or not ini.is_file())
    ini_keys = fresh if use_fresh else (settingsfile.read_all(str(ini)) if do_drishti else {})
    roots = javacmd.launch_roots(None, a.data_dir, env, {**ini_keys, **keys})
    data, modules = roots["data"][0], roots["modules"][0]
    line("ok", "home (DRISHTI_HOME)", f"{home}  ({roots['home'][1]}; the install, read only)")
    jar = roots["jar"][0]
    moved: dict = {"to": None}

    def move_aside(files: list, why: str) -> bool:
        """Moves ``files`` into this run's dated folder in the settings folder (one per run) and says so in one line; False (a [FAIL]
        line naming the file) when one cannot be moved (open in another program on Windows): nothing is then written over it."""
        try:
            moved["to"] = settingsfile.move_to_dated(files, edir, moved["to"])
        except settingsfile.MovedError as e:
            line("FAIL", "old settings", str(e))
            problems.append(str(e))
            return False
        line("move", "old settings", f"{', '.join(f.name for f in files)} moved to {moved['to']}  ({why})")
        return True
    yaml_ok = have_yaml()
    if do_drishti:
        # Java: the one lookup
        j = javacmd.describe_java(None, a.java_home, env)
        if j["ok"]:
            line("ok", "java", f"{j['java']}  Java {j['version']}  (from {j['why']})")
        else:
            line("FAIL", "java", j["problem"] + (f"  ({j['java']}, from {j['why']})" if j["java"] else ""))
            problems.append("install a JDK 21 (Temurin) and set drishti.java.home in your settings file (or DRISHTI_JAVA_HOME) to its folder, or pass --java-home")
        # the settings folder and dev.ini
        if custom:
            line("ok" if ini.is_file() else "FAIL", "settings file", f"{ini}  (named by --settings: kept as it is; no dev.ini is made in {edir})"
                 if ini.is_file() else f"{ini}: no such file (--settings)")
            if not ini.is_file():
                problems.append(f"make {ini} (config/settings.examples/prod.ini is the example), then run setup again")
        elif stale and check:
            line("todo", "settings file", f"{ini} names {'; '.join(stale)}: setup moves it to {edir / 'replaced-<date>'} and writes a fresh one")
            todo.append(procs.command("setup") + f"  (moves the old {ini.name} to a dated folder in {edir} and writes one for this checkout)")
        elif ini.is_file() and not stale:
            line("ok", "settings file", f"{ini}  (names this checkout: kept as it is)")
            for o in ini_outside(ini, home):
                line("ok", "outside this checkout", f"{o}  (from your dev.ini: kept; setup makes nothing there unless you pass --data-dir)")
        elif check:
            line("todo", "settings file", f"{ini} is missing")
            todo.append(f"copy config/settings.examples/dev.ini to {ini}")
        elif stale and not move_aside([ini], f"it named {'; '.join(stale)}"):
            pass                                     # the [FAIL] line says which file to close; nothing is written over it
        else:
            edir.mkdir(parents=True, exist_ok=True)
            jdk = Path(j["java"]).resolve().parent.parent if j["ok"] and j["java"] else None
            ini.write_text(dev_ini_text(home, data, modules, jdk), encoding="utf-8")
            line("made", "settings file", f"{ini}  (from config/settings.examples/dev.ini, its paths filled in for this checkout" +
                 (f"; drishti.java.home={jdk.as_posix()}" if jdk else "") + ")")
            if jdk is None:
                line("todo", "drishti.java.home", f"no Java 21+ found ({j['problem'] or 'none'}): in {ini} uncomment drishti.java.home and set it to a JDK 21 folder")
                todo.append(f"set drishti.java.home in {ini} to a JDK 21 folder")
        # the data folder and its layout
        missing = [d for d in DATA_LAYOUT if not (data / d).is_dir()]
        ours = explicit is not None or settingsfile.inside(data, home)     # setup makes nothing outside this checkout unless --data-dir
        if missing and not ours:
            line("todo", "data (DRISHTI_DATA_DIR)", f"{data}  ({roots['data'][1]}; outside this checkout): {', '.join(missing)} missing")
            todo.append(procs.command("setup", "--data-dir", str(data)) + "  (makes the layout of your data folder outside this checkout)")
        elif missing and not check:
            for d in DATA_LAYOUT:
                (data / d).mkdir(parents=True, exist_ok=True)
            copy_readmes(home, data)
            line("made", "data (DRISHTI_DATA_DIR)", f"{data}  ({roots['data'][1]}): made {', '.join(missing)}")
        elif missing:
            line("todo", "data (DRISHTI_DATA_DIR)", f"{data}  ({roots['data'][1]}): {', '.join(missing)} missing")
            todo.append(f"make the data folder's {', '.join(missing)}")
        else:
            line("ok", "data (DRISHTI_DATA_DIR)", f"{data}  ({roots['data'][1]}; writable)")
        import modulesbuild
        pk = modulesbuild.packages(modules)
        line("ok" if pk else "todo", "modules (packages)", f"{modules}  ({roots['modules'][1]}): {len(pk)} .dmod file(s)" + ("" if pk else "; " + procs.command("build", "modules")))
        # what an older release kept under the data folder: no longer read, safe to delete (never deleted here)
        for old in ("installed", "store", "shipped", "staging", "tombstones", "registry.json"):
            if (data / "modules" / old).exists():
                line("todo", "old modules leftover", f"{data / 'modules' / old}: not read any more, safe to delete")
        # leftovers of an older checkout under the modules folder (old build folders a `git pull` keeps): reported, never deleted
        for note in (__import__("module_layout").leftovers(modules) if yaml_ok else []):
            line("todo", "modules leftover", note)
            todo.append(f"delete {note.split(':')[0]} (git clean -fdX modules/ removes every ignored file under modules/)")
        # every jar under the home: Drishti's own in <home>/lib, the site's in <home>/lib/ext (drishti.lib.path)
        line("ok" if jar else "todo", "server jar (<home>/lib)", str(jar) if jar else roots["jar"][1])
        ext = Path(settingsfile.home_value((ini_keys.get("drishti.lib.path") or "").strip(), home) or env.get("DRISHTI_LIB_PATH") or roots["ext"][0])
        if not ext.is_dir() and not check and settingsfile.inside(ext, home):
            ext.mkdir(parents=True, exist_ok=True)
            line("made", "your jars (lib/ext)", f"{ext}  (JDBC drivers, connector plugins: drishti.lib.path; kept on an upgrade)")
        else:
            line("ok" if ext.is_dir() else "todo", "your jars (lib/ext)", f"{ext}  (JDBC drivers, connector plugins: drishti.lib.path; kept on an upgrade)")
        # the JVM arguments files of the IDE's server launchers (generated, from the function start server runs): one that differs is moved
        if custom:
            line("skip", "JVM options", f"the IDE server launchers' files are written only with ~/drishti-envs/dev.ini (not with --settings {ini.name})")
        elif not yaml_ok:
            line("todo", "JVM options", "needs PyYAML, which this Python has not got: " + procs.command("setup", "--wheels", "<folder>"))
            todo.append(fix_libs(javacmd.settings()[0]) + "; then setup writes the JVM options and the rest")
        else:
            import launchers
            try:
                want = launchers.machine_files(home, edir, ini, fresh if (use_fresh and check) else None)
            except Exception as e:                                   # noqa: BLE001 - a setup step reports, never stops the others
                want = None
                line("FAIL", "JVM options", f"{e}")
                problems.append(procs.command("launchers", "--machine", settings=str(ini)))
            if want is not None:
                missing = [f.name for f in want if not f.is_file()]
                differ = [f for f, (text, _) in want.items() if f.is_file() and f.read_text(encoding="utf-8") != text]
                if check:
                    detail = "; ".join([*([f"missing {', '.join(missing)}"] if missing else []),
                                        *([f"{', '.join(f.name for f in differ)} differ: setup moves them to {edir / 'replaced-<date>'}"] if differ else [])])
                    line("todo" if detail else "ok", "JVM options", f"{edir}: " + (detail or ", ".join(f.name for f in want)))
                    if detail:
                        todo.append("write the IDE server launchers' JVM options: " + procs.command("setup"))
                else:
                    if differ and not move_aside(differ, "they differ from this checkout's"):
                        want = {f: v for f, v in want.items() if f not in differ}      # a file that could not be moved is never written over
                    edir.mkdir(parents=True, exist_ok=True)
                    for f, (text, folder) in want.items():
                        if not f.is_file():
                            f.write_text(text, encoding="utf-8", newline="\n")
                        # the folders the file names, only under this checkout or the data folder resolved above (never elsewhere)
                        if settingsfile.inside(folder, home) or (explicit is not None and folder == data):
                            for d in launchers.ARGS_FOLDERS:
                                (folder / d).mkdir(parents=True, exist_ok=True)
                    line("made" if (missing or differ) else "ok", "JVM options",
                         f"{edir}: {', '.join(f.name for f in want)}  (the IDE server launchers' @files; data folder from {ini.name})")
    # the Python libraries: into the Python running setup; Drishti never makes a virtual environment
    settings = javacmd.settings()[0]
    target = sys.executable
    py_used = target
    if do_libs:
        try:
            bad = pylibs.verify(target, home)
            made = False
            # an operating system's Python (PEP 668) and a pack for another Python version: one [FAIL] line each, pip never runs
            refused = [m for m in ((pylibs.MANAGED if bad and pylibs.externally_managed(target, run) else ""),
                                   (pylibs.pack_mismatch(target, wheels, run) if bad and wheels else "")) if m]
            for m in refused:
                line("FAIL", "libraries", m)
                problems.append(m)
            if refused:
                bad = []                              # said above; nothing is installed
            elif bad and not check and (wheels or index):
                pylibs.install(target, home, run=run, wheels=wheels)
                bad = pylibs.verify(target, home)
                made = not bad
            if made:
                line("made", "libraries", f"{target}: what was missing installed " + (f"from {wheels}" if wheels else "from your package index (--index)") + " (the Lake wheel first)")
            elif not bad and not refused:
                line("ok", "libraries", f"{target}: every requirement of drishti-console/requirements.txt, the Lake wheel at its pin")
            elif check:
                line("todo", "libraries", f"{target}: " + "; ".join(bad))
                todo.append(fix_libs(settings))
            else:
                line("FAIL", "libraries", f"{target}: " + "; ".join(bad) + ("" if (wheels or index) else "  (no wheel pack found and no --index: nothing installed)"))
                problems.append(fix_libs(settings) if not wheels else f"add what {wheels} lacks to the wheel pack, then run setup again: " + "; ".join(bad))
        except LaunchError as e:
            line("FAIL", "libraries", str(e))
            problems.append(str(e) if wheels else fix_libs(settings) + "\n     (" + str(e).splitlines()[0] + ")")
    if custom:                                         # a named file: only one naming another drishti.home is another copy's
        other = (settingsfile.read_all(str(ini)).get("drishti.home") or "").strip() if ini.is_file() else ""
        foreign_ini = bool(other) and not (settingsfile.inside(other, home) and settingsfile.inside(home, other))
    else:
        foreign_ini = ini.is_file() and bool(ini_collision(ini, home))      # another copy's dev.ini (not moved): never written into
    if (do_libs or do_drishti) and foreign_ini:
        line("skip", "drishti.python", f"{ini} names another folder: not written (setup moves it first)")
    else:                                             # the one Python Drishti runs with, written whenever setup runs
        try:
            state, detail = pylibs.ensure_setting(ini, py_used or target, check)
        except OSError as e:                           # a settings file this account may not write (/etc/drishti/prod.ini)
            state, detail = "FAIL", f"{ini}: {e.strerror or e}: add  {pylibs.SETTING}={Path(py_used or target).as_posix()}  to it (as its owner)"
            problems.append(detail)
        line(state, "drishti.python", detail)
        if state == "todo":
            todo.append(f"set {pylibs.SETTING} in {ini}: " + procs.command("setup"))
    # the environment
    say("\nEnvironment variables seen:")
    for n in ENV_VARS:
        val = (original.get(n) or "").strip()
        say(f"  {n:<24} {('set (not shown)' if secret(n) else val) if val else '-'}" +
            (f"  WARNING: names a folder outside this checkout; ignored: your settings file wins; you may delete it {where}" if n in foreign else ""))
    say("\nNext steps:")
    steps = [*problems, *todo]
    import modulesbuild
    if not jar:
        steps.append(procs.command("build", "all") + "   (the server jar into lib/ and the modules into modules/)")
    else:
        steps += [procs.command("build", "modules") + f"   ({w})" for w in modulesbuild.missing_report(modules)[:1]]
        steps += [f"{w}" for w in modulesbuild.fresh_report(modules, jar)[:3]]
    # the default settings file needs no --settings (tools/drishti.py takes ~/drishti-envs/dev.ini naming this checkout by itself)
    named = None if not settings and not a.envs_dir else str(settings or ini)
    fixes = len(dict.fromkeys(steps))
    if steps:                                        # never a start line while a [FAIL] or [todo] remains: the fixes, then setup again
        steps.append(procs.command("setup", settings=named) + "   (again: when nothing is left to do it lists the start commands)")
    else:
        steps += [procs.command("lake", "demo", "--engine", "java", settings=named) + "   (the demo lake)",
                  procs.command("start", "all", "--demo", settings=named) + "   (server and console; then status, stop all)"]
    for i, s in enumerate(dict.fromkeys(steps), 1):
        say(f"  {i}. {s}")
    # the verdict, the last line (S9)
    say("\nDrishti is ready." if not fixes else f"\nDrishti is NOT ready: {fixes} to fix (above).")
    if problems:
        return 1
    return 1 if (check and todo) else 0


def maven_command(home: Path) -> list:
    """The Maven to run: the wrapper of the install (mvnw; Windows mvnw.cmd through cmd /c), else mvn on the PATH."""
    if procs.windows():
        if (home / "mvnw.cmd").is_file():
            return ["cmd", "/c", str(home / "mvnw.cmd")]
    elif (home / "mvnw").is_file():
        return ["sh", str(home / "mvnw")]
    mvn = shutil.which("mvn")
    if not mvn:
        raise LaunchError("no Maven: the install has no mvnw and mvn is not on the PATH")
    return [mvn]
