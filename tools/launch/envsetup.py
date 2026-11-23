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
(~/drishti-envs; Windows %USERPROFILE%\\drishti-envs) with a copy of the example dev.ini (its paths filled in; an existing file is never
overwritten), writes the JVM arguments files and checks the server jar and the modules.
``setup venv``: makes or checks drishti-console/.venv (the pinned Lake wheel first, then drishti-console/requirements.txt; --reinstall remakes it).
``setup libs``: the same libraries into the CURRENT Python, no venv. ``setup --system`` = drishti + libs, where no .venv is allowed.
``setup`` alone = drishti, then venv (``--skip-venv`` = drishti only). The Python that was set up or used is written once into dev.ini as
drishti.python (an existing value is never changed): the server's Python where its own setting is blank; DRISHTI_PYTHON overrides.
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


def other_home(ini: Path, home: Path) -> str | None:
    """The folder an existing dev.ini's drishti.home names when it is not this checkout (else None: no such line, or the same folder)."""
    try:
        text = ini.read_text(encoding="utf-8")
    except OSError:
        return None
    m = re.search(r"^\s*drishti\.home\s*[=:]\s*(.+?)\s*$", text, re.M)
    if not m or not m.group(1):
        return None
    named = m.group(1).strip().strip("\"'")

    def norm(x) -> str:
        return os.path.normcase(os.path.normpath(str(x).replace("\\", "/"))).replace("\\", "/").rstrip("/")
    try:
        same = norm(named) == norm(home) or norm(Path(named).expanduser().resolve()) == norm(Path(home).resolve())
    except (OSError, RuntimeError):
        same = False
    return None if same else named


def rename_command(ini: Path) -> str:
    """The command that moves an old settings file aside: ren on Windows, mv elsewhere."""
    if procs.windows():
        return f'ren "{ini}" "{ini.stem}-old{ini.suffix}"'                                      # quoted: a user name may hold a space
    return f'mv "{ini}" "{ini.with_name(ini.stem + "-old" + ini.suffix)}"'


def have_yaml() -> bool:
    """PyYAML importable in this interpreter (the parts of setup that read the modules and the launcher files need it)."""
    import importlib.util
    return importlib.util.find_spec("yaml") is not None


def bootstrap(a, home: Path, env: dict, argv: list | None = None, run=subprocess.run, yaml_here=have_yaml, wheels: Path | None = None) -> int | None:
    """Setup on a Python that has only the standard library: installs what is missing first, then runs the same command again in a fresh
    process that sees it (a user-site folder pip made is not on this process's path). None: nothing to do, setup goes on in this process.
    ``--system``: the Lake wheel and requirements.txt into this Python; the default (venv) path: drishti-console/.venv first, then setup
    under the venv's Python. Never with ``--check`` (it changes nothing; the missing parts are reported)."""
    part = getattr(a, "part", None)
    system = getattr(a, "system", False)
    needs_yaml = (part in (None, "drishti") or system) and not a.check
    if not needs_yaml or yaml_here():
        return None
    if env.get(REEXEC_ENV):
        raise LaunchError("PyYAML is still not importable after the libraries were installed: run  python tools/drishti.py setup libs --check")
    if system:
        target = a.python or sys.executable
        say(f"installing the Lake wheel and drishti-console/requirements.txt into {target} with pip: setup reads PyYAML (and the console needs the rest) and this Python has not got them")
        pylibs.install(target, home, run=run, wheels=wheels)
    elif part is None and not a.skip_venv:
        import consolestart
        say("making drishti-console/.venv and installing the libraries into it: setup reads PyYAML and this Python has not got it")
        target = str(consolestart.make_venv(home, a.python or sys.executable, a.reinstall, run=run, wheels=wheels))
    else:
        raise LaunchError("this part of setup reads PyYAML, which this Python has not got: run  python tools/drishti.py setup --system  (the libraries into this Python) or  setup  (a .venv) first")
    argv = list(sys.argv if argv is None else argv)
    say(f"running setup again with {target} ...")
    return run([target, str(Path(argv[0]).resolve()), *argv[1:]], env={**env, REEXEC_ENV: "1"}).returncode


def setup(a, env: dict | None = None, argv: list | None = None, run=subprocess.run) -> int:
    """setup; ``--wheels <folder or .zip>`` (else DRISHTI_WHEELS) installs the libraries only from that folder, offline."""
    env = dict(os.environ if env is None else env)
    spec = (getattr(a, "wheels", None) or env.get("DRISHTI_WHEELS") or "").strip() or None
    if spec and getattr(a, "check", False):
        spec = None                                  # --check changes nothing and installs nothing
    with pylibs.wheel_folder(spec) as wheels:
        return _setup(a, env, argv, run, wheels)


