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
checked name by name; NEVER installed (owner rule, 2026-11-23: an automatic install can corrupt an environment). ``setup`` alone =
drishti, then libs, on every system. Drishti never makes a virtual environment and never runs pip: the Python is yours (a dedicated one,
or a virtual environment you made yourself and run setup with); ``--system`` is accepted and changes nothing. A missing library is a
[FAIL] line and the pip command you may run yourself, from drishti-console: offline from the wheel pack when one is found (``--wheels
<folder or .zip>``, else DRISHTI_WHEELS, else ~/drishti-wheels, else the pack zip in Downloads; a zip is never unzipped: you unzip it), and
from your package index as the second option. An operating system's own Python (PEP 668, EXTERNALLY-MANAGED) and a pack for another
Python version are one [FAIL] line each. The Python setup ran with is written as drishti.python into the settings file
(~/drishti-envs/dev.ini, or the file --settings names): the Python of ``start console``, the cluster, the feed schedules and the server's
Python work; DRISHTI_PYTHON overrides. ``start`` never makes an environment and never installs anything. The next steps never say
``start`` while a [FAIL] or [todo] remains: the fixes come first.
Idempotent, on Linux, macOS and Windows alike; lists the environment
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


def fix_libs(py: str, home: Path, pack: "pylibs.Pack | None" = None,
             what: tuple = ("requirements.txt", "../config/compute/locks/python.lock")) -> str:
    """The command(s) the owner may run himself to put the missing libraries into ``py``: offline from the wheel pack when one was found,
    else (and always as the second option) from his package index. Drishti never runs them."""
    folder = pack.path if pack is not None and pack.path is not None else None
    if folder is not None and folder.is_file():                 # a zip (setup never unzips it): where the person unzips it
        folder = pylibs.wheels_home() / folder.name[:-4]
    cmds = pylibs.install_commands(py, home, folder, what=what)
    if folder is None:
        cmds[0] += f"; or offline: put {pack.wanted if pack is not None else 'the wheel pack zip'} in your Downloads folder and run setup again"
    return "   or   ".join(cmds) + "   (Drishti never installs libraries itself)"


def setup(a, env: dict | None = None, argv: list | None = None, run=subprocess.run) -> int:
    """setup. It never installs a library (owner rule): it finds the offline wheel pack (``--wheels <folder or .zip>``, else DRISHTI_WHEELS,
    else ~/drishti-wheels, else the pack zip in Downloads) only to name it in the command it prints for missing libraries. A pack found only
    as a zip is never unzipped (setup writes nothing outside the checkout): when libraries are missing, one [FAIL] says to unzip it."""
    real = env is None
    env = dict(os.environ if env is None else env)
    pack = pylibs.find_pack(getattr(a, "wheels", None), env) if a.part != "drishti" else None
    spec = str(pack.path) if pack is not None and pack.path is not None and pack.path.is_dir() and not getattr(a, "check", False) else None
    with pylibs.wheel_folder(spec, env) as wheels:
        if wheels is not None:
            pack.path = wheels
        return _setup(a, env, argv, run, pack, real)


def _setup(a, env: dict, argv: list | None, run, pack: "pylibs.Pack | None", real: bool = False) -> int:
    check = a.check
    part = getattr(a, "part", None)
    do_drishti = part in (None, "drishti")
    do_libs = part in (None, "libs")
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
    # DRISHTI_PYTHON beats drishti.python everywhere (procs.tools_python): one naming a Python that is gone breaks every start
    named_py = (env.get("DRISHTI_PYTHON") or "").strip()
    if named_py and not Path(named_py).expanduser().is_file():
        gone = f"DRISHTI_PYTHON={named_py} is not there (start console, the cluster and the feeds would run it)"
        fix = (f"delete DRISHTI_PYTHON {'in Environment Variables' if procs.windows() else 'from your shell profile'} (setup writes "
               f"{pylibs.SETTING} into your settings file), or point it at a Python that exists")
        line("FAIL", "DRISHTI_PYTHON", f"{gone}. Fix: {fix}")
        problems.append(fix)
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
            problems.append(f"install Java 21 (a JDK, e.g. Temurin) and set JAVA_HOME to its folder, or set drishti.java.home in {ini} "
                            "(or DRISHTI_JAVA_HOME) to it, or pass --java-home")
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
            try:
                for d in DATA_LAYOUT:
                    (data / d).mkdir(parents=True, exist_ok=True)
                copy_readmes(home, data)
                line("made", "data (DRISHTI_DATA_DIR)", f"{data}  ({roots['data'][1]}): made {', '.join(missing)}")
            except PermissionError as e:             # a folder this account may not write: named, with the fix (never the catch-all)
                folder = Path(e.filename).parent if e.filename else data
                why = f"{folder} is not writable ({e.strerror or e})"
                fix = f"check you may write to {folder} (or close the program that has it open), or pass --data-dir <a folder you may write>"
                line("FAIL", "data (DRISHTI_DATA_DIR)", f"{data}: {why}. Fix: {fix}")
                problems.append(fix)
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
            line("todo", "JVM options", "needs PyYAML, which this Python has not got (the libraries step below names the command)")
            todo.append(fix_libs(sys.executable, home, pack) + "; then run setup again: it writes the JVM options and the rest")
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
        # CHECKED, never installed (owner rule): what is missing is one [FAIL] line and the command the owner may run himself
        try:                                              # requirements.txt AND python.lock: both checked, neither installed
            bad = pylibs.verify(target, home)
        except LaunchError as e:
            bad = [str(e)]
        folder = pack.path if pack is not None and pack.path is not None and pack.path.is_dir() else None
        if not bad:
            line("ok", "libraries", f"{target}: every requirement of drishti-console/requirements.txt and {pylibs.LOCK.as_posix()}, "
                 "the Lake wheel at its pin")
        elif pylibs.externally_managed(target, run):
            line("FAIL", "libraries", pylibs.MANAGED)             # an operating system's Python (PEP 668): no install command at all
            problems.append(pylibs.MANAGED)
        elif pack is not None and pack.problem:
            line("FAIL", "libraries", f"{target}: " + "; ".join(bad))
            line("FAIL", "wheel pack", pack.problem)
            problems.append(pack.fail(procs.command("setup", settings=settings)))
        elif pack is not None and pack.path is not None and pack.path.is_file():     # a zip: never unzipped here, the person unzips it
            zipped = pylibs.still_zipped(pack.path, env)
            line("FAIL", "libraries", f"{target}: " + "; ".join(bad))
            line("FAIL", "wheel pack", f"{pack.path} is still zipped (setup never unzips it)")
            problems.append(zipped)
        elif folder is not None and pylibs.pack_mismatch(target, folder, run):
            line("FAIL", "libraries", pylibs.pack_mismatch(target, folder, run))
            problems.append(pylibs.pack_mismatch(target, folder, run))
        else:
            line("todo" if check else "FAIL", "libraries", f"{target}: " + "; ".join(bad) + "  (Drishti installs nothing: the command is below)")
            (todo if check else problems).append(fix_libs(target, home, pack))
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
