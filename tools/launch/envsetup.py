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

setup, idempotent, on Linux, macOS and Windows alike: checks Python and Java 21+ (says which java and why), shows the install folder
(DRISHTI_HOME, read only), the data folder (DRISHTI_DATA_DIR, writable) and the modules folder with where each came from, makes the data
folder's layout and the settings folder (~/drishti-envs; Windows %USERPROFILE%\\drishti-envs) with a copy of the example dev.ini (its
paths filled in; an existing file is never overwritten), makes or checks drishti-console/.venv, lists the environment variables it sees
and prints the next steps. ``--check`` changes nothing and only reports (exit 1 when something needs doing)."""
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

#: the folders a data folder holds (docs/DEPLOYMENT_AND_QA_GUIDE.md, "Prepare your environment")
DATA_LAYOUT = ("run", "logs", "tmp", "dumps", "delta")
#: the variables setup reports (a secret's value is never shown)
ENV_VARS = ("DRISHTI_HOME", "DRISHTI_DATA_DIR", "DRISHTI_MODULES_HOME", "DRISHTI_JAVA_HOME", "JAVA_HOME", "DRISHTI_PYTHON", "DRISHTI_PORT",
            "DRISHTI_CONSOLE_PORT", "DRISHTI_SERVER_URL", "DRISHTI_LIB_PATH", "DRISHTI_TMP_DIR", "DRISHTI_DISTRIBUTION", "DRISHTI_PACKS",
            "DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET", "DRISHTI_NET_SECRET")
MIN_PYTHON = (3, 11)
PLACEHOLDER = "/path/to/drishti"


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


def setup(a, env: dict | None = None) -> int:
    env = dict(os.environ if env is None else env)
    check = a.check
    todo: list[str] = []
    problems: list[str] = []

    def line(state: str, what: str, detail: str) -> None:
        say(f"  [{state:>4}] {what:<22} {detail}")

    say("Drishti setup" + (" (--check: nothing is changed)" if check else "") + f" on {sys.platform}")
    # Python
    v = sys.version_info
    ok = (v.major, v.minor) >= MIN_PYTHON
    line("ok" if ok else "FAIL", "python", f"{sys.executable} {v.major}.{v.minor}.{v.micro}" + ("" if ok else f": Drishti needs Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]} or newer"))
    if not ok:
        problems.append(f"install Python {MIN_PYTHON[0]}.{MIN_PYTHON[1]}+ (python.org; Windows: winget install Python.Python.3.13)")
    # Java: the one lookup
    j = javacmd.describe_java(None, a.java_home, env)
    if j["ok"]:
        line("ok", "java", f"{j['java']}  Java {j['version']}  (from {j['why']})")
    else:
        line("FAIL", "java", j["problem"] + (f"  ({j['java']}, from {j['why']})" if j["java"] else ""))
        problems.append("install a JDK 21 (Temurin) and set drishti.java.home in your settings file (or DRISHTI_JAVA_HOME) to its folder, or pass --java-home")
    # the roots
    roots = javacmd.launch_roots(None, a.data_dir, env, javacmd.settings()[1])
    home, data, modules = roots["home"][0], roots["data"][0], roots["modules"][0]
    line("ok", "home (DRISHTI_HOME)", f"{home}  ({roots['home'][1]}; the install, read only)")
    # the data folder and its layout
    missing = [d for d in DATA_LAYOUT if not (data / d).is_dir()]
    if missing and not check:
        for d in DATA_LAYOUT:
            (data / d).mkdir(parents=True, exist_ok=True)
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
    import module_layout
    for note in module_layout.leftovers(modules):
        line("todo", "modules leftover", note)
        todo.append(f"delete {note.split(':')[0]} (git clean -fdX modules/ removes every ignored file under modules/)")
    # every jar under the home: Drishti's own in <home>/lib, the site's in <home>/lib/ext (drishti.lib.path)
    jar = roots["jar"][0]
    line("ok" if jar else "todo", "server jar (<home>/lib)", str(jar) if jar else roots["jar"][1])
    ext = Path(env.get("DRISHTI_LIB_PATH") or roots["ext"][0])
    if not ext.is_dir() and not check:
        ext.mkdir(parents=True, exist_ok=True)
        line("made", "your jars (lib/ext)", f"{ext}  (JDBC drivers, connector plugins: drishti.lib.path; kept on an upgrade)")
    else:
        line("ok" if ext.is_dir() else "todo", "your jars (lib/ext)", f"{ext}  (JDBC drivers, connector plugins: drishti.lib.path; kept on an upgrade)")
    # the settings folder and dev.ini
    edir = Path(a.envs_dir).expanduser() if a.envs_dir else envs_dir(env)
    ini = edir / "dev.ini"
    if ini.is_file():
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
    # the console's Python environment
    py = procs.venv_python(home)
    if a.skip_venv:
        line("skip", "console .venv", f"{py.parent.parent}  (--skip-venv)")
    elif py.exists() and not a.reinstall:
        r = subprocess.run([str(py), "-c", "import yaml, fastapi, uvicorn"], capture_output=True, text=True)
        line("ok" if r.returncode == 0 else "FAIL", "console .venv", f"{py}" + ("" if r.returncode == 0 else ": its packages are missing; run setup --reinstall"))
        if r.returncode:
            problems.append("python tools/drishti.py setup --reinstall (remakes drishti-console/.venv)")
    elif check:
        line("todo", "console .venv", f"{py} is missing")
        todo.append("make drishti-console/.venv: python tools/drishti.py setup")
    else:
        import consolestart
        try:
            consolestart.make_venv(home, a.python or sys.executable, a.reinstall)
            line("made", "console .venv", str(py))
        except LaunchError as e:
            line("FAIL", "console .venv", str(e))
            problems.append(str(e))
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