def _setup(a, env: dict, argv: list | None, run, wheels: Path | None) -> int:
    check = a.check
    part = getattr(a, "part", None)
    system = getattr(a, "system", False)
    if system and part not in (None, "drishti", "libs"):
        raise LaunchError("--system means setup drishti + setup libs: it does not go with " + part)
    do_drishti = part in (None, "drishti") or system
    do_venv = part in (None, "venv") and not system and not a.skip_venv
    do_libs = part == "libs" or system
    todo: list[str] = []
    problems: list[str] = []

    def line(state: str, what: str, detail: str) -> None:
        say(f"  [{state:>4}] {what:<22} {detail}")

    say("Drishti setup" + (f" {'--system' if system else part}" if (part or system) else "") + (" (--check: nothing is changed)" if check else "") + f" on {sys.platform}")
    # Python
    v = sys.version_info
    ok = (v.major, v.minor) >= MIN_PYTHON
    line("ok" if ok else "FAIL", "python", f"{sys.executable} {v.major}.{v.minor}.{v.micro}" + ("" if ok else f": Drishti needs Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]} or newer"))
    if not ok:
        problems.append(f"install Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]}+ (python.org; Windows: winget install Python.Python.3.13)")
    # the roots
    roots = javacmd.launch_roots(None, a.data_dir, env, javacmd.settings()[1])
    home, data, modules = roots["home"][0], roots["data"][0], roots["modules"][0]
    line("ok", "home (DRISHTI_HOME)", f"{home}  ({roots['home'][1]}; the install, read only)")
    edir = Path(a.envs_dir).expanduser() if a.envs_dir else envs_dir(env)
    ini = edir / "dev.ini"
    jar = roots["jar"][0]
    j = {"ok": False, "java": "", "problem": ""}
    rc = bootstrap(a, home, env, argv, run, wheels=wheels)
    if rc is not None:
        return rc
    yaml_ok = have_yaml()
    if do_drishti:
        # Java: the one lookup
        j = javacmd.describe_java(None, a.java_home, env)
        if j["ok"]:
            line("ok", "java", f"{j['java']}  Java {j['version']}  (from {j['why']})")
        else:
            line("FAIL", "java", j["problem"] + (f"  ({j['java']}, from {j['why']})" if j["java"] else ""))
            problems.append("install a JDK 21 (Temurin) and set drishti.java.home in your settings file (or DRISHTI_JAVA_HOME) to its folder, or pass --java-home")
        # the data folder and its layout
        missing = [d for d in DATA_LAYOUT if not (data / d).is_dir()]
        if missing and not check:
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
        line("ok" if pk else "todo", "modules (packages)", f"{modules}  ({roots['modules'][1]}): {len(pk)} .dmod file(s)" + ("" if pk else "; python tools/drishti.py build modules"))
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
        ext = Path(env.get("DRISHTI_LIB_PATH") or roots["ext"][0])
        if not ext.is_dir() and not check:
            ext.mkdir(parents=True, exist_ok=True)
            line("made", "your jars (lib/ext)", f"{ext}  (JDBC drivers, connector plugins: drishti.lib.path; kept on an upgrade)")
        else:
            line("ok" if ext.is_dir() else "todo", "your jars (lib/ext)", f"{ext}  (JDBC drivers, connector plugins: drishti.lib.path; kept on an upgrade)")
        # the settings folder and dev.ini
        if ini.is_file():
            other = other_home(ini, home)
            if other:
                fix = f"your settings file {ini} points at {other}, not this one ({home}): rename it ({rename_command(ini)}) and run setup again"
                line("FAIL" if check else "WARN", "settings file", fix + "  (it is never overwritten)")
                (problems if check else todo).append(fix)
            else:
                line("ok", "settings file", f"{ini}  (kept as it is)")
        elif check:
            line("todo", "settings file", f"{ini} is missing")
            todo.append(f"copy config/settings.examples/dev.ini to {ini}")
        else:
            edir.mkdir(parents=True, exist_ok=True)
            jdk = Path(j["java"]).resolve().parent.parent if j["ok"] and j["java"] else None
            ini.write_text(dev_ini_text(home, data, modules, jdk), encoding="utf-8")
            line("made", "settings file", f"{ini}  (from config/settings.examples/dev.ini, its paths filled in" +
                 (f"; drishti.java.home={jdk.as_posix()}" if jdk else "") + ")")
            if jdk is None:
                line("todo", "drishti.java.home", f"no Java 21+ found ({j['problem'] or 'none'}): in {ini} uncomment drishti.java.home and set it to a JDK 21 folder")
                todo.append(f"set drishti.java.home in {ini} to a JDK 21 folder")
        # the JVM arguments files of the IDE's server launchers (generated: rewritten every time, from the function start server runs)
        if not yaml_ok:
            line("todo", "JVM options", "needs PyYAML, which this Python has not got: python tools/drishti.py setup --system")
            todo.append("python tools/drishti.py setup --system  (installs the libraries into this Python, then the JVM options and the rest)")
            args_files = []
        else:
            import launchers
            args_files = [edir / f"jvm-{p}.args" for p in launchers.profiles_of_servers(home)]
        if not yaml_ok:
            pass
        elif check:
            missing = [f.name for f in args_files if not f.is_file()]
            line("todo" if missing else "ok", "JVM options", f"{edir}: " + (f"missing {', '.join(missing)}" if missing else ", ".join(f.name for f in args_files)))
            if missing:
                todo.append("write the IDE server launchers' JVM options: python tools/drishti.py setup (or launchers --machine)")
        elif ini.is_file():
            try:
                written = launchers.machine(home, edir, ini)
                line("made", "JVM options", f"{edir}: {', '.join(f.name for f in written)}  (the IDE server launchers' @files; data folder from {ini.name})")
            except Exception as e:                                   # noqa: BLE001 - a setup step reports, never stops the others
                line("FAIL", "JVM options", f"{e}")
                problems.append("python tools/drishti.py --settings <dev.ini> launchers --machine")
    # the Python libraries: the console's .venv, or the current interpreter (--system, setup libs)
    py_used = None
    if do_venv:
        py = procs.venv_python(home)
        if py.exists() and not a.reinstall:
            bad = pylibs.verify(str(py), home)
            if bad and not check:                   # only what is missing or wrong is installed, into the venv that is there
                try:
                    pylibs.install(str(py), home, run=run, wheels=wheels)
                except LaunchError as e:
                    line("FAIL", "console .venv", str(e))
                    problems.append(str(e))
                bad = pylibs.verify(str(py), home)
            line("ok" if not bad else "FAIL", "console .venv", f"{py}" + ("" if not bad else ": " + "; ".join(bad)))
            if bad:
                problems.append("python tools/drishti.py setup venv --reinstall (remakes drishti-console/.venv); missing or wrong: " + "; ".join(bad))
            else:
                py_used = str(py)
        elif check:
            line("todo", "console .venv", f"{py} is missing")
            todo.append("make drishti-console/.venv: python tools/drishti.py setup venv")
        else:
            import consolestart
            try:
                consolestart.make_venv(home, a.python or sys.executable, a.reinstall, run=run, wheels=wheels)
                line("made", "console .venv", str(py))
                py_used = str(py)
            except LaunchError as e:
                line("FAIL", "console .venv", str(e))
                problems.append(str(e))
    elif not do_libs and part in (None, "venv"):
        line("skip", "console .venv", f"{procs.venv_python(home).parent.parent}  (--skip-venv)")
    if do_libs:
        target = a.python or sys.executable
        try:
            bad = pylibs.verify(target, home)
            if bad and not check:
                pylibs.install(target, home, run=run, wheels=wheels)
                bad = pylibs.verify(target, home)
                if not bad:
                    line("made", "libraries", f"{target}: what was missing installed" + (f" from {wheels}" if wheels else "") + " (the Lake wheel first)")
            elif bad:
                line("todo", "libraries", f"{target}: " + "; ".join(bad))
                todo.append(f"python tools/drishti.py setup libs  (installs the Lake wheel, then drishti-console/requirements.txt, into {target})")
            else:
                line("ok", "libraries", f"{target}: every requirement of drishti-console/requirements.txt, the Lake wheel at its pin")
            if bad and not check:
                line("FAIL", "libraries", f"{target}: " + "; ".join(bad))
                problems.append("fix the libraries of " + target + ": " + "; ".join(bad))
            elif not bad:
                py_used = target
        except LaunchError as e:
            line("FAIL", "libraries", str(e))
            problems.append(str(e))
    if py_used and (do_venv or do_libs):
        state, detail = pylibs.ensure_setting(ini, py_used, check)
        line(state, "drishti.python", detail)
        if state == "todo":
            todo.append(f"set {pylibs.SETTING} in {ini}: python tools/drishti.py setup" + (" libs" if do_libs else " venv"))
    # the environment
    say("\nEnvironment variables seen:")
    for n in ENV_VARS:
        val = (env.get(n) or "").strip()
        say(f"  {n:<24} {('set (not shown)' if secret(n) else val) if val else '-'}")
    settings = javacmd.settings()[0]
    say("\nNext steps" + (f" (python is {Path(sys.executable).name}; on Windows use py or python):" if procs.windows() else ":"))
    steps = [*problems, *todo]
    import modulesbuild
    if not jar:
        steps.append("python tools/drishti.py build all   (the server jar into lib/ and the modules into modules/)")
    else:
        steps += [f"python tools/drishti.py build modules   ({w})" for w in modulesbuild.missing_report(modules)[:1]]
        steps += [f"{w}" for w in modulesbuild.fresh_report(modules, jar)[:3]]
    steps += [f"python tools/drishti.py --settings {settings or ini} lake demo --engine java   (the demo lake)",
              f"python tools/drishti.py --settings {settings or ini} start all --demo   (server and console; drishti.py status, drishti.py stop all)"]
    for i, s in enumerate(dict.fromkeys(steps), 1):
        say(f"  {i}. {s}")
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
